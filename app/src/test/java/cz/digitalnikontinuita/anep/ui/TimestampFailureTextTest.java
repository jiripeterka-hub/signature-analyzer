package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Texty u důvodu 21, které pojmenovávají příčinu selhání razítka.
 *
 * <p>Větve {@code REVOKED}, {@code NO_REVOCATION} a {@code BROKEN} se ve sbírce neukázaly ani
 * jednou — sken 54 souborů z {@code D:\tmp} dal 11 prvků s důvodem 21, a to jen CRYPTO (7),
 * NO_CHAIN (3) a EXPIRED_NO_POE (1). Bez tohohle testu by tedy zůstaly nespuštěné a první, kdo by
 * si jich všiml, by byl uživatel s takovým dokumentem.
 */
class TimestampFailureTextTest {

    private static final Instant STAMPED = Instant.parse("2012-01-17T22:09:02Z");
    private static final Instant NOT_AFTER = Instant.parse("2016-12-21T00:00:00Z");

    private static SignatureSummary sigWith(TimestampFailure cause, Integer... reasons) {
        return SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(reasons)
                .timestamps(List.of(new TimestampEntry(
                        STAMPED, false, "I.CA TSS/TSU 3", NOT_AFTER, "sig", false, true, cause)))
                .build();
    }

    @Test
    @DisplayName("každá pojmenovaná příčina má větu o problému i radu")
    void everyNamedCauseHasProblemAndSolution() {
        for (TimestampFailure cause : TimestampFailure.values()) {
            if (cause == TimestampFailure.OTHER) continue;
            SignatureSummary sig = sigWith(cause, 21);
            assertNotNull(AdvisorView.timestampFailureProblem(sig),
                    "chybí věta o problému pro " + cause);
            // Přes reasonSolution, ne přes timestampFailureSolution: příčina nemusí mít vlastní
            // radu a pak se ukáže obecná z katalogu důvodu 21. Čtenáře zajímá, že tam nějaká
            // stojí, ne ze kterého oddílu souboru pochází.
            assertNotNull(AdvisorView.reasonSolution(sig, 21, ResultView.REASONS.get(21)),
                    "chybí rada pro " + cause);
            assertNotNull(AdvisorView.timestampFailureDetail(sig),
                    "chybí rozvedení pro " + cause);
        }
    }

    /**
     * Nález uživatele z 20. 9. 2026: u razítka, které kryje pozdější razítko v témže dokumentu,
     * stály v důvodu 21 tři nepravdy — „nic nedokládá, že razítko vzniklo ještě za ní“,
     * „to měl zajistit archivní (uchovávací) zásah ještě v době, kdy certifikát platil“
     * a „Tady ověřování takový doklad nemá“. Doklad tam je a je ze správné doby; DSS ten čas
     * samo vede jako {@code TIMESTAMP_POE_TIME}.
     */
    @Test
    @DisplayName("Kryje-li razítko pozdější, texty důvodu 21 o chybějícím dokladu mlčí")
    void aLaterTimestampChangesAllThreeTexts() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(21)
                .timestamps(List.of(
                        new TimestampEntry(STAMPED, false, "I.CA TSS/TSU 3", NOT_AFTER,
                                "sig", false, true, TimestampFailure.EXPIRED_NO_POE),
                        // Pozdější, samo obstálo a vzniklo ještě za platnosti certifikátu
                        // toho prvního.
                        new TimestampEntry(NOT_AFTER.minusSeconds(86_400), true, "I.CA TSU 2",
                                Instant.parse("2030-01-01T00:00:00Z"), "doc", true, true)))
                .build();

        String problem = AdvisorView.timestampFailureProblem(sig);
        assertFalse(problem.contains("nic nedokládá"), problem);
        assertTrue(problem.contains("dokládá pozdější razítko"), problem);

        String detail = AdvisorView.timestampFailureDetail(sig);
        assertFalse(detail.contains("takový doklad nemá"), detail);
        assertTrue(detail.contains("je ze správné doby"), detail);

