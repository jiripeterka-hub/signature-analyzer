package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.CertificateStatus;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;

import java.util.Date;

/**
 * The one place that answers "does this revocation token say anything about this certificate?".
 *
 * <p>The rule is short but easy to get subtly wrong, and it is needed at three points that must
 * agree: choosing between OCSP and CRL
 * ({@link OcspFirstCrlFallbackRevocationDataLoadingStrategy}), deciding whether the user's custom
 * CRL store is worth consulting ({@link FallbackCrlSource}), and filtering the online source
 * ({@link FilteringCRLSource}). Every time a copy of it drifted, a document either lost a warning
 * it needed or gained a promise that did not hold.
 */
final class RevocationCoverage {

    private RevocationCoverage() {}

    /**
     * True when the token makes an authoritative statement about the certificate's status during
     * its validity: it was issued while the certificate was still alive, or it is a CRL whose
     * {@code expiredCertsOnCRL} reaches back over the certificate's lifetime
     * (ITU-T X.509, OID 2.5.29.60).
     */
    static boolean isAuthoritative(RevocationToken<?> token, CertificateToken cert) {
        if (token == null || cert == null) return false;
        Date thisUpdate = token.getThisUpdate();
        Date notAfter = cert.getNotAfter();
        if (thisUpdate == null || notAfter == null) return true;   // nothing to compare — keep
        if (!thisUpdate.after(notAfter)) return true;
        Date eco = token.getExpiredCertsOnCRL();
        return eco != null && !eco.after(notAfter);
    }

    /**
     * True when the token is worth using at all: either it is authoritative, or it names the
     * certificate as revoked.
     *
     * <p>The second half is the part that is easy to miss. A list published after the certificate
     * expired proves nothing by its <em>silence</em> — the CA may legitimately have dropped the
     * entry — but an entry that is still there is a plain statement of fact, and often the only
     * one obtainable, since responders tend to answer UNKNOWN for long-expired certificates.
     */
    static boolean saysSomething(RevocationToken<?> token, CertificateToken cert) {
        if (token == null) return false;
        return isAuthoritative(token, cert)
                || CertificateStatus.REVOKED.equals(token.getStatus());
    }
}
