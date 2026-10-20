package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.crl.CRL;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import eu.europa.esig.dss.spi.x509.revocation.crl.CRLToken;
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * CRL source backed by the user's custom CRL store.
 *
 * <p>For each {@code getRevocationToken} call, ALL stored CRLs from the matching issuer are
 * evaluated and the best applicable one is selected using a two-search algorithm:
 *
 * <h3>Search −1 — the list cannot predate the certificate</h3>
 * A CRL lists what had been revoked by the time it was issued, among certificates that existed
 * then. Is its {@code thisUpdate} earlier than the certificate's {@code notBefore}? Then the
 * certificate did not exist yet, the list could not have named it, and its silence is no evidence
 * of anything — it is silence about a certificate that had not been issued.
 *
 * <p>Nález uživatele 13. 9. 2026: `JAdES B-B kvalifikovany detached PS.json` (podpis 14. 1. 2026,
 * certifikát vydán 7. 6. 2025) se opřel o uloženou CRL z 9. 9. 2021 a prohlásil „certifikát nebyl
 * revokován" — a odsunul tím odpověď OCSP staženou dnes, podle které byl certifikát revokován
 * 28. 4. 2026. Search 0 níž tehdy nepomohl: prvek je úrovně B, v dokumentu není žádné razítko,
 * takže rozhodný okamžik vyšel {@code null} a filtr se vypnul. Tahle podmínka na rozhodném
 * okamžiku nezávisí.
 *
 * <h3>Search 0 — the list has to be younger than the moment being asked about</h3>
 * A CRL states who had been revoked <em>by the time it was issued</em>. To conclude that a
 * certificate was not revoked at the best-signature-time, the list must therefore be issued at or
 * after that moment: one published earlier cannot rule out a revocation recorded between its own
 * issuance and the signature. Candidates whose {@code thisUpdate} precedes {@code referenceTime}
 * are dropped before either search below, whatever else speaks for them.
 *
 * <p>This is separate from covering the certificate's validity period. A list can be perfectly
 * authoritative about the certificate — issued while it was still alive — and still be useless
 * here: measured on bajecny svet\2026\01 14 full\PAdES B-T neviditelny PS.pdf, whose signature is
 * time-stamped 14 January 2026 while the newest stored list from that CA was issued on
 * 13 June 2025. Without this filter the document came out "platný" on the strength of a list that
 * predates the signature by seven months.
 *
 * <h3>Search 1 — CRLs with expiredCertsOnCRL (eco)</h3>
 * Among CRLs that carry an {@code expiredCertsOnCRL} extension whose value is strictly before
 * {@code cert.notAfter}: select the one with the highest (most-recent) eco date.
 * The CRL's own {@code thisUpdate} is irrelevant — a post-expiry CRL with a valid eco still
 * covers the certificate's active revocation history.
 *
 * <h3>Search 2 — CRLs without expiredCertsOnCRL</h3>
 * Among CRLs that carry NO {@code expiredCertsOnCRL} extension: select the one whose
 * {@code thisUpdate} is the highest date still strictly before {@code cert.notAfter}.
 *
 * <h3>Winner selection</h3>
 * If only one search found a candidate, that candidate is used.
 * If both found a candidate, the one whose comparison score (eco for search 1, thisUpdate
 * for search 2) is higher (more-recent, closer to notAfter) wins.
 * If neither search found a candidate, {@code null} is returned.
 */
public class BestMatchCustomCrlSource implements RevocationSource<CRL> {

    private static final long serialVersionUID = 1L;

    private final List<ExternalResourcesCRLSource> singleSources;
    private final Date referenceTime;

    /** Without a reference time nothing is dropped on age — see the class comment, Search 0. */
    public BestMatchCustomCrlSource(List<DSSDocument> crlDocs) {
        this(crlDocs, null);
    }

    /**
     * @param referenceTime the moment the revocation status is being asked about — the
     *                      best-signature-time. May be null when it cannot be established, in
     *                      which case no candidate is rejected for being too old.
     */
    public BestMatchCustomCrlSource(List<DSSDocument> crlDocs, Date referenceTime) {
        this.referenceTime = referenceTime;
        singleSources = new ArrayList<>(crlDocs.size());
        for (DSSDocument doc : crlDocs) {
            singleSources.add(new ExternalResourcesCRLSource(doc));
        }
    }

