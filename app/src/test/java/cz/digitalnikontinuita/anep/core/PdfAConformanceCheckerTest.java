package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prověřování shody s PDF/A: co se ověřuje, co ne a co z toho plyne.
 *
 * <p>Že validátor umí říct „vyhovuje" o skutečném dokumentu, hlídá {@code ArchivalExportTest}
 * nad protokoly, které AnEP sám vydává. Tady jde o to, co je kolem: proti čemu se měří,
 * kdy se neměří vůbec a že se nález nikdy nevydává za verdikt o podpisu.
 */
class PdfAConformanceCheckerTest {

    @Test
    @DisplayName("Bez prohlášení o PDF/A se neověřuje nic")
    void nothingDeclaredNothingChecked() {
        assertNull(PdfAConformanceChecker.check(source(null), profile(null)));
        assertNull(PdfAConformanceChecker.check(source(null),
                new PdfProfileExtractor.Profile("1.7", List.of(), false, null)));
    }

    @Test
    @DisplayName("PDF/UA se neověřuje — normu nelze rozhodnout strojem celou")
    void pdfUaIsNotChecked() {
        // Dokument hlásící se jen k PDF/UA-1 nemá flavour PDF/A, takže se neprověřuje.
        PdfProfileExtractor.Profile ua = profileOf(
                "<pdfuaid:part>1</pdfuaid:part>");
        assertEquals(List.of("PDF/UA-1"), ua.standards());
        assertNull(ua.pdfaFlavour());
        assertNull(PdfAConformanceChecker.check(source("<pdfuaid:part>1</pdfuaid:part>"), ua));
    }

    @Test
    @DisplayName("Deklarovaný profil určuje, proti čemu se měří")
    void declaredProfileDecidesTheYardstick() {
        String xmp = "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>";
        PdfProfileExtractor.Profile profile = profileOf(xmp);
        assertEquals("2b", profile.pdfaFlavour());

        PdfAConformanceChecker.Conformance c =
                PdfAConformanceChecker.check(source(xmp), profile);
        assertNotNull(c);
        assertEquals("2b", c.flavour());
        assertFalse(c.inconclusive(), "ověření mělo proběhnout: " + c.failure());
        // Prázdná stránka s poctivě zapsanou deklarací PDF/A-2b opravdu vyhovuje: norma
        // vyjmenovává, co se v dokumentu nesmí objevit, a v tomhle není nic z toho. Nálezy
        // nedělá pouhá stručnost dokumentu.
        assertTrue(c.compliant(), "očekáváno vyhovuje, nálezy: " + c.violations());
        assertTrue(c.violations().isEmpty());
    }

    @Test
    @DisplayName("Porušené pravidlo se vyjmenuje i s odkazem do normy")
    void violationsAreNamed() {
        // Barva v DeviceRGB bez výstupního profilu — ISO 19005-2, 6.2.4.3.
        byte[] pdf = PdfProfileExtractorTest.pdfWithUntaggedRgb();
        PdfProfileExtractor.Profile profile = PdfProfileExtractor.extract(PdfSource.of(pdf), null);
        assertEquals("2b", profile.pdfaFlavour());

        PdfAConformanceChecker.Conformance c =
                PdfAConformanceChecker.check(PdfSource.of(pdf), profile);
        assertNotNull(c);
        assertFalse(c.inconclusive(), "ověření mělo proběhnout: " + c.failure());
        assertFalse(c.compliant());
        assertFalse(c.violations().isEmpty(), "porušená pravidla se měla vyjmenovat");
        assertTrue(c.violations().stream().allMatch(v -> v.occurrences() >= 1));
        assertTrue(c.violations().stream()
                        .allMatch(v -> v.reference().startsWith("ISO 19005-2:2011")),
                c.violations().toString());
    }

    @Test
    @DisplayName("Neznámé datované vydání PDF/A-4 se proti roku 2020 neměří")
    void unknownDatedRevisionIsNotMeasured() {
        // Měřit vydání z roku 2027 zněním z roku 2020 by vydalo nálezy, které o dokumentu
        // nevypovídají. Deklarace se vypíše, ověření se neprovede.
        PdfProfileExtractor.Profile future = profileOf(
                "<pdfaid:part>4</pdfaid:part><pdfaid:rev>2027</pdfaid:rev>");
        assertEquals(List.of("PDF/A-4 (2027)"), future.standards());
        assertNull(future.pdfaFlavour());
        assertNull(PdfAConformanceChecker.check(source(""), future));

        // Známé vydání se naopak měří.
        assertEquals("4f", profileOf("<pdfaid:part>4</pdfaid:part><pdfaid:rev>2020</pdfaid:rev>"
                + "<pdfaid:conformance>F</pdfaid:conformance>").pdfaFlavour());
    }

    @Test
    @DisplayName("Selhání validátoru není nález o dokumentu")
    void toolFailureIsNotAVerdict() {
        // Nad korpusem padl parser veraPDF na 25 souborech z 473. Takový výsledek se musí dát
        // odlišit od „nevyhovuje", jinak by aplikace obviňovala dokument z cizí chyby.
        PdfAConformanceChecker.Conformance broken = new PdfAConformanceChecker.Conformance(
                "2b", false, List.of(), "IndexOutOfBoundsException: Index: 0");
        assertTrue(broken.inconclusive());
        assertFalse(broken.compliant(), "compliant nesmí nikdo číst, dokud inconclusive platí");
        assertTrue(broken.violations().isEmpty());
    }

    // ---- pomůcky ----------------------------------------------------------------

    private static PdfProfileExtractor.Profile profile(String flavour) {
        return new PdfProfileExtractor.Profile("1.7", List.of("PDF/A-2b"), false, flavour);
    }

    private static PdfProfileExtractor.Profile profileOf(String xmpElements) {
        return PdfProfileExtractor.extract(source(xmpElements), null);
    }

    private static PdfSource source(String xmpElements) {
        return PdfSource.of(PdfProfileExtractorTest.pdfWithXmp(xmpElements));
    }
}