        String help = AdvisorView.timestampFailureSolution(sig);
        assertFalse(help.contains("Doložit zpětně"), help);
        assertTrue(help.contains("přišel včas"), help);
        assertTrue(help.contains("vložit certifikát"), "Rada o připnutí platí dál: " + help);
    }

    @Test
    @DisplayName("Bez pozdějšího razítka zůstávají texty beze změny")
    void withoutALaterTimestampNothingChanges() {
        SignatureSummary sig = sigWith(TimestampFailure.EXPIRED_NO_POE, 21);
        assertTrue(AdvisorView.timestampFailureProblem(sig).contains("nic nedokládá"));
        assertTrue(AdvisorView.timestampFailureSolution(sig).contains("Doložit zpětně"));
        assertTrue(AdvisorView.timestampFailureDetail(sig).contains("takový doklad nemá"));
    }

    @Test
    @DisplayName("příčina, kterou výklad nerozlišuje, se nechá na obecném znění")
    void otherFallsBackToTheStaticText() {
        SignatureSummary sig = sigWith(TimestampFailure.OTHER, 21);
        assertAll(
                () -> assertNull(AdvisorView.timestampFailureProblem(sig)),
                () -> assertNull(AdvisorView.timestampFailureSolution(sig)),
                () -> assertNull(AdvisorView.timestampFailureDetail(sig)));
    }

    @Test
    @DisplayName("dvě různé příčiny naráz se nepojmenovávají — věta by mluvila jen o jedné")
    void twoCausesFallBack() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(21)
                .timestamps(List.of(
                        new TimestampEntry(STAMPED, false, "TSU 1", NOT_AFTER, "sig", false, true,
                                TimestampFailure.EXPIRED_NO_POE),
                        new TimestampEntry(STAMPED, false, "TSU 2", NOT_AFTER, "doc", false, true,
                                TimestampFailure.NO_CHAIN)))
                .build();
        assertNull(AdvisorView.timestampFailureProblem(sig));
        assertNull(AdvisorView.timestampFailureSolution(sig));
    }

    @Test
    @DisplayName("razítko, které prošlo, žádnou příčinu nevydá")
    void passedTimestampNamesNothing() {
        SignatureSummary sig = SigFixture.sig()
                .timestamps(List.of(new TimestampEntry(
                        STAMPED, true, "TSU", NOT_AFTER, "sig", true, true, null)))
                .build();
        assertNull(AdvisorView.timestampFailureProblem(sig));
    }

    @Test
    @DisplayName("u expirovaného certifikátu stojí ve větě jméno autority i konec platnosti")
    void expiredNamesAuthorityAndDate() {
        String problem = AdvisorView.timestampFailureProblem(
                sigWith(TimestampFailure.EXPIRED_NO_POE, 21));
        assertAll(
                () -> assertTrue(problem.contains("I.CA TSS/TSU 3"), problem),
                () -> assertTrue(problem.contains("21. 12. 2016"), problem));
    }

    /**
     * Rada nesmí skončit u připnutí tam, kde připnutí samo nestačí. Změřeno 14. 9. 2026: připnutí
     * certifikátu TSU posunulo indikaci z {@code OUT_OF_BOUNDS_NO_POE} na
     * {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE} a teprve s tolerancí k SHA-1 razítko prošlo.
     */
    @Test
    @DisplayName("je-li vedle indikován i důvod 15, rada u expirace žádá obojí naráz")
    void expiredWithSha1AsksForBoth() {
        String withSha1 = AdvisorView.timestampFailureSolution(
                sigWith(TimestampFailure.EXPIRED_NO_POE, 15, 21));
        String withoutSha1 = AdvisorView.timestampFailureSolution(
                sigWith(TimestampFailure.EXPIRED_NO_POE, 21));
        assertAll(
                () -> assertTrue(withSha1.contains("Samo o sobě to ale nestačí"), withSha1),
                () -> assertTrue(withSha1.contains("SHA-1"), withSha1),
                () -> assertFalse(withoutSha1.contains("SHA-1"), withoutSha1));
    }

    /**
     * Kotva důvěry projde v DSS jedinou kontrolou (sunset) a revokace se u ní nezjišťuje, takže
     * připnutí certifikátu odvolané autority by důvod odstranilo, aniž by odvolání vyvrátilo.
     * Rada to musí přiznat — jinak si tu zkratku čtenář najde sám a bude ji mít za řešení.
     */
    @Test
    @DisplayName("rada u odvolané autority varuje před zkratkou přes vlastní úložiště")
    void revokedWarnsAboutPinningShortcut() {
        String solution = AdvisorView.timestampFailureSolution(sigWith(TimestampFailure.REVOKED, 21));
        assertAll(
                // Slovesa „připnout" se texty od 20. 9. 2026 zbavily — pokyn uživatele: certifikát
                // se do vlastního úložiště *vkládá*, stejně jako to říká tlačítko v nastavení.
                () -> assertTrue(solution.contains("vložením"), solution),
                () -> assertTrue(solution.contains("odvolání"), solution));
    }

    /**
     * Sehnat seznam CRL z vhodné doby je úkon pro zkušenějšího uživatele, ne obecná rada, a od
     * 19. 9. 2026 podle toho i stojí — v řádku „Pro zkušenější uživatele", který se u téhle
     * příčiny bere z oddílu {@code [21/NO_REVOCATION]}. Do té doby se {@code zkusenejsi} četla
     * jen z katalogových oddílů, takže napsaná u příčiny by byla mrtvým textem.
     */
    @Test
    @DisplayName("rada pro zkušenější u nezjištěné revokace vede do úložiště vlastních CRL")
    void noRevocationPointsAtTheCrlStore() {
        String tip = AdvisorView.expertTip(sigWith(TimestampFailure.NO_REVOCATION, 21), 21);
        assertNotNull(tip, "u NO_REVOCATION chybí rada pro zkušenější");
        assertAll(
                () -> assertTrue(tip.contains("CRL"), tip),
                () -> assertTrue(tip.contains("úložišt"), tip));
    }

    /** Ostatním příčinám se ta rada nepodsouvá — jim by neřekla nic. */
    @Test
    @DisplayName("rada pro zkušenější stojí jen u příčiny, které se týká")
    void expertTipOnlyWhereItBelongs() {
        assertNull(AdvisorView.expertTip(sigWith(TimestampFailure.CRYPTO, 21), 21));
        assertNull(AdvisorView.expertTip(sigWith(TimestampFailure.NO_CHAIN, 21), 21));
    }

    /**
     * Neshoda otisku a nenalezená data jsou od 25. 9. 2026 dvě příčiny. Dřív je věta u BROKEN
     * zmiňovala obě a důvod 31 o samostatném razítku předloženém bez dat tvrdil, že otisk
     * nesouhlasí.
     */
    @Test
    @DisplayName("porušené razítko a nenalezená data mluví každé o svém")
    void brokenAndNoDataAreTold() {
        String broken = AdvisorView.timestampFailureProblem(sigWith(TimestampFailure.BROKEN, 21));
        String noData = AdvisorView.timestampFailureProblem(sigWith(TimestampFailure.NO_DATA, 21));
        assertAll(
                () -> assertTrue(broken.contains("neodpovídá"), broken),
                () -> assertTrue(!broken.contains("nenašla"), broken),
                () -> assertTrue(noData.contains("nenašla"), noData),
                () -> assertTrue(!noData.contains("neodpovídá"), noData));
    }

    @Test
    @DisplayName("věta o problému vždy dořekne, co z toho plyne pro důkaz existence")
    void problemAlwaysExplainsTheConsequence() {
        for (TimestampFailure cause : TimestampFailure.values()) {
            if (cause == TimestampFailure.OTHER) continue;
            String problem = AdvisorView.timestampFailureProblem(sigWith(cause, 21));
            assertTrue(problem.contains("důkaz existence podpisu (PoE)"), cause + ": " + problem);
            assertEquals('.', problem.charAt(problem.length() - 1), cause + ": " + problem);
        }
    }
}
