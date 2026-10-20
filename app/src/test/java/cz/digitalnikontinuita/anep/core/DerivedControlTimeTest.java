package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Okamžik, ke kterému se pouští druhý průchod u prvku krytého pozdějším prvkem, a věta, která to
 * čtenáři říká.
 *
 * <p>Čísla jsou z „blok5v4 signed.pdf", prvek č. 3: vlastní razítko 19:33:34, vložená odpověď OCSP
 * 19:35:24, razítko krycího prvku 19:39:19. Rozhodný okamžik je to vlastní razítko, ale posuzovat
 * se k němu nedá — revokační údaj v tu chvíli ještě nebyl na světě.
 */
class DerivedControlTimeTest {

    private static final Instant OWN = Instant.parse("2019-10-06T19:33:34.668Z");
    private static final Instant OCSP = Instant.parse("2019-10-06T19:35:24Z");
    private static final Instant PROOF = Instant.parse("2019-10-06T19:39:19.107Z");

    @Test
    @DisplayName("je-li rozhodný okamžik starší než revokační údaj, počítá se k okamžiku krycího prvku")
    void anachronisticInstantFallsBackToTheProof() {
        assertEquals(PROOF, ValidatorFactory.controlTimeFor(OWN, PROOF, OCSP));
    }

    @Test
    @DisplayName("údaj z doby před rozhodným okamžikem ten okamžik nechává být")
    void olderRevocationKeepsTheEarlierInstant() {
        Instant older = Instant.parse("2019-10-06T19:30:00Z");
        assertEquals(OWN, ValidatorFactory.controlTimeFor(OWN, PROOF, older));
    }

    @Test
    @DisplayName("bez revokačního údaje se nemá co porovnávat")
    void noRevocationDataKeepsTheEarlierInstant() {
        assertEquals(OWN, ValidatorFactory.controlTimeFor(OWN, PROOF, null));
    }

    @Test
    @DisplayName("nemá-li prvek vlastní starší razítko, je okamžik jen jeden")
    void withoutOwnTimestampNothingToChoose() {
        assertAll(
                () -> assertEquals(PROOF, ValidatorFactory.controlTimeFor(PROOF, PROOF, OCSP)),
                () -> assertEquals(null, ValidatorFactory.controlTimeFor(null, PROOF, OCSP)));
    }

    /**
     * Dokud věta rozlišovala jen dva případy, tvrdila i tady „platnost se posuzovala k okamžiku
     * ještě dřívějšímu" — což od chvíle, kdy se k němu neposuzuje, není pravda.
     */
    @Test
    @DisplayName("věta o krytí přizná, že se posuzovalo k okamžiku krycího prvku")
    void sentenceAdmitsTheActualInstant() {
        String text = ReportMapper.coveringProofDetail(
                new ReportMapper.CoveringProof(PROOF, false, "S-4"), OWN, PROOF,
                Map.of("S-4", 4), true);
        assertAll(
                () -> assertTrue(text.contains("Prvek č. 4"), text),
                () -> assertTrue(text.contains("ještě neexistoval"), text),
                () -> assertFalse(text.contains("Platnost se pak posuzovala"), text));
    }

    @Test
    @DisplayName("kde se posuzovalo k dřívějšímu okamžiku, věta zůstává původní")
    void sentenceUnchangedWhenTheEarlierInstantWasUsed() {
        String text = ReportMapper.coveringProofDetail(
                new ReportMapper.CoveringProof(PROOF, false, "S-4"), OWN, OWN,
                Map.of("S-4", 4), true);
        assertTrue(text.contains("Platnost se pak posuzovala k okamžiku ještě dřívějšímu"), text);
    }

    @Test
    @DisplayName("kde je okamžik jen jeden, věta na něj ukazuje rovnou")
    void sentenceForTheSingleInstant() {
        String text = ReportMapper.coveringProofDetail(
                new ReportMapper.CoveringProof(PROOF, false, "S-4"), PROOF, PROOF,
                Map.of("S-4", 4), true);
        assertTrue(text.contains("právě k němu se jeho platnost posuzovala"), text);
    }

