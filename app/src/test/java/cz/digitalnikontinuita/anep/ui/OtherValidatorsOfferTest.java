package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Druhá cesta do bloku „Kde je šance na úspěšné ověření“: překážkou není revokační údaj, nýbrž
 * časové razítko, které zdejší přísné ověřování nepustí, ale tolerantnější ověřovatel ano.
 *
 * <p>Založeno na vnějším potvrzení z 15. 9. 2026: Úschovna Czech POINT ověřila jako platný
 * {@code priklad6_2_Razitko_do_03_08_2034.pdf}, jehož dvě nejstarší razítka zdejší ověřování
 * nepřijalo — stojí na SHA-1 a vydala je služba s odebraným kvalifikovaným statusem. Ani jedna
 * z těch dvou překážek není revokační, takže dosavadní podmínka blok neotevřela.
 */
class OtherValidatorsOfferTest {

    /** Razítko, které obstálo a jehož autorita má certifikát ještě dlouho platný. */
    private static TimestampEntry anchoring() {
        return new TimestampEntry(SigFixture.STAMPED, true, "Sectigo", SigFixture.LATER,
                "document", true, true, null);
    }

    /** Neporušené razítko, které neobstálo z dané příčiny. */
    private static TimestampEntry rejected(TimestampFailure cause) {
        return new TimestampEntry(SigFixture.STAMPED, false, "I.CA", SigFixture.PAST,
                "signature", false, true, cause);
    }

    /** Tvar, který potvrdila Úschovna: řetěz ukotvený, nejstarší razítko odmítnuté. */
    private static SigFixture.Builder czechPointShape() {
        return SigFixture.sig()
                .format("PAdES-BASELINE-LTA")
                .certNotAfter(SigFixture.PAST)
                .timestamps(List.of(rejected(TimestampFailure.EXPIRED_NO_POE), anchoring()))
                .situations(4, 15, 25, 27, 32, 33, 34, 43)
                .reasons(15, 21);
    }

    @Test
    @DisplayName("odebraný kvalifikovaný status a SHA-1 blok otevřou")
    void withdrawnServiceAndSha1Open() {
        assertTrue(AdvisorView.shouldOfferOtherValidators(czechPointShape().build()));
    }

    @Test
    @DisplayName("stačí zastaralý otisk, i když status odebrán nebyl")
    void cryptoAlone() {
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(SigFixture.PAST)
                .timestamps(List.of(rejected(TimestampFailure.CRYPTO), anchoring()))
                .situations(4, 15, 27)
                .reasons(15, 21)
                .build();
        assertTrue(AdvisorView.shouldOfferOtherValidators(sig));
    }

    @Test
    @DisplayName("porušené razítko cestu neotevře — rozbitý otisk neuzná nikdo")
    void brokenTimestampStaysShut() {
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(SigFixture.PAST)
                .timestamps(List.of(
                        new TimestampEntry(SigFixture.STAMPED, false, "I.CA", SigFixture.PAST,
                                "signature", false, false, TimestampFailure.BROKEN),
                        anchoring()))
                .situations(4, 27, 36)
                .reasons(21)
                .build();
        assertFalse(AdvisorView.shouldOfferOtherValidators(sig));
    }

    @Test
    @DisplayName("řetěz bez kotvy důvěry cestu neotevře")
    void noChainStaysShut() {
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(SigFixture.PAST)
                .timestamps(List.of(rejected(TimestampFailure.NO_CHAIN), anchoring()))
                .situations(4, 27)
                .reasons(21)
                .build();
        assertFalse(AdvisorView.shouldOfferOtherValidators(sig));
    }

    @Test
    @DisplayName("služba, kterou seznamy nikdy neznaly (situace 23), je veto")
    void neverListedServiceIsVeto() {
        // Uživatelovo pozorování: tuzemské validátory směřují shovívavost ke službě, která
        // kvalifikovaný status (dříve akreditaci) kdysi měla — ne k té, o níž seznamy nevědí.
        SignatureSummary sig = czechPointShape().situations(4, 15, 23, 25, 27, 43).build();
        assertFalse(AdvisorView.shouldOfferOtherValidators(sig));
    }

    @Test
    @DisplayName("beznadějný důvod přebije i druhou cestu")
    void hopelessReasonWins() {
        // Důvod 20 — podpis prokazatelně vznikl po expiraci certifikátu. To žádná shovívavost
        // k razítku nespraví.
        SignatureSummary sig = czechPointShape().reasons(15, 20, 21).build();
        assertFalse(AdvisorView.shouldOfferOtherValidators(sig));
    }

    @Test
    @DisplayName("vlastní kotva důvěry přebije i druhou cestu")
    void customAnchorWins() {
        assertFalse(AdvisorView.shouldOfferOtherValidators(
                czechPointShape().customAnchor().build()));
    }

    @Test
    @DisplayName("neukotvený řetěz cestu neotevře — isTooLate platí pro obě")
    void unanchoredChainStaysShut() {
        // Bez razítka, které by obstálo, padá rozhodný okamžik na přítomnost a podpisový
        // certifikát je po expiraci.
        //
        // TOHLE NENÍ OPATRNOST, JE TO ZMĚŘENÁ DĚLÍCÍ ČÁRA. Bez té závory přibudou na D:/tmp
        // přesně tři dokumenty s nekladným verdiktem a Úschovna Czech POINT neuzná ani jeden
        // (15. 9. 2026) — kdežto 44 dokumentů, které cesta otevře uvnitř závory, uzná všechny.
        // Uvnitř 44 ze 44, vně 0 ze 3; podrobnosti v javadoc {@code tolerantTimestampMayHelp}
        // a {@code isTooLate}. Kdo tenhle test ruší, ruší to měření.
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(SigFixture.PAST)
                .timestamps(List.of(rejected(TimestampFailure.EXPIRED_NO_POE)))
                .situations(4, 15, 27, 43)
                .reasons(15, 21)
                .build();
        assertFalse(AdvisorView.shouldOfferOtherValidators(sig));
    }

    @Test
    @DisplayName("kladný výsledek cestu neotevře, i když nese odmítnuté razítko")
    void passedSignatureStaysShut() {
        // Naměřeno na „kratsi razitko\\LT s podpisem delsim nez je casove razitko extended.pdf“:
        // platný podpis, který vedle platných razítek nese ještě jedno odmítnuté.
        SignatureSummary sig = SigFixture.sig()
                .indication("TOTAL_PASSED")
                .certNotAfter(SigFixture.PAST)
                .timestamps(List.of(rejected(TimestampFailure.EXPIRED_NO_POE), anchoring()))
                .situations(31, 32, 33, 34)
                .build();
        assertFalse(AdvisorView.shouldOfferOtherValidators(sig));
    }

    @Test
    @DisplayName("revokační cesta funguje dál beze změny")
    void revocationRouteUnchanged() {
        SignatureSummary sig = SigFixture.sig()
                .certNotAfter(SigFixture.PAST)
                .timestamps(List.of(anchoring()))
                .situations(6)
                .reasons(2)
                .build();
        assertTrue(AdvisorView.shouldOfferOtherValidators(sig));
    }
}
