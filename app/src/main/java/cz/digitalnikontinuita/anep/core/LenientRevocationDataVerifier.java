package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.validation.RevocationDataVerifier;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import eu.europa.esig.dss.spi.x509.revocation.crl.CRLToken;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.List;

/**
 * Configurable lenient verifier covering two independently selectable relaxations:
 *
 * <ul>
 *   <li><b>relaxConsistency</b> — overrides {@code isConsistent()} to accept post-expiry
 *       revocation data: OCSP responses and CRLs whose {@code thisUpdate} is after the
 *       certificate's {@code NotAfter}. For CRLs the relaxation only applies when the CRL
 *       carries {@code expiredCertsOnCRL} covering the certificate's validity period
 *       (i.e. {@code expiredCertsOnCRL ≤ certNotAfter}); without it the CRL makes no
 *       authoritative statement about the already-expired certificate. Maps to the
 *       "Využij i revokační informace, vydané po expiraci certifikátu" setting.</li>
 *   <li><b>relaxAcceptability</b> — overrides {@code isAcceptable()} to retry with current
 *       wall-clock time when the strict check fails. Needed when embedded revocation data was
 *       issued by a responder or CRL issuer whose own certificate has since expired, causing
 *       DSS to reject the data during responder-cert chain validation. Maps to the
 *       "Využij i revokační informace od již expirovaného vydavatele" setting.</li>
 * </ul>
 *
 * <p>Either or both relaxations may be active at once. All other checks (cert hash, issuer
 * identity, algorithm strength) are always inherited from the default verifier.</p>
 */
public class LenientRevocationDataVerifier extends RevocationDataVerifier {

    private final boolean relaxConsistency;
    private final boolean relaxAcceptability;

    private LenientRevocationDataVerifier(boolean relaxConsistency, boolean relaxAcceptability) {
        super();
        this.relaxConsistency   = relaxConsistency;
        this.relaxAcceptability = relaxAcceptability;
    }

    /**
     * Creates a lenient verifier with the same algorithm defaults as the standard one.
     *
     * @param relaxConsistency   enable post-expiry OCSP acceptance (isConsistent override)
     * @param relaxAcceptability enable expired-revocation-source acceptance (isAcceptable retry)
     */
    public static LenientRevocationDataVerifier createFromDefault(
            boolean relaxConsistency, boolean relaxAcceptability) {
        LenientRevocationDataVerifier lenient =
                new LenientRevocationDataVerifier(relaxConsistency, relaxAcceptability);
        RevocationDataVerifier defaults = RevocationDataVerifier.createDefaultRevocationDataVerifier();
        for (Field f : RevocationDataVerifier.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            try {
                f.set(lenient, f.get(defaults));
            } catch (Exception ignored) {
                // Skip fields that can't be copied — default is safer than failing hard.
            }
        }
        return lenient;
    }

    /**
     * When {@code relaxConsistency} is active: accept post-expiry revocation data.
     * For OCSP tokens the only remaining requirement is notBefore ≤ thisUpdate.
     * For CRL tokens: if {@code expiredCertsOnCRL} is EXPLICITLY set to after
     * {@code certNotAfter} the CRL makes no authoritative statement about this
     * certificate's revocation status, so the strict check is applied (returns false).
     * If the extension is absent (eco == null) the coverage is ambiguous; the lenient
     * check (notBefore ≤ thisUpdate) is kept — the user opted in to accepting
     * post-expiry revocation data and we should not refuse ambiguous-but-possible data.
     * CRLs with explicitly invalid eco are additionally blocked at the source level by
     * {@link FilteringCRLSource} so they never even reach this check.
     */
    @Override
    protected boolean isConsistent(RevocationToken<?> revocation) {
        if (!relaxConsistency) return super.isConsistent(revocation);
        CertificateToken certToken = revocation.getRelatedCertificate();
        if (certToken == null || revocation.getThisUpdate() == null) return false;

        if (revocation instanceof CRLToken crlToken
                && certToken.getNotAfter() != null
                && revocation.getThisUpdate().after(certToken.getNotAfter())) {
            Date expiredCertsOnCRL = crlToken.getExpiredCertsOnCRL();
            if (expiredCertsOnCRL != null && expiredCertsOnCRL.after(certToken.getNotAfter())) {
                return super.isConsistent(revocation);
            }
        }

        return certToken.getNotBefore().compareTo(revocation.getThisUpdate()) <= 0;
    }

    /**
     * When {@code relaxAcceptability} is active: if the strict check fails, retry with the
     * current wall-clock time as control time. This allows DSS to accept embedded revocation
     * data whose issuer certificate has since expired (the responder cert chain check uses the
     * control time; retrying at "now" bypasses stale-revocation rejection for those certs).
     */
    @Override
    public boolean isAcceptable(RevocationToken<?> revocationToken,
                                CertificateToken certificateToken,
                                List<CertificateToken> certificateChain,
                                Date controlTime) {
        if (super.isAcceptable(revocationToken, certificateToken, certificateChain, controlTime)) {
            return true;
        }
        if (!relaxAcceptability) return false;
        return super.isAcceptable(revocationToken, certificateToken, certificateChain, new Date());
    }
}