    // ---------------------------------------------------------------- pozdější kontrolní čas

    /**
     * Čísla z nálezu uživatele 22. 9. 2026: pečeť bez vlastního
     * razítka, krycí razítko 29. 4. 2025, certifikát pečeti platil do 4. 4. 2026, jediná odpověď
     * OCSP je z 22. 9. 2026 a její respondér platí od 23. 3. 2026. K okamžiku krycího razítka
     * tedy revokační údaj ještě „nebyl na světě"; k poslednímu okamžiku platnosti certifikátu už
     * ano.
     */
    private static final Instant COVERING = Instant.parse("2025-04-29T07:59:06Z");
    private static final Instant CERT_END = Instant.parse("2026-04-04T11:45:05Z");
    private static final Instant TODAY = Instant.parse("2026-09-22T09:00:00Z");

    @Test
    @DisplayName("druhý pokus míří na poslední okamžik platnosti certifikátu")
    void theLateAttemptAimsAtTheLastMomentOfValidity() {
        assertEquals(CERT_END.minusSeconds(1),
                ValidatorFactory.lateControlTimeFor(CERT_END, COVERING, COVERING, TODAY));
    }

    @Test
    @DisplayName("dokud certifikát platí, není kam posunout")
    void nothingToTryWhileTheCertificateIsStillValid() {
        Instant beforeExpiry = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(null,
                ValidatorFactory.lateControlTimeFor(CERT_END, COVERING, COVERING, beforeExpiry));
    }

    @Test
    @DisplayName("důkaz až po expiraci pozdější okamžik nezakládá")
    void aProofAfterExpiryProvesNothingEarlier() {
        Instant proofAfter = Instant.parse("2026-06-01T00:00:00Z");
        assertEquals(null,
                ValidatorFactory.lateControlTimeFor(CERT_END, proofAfter, proofAfter, TODAY));
    }

    @Test
    @DisplayName("nemá-li se kam posunout dál, druhý pokus se nedělá")
    void noSecondAttemptWhenTheFirstIsAlreadyLater() {
        Instant primary = Instant.parse("2026-04-04T11:45:04Z");
        assertEquals(null,
                ValidatorFactory.lateControlTimeFor(CERT_END, COVERING, primary, TODAY));
    }

    @Test
    @DisplayName("bez konce platnosti certifikátu se nerozhoduje")
    void withoutTheExpiryThereIsNothingToCompute() {
        assertEquals(null,
                ValidatorFactory.lateControlTimeFor(null, COVERING, COVERING, TODAY));
    }

    /**
     * Věta na kartě musí přiznat i tenhle třetí, nejpozdější okamžik — jinak by tvrdila, že se
     * posuzovalo k času krycího razítka, ačkoli se posuzovalo skoro o rok později.
     */
    @Test
    @DisplayName("věta o krytí přizná i posun za čas krycího razítka")
    void sentenceAdmitsTheLateInstant() {
        String text = ReportMapper.coveringProofDetail(
                new ReportMapper.CoveringProof(COVERING, false, "S-4"), COVERING,
                CERT_END.minusSeconds(1), Map.of("S-4", 4), true);
        assertAll(
                () -> assertTrue(text.contains("ještě pozdějšímu"), text),
                () -> assertTrue(text.contains("přísnější"), text));
    }

    /**
     * A tam, kde důkaz nepomohl, se o něm musí psát taky — dokud se text vydával jen se
     * situací 54, tvrdily texty u takového prvku, že není o co se opřít.
     */
    @Test
    @DisplayName("nepomohl-li důkaz, věta to řekne")
    void sentenceSaysTheProofDidNotHelp() {
        String text = ReportMapper.coveringProofDetail(
                new ReportMapper.CoveringProof(COVERING, false, "S-4"), null, null,
                Map.of("S-4", 4), false);
        assertAll(
                () -> assertTrue(text.contains("existoval nejpozději"), text),
                () -> assertTrue(text.contains("prokázat nepodařilo"), text));
    }
}
