package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.enumerations.CertificateStatus;
import eu.europa.esig.dss.enumerations.RevocationType;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The revocation lists the just-finished validation leaned on, so that preservation can put them
 * into the document instead of leaving the reader to find them again.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>Validation and preservation ask different sources, and the answers diverge. Validation goes to
 * the network for OCSP; where the responder cannot usefully answer for a long-expired certificate,
 * the strategy falls back to CRL and reaches the user's own store, and that list is what carries the
 * verdict. Preservation puts {@link DocumentOcspSource} in front of the network, so the response the
 * document already carries satisfies the OCSP step, the CRL step never runs, and the custom store is
 * never opened. The preserved document then lacks the very evidence its verdict rested on.
 *
 * <p>Measured on {@code AnEP\test prodlouzeni\faktura ICA}: the seal validates on a CRL from the
 * custom store (thisUpdate 2020-10-15, {@code expiredCertsOnCRL} 2020-10-12, authoritative for a
 * certificate that expired in 2021), and the prolonged document came out carrying only the root's
 * CRL — 750 B and no entries. I.CA publishes only its current list, which says nothing about that
 * certificate, so no reader could obtain the missing one afterwards.
 *
 * <h2>What counts as relied upon</h2>
 *
 * <p>Deliberately not "everything in the diagnostic data". Only lists that
 *
 * <ul>
 *   <li>are attached to a certificate in the signature's own chain or in a time-stamp's chain —
 *       material DSS gathered while walking something else is not this signature's evidence;
 *   <li>came from outside the document. What is already inside travels anyway, and re-injecting it
 *       would add a second copy of bytes an incremental save can never reclaim;
 *   <li><b>say something the document does not already say.</b> Totožnost seznamu na to nestačí:
 *       jiný seznam od téhož vydavatele, který o tomtéž certifikátu tvrdí totéž, je pro čtenáře
 *       tentýž důkaz — a stojí celý znovu. Viz níž;
 *   <li>say something definite about the certificate they cover — a status that is not
 *       {@code UNKNOWN}, and a validity window that reaches the certificate, whether by
 *       {@code thisUpdate} falling inside it or by {@code expiredCertsOnCRL} reaching back over it.
 *       That is the test a conformant validator applies, so a list failing it is not evidence and
 *       embedding it would only cost bytes.
 * </ul>
 *
 * <p>CRLs only. The channel these travel down — {@code extraCrls} into
 * {@code AugmentationService.postProcessDss} — writes {@code /CRLs}, and an OCSP would need the
 * other array. That is no loss in the case this exists for: there is no custom OCSP store, so an
 * external OCSP is one preservation can fetch again for itself.
 */
public final class ReliedUponRevocation {

    /**
     * DER bytes of the CRLs the validation relied on, deduplicated. Never null; empty whenever the
     * document's own material was enough, which is the ordinary case.
     */
    public static List<byte[]> crlBytes(DiagnosticData diagnosticData) {
        List<byte[]> out = new ArrayList<>();
        if (diagnosticData == null) return out;

        java.util.Set<String> seenCerts = new java.util.HashSet<>();
        for (SignatureWrapper sw : diagnosticData.getSignatures()) {
            collect(sw.getCertificateChain(), seenCerts, out);
            for (TimestampWrapper ts : sw.getTimestampList()) {
                collect(ts.getCertificateChain(), seenCerts, out);
            }
        }
        for (TimestampWrapper ts : diagnosticData.getTimestampList()) {
            collect(ts.getCertificateChain(), seenCerts, out);
        }
        return out;
    }

