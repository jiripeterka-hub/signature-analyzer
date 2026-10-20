package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kdy rada o revokačních údajích přestane mít smysl, a tak se neuvádí. Dva tituly, obojí totéž:
 * rada, po které se nic nestane, posílá čtenáře zkoušet zaškrtávátka nadarmo.
 *
 * <p><b>Chybí důkaz existence</b> — důvod 4 (expirovaný podpisový certifikát bez časového
 * razítka) a s ním 20 a 21.
 *
 * <p><b>Řetěz nevede k žádné kotvě</b> — důvod 3. Revokace se podle ETSI EN 319 102-1 čl. 5.2.6
 * zjišťuje uvnitř ověření certifikačního řetězu, až po sestavení cesty ke kotvě; bez ní se na ni
 * vůbec nedojde. Potlačuje se ale jen to, co se týká podpisového řetězu: řetěz razítka může být
 * ukotvený, i když podpisový není.
 *
 * <p>Čtyřka znamená, že chybí důkaz existence: rozhodný okamžik proto stojí na přítomnosti, tedy
 * mimo dobu platnosti certifikátu. Každá rada o revokačních údajích tam míří vedle — ať už radí
 * údaj pořídit, nebo připustit, výsledek zůstane týž. Čtenář by procházel zaškrtávátka, po
 * kterých se nic nestane.
 *
 * <p>Zkouší se nad čistou funkcí, ne na sbírce: v korpusu {@code D:\tmp} se čtyřka potkává jen
 * s částí té rodiny (6 třináctkrát, 12 desetkrát), kdežto pravidlo má platit pro všechny.
 */
class ReasonSuppressionTest {

    /** Situace, ze kterých se odvozují jednotlivé důvody — viz {@code ReportMapper.detectReasons}. */
    private static final int S_EXPIRED_CERT_NO_TS = 3;   // → důvod 4
    private static final int S_POST_EXPIRY_OCSP   = 6;   // → důvod 2
    private static final int S_EXPIRED_RESPONDER  = 5;   // → důvod 6
    private static final int S_UNKNOWN_STATUS     = 7;   // → důvod 10
    private static final int S_UNUSABLE_REVOC     = 8;   // → důvod 11
    private static final int S_NO_REVOC           = 9;   // → důvod 12
    private static final int S_ISSUER_OUT_OF_RANGE = 46; // → důvod 24
    private static final int S_SILENT_CRL         = 47;  // → důvod 25
    private static final int S_SHA1               = 15;  // → důvod 15
    private static final int S_UNKNOWN_CERT       = 12;  // → důvod 3

    private static List<Integer> reasons(int... situations) {
        List<Integer> list = new java.util.ArrayList<>();
        for (int s : situations) list.add(s);
        return ReportMapper.detectReasons(list, new AppSettings());
    }

    /**
     * Totéž se znalostí toho, čeho se revokační nálezy týkají: {@code onTimestampChains} jsou
     * důvody, jejichž nález má zdroj i v řetězu některého razítka.
     */
    private static List<Integer> reasons(java.util.Set<Integer> onTimestampChains, int... situations) {
        return reasons(new ReportMapper.ReasonFacts(onTimestampChains, false), situations);
    }

    private static List<Integer> reasons(ReportMapper.ReasonFacts facts, int... situations) {
        List<Integer> list = new java.util.ArrayList<>();
        for (int s : situations) list.add(s);
        return ReportMapper.detectReasons(list, new AppSettings(), facts);
    }

    /** Situace celé revokační rodiny naráz — 2, 6, 10, 11, 12, 24 a 25. */
    private static final int[] REVOCATION_FAMILY = {
        S_POST_EXPIRY_OCSP, S_EXPIRED_RESPONDER, S_UNKNOWN_STATUS, S_UNUSABLE_REVOC,
        S_NO_REVOC, S_ISSUER_OUT_OF_RANGE, S_SILENT_CRL
    };

    private static int[] withUnknownCert() {
        int[] out = new int[REVOCATION_FAMILY.length + 1];
        out[0] = S_UNKNOWN_CERT;
        System.arraycopy(REVOCATION_FAMILY, 0, out, 1, REVOCATION_FAMILY.length);
        return out;
    }