    @Override
    public RevocationToken<CRL> getRevocationToken(CertificateToken cert,
                                                    CertificateToken issuer) {
        Date notAfter = cert.getNotAfter();
        if (notAfter == null) return null;

        // Gather all CRL tokens from the matching issuer across all stored files.
        List<CRLToken> candidates = new ArrayList<>();
        for (ExternalResourcesCRLSource src : singleSources) {
            RevocationToken<CRL> token;
            try {
                token = src.getRevocationToken(cert, issuer);
            } catch (Exception ignored) {
                continue;
            }
            if (token instanceof CRLToken crlToken
                    && issuedAfterTheCertificateExisted(crlToken.getThisUpdate(),
                            cert.getNotBefore())
                    && saysSomethingAboutReferenceTime(crlToken)) {
                candidates.add(crlToken);
            }
        }
        if (candidates.isEmpty()) return null;

        // Search 1: CRLs with eco strictly before notAfter — highest eco wins.
        CRLToken best1 = null;
        Date score1 = null;
        for (CRLToken token : candidates) {
            Date eco = token.getExpiredCertsOnCRL();
            if (eco == null || !eco.before(notAfter)) continue;  // no eco, or eco >= notAfter
            if (best1 == null || eco.after(score1)) {
                best1 = token;
                score1 = eco;
            }
        }

        // Search 2: CRLs without eco and thisUpdate strictly before notAfter — highest thisUpdate wins.
        CRLToken best2 = null;
        Date score2 = null;
        for (CRLToken token : candidates) {
            if (token.getExpiredCertsOnCRL() != null) continue;  // has eco — belongs to search 1 only
            Date thisUpdate = token.getThisUpdate();
            if (thisUpdate == null || !thisUpdate.before(notAfter)) continue;
            if (best2 == null || thisUpdate.after(score2)) {
                best2 = token;
                score2 = thisUpdate;
            }
        }

        // Return the better candidate.
        if (best1 == null) return best2;
        // (both searches already work only on lists that reach the reference time — Search 0)
        if (best2 == null) return best1;
        // Both found: the one whose score is closer to (higher than) the other wins.
        return score1.compareTo(score2) >= 0 ? best1 : best2;
    }

    /**
     * Search −1: could this list have named the certificate at all?
     *
     * <p>Nezávisí na rozhodném okamžiku a platí i tam, kde se žádný stanovit nedá — právě proto
     * je oddělená od {@link #saysSomethingAboutReferenceTime}. Seznam vydaný dřív, než certifikát
     * vůbec vznikl, o něm mlčí nutně, ne proto, že by nebyl odvolán.
     */
    static boolean issuedAfterTheCertificateExisted(Date thisUpdate, Date certNotBefore) {
        if (certNotBefore == null || thisUpdate == null) return true;
        return !thisUpdate.before(certNotBefore);
    }

    /**
     * Search 0: does this list say anything about the moment in question?
     *
     * <p>It does when its validity window still reaches that moment, which is the standard's own
     * default: EN 319 102-1 V1.4.1 clause 5.2.5.4 step 1 derives the accepted freshness from the
     * interval between {@code thisUpdate} and {@code nextUpdate} when no policy sets one. A list
     * issued after the moment qualifies outright — its window has not opened yet.
     *
     * <p>What this rejects is a list whose window closed before the signature existed. Such a list
     * is silent about it, and silence is not evidence of non-revocation. Applied to eco-bearing
     * lists too: {@code expiredCertsOnCRL} extends what a list covers backwards, never forwards.
     */
    private boolean saysSomethingAboutReferenceTime(CRLToken token) {
        if (referenceTime == null) return true;
        Date thisUpdate = token.getThisUpdate();
        if (thisUpdate == null || !thisUpdate.before(referenceTime)) return true;
        Date nextUpdate = token.getNextUpdate();
        return nextUpdate != null && nextUpdate.after(referenceTime);
    }
}
