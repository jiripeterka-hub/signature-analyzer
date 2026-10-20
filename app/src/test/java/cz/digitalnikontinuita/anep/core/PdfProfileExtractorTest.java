package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Čtení podoby PDF: verze a podmnožin, ke kterým se dokument hlásí.
 *
 * <p>Korpusy {@code D:\Downloads} a {@code D:\tmp} dají 2 260 PDF a 480 deklarací, ale jen těch
 * dávno vydaných: PDF/A-1, -2, -3, PDF/UA-1, PDF/X. Ani jedno PDF 2.0, ani jedno PDF/A-4.
 * Právě ty větve — část 4 s povinným rokem vydání, datované vydání „z budoucnosti", PDF/UA-2 —
 * si tedy musí ověřit tenhle test, protože je nemá na čem potkat.
 */
class PdfProfileExtractorTest {

    @Test
    @DisplayName("Obyčejné PDF bez metadat vydá verzi a žádnou podmnožinu")
    void plainPdf() {
        PdfProfileExtractor.Profile profile = profileOf(pdf(null, "1.7", null));
        assertNotNull(profile);
        assertEquals("1.7", profile.version());
        assertEquals(List.of(), profile.standards());
        assertFalse(profile.unexpected());
    }

    @Test
    @DisplayName("Klíč /Version v katalogu přebíjí hlavičku, je-li pozdější (ISO 32000-1, 7.5.5)")
    void catalogVersionOverridesHeader() {
        assertEquals("1.7", profileOf(pdf(null, "1.6", "1.7")).version());
        // Opačný směr ne: katalog verzi nesnižuje.
        assertEquals("1.7", profileOf(pdf(null, "1.7", "1.6")).version());
    }

    @Test
    @DisplayName("PDF/A-2b se pozná z prvkového i atributového zápisu XMP")
    void pdfA2bBothXmpForms() {
        String elements = "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>";
        assertEquals(List.of("PDF/A-2b"), profileOf(pdf(rdf(elements, ""), "1.7", null)).standards());

        String attributes = "pdfaid:part=\"2\" pdfaid:conformance=\"B\"";
        assertEquals(List.of("PDF/A-2b"), profileOf(pdf(rdf("", attributes), "1.7", null)).standards());
    }

    @Test
    @DisplayName("Části 1–3 mají úroveň shody povinně; chybí-li, je to nečekaný údaj")
    void conformanceLevels() {
        assertEquals(List.of("PDF/A-1a"), standardsOf("<pdfaid:part>1</pdfaid:part>"
                + "<pdfaid:conformance>A</pdfaid:conformance>"));
        assertEquals(List.of("PDF/A-3u"), standardsOf("<pdfaid:part>3</pdfaid:part>"
                + "<pdfaid:conformance>U</pdfaid:conformance>"));
        // U (Unicode) přibylo až v části 2 — v části 1 je to hodnota mimo standard.
        PdfProfileExtractor.Profile one = profileOf(pdf(rdf("<pdfaid:part>1</pdfaid:part>"
                + "<pdfaid:conformance>U</pdfaid:conformance>", ""), "1.7", null));
        assertEquals(List.of("PDF/A-1"), one.standards());
        assertTrue(one.unexpected());
    }

    @Test
    @DisplayName("PDF/A-4: úroveň shody je nepovinná, rok vydání povinný")
    void pdfA4() {
        assertEquals(List.of("PDF/A-4"), standardsOf("<pdfaid:part>4</pdfaid:part>"
                + "<pdfaid:rev>2020</pdfaid:rev>"));
        assertEquals(List.of("PDF/A-4f"), standardsOf("<pdfaid:part>4</pdfaid:part>"
                + "<pdfaid:rev>2020</pdfaid:rev><pdfaid:conformance>F</pdfaid:conformance>"));
        assertEquals(List.of("PDF/A-4e"), standardsOf("<pdfaid:part>4</pdfaid:part>"
                + "<pdfaid:rev>2020</pdfaid:rev><pdfaid:conformance>E</pdfaid:conformance>"));

        // Datované vydání, které tahle verze AnEP nezná: ať je vidět, o které jde.
        PdfProfileExtractor.Profile future = profileOf(pdf(rdf("<pdfaid:part>4</pdfaid:part>"
                + "<pdfaid:rev>2027</pdfaid:rev>", ""), "2.0", null));
        assertEquals(List.of("PDF/A-4 (2027)"), future.standards());
        assertTrue(future.unexpected());
        assertEquals("2.0", future.version());
    }

    @Test
    @DisplayName("PDF/UA a PDF/X vedle PDF/A; hlásit se lze k víc podmnožinám naráz")
    void severalStandardsAtOnce() {
        assertEquals(List.of("PDF/A-2b", "PDF/UA-1"), standardsOf(
                "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>"
                        + "<pdfuaid:part>1</pdfuaid:part>"));
        assertEquals(List.of("PDF/UA-2"), standardsOf(
                "<pdfuaid:part>2</pdfuaid:part><pdfuaid:rev>2024</pdfuaid:rev>"));
        assertEquals(List.of("PDF/X-4"), standardsOf(
                "<pdfxid:GTS_PDFXVersion>PDF/X-4</pdfxid:GTS_PDFXVersion>"));
    }