    @Test
    @DisplayName("Neznámá kotva umlčí rady o revokaci podpisového řetězu")
    void unknownAnchorSilencesRevocationAdvice() {
        // Bez kotvy se podle ETSI EN 319 102-1 čl. 5.2.6 na revokaci vůbec nedojde: zjišťuje se
        // uvnitř ověření řetězu, až po sestavení cesty ke kotvě. Rada „sežeňte CRL" ani „zapněte
        // toleranci" tedy s výsledkem nehne, dokud trojka stojí.
        List<Integer> derived = reasons(java.util.Set.of(), withUnknownCert());
        assertEquals(List.of(3), derived,
                "Na nesestaveném řetězu nevede rada o revokaci nikam — má zůstat jen důvod 3: "
                        + derived);
    }

    @Test
    @DisplayName("Nález z řetězu razítka trojka neumlčí — ten řetěz ukotvený být může")
    void findingsOnTimestampChainsSurviveTheUnknownAnchor() {
        List<Integer> derived = reasons(java.util.Set.of(6, 12), withUnknownCert());
        assertTrue(derived.contains(6), "Vydavatel vloženého údaje u razítka: " + derived);
        assertTrue(derived.contains(12), "Chybějící údaj u razítka: " + derived);
        assertTrue(derived.contains(3), derived.toString());
        assertFalse(derived.contains(2), "Tenhle nález je z podpisového řetězu: " + derived);
        assertFalse(derived.contains(25), "Tenhle také: " + derived);
    }

    @Test
    @DisplayName("Důvod 21 trojka neumlčuje: jiný prvek, jiný lék")
    void unknownAnchorLeavesTheTimestampReasonStanding() {
        List<Integer> derived = reasons(java.util.Set.of(), S_UNKNOWN_CERT, 27);
        assertTrue(derived.contains(21),
                "Vložit certifikát razítkovací autority je samostatný úkon: " + derived);
        assertTrue(derived.contains(3), derived.toString());
    }

    @Test
    @DisplayName("Fakta o revokaci trojka neumlčuje")
    void unknownAnchorLeavesTheFactsStanding() {
        // Důvod 22 (odvolání prokázané údajem, který standardy nepouštějí ke slovu) je fakt,
        // ne rada — a fakt se neumlčuje, i když vedle něj žádná rada nikam nevede.
        List<Integer> revoked = reasons(java.util.Set.of(), S_UNKNOWN_CERT, 30);
        assertTrue(revoked.contains(22), revoked.toString());
        // Důvod 9 (revokovaný certifikát) totéž.
        List<Integer> revokedCert = reasons(java.util.Set.of(), S_UNKNOWN_CERT, 2);
        assertTrue(revokedCert.contains(9), revokedCert.toString());
    }

    /**
     * Neznámá razítkovací autorita: důvod 19 (TSA není v EUTL) a důvod 21 s příčinou „řetěz
     * nevede k žádné kotvě" jsou táž zpráva, a jedenadvacítka ji nese líp — jmenuje autoritu
     * a její rada dodává, co po vložení certifikátu ještě zbývá.
     *
     * <p>Nález uživatele 21. 9. 2026 na „kval podpis SK eOP.pdf": na kartě stály oba, o témž
     * razítku od „Free TSA Server", a řekly totéž dvakrát.
     */
    @Test
    @DisplayName("Je-li razítko mimo EUTL i bez kotvy, mluví o tom jen důvod 21")
    void reasonNineteenIsSilencedWhenTwentyOneSaysItBetter() {
        // 23 → důvod 19, 27 → důvod 21.
        ReportMapper.ReasonFacts noChain =
                new ReportMapper.ReasonFacts(java.util.Set.of(), true);
        List<Integer> derived = reasons(noChain, 23, 27);
        assertEquals(List.of(21), derived, derived.toString());
    }

