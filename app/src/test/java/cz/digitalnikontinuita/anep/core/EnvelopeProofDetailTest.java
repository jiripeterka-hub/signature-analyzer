package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Věta situace 57 — důkaz existence z obalu (1. 11. 2026).
 *
 * <p>Čísla jsou z měřeného kontejneru ASiC-E z archivační služby: razítko kontejneru
 * 30. 12. 2019, podpisové razítko vloženého PDF 19. 1. 2015, které obstálo teprve díky razítku
 * kontejneru. Rozhodným okamžikem je pak to dřívější, a věta to musí říct — „posuzovala se
 * k okamžiku razítka obalu" by o roce 2019 lhala.
 */
class EnvelopeProofDetailTest {

    private static final Instant COVER = Instant.parse("2019-12-30T10:28:27.971Z");
    private static final Instant OWN = Instant.parse("2015-01-19T20:03:30.282Z");

    private static EnvelopeProof proof() {
        return new EnvelopeProof(COVER, "kontejner.asice", "dokument.pdf",
                "dokument je uveden otiskem v manifestu kontejneru", null, null, null, null);
    }

    @Test
    @DisplayName("kde obstálo starší vlastní razítko, věta jmenuje jeho čas jako rozhodný")
    void earlierOwnTimestampBecomesDecisive() {
        String text = ReportMapper.envelopeProofDetail(proof(), OWN);
        assertAll(
                () -> assertTrue(text.contains("kontejner.asice"), text),
                () -> assertTrue(text.contains("30. 12. 2019"), text),
                () -> assertTrue(text.contains("19. 1. 2015"), text),
                () -> assertTrue(text.contains("tento dřívější čas"), text),
                () -> assertFalse(text.contains("právě k němu"), text));
    }

    @Test
    @DisplayName("kde rozhoduje razítko obalu samo, věta ukazuje na něj")
    void envelopeTimestampDecides() {
        String text = ReportMapper.envelopeProofDetail(proof(), COVER);
        assertAll(
                () -> assertTrue(text.contains("právě k němu se jeho platnost posuzovala"), text),
                () -> assertFalse(text.contains("dřívější čas"), text));
    }

    @Test
    @DisplayName("věta vždy řekne, že vyjmutý dokument důkaz ztrácí")
    void sentenceWarnsAboutExtraction() {
        assertAll(
                () -> assertTrue(ReportMapper.envelopeProofDetail(proof(), OWN)
                        .contains("Vyjmutý z obalu")),
                () -> assertTrue(ReportMapper.envelopeProofDetail(proof(), COVER)
                        .contains("Vyjmutý z obalu")));
    }
}