    private static void collect(List<CertificateWrapper> chain, java.util.Set<String> seenCerts,
                                List<byte[]> out) {
        if (chain == null) return;
        for (CertificateWrapper c : chain) {
            if (!seenCerts.add(c.getId())) continue;
            for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                if (rev.getRevocationType() != RevocationType.CRL) continue;
                if (rev.isInternalRevocationOrigin()) continue;
                if (rev.getStatus() == CertificateStatus.UNKNOWN) continue;
                if (!covers(rev, c.getNotAfter())) continue;
                if (alreadyAnsweredInside(c, rev)) continue;
                byte[] der = rev.getBinaries();
                if (der == null || der.length == 0) continue;
                boolean known = out.stream().anyMatch(b -> java.util.Arrays.equals(b, der));
                if (!known) out.add(der);
            }
        }
    }

    /**
     * Nese už dokument sám seznam, který o tomhle certifikátu říká totéž?
     *
     * <p>Podmínka {@code isInternalRevocationOrigin()} výš vyloučí <b>tentýž</b> seznam, ne tutéž
     * <b>odpověď</b>. Vydavatel jich publikuje řadu a každý další obsahuje všechno, co ten
     * předchozí; stažený a vložený seznam se proto liší bajty, ale pro čtenáře jsou to dva
     * doklady téhož. Druhý z nich stojí celou svou velikost a {@code saveIncremental} ji už nikdy
     * nevrátí.
     *
     * <p><b>Změřeno na „PAdES T-LEvel jen uznavany extended.pdf“</b> (podpis z roku 2016,
     * PostSignum Qualified CA 2). Podpisový certifikát platil do 30. 1. 2017 a měl dvě
     * autoritativní CRL: vloženou z 30. 1. 2017 (139 kB, 3 844 položek) a staženou z 19. 1. 2020
     * (2 627 602 B, 72 763 položek, {@code expiredCertsOnCRL} 2010, takže po expiraci platí taky).
     * Obě říkaly totéž. Uchování z 549kB souboru udělalo 3,25 MB, z toho 2,63 MB byla ta druhá
     * CRL. Táž operace v referenční aplikaci DSS přidala 120 kB — DSS totiž nic takového
     * nedokládá.
     *
     * <p><b>Rozdílný stav se nepřeskakuje.</b> Právě proto se porovnává {@link CertificateStatus}:
     * říká-li vložená odpověď „neodvolán“ a stažená „odvolán“, je ta stažená jediný důkaz toho,
     * na čem závěr stojí, a musí do dokumentu jít — to je situace 44 a kvůli ní celá tahle třída
     * vznikla. Přeskakuje se jen shoda.
     *
     * <p><b>Jen CRL proti CRL.</b> Vložená odpověď OCSP by čtenáři posloužila stejně, ale
     * {@code covers()} je psané na seznamy a přísnější podmínka znamená vložit víc, ne míň.
     */
    private static boolean alreadyAnsweredInside(CertificateWrapper cert,
                                                 CertificateRevocationWrapper external) {
        for (CertificateRevocationWrapper rev : cert.getCertificateRevocationData()) {
            if (!rev.isInternalRevocationOrigin()) continue;
            if (rev.getRevocationType() != RevocationType.CRL) continue;
            if (insideAnswersToo(rev.getStatus(), rev.getThisUpdate(), rev.getExpiredCertsOnCRL(),
                    external.getStatus(), cert.getNotAfter())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Samotné pravidlo, oddělené od procházení diagnostických dat, aby šlo měřit přímo.
     *
     * <p>Sbírka {@code D:\tmp} ho nevyvolá ani jednou — 188 dokumentů, žádný neplatí za vnější
     * seznam — takže na obou jeho stranách stojí jen tenhle test. Ta strana, která odhazuje, je
     * přitom tichá: kdyby odhodila víc, než má, uchovaný dokument by přišel o důkaz a nikdo by si
     * toho nevšiml až do chvíle, kdy ho někdo zkusí ověřit.
     *
     * @param insideStatus     co o certifikátu říká seznam, který dokument už nese
     * @param insideThisUpdate jeho {@code thisUpdate}
     * @param insideEco        jeho {@code expiredCertsOnCRL}, nebo {@code null}
     * @param outsideStatus    co o témž certifikátu říká seznam zvenčí
     * @param notAfter         konec platnosti certifikátu, o který jde
     */
    static boolean insideAnswersToo(CertificateStatus insideStatus, Date insideThisUpdate,
                                    Date insideEco, CertificateStatus outsideStatus,
                                    Date notAfter) {
        if (insideStatus == null || insideStatus != outsideStatus) return false;
        if (insideStatus == CertificateStatus.UNKNOWN) return false;
        return coversDates(insideThisUpdate, insideEco, notAfter);
    }

    /**
     * Does this list say anything about a certificate that lives until {@code notAfter}?
     *
     * <p>{@code thisUpdate} inside the certificate's lifetime is the plain case. Past it, only
     * {@code expiredCertsOnCRL} (ITU-T X.509, OID 2.5.29.60) helps: the authority states from which
     * moment it keeps entries for certificates that have since expired, and a list reaching back
     * over this one covers it. Without that, absence from the list means nothing — RFC 5280
     * clause 3.3 lets the entry be dropped once the certificate expires.
     */
    private static boolean covers(CertificateRevocationWrapper rev, Date notAfter) {
        return coversDates(rev.getThisUpdate(), rev.getExpiredCertsOnCRL(), notAfter);
    }

    /** Táž otázka na holých datech, aby ji mohlo použít i {@link #insideAnswersToo}. */
    private static boolean coversDates(Date thisUpdate, Date eco, Date notAfter) {
        if (thisUpdate == null || notAfter == null) return false;
        if (!thisUpdate.after(notAfter)) return true;
        return eco != null && !eco.after(notAfter);
    }

    private ReliedUponRevocation() { }
}