    @Test
    @DisplayName("Jakmile se ty důvody rozejdou, devatenáctka zůstane")
    void reasonNineteenStaysWhenItIsTheOnlyCarrier() {
        // Certifikát autority vložený do úložiště: razítko se ověří, situace 27 nenastane —
        // a že ta autorita v EUTL není, řekne už jen důvod 19.
        List<Integer> pinned = reasons(new ReportMapper.ReasonFacts(java.util.Set.of(), true), 23);
        assertTrue(pinned.contains(19), pinned.toString());

        // Razítko od autority v EUTL, které neobstálo z jiné příčiny: tam se ty dvě věty
        // nepřekrývají a předpoklad potlačení neplatí.
        List<Integer> otherCause = reasons(new ReportMapper.ReasonFacts(java.util.Set.of(), false),
                23, 27);
        assertTrue(otherCause.contains(19), otherCause.toString());
        assertTrue(otherCause.contains(21), otherCause.toString());
    }

    @Test
    @DisplayName("Umlčí-li jedenadvacítku poškozené razítko, devatenáctka zůstane")
    void reasonNineteenSurvivesWhenTwentyOneIsItselfSilenced() {
        // 36 → důvod 23, a ten důvod 21 potlačuje. Text o poškozeném razítku ale mluví o jiném
        // razítku než to mimo EUTL, takže by zpráva o EUTL zmizela úplně.
        List<Integer> derived = reasons(new ReportMapper.ReasonFacts(java.util.Set.of(), true),
                23, 27, 36);
        assertTrue(derived.contains(19), derived.toString());
        assertTrue(derived.contains(23), derived.toString());
        assertFalse(derived.contains(21), derived.toString());
    }

    @Test
    @DisplayName("Bez znalosti řetězů se nepotlačuje nic")
    void withoutTheChainAnswerNothingIsSilenced() {
        // Tvar o dvou parametrech je konzervativní z definice: radu je lépe nechat stát než umlčet
        // tu, která vede k cíli. Volají ho zkoušky a nástroje nad čistou funkcí.
        List<Integer> derived = reasons(withUnknownCert());
        assertTrue(derived.contains(6), derived.toString());
        assertTrue(derived.contains(12), derived.toString());
    }

    @Test
    @DisplayName("Důvod 4 umlčí celou rodinu rad o revokačních údajích")
    void reasonFourSilencesRevocationAdvice() {
        List<Integer> derived = reasons(S_EXPIRED_CERT_NO_TS, S_POST_EXPIRY_OCSP,
                S_EXPIRED_RESPONDER, S_UNKNOWN_STATUS, S_UNUSABLE_REVOC, S_NO_REVOC,
                S_ISSUER_OUT_OF_RANGE, S_SILENT_CRL);
        assertEquals(List.of(4), derived,
                "Bez důkazu existence nezmění stav revokace nic — má zůstat jen důvod 4: " + derived);
    }

    @Test
    @DisplayName("Bez důvodu 4 ty rady svítí dál")
    void withoutReasonFourTheAdviceStays() {
        List<Integer> derived = reasons(S_EXPIRED_RESPONDER, S_NO_REVOC);
        assertTrue(derived.contains(6), derived.toString());
        assertTrue(derived.contains(12), derived.toString());
        assertFalse(derived.contains(4), derived.toString());
    }

    @Test
    @DisplayName("Touž rodinu umlčují i důvody 20 a 21 — rozhodný okamžik leží mimo platnost stejně")
    void reasonsTwentyAndTwentyOneSilenceTheSameFamily() {
        // 27 → důvod 21 (žádné razítko neslouží jako PoE); 25 bez 27 → důvod 20 (podpis až po
        // expiraci). Obě situace naráz nastat nemohou, proto dvě samostatná měření.
        List<Integer> withoutPoe = reasons(27, S_EXPIRED_RESPONDER, S_NO_REVOC, S_UNKNOWN_STATUS);
        assertEquals(List.of(21), withoutPoe, withoutPoe.toString());

        List<Integer> signedAfterExpiry = reasons(25, S_EXPIRED_RESPONDER, S_NO_REVOC);
        assertEquals(List.of(20), signedAfterExpiry, signedAfterExpiry.toString());
    }

