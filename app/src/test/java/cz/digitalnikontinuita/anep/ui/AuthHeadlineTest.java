package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.PdfProfileExtractor;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Záhlaví záložek říká, v čem ty autentizační prvky jsou — a nesmí z dokumentu udělat kontejner.
 *
 * <p>Rozhoduje se podle formátu prvního prvku, a ten výčet se rozešel se skutečností: uváděl jen
 * PAdES a PDF-NOT-ETSI, takže podpis se starším SubFiltrem {@code adbe.pkcs7.*}, který DSS hlásí
 * jako PKCS7-B/T/LT/LTA, propadl do větve pro kontejnery. Nad
 * „pecet_certifikacni_uznavana_basic_razitko.pdf“ pak v záhlaví stálo „Kontejner má 1 autentizační
 * prvek“, ačkoli šlo o obyčejné PDF (nález uživatele 9. 9. 2026). V korpusu {@code D:\tmp} nese
 * formát PKCS7 čtrnáct podpisů ve dvanácti souborech, takže to nebyl ojedinělý kus.
 */
class AuthHeadlineTest {

    private static ValidationResult resultWith(String format) {
        return new ValidationResult("dokument.pdf", null, false,
                List.of(SigFixture.sig().format(format).build()), null, null, null);
    }

    @Test
    @DisplayName("Podpis v PDF je dokument, ať už ho DSS pojmenuje jakkoli")
    void pdfSignatureFormatsAreDocuments() {
        for (String format : List.of("PAdES-BASELINE-B", "PAdES-BASELINE-LTA",
                "PKCS7-B", "PKCS7-T", "PKCS7-LT", "PKCS7-LTA", "PDF-NOT-ETSI")) {
            String headline = ResultView.authCountLabel(resultWith(format));
            assertTrue(headline.startsWith("Na dokumentu"),
                    "Formát " + format + " je podpis v PDF, ne kontejner: " + headline);
        }
    }

    @Test
    @DisplayName("Samostatné časové razítko zůstává dokumentem")
    void standaloneTimestampStaysADocument() {
        String headline = ResultView.authCountLabel(resultWith("TimeStampToken"));
        assertTrue(headline.startsWith("Na dokumentu"), headline);
    }

    private static ValidationResult pdf(String version, List<String> standards, int signatures) {
        ValidationResult result = new ValidationResult("dokument.pdf", null, false,
                signatures == 0 ? List.of() : List.of(SigFixture.sig().format("PAdES-BASELINE-LTA").build()),
                null, null, null);
        result.setPdfProfile(new PdfProfileExtractor.Profile(version, standards, false, null));
        return result;
    }

    @Test
    @DisplayName("U PDF pojmenuje záhlaví podtyp i verzi souboru")
    void pdfSubtypeAndVersion() {
        assertEquals("Na dokumentu PDF (podtypu PDF/A-2b, verze 1.7) je 1 autentizační prvek",
                ResultView.authCountLabel(pdf("1.7", List.of("PDF/A-2b"), 1)));
    }

    @Test
    @DisplayName("Bez deklarace podtypu zbyde v závorce verze")
    void versionAlone() {
        assertEquals("Na dokumentu PDF (verze 1.4) není žádný autentizační prvek",
                ResultView.authCountLabel(pdf("1.4", List.of(), 0)));
    }

    @Test
    @DisplayName("K víc podmnožinám naráz — typicky PDF/A spolu s PDF/UA")
    void severalStandards() {
        assertEquals("Na dokumentu PDF (podtypů PDF/A-2b a PDF/UA-1, verze 1.7) je 1 autentizační prvek",
                ResultView.authCountLabel(pdf("1.7", List.of("PDF/A-2b", "PDF/UA-1"), 1)));
    }

    @Test
    @DisplayName("Není-li to PDF, záhlaví o podobě souboru nic netvrdí")
    void withoutProfileNothingChanges() {
        assertTrue(ResultView.authCountLabel(resultWith("PAdES-BASELINE-B")).startsWith("Na dokumentu "));
    }

