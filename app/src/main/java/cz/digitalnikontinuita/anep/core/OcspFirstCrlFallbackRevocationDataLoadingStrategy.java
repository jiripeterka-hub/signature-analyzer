package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.CertificateStatus;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.validation.RevocationDataLoadingStrategy;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;

import java.util.Date;
import java.util.List;

/**
 * OCSP-first revocation loading strategy that falls back to CRL only when the OCSP
 * response is post-expiry (thisUpdate &gt; cert.notAfter).
 *
 * <p>Unlike {@code CRLFirstRevocationDataLoadingStrategy}, this strategy does NOT change
 * the validation behaviour for still-valid certificates: if a fresh online OCSP is
 * available, it is returned immediately and the CRL source (which may include the custom
 * store via {@link FallbackCrlSource}) is never called.  The CRL path is taken only when:
 * <ul>
 *   <li>no OCSP response is available at all, or</li>
 *   <li>the OCSP response is post-expiry — i.e. its thisUpdate is strictly after the
 *       certificate's notAfter, so it carries no authoritative information about the
 *       certificate's revocation status during its validity period.</li>
 * </ul>
 *
 * <p>This ensures that the custom CRL store acts as a true last-resort fallback and does
 * not affect validation of certificates whose revocation can be determined from normal
 * online sources.
 *
 * <p>When the strategy falls back to a CRL (typically from the custom store), any post-expiry
 * OCSP that was fetched but not used is recorded in the {@code skippedCollector} so that
 * {@link ReportMapper} can still detect revocation-before-BST scenarios (S20/S21) even
 * when the CRL result leads DSS to report TOTAL_PASSED.
 */
public class OcspFirstCrlFallbackRevocationDataLoadingStrategy extends RevocationDataLoadingStrategy {

    /**
     * A post-expiry OCSP this strategy fetched. Recorded whether or not it is the token handed
     * back to DSS: either way DSS refuses to use it in standards mode and leaves no trace of it on
     * the certificate in DiagnosticData, so this is where ReportMapper finds it when detecting
     * revocation-before-BST (S20/S21).
     */
    public record SkippedRevocation(
            String certId,
            CertificateStatus status,
            Date thisUpdate,
            Date revocationDate) {}

    private final List<SkippedRevocation> skippedCollector;

    public OcspFirstCrlFallbackRevocationDataLoadingStrategy(List<SkippedRevocation> skippedCollector) {
        this.skippedCollector = skippedCollector;
    }

    public OcspFirstCrlFallbackRevocationDataLoadingStrategy() {
        this(null);
    }

    @Override
    public RevocationToken<?> getRevocationToken(CertificateToken certificateToken,
                                                  CertificateToken issuerCertificateToken) {
        // Try OCSP first.
        RevocationToken<?> ocspToken = checkOCSP(certificateToken, issuerCertificateToken);
        if (ocspToken != null && !isPostExpiry(ocspToken, certificateToken)) {
            // OCSP is within the certificate's validity period → use it.
            return ocspToken;
        }

        // Whatever happens below, a post-expiry OCSP we fetched must be recorded. DSS discards
        // such a response as inconsistent and then does not attach it to the certificate in
        // DiagnosticData at all, so this collector is the report's only way of learning that the
        // response exists — which is what S20/S21 need to say "an OCSP proves this certificate was
        // revoked, the standards just do not let us use it". Recording only in the CRL branch lost
        // that warning for five documents in the corpus the moment the OCSP started being returned.
        if (ocspToken != null && skippedCollector != null) {
            skippedCollector.add(new SkippedRevocation(
                    ocspToken.getRelatedCertificateId(),
                    ocspToken.getStatus(),
                    ocspToken.getThisUpdate(),
                    ocspToken.getRevocationDate()));
        }

        // OCSP missing or post-expiry → try CRL.
        // FallbackCrlSource will try the online CRL first, and only use the custom store
        // when the online CRL is also post-expiry (or unavailable).
        RevocationToken<?> crlToken = checkCRL(certificateToken, issuerCertificateToken);

        // Which of the two answers to hand DSS is decided by which one carries information, not by
        // type. Trading a post-expiry OCSP for a post-expiry CRL used to happen unconditionally,
        // and where both were empty of meaning it cost the report the only evidence it had: the
        // OCSP never reached DiagnosticData, so nothing could report that a post-expiry answer
        // exists at all and that accepting it would settle the question.
        //
        // A CRL is worth taking when it is authoritative, and also when it names the certificate
        // as revoked. The second case is easy to overlook: a CRL published after the certificate
        // expired proves nothing by *silence*, because the CA may legitimately have dropped the
        // entry (RFC 5280 clause 3.3) — but an entry that is still there is a plain statement of
        // fact. It is often the only one available, since responders tend to answer UNKNOWN for
        // long-expired certificates. Dropping those CRLs cost five documents in the corpus their
        // S21/S30 warning that the signing certificate had been revoked.
        if (RevocationCoverage.saysSomething(crlToken, certificateToken)) return crlToken;

        // The CRL is silent. Prefer the OCSP unless it is equally uninformative, so that a
        // definite post-expiry answer reaches DiagnosticData; DSS still makes the final call and
        // in standards mode rejects it, leaving the verdict where the CRL would have left it.
        if (ocspToken != null && !CertificateStatus.UNKNOWN.equals(ocspToken.getStatus())) {
            return ocspToken;
        }
        return crlToken != null ? crlToken : ocspToken;
    }

    private static boolean isPostExpiry(RevocationToken<?> token, CertificateToken cert) {
        Date thisUpdate = token.getThisUpdate();
        Date notAfter  = cert.getNotAfter();
        return thisUpdate != null && notAfter != null && thisUpdate.after(notAfter);
    }
}