    @Test
    @DisplayName("Odvolání zůstane pojmenované i tam, kde chybí důkaz existence")
    void provenRevocationIsNotSilenced() {
        // Důvod 22 není rada, nýbrž fakt: certifikát byl k rozhodnému okamžiku odvolán. Na sbírce
        // je 14 podpisů se sub-indikací REVOKED_NO_POE, kterým by po umlčení zůstalo jen „SHA-1
        // a žádné razítko neslouží jako PoE“ — a o odvolání by se čtenář z výčtu nedozvěděl nic.
        List<Integer> withoutPoe = reasons(27, 30, S_SHA1);
        assertTrue(withoutPoe.contains(22), "Odvolání musí zůstat pojmenované: " + withoutPoe);
        assertTrue(withoutPoe.contains(21), withoutPoe.toString());

        // Totéž u čtyřky: do 9. 9. 2026 ji umlčovala, a to z předpokladu, který mezitím padl —
        // text důvodu 22 už změnu verdiktu neslibuje.
        List<Integer> expiredNoTs = reasons(S_EXPIRED_CERT_NO_TS, 30);
        assertTrue(expiredNoTs.contains(22), expiredNoTs.toString());
    }

    @Test
    @DisplayName("Nálezy mimo revokaci čtyřka neumlčuje")
    void findingsOutsideRevocationSurvive() {
        List<Integer> derived = reasons(S_EXPIRED_CERT_NO_TS, S_SHA1, S_UNKNOWN_CERT);
        assertTrue(derived.contains(15), "SHA-1 je druhá překážka, na kterou čtenář narazí: " + derived);
        assertTrue(derived.contains(3), "Připnout certifikát autority lze bez ohledu na čtyřku: " + derived);
    }

    /**
     * Krytý prvek bez vlastního razítka: důkaz existence z doby platnosti dodal pozdější prvek,
     * takže čtyřka nesmí umlčet revokační rady — překážkou je právě revokační údaj.
     */
    @Test
    void coveredElementKeepsRevocationAdviceNextToReason4() {
        var covered = new ReportMapper.ReasonFacts(
                java.util.Set.copyOf(java.util.List.of(2, 6, 10, 11, 12, 24, 25)), false, true);
        List<Integer> withCover = reasons(covered, S_EXPIRED_CERT_NO_TS, S_POST_EXPIRY_OCSP);
        assertTrue(withCover.contains(4), "Čtyřka zůstává: " + withCover);
        assertTrue(withCover.contains(2), "Ústupek k post-expiračním údajům pomůže: " + withCover);

        var notCovered = new ReportMapper.ReasonFacts(
                java.util.Set.copyOf(java.util.List.of(2, 6, 10, 11, 12, 24, 25)), false, false);
        List<Integer> without = reasons(notCovered, S_EXPIRED_CERT_NO_TS, S_POST_EXPIRY_OCSP);
        assertFalse(without.contains(2), "Bez krytí rada důvodu 2 nikam nevede: " + without);
    }

    /**
     * Poškozené razítko vedle platného razítka z doby platnosti certifikátu výsledek nedrží:
     * důvody 21 a 23 se nevydají a revokační rada (důvod 12) zůstane. Tvar podle nálezu
     * uživatele 13. 10. 2026 — situace 4, 9, 27, 36 nad podpisem CAdES-T.
     */
    @Test
    @DisplayName("platné razítko z doby platnosti: neobstálé razítko nerozhoduje, rada 12 zůstane")
    void brokenTimestampNextToValidPoe() {
        int sFailedTs = 27, sBrokenTs = 36;
        var withPoe = new ReportMapper.ReasonFacts(
                java.util.Set.copyOf(java.util.List.of(2, 6, 10, 11, 12, 24, 25)), false, false, true);
        List<Integer> r = reasons(withPoe, S_NO_REVOC, sFailedTs, sBrokenTs);
        assertFalse(r.contains(21), "Neobstálé razítko výsledek nedrží: " + r);
        assertFalse(r.contains(23), "Poškozené razítko jde do poznámky: " + r);
        assertTrue(r.contains(12), "Skutečná příčina — chybějící revokační údaj: " + r);

        var withoutPoe = new ReportMapper.ReasonFacts(
                java.util.Set.copyOf(java.util.List.of(2, 6, 10, 11, 12, 24, 25)), false, false, false);
        List<Integer> r2 = reasons(withoutPoe, S_NO_REVOC, sFailedTs, sBrokenTs);
        assertTrue(r2.contains(23), "Bez jiného razítka poškozené razítko rozhoduje: " + r2);
        assertFalse(r2.contains(12), "A revokační rada pak nikam nevede: " + r2);
    }
}
