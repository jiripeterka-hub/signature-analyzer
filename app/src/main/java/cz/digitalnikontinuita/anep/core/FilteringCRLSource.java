package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.crl.CRL;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import eu.europa.esig.dss.spi.x509.revocation.crl.CRLToken;

import java.util.Date;

/**
 * Wraps a CRL source and discards any CRL that is not authoritative for an expired
 * certificate: RFC 5280 clause 3.3 lets the authority drop the entry once the certificate has
 * expired, and only {@code expiredCertsOnCRL} (ITU-T X.509, OID 2.5.29.60) reaching back over
 * its lifetime says otherwise.
 *
 * A CRL is discarded when ALL of the following hold:
 * <ol>
 *   <li>The CRL's {@code thisUpdate} is after the certificate's {@code notAfter} — the CRL
 *       was published after the certificate had already expired.</li>
 *   <li>The CRL carries the {@code expiredCertsOnCRL} extension <em>and</em> its value is
 *       after {@code certNotAfter} — this is an explicit CA declaration that the CRL does
 *       not cover revocations from before the certificate expired.</li>
 * </ol>
 *
 * If {@code expiredCertsOnCRL} is absent (the extension is optional), the CRL is passed
 * through: coverage is then ambiguous and {@link LenientRevocationDataVerifier} decides
 * based on the active relaxation mode.
 *
 * Such a CRL is silently discarded ({@code null} returned) so that DSS falls back to OCSP
 * or another evidence source.  Filtering at the source level is necessary because DSS's LTV
 * {@code CertificateRevocationSelector} re-evaluates all tokens in the validation context via
 * the RAC, and DSS's default policy marks the {@code doesRevocationKnowCertificate} check
 * ({@code BBB_XCV_REVOC_HAS_CERT_INFO}) as IGNORED — meaning the RAC alone cannot filter
 * out an unauthoritative CRL that has already been fetched.
 *
 * This filtering is unconditional (independent of lenient-mode settings): no setting can make
 * an unauthoritative CRL authoritative.
 */
public class FilteringCRLSource implements RevocationSource<CRL> {

    private static final long serialVersionUID = 1L;

    private final RevocationSource<CRL> delegate;

    public FilteringCRLSource(RevocationSource<CRL> delegate) {
        this.delegate = delegate;
    }

    @Override
    public RevocationToken<CRL> getRevocationToken(CertificateToken certificateToken,
                                                   CertificateToken issuerCertificateToken) {
        RevocationToken<CRL> token;
        try {
            token = delegate.getRevocationToken(certificateToken, issuerCertificateToken);
        } catch (Exception e) {
            return null;
        }
        if (token == null) return null;
        if (!(token instanceof CRLToken crlToken)) return token;

        Date notAfter = certificateToken.getNotAfter();
        Date thisUpdate = token.getThisUpdate();
        if (notAfter == null || thisUpdate == null) return token;
        if (!thisUpdate.after(notAfter)) return token; // CRL within cert's validity — keep

        // CRL published after cert expired: only discard it when expiredCertsOnCRL is
        // EXPLICITLY set to a date after certNotAfter — that is an unambiguous declaration
        // by the CA that this CRL does not cover the certificate's validity period.
        // If the extension is absent (eco == null) we cannot determine coverage from the
        // CRL alone; pass it through and let DSS's isConsistent() decide based on the
        // active relaxation mode (strict → super.isConsistent() → false; lenient → true).
        // Use getExpiredCertsOnCRL() from the RevocationToken base class — the field is
        // populated during CRLToken initialisation and is more reliably set than the
        // CRLValidity object (which may be null when the token has not yet been verified).
        Date eco = crlToken.getExpiredCertsOnCRL();
        if (eco != null && eco.after(notAfter)) {
            return null; // explicitly not authoritative for this cert — discard
        }
        return token;
    }
}
