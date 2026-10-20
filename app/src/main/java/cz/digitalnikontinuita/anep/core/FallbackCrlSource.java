package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.crl.CRL;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;

/**
 * Tries the primary (online) CRL source first and falls back to the
 * {@link BestMatchCustomCrlSource}, backed by the user's custom CRL store, only when the
 * primary returns nothing.
 *
 * <p>This is always active when the custom CRL store is non-empty — see
 * {@link cz.digitalnikontinuita.anep.core.ValidatorFactory} where it is wired unconditionally.
 */
public class FallbackCrlSource implements RevocationSource<CRL> {

    private static final long serialVersionUID = 1L;

    private final RevocationSource<CRL> primary;
    private final RevocationSource<CRL> fallback;

    public FallbackCrlSource(RevocationSource<CRL> primary, RevocationSource<CRL> fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /**
     * The primary is used when its CRL actually covers the certificate; only then is the custom
     * store left alone. Otherwise the store gets its turn, and a CRL from there that does cover
     * the certificate wins.
     *
     * <p>This class first applied its own post-expiry test and discarded any CRL issued after the
     * certificate's notAfter — too blunt, because it ignored {@code expiredCertsOnCRL}, which
     * makes a post-expiry CRL authoritative after all (ITU-T X.509, OID 2.5.29.60); those CRLs were
     * thrown away and the code fell through to a store that, for most users, is empty, leaving the
     * certificate with no revocation data at all. It surfaced as augmentation failing on a valid
     * PostSignum seal.
     *
     * <p>Removing that test fixed the seal but went too far the other way: "use whatever the
     * primary returns" meant the store was consulted only when the online source returned
     * <em>nothing</em>. A CA that publishes a current CRL therefore silenced the store completely
     * — the online list came back, useless for a certificate that expired years earlier, and the
     * within-validity CRL sitting in the store was never looked at. The store exists precisely for
     * that case, so the condition is coverage, not mere presence.
     */
    @Override
    public RevocationToken<CRL> getRevocationToken(CertificateToken certificateToken,
                                                    CertificateToken issuerCertificateToken) {
        RevocationToken<CRL> token = null;
        try {
            token = primary.getRevocationToken(certificateToken, issuerCertificateToken);
        } catch (Exception ignored) {
            // fall through to the custom store
        }
        if (RevocationCoverage.saysSomething(token, certificateToken)) return token;

        RevocationToken<CRL> stored = null;
        try {
            stored = fallback.getRevocationToken(certificateToken, issuerCertificateToken);
        } catch (Exception ignored) {
            // keep whatever the primary gave us
        }
        if (RevocationCoverage.saysSomething(stored, certificateToken)) return stored;
        return token != null ? token : stored;
    }
}
