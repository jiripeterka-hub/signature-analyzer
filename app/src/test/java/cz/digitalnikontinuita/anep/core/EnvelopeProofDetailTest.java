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
                "dokument je uveden otiskem v manifestu kontejneru", null, null, null, null, null, false);
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

    @Test
    @DisplayName("u vnořeného obalu věta jmenuje oba obaly a řekne, co razítko kryje")
    void nestedEnvelopeNamesBoth() {
        String text = ReportMapper.envelopeProofDetail(proof().through("vnitrni.asice"), COVER);
        assertAll(
                () -> assertTrue(text.contains("z obalu vnitrni.asice, který sám leží v obalu"
                        + " kontejner.asice"), text),
                () -> assertTrue(text.contains("kryje obal vnitrni.asice, v němž dokument leží"), text),
                () -> assertTrue(text.contains("ten je uveden otiskem"), text));
    }

    @Test
    @DisplayName("razítko podržené vnějším obalem: věta to řekne a jmenuje vnější obal")
    void supportedTimestampNamesOuterEnvelope() {
        EnvelopeProof outer = new EnvelopeProof(Instant.parse("2021-03-01T08:00:00Z"),
                "zprava.zfo", "kontejner.asice", "dokument leží jako příloha v podepsaném obsahu",
                null, null, null, null, null, false);
        String text = ReportMapper.envelopeProofDetail(proof().supportedBy(outer), COVER);
        assertAll(
                () -> assertTrue(text.contains("by samo už neobstálo"), text),
                () -> assertTrue(text.contains("obalu zprava.zfo z 1. 3. 2021"), text),
                () -> assertTrue(text.contains("Vyjmutý z vnějšího obalu"), text));
    }

    @Test
    @DisplayName("u externího podpisu věta nemluví o obalu, z něhož byl dokument předán")
    void detachedSignatureSentence() {
        EnvelopeProof detached = new EnvelopeProof(COVER, "podpis.p7s", "dokument.pdf",
                "otisk dokumentu je v tomto podpisu", null, null, null, null, null, true);
        String text = ReportMapper.envelopeProofDetail(detached, COVER);
        assertAll(
                () -> assertTrue(text.contains("externím podpisem podpis.p7s"), text),
                () -> assertTrue(text.contains("bez externího podpisu"), text),
                () -> assertFalse(text.contains("předán z obalu"), text));
    }
}