    /**
     * Rozdělení záhlaví na dvě věty, když se pod ním vysázejí dokumentové bloky.
     *
     * <p>Pokyn uživatele z 18. 9. 2026. Dokud stálo všechno v jedné větě, nebylo z ničeho poznat,
     * které z bloků pod ní mluví o dokumentu a které o jednotlivých prvcích.
     */
    @Test
    @DisplayName("Nadpis nad dokumentovými bloky jmenuje formát i verzi")
    void documentHeadingNamesTheProfile() {
        // Čárka před verzí: bez ní by se 1.7 četla jako verze té podmnožiny, a PDF/A-2b žádné
        // verze nemá. S čárkou stojí oba údaje vedle sebe jako dva samostatné.
        assertEquals("Dokument je ve formátu PDF/A-2b, verze 1.7",
                ResultView.documentProfileSentence(pdf("1.7", List.of("PDF/A-2b"), 1)));
        assertEquals("Dokument je ve formátu PDF/A-2b a PDF/UA-1, verze 1.7",
                ResultView.documentProfileSentence(pdf("1.7", List.of("PDF/A-2b", "PDF/UA-1"), 1)));
        // Bez deklarované podmnožiny zbývá prosté PDF — „ve formátu" a nic za tím by byla věta
        // bez obsahu.
        assertEquals("Dokument je ve formátu PDF, verze 1.4",
                ResultView.documentProfileSentence(pdf("1.4", List.of(), 0)));
        // A bez verze zůstane samotný formát.
        assertEquals("Dokument je ve formátu PDF/A-2b",
                ResultView.documentProfileSentence(pdf(null, List.of("PDF/A-2b"), 1)));
    }

    @Test
    @DisplayName("Bez deklarované podoby nemá nadpis co jmenovat")
    void noProfileNoSentence() {
        assertNull(ResultView.documentProfileSentence(resultWith("PAdES-BASELINE-B")),
                "Volající pak sáhne po obecném „Dokument jako celek“");
    }

    /**
     * Druhá věta už podobu souboru neopakuje — jinak by ji čtenář měl na obrazovce dvakrát,
     * jednou nad bloky a jednou nad kartami.
     */
    @Test
    @DisplayName("Věta o prvcích se bez podoby souboru obejde")
    void countSentenceWithoutTheProfile() {
        assertEquals("Na dokumentu je 1 autentizační prvek",
                ResultView.authCountLabel(pdf("1.7", List.of("PDF/A-2b"), 1), false));
        // A s ní zůstává, jak byla — jedno záhlaví nad dokumentem bez bloků se nemění.
        assertEquals("Na dokumentu PDF (podtypu PDF/A-2b, verze 1.7) je 1 autentizační prvek",
                ResultView.authCountLabel(pdf("1.7", List.of("PDF/A-2b"), 1), true));
    }

    /**
     * Podpisy, které nese samo PDF předložené k externímu podpisu. Nález uživatele 3. 10. 2026:
     * karta je vydávala za další prvky externího souboru; teď o nich jen říká, že tu jsou.
     */
    private static ValidationResult external(int pdfSignatures, int pdfTimestamps) {
        ValidationResult result = new ValidationResult("podpis.p7s", null, true,
                List.of(SigFixture.sig().format("CAdES-BASELINE-T").build()), null, null, null);
        result.setContentPdfSignatures(pdfSignatures, pdfTimestamps);
        return result;
    }

    @Test
    @DisplayName("Externí podpis upozorní na vlastní podpis předloženého PDF")
    void externalSignatureNotesTheContentPdfSignature() {
        assertEquals("V externím souboru je 1 autentizační prvek. Předložený dokument PDF nese i"
                        + " vlastní podpis či pečeť; spolu s externím podpisem se neověřuje,"
                        + " ověříte jej předložením samotného PDF",
                ResultView.authCountLabel(external(1, 0)));
        assertEquals("V externím souboru je 1 autentizační prvek. Předložený dokument PDF nese i"
                        + " 2 vlastní podpisy či pečeti a dokumentové časové razítko; spolu"
                        + " s externím podpisem se neověřují, ověříte je předložením samotného PDF",
                ResultView.authCountLabel(external(2, 1)));
        assertEquals("V externím souboru je 1 autentizační prvek",
                ResultView.authCountLabel(external(0, 0)));
    }
}