    @Test
    @DisplayName("Holý rok v pdfaid:amd se nevypisuje a za nečekaný údaj se nebere")
    void bareYearAmendmentIsIgnored() {
        // 22 souborů z 2 260 měřených takový zápis má; dodatek to není a na podtypu nic nemění.
        PdfProfileExtractor.Profile profile = profileOf(pdf(rdf("<pdfaid:part>1</pdfaid:part>"
                + "<pdfaid:conformance>B</pdfaid:conformance><pdfaid:amd>2005</pdfaid:amd>", ""),
                "1.7", null));
        assertEquals(List.of("PDF/A-1b"), profile.standards());
        assertFalse(profile.unexpected());

        assertEquals(List.of("PDF/A-1b Amd 1:2019"), standardsOf("<pdfaid:part>1</pdfaid:part>"
                + "<pdfaid:conformance>B</pdfaid:conformance><pdfaid:amd>1:2019</pdfaid:amd>"));
    }

    @Test
    @DisplayName("Nesmyslný identifikační údaj nevydá podtyp, jen příznak")
    void nonsenseDeclaration() {
        // Skutečný nález z korpusu: producent zapsal do obou polí názvy vlastních tříd.
        PdfProfileExtractor.Profile profile = profileOf(pdf(rdf(
                "<pdfaid:part>iText.Kernel.Pdf.PdfAConformanceLevel</pdfaid:part>"
                        + "<pdfaid:conformance>itext.kernel.pdf.pdfaconformancelevel</pdfaid:conformance>",
                ""), "1.7", null));
        assertEquals(List.of(), profile.standards());
        assertTrue(profile.unexpected());
    }

    @Test
    @DisplayName("Nad souborem, který není PDF, se nic nezjišťuje")
    void nonPdfYieldsNothing() {
        org.junit.jupiter.api.Assertions.assertNull(
                PdfProfileExtractor.extract(PdfSource.of("tohle není PDF".getBytes(StandardCharsets.UTF_8)), null));
    }

    // ---- pomůcky ----------------------------------------------------------------

    private static List<String> standardsOf(String xmpElements) {
        return profileOf(pdf(rdf(xmpElements, ""), "1.7", null)).standards();
    }

    private static PdfProfileExtractor.Profile profileOf(byte[] pdf) {
        return PdfProfileExtractor.extract(PdfSource.of(pdf), null);
    }

    /** Nejmenší PDF s daným obsahem identifikačního XMP — sdílí ho i test prověřování shody. */
    static byte[] pdfWithXmp(String xmpElements) {
        return pdf(xmpElements == null ? null : rdf(xmpElements, ""), "1.7", null);
    }

    /**
     * PDF hlásící se k PDF/A-2b, které normě nevyhovuje: kreslí barvou v DeviceRGB, ačkoli
     * dokument nemá výstupní profil, který by řekl, co ta barva znamená. Norma to zakazuje
     * (ISO 19005-2, 6.2.4.3) právě proto, že bez něj se dokument za deset let může vykreslit
     * jinak, než jak vypadá dnes — je to tedy porušení přímo z jádra toho, o čem PDF/A je.
     */
    static byte[] pdfWithUntaggedRgb() {
        return pdf(rdf("<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>", ""),
                "1.7", null, true);
    }

    /** XMP s jedním rdf:Description — údaje se dají zapsat jako prvky i jako atributy. */
    private static String rdf(String elements, String attributes) {
        return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\""
                + " xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\""
                + " xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\""
                + " xmlns:pdfxid=\"http://www.npes.org/pdfx/ns/id/\""
                + " " + attributes + ">"
                + elements
                + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
    }

    /**
     * Nejmenší PDF, které tuhle otázku umí položit: jedna stránka, zadaná verze v hlavičce,
     * nepovinně {@code /Version} v katalogu a nepovinně XMP.
     *
     * <p>Pod 1.6 se tudy hlavička dostat nedá — PDFBox zapisuje křížové odkazy proudem a na ten
     * verzi souboru sám zvedne. Nevadí to: nízké verze hlavičky pokrývá korpus (705 z 2 260
     * měřených PDF hlásí 1.4), tady jde o pravidlo, kterým katalog hlavičku přebíjí.
     */
    private static byte[] pdf(String xmp, String headerVersion, String catalogVersion) {
        return pdf(xmp, headerVersion, catalogVersion, false);
    }

    private static byte[] pdf(String xmp, String headerVersion, String catalogVersion,
                              boolean paintRgb) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.getDocument().setVersion(Float.parseFloat(headerVersion));
            if (catalogVersion != null) doc.getDocumentCatalog().setVersion(catalogVersion);
            if (paintRgb) {
                try (org.apache.pdfbox.pdmodel.PDPageContentStream content =
                             new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, doc.getPage(0))) {
                    content.setNonStrokingColor(new java.awt.Color(200, 30, 30));
                    content.addRect(50, 50, 200, 100);
                    content.fill();
                }
            }
            if (xmp != null) {
                doc.getDocumentCatalog().setMetadata(new PDMetadata(doc,
                        new ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8))));
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
