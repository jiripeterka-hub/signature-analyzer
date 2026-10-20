package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the archival format of the exported protocols.
 *
 * <p>Formátová pravidla Národního archivu accept PDF/A and nothing else for a document meant for
 * permanent storage, so an export that quietly stops being PDF/A is a silent regression: the file
 * still opens, still prints, and is no longer archival. The likely triggers are ordinary — a new
 * FOP version, a change to the font configuration, a stylesheet that starts using a construct the
 * profile forbids — which is why this is checked rather than trusted.
 *
 * <p>Validated with veraPDF, the reference implementation, and not by looking for the PDF/A
 * markers in the file. Those markers were all present in output that veraPDF rejected: FOP writes
 * no CIDSet stream for embedded font subsets, which PDF/A-1b requires (ISO 19005-1, 6.3.5). That
 * is what settled the profile on PDF/A-2b, and a weaker check here would not have noticed.
 */
class ArchivalExportTest {

    /**
     * The two shapes of XSL-FO the application renders. They differ in the one way that decides
     * whether PDF/A is reachable at all: whether the FO names a font.
     */
    private enum Source {

        /** As {@code PrintUtil.buildFo} emits it — explicit font stack, card colours, a table. */
        ANEP("""
            <fo:block background-color="#003189" color="#ffffff" padding="4pt" font-weight="bold">
              AnEP (Analyzátor elektronických podpisů)</fo:block>
            <fo:table table-layout="fixed" width="100%">
              <fo:table-column column-width="145pt"/>
              <fo:table-column column-width="proportional-column-width(1)"/>
              <fo:table-body>
                <fo:table-row>
                  <fo:table-cell><fo:block font-weight="bold">Výsledek:</fo:block></fo:table-cell>
                  <fo:table-cell><fo:block>platný — příliš žluťoučký kůň úpěl ďábelské ódy</fo:block></fo:table-cell>
                </fo:table-row>
              </fo:table-body>
            </fo:table>
            """, " font-family=\"Arial,Helvetica,sans-serif\" font-size=\"8.5pt\""),

        /**
         * As the DSS report stylesheets emit it. They set no font attribute anywhere, so FOP falls
         * back to base-14 Helvetica — which cannot be embedded and which PDF/A refuses outright.
         * This case fails without the base-14 aliases in {@code PrintUtil.fontConfig()}.
         */
        DSS("""
            <fo:block>Jednoduchý přehled — TOTAL_PASSED</fo:block>
            <fo:block font-weight="bold">Podpis č. 1 — kvalifikovaný elektronický podpis</fo:block>
            <fo:block font-style="italic">Kurzíva — řěžčťďň</fo:block>
            <fo:block><fo:basic-link external-destination="url(https://ec.europa.eu)">odkaz</fo:basic-link></fo:block>
            """, "");

        private final String body;
        private final String flowAttributes;

        Source(String body, String flowAttributes) {
            this.body = body;
            this.flowAttributes = flowAttributes;
        }

        byte[] fo() {
            return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
                  <fo:layout-master-set>
                    <fo:simple-page-master master-name="A4" page-height="29.7cm" page-width="21cm"
                        margin-top="1.5cm" margin-bottom="1.5cm" margin-left="1.5cm" margin-right="1.5cm">
                      <fo:region-body/>
                    </fo:simple-page-master>
                  </fo:layout-master-set>
                  <fo:page-sequence master-reference="A4">
                    <fo:flow flow-name="xsl-region-body"%s>
                %s    </fo:flow>
                  </fo:page-sequence>
                </fo:root>
                """).formatted(flowAttributes, body).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Protokol AnEP je PDF/A podle veraPDF")
    void anepProtocolIsArchival(@TempDir Path dir) throws Exception {
        assertArchival(Source.ANEP, dir);
    }

    @Test
    @DisplayName("Protokol DSS je PDF/A podle veraPDF, ačkoli si o písmo neřekne")
    void dssProtocolIsArchival(@TempDir Path dir) throws Exception {
        assertArchival(Source.DSS, dir);
    }

    /**
     * Razítko se k protokolu přidává <b>až potom</b>, co {@code PrintUtil.writeProtocol} rozhodl,
     * že soubor je archivní: pořadí je vysázet, přiložit, orazítkovat, a příznak padne hned
     * v prvním kroku. Přílohu hlídá zkouška níž, razítko dosud nehlídal nikdo — a přitom
     * zakládá nové podpisové pole a přírůstkovou aktualizaci, tedy přesně ty věci, na které
     * má PDF/A pravidla. Kdyby konformitu bořilo, aplikace by o souboru tvrdila „archivní"
     * a nebyla by to pravda.
     *
     * <p>Razítko vydává {@link cz.digitalnikontinuita.anep.core.ThrowawayTsa}, autorita na jedno
     * použití uvnitř běhu: otázka je o tvaru souboru, ne o důvěryhodnosti razítka. Že to není
     * náhrada, která by odpověď zkreslila, ověřuje {@code TsaCrossCheckProbe} proti testovací
     * TSA DEMO PostSignum — nad čtyřmi dokumenty se obě autority shodly.
     */
    @Test
    @DisplayName("Protokol zůstane PDF/A i po orazítkování")
    void stampedProtocolStaysArchival(@TempDir Path dir) throws Exception {
        requireFonts();

        File pdf = dir.resolve("orazitkovany.pdf").toFile();
        assertTrue(PrintUtil.renderPdfA(Source.ANEP.fo(), pdf),
                "Export se nevzdal PDF/A jen kvůli chybějícímu písmu, viz requireFonts()");
        assertTrue(validate(pdf).isCompliant(), "protokol nebyl archivní už před razítkem");

        var stamped = cz.digitalnikontinuita.anep.core.ThrowawayTsa.stamp(pdf);
        File out = dir.resolve("orazitkovany-vystup.pdf").toFile();
        try (var os = java.nio.file.Files.newOutputStream(out.toPath())) {
            stamped.writeTo(os);
        }

        ValidationResult after = validate(out);
        assertTrue(after.isCompliant(),
                "razítko rozbilo PDF/A (" + after.getPDFAFlavour().getId() + "): "
                        + failures(after));
    }

    /**
     * Vizualizace podpisu je obrázek, a obrázek je jediná věc v protokolu, která není text.
     *
     * <p>Do 30. 8. 2026 tam žádný nebyl — {@code PrintUtil} uměl vysázet jen logo v záhlaví a
     * {@code ImageView} v obsahu tiše přeskočil, takže protokol nesl popisek „Vizualizace
     * podpisu:“ a pod ním prázdno. Teď se sází, a tím vstupuje do PDF/A nový druh obsahu:
     * PDF/A-2b žádá barevný prostor pro každý obrázek a přes výstupní záměr to projde jen
     * tehdy, když se FOP zachová tak, jak čekáme. Proto se to měří, a ne předpokládá.
     */
    @Test
    @DisplayName("Protokol s vizualizací podpisu zůstává PDF/A")
    void protocolWithAnImageIsArchival(@TempDir Path dir) throws Exception {
        requireFonts();

        // Obrázek schválně mimo {@code @TempDir}: FOP si na něj ve své paměti obrázků podrží
        // otevřený popisovač a Windows takový soubor smazat nedovolí — úklid dočasného adresáře
        // by pak sestřelil test, který právě prošel.
        Path png = java.nio.file.Files.createTempFile("anep-test-img", ".png");
        png.toFile().deleteOnExit();
        java.nio.file.Files.write(png, java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAHUlEQVR4nGP8"
                + "//8/AzGAiYFIMKpwVOGowlGFRAEA4h4DFxbLbfIAAAAASUVORK5CYII="));

        byte[] fo = ("""
            <?xml version="1.0" encoding="UTF-8"?>
            <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
              <fo:layout-master-set>
                <fo:simple-page-master master-name="A4" page-height="29.7cm" page-width="21cm"
                    margin-top="1.5cm" margin-bottom="1.5cm" margin-left="1.5cm" margin-right="1.5cm">
                  <fo:region-body/>
                </fo:simple-page-master>
              </fo:layout-master-set>
              <fo:page-sequence master-reference="A4">
                <fo:flow flow-name="xsl-region-body" font-family="Arial,Helvetica,sans-serif" font-size="8.5pt">
                  <fo:block font-weight="bold">Vizualizace podpisu:</fo:block>
                  <fo:block><fo:external-graphic src="url('%s')" content-width="120pt"
                      content-height="120pt" scaling="uniform"/></fo:block>
                </fo:flow>
              </fo:page-sequence>
            </fo:root>
            """).formatted(png.toUri()).getBytes(StandardCharsets.UTF_8);

        File pdf = dir.resolve("s-obrazkem.pdf").toFile();
        assertTrue(PrintUtil.renderPdfA(fo, pdf), "Export se vzdal PDF/A, viz requireFonts()");

        ValidationResult result = validate(pdf);
        assertTrue(result.isCompliant(),
                "veraPDF odmítl protokol s obrázkem (" + result.getPDFAFlavour().getId() + "): "
                        + failures(result));
    }

    /**
     * Příloha mění archivní profil, a to je jediný důvod, proč se na ni uživatele ptáme.
     *
     * <p>PDF/A-2 připouští vloženou přílohu jen tehdy, je-li sama PDF/A (ISO 19005-2, klauzule
     * 6.8), což ověřovaný soubor skoro nikdy není — změřeno: PDF/A-2b s přílohou veraPDF odmítá
     * pravidlem 6.8-5. Protokol s přílohou se proto sází jako PDF/A-3b. Tenhle test hlídá obojí:
     * že si bez přílohy protokol podrží PDF/A-2, a že s přílohou projde jako PDF/A-3 —
     * i s {@code /AF} a {@code AFRelationship}, bez kterých by veraPDF přílohu odmítl.
     */
    @Test
    @DisplayName("Bez přílohy PDF/A-2, s přílohou PDF/A-3 — a obojí projde veraPDF")
    void attachingTheVerifiedFileSwitchesTheProfile(@TempDir Path dir) throws Exception {
        requireFonts();

        Path attached = dir.resolve("overovany.pdf");
        java.nio.file.Files.write(attached, "obsah ověřovaného souboru".getBytes(StandardCharsets.UTF_8));
        var files = java.util.List.of(
                new cz.digitalnikontinuita.anep.core.PdfAttachments.Attachment(attached.toFile(),
                        "Soubor, jak byl předložen k ověření"));

        File plain = dir.resolve("bez-prilohy.pdf").toFile();
        PrintUtil.Outcome bare = PrintUtil.writeProtocol(Source.ANEP.fo(), plain,
                new PrintUtil.ProtocolOptions(false, false), files, null);
        assertTrue(bare.archival(), "Export se vzdal PDF/A, viz requireFonts()");
        ValidationResult bareResult = validate(plain);
        assertTrue(bareResult.isCompliant(),
                "veraPDF odmítl protokol bez přílohy: " + failures(bareResult));
        assertEquals("2b", bareResult.getPDFAFlavour().getId(),
                "Bez přílohy nemá být důvod opouštět PDF/A-2.");

        File withFile = dir.resolve("s-prilohou.pdf").toFile();
        PrintUtil.Outcome attachedOut = PrintUtil.writeProtocol(Source.ANEP.fo(), withFile,
                new PrintUtil.ProtocolOptions(true, false), files, null);
        assertTrue(attachedOut.archival());
        ValidationResult attachedResult = validate(withFile);
        assertTrue(attachedResult.isCompliant(),
                "veraPDF odmítl protokol s přílohou: " + failures(attachedResult));
        assertEquals("3b", attachedResult.getPDFAFlavour().getId(),
                "Příloha si PDF/A-3 vynucuje; PDF/A-2 by ji zakázalo.");

        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.Loader.loadPDF(withFile)) {
            var names = new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(
                    doc.getDocumentCatalog()).getEmbeddedFiles();
            assertNotNull(names, "protokol nenese strom vložených souborů");
            assertTrue(names.getNames().containsKey("overovany.pdf"),
                    "přiložený soubor se v protokolu nejmenuje, jak byl předložen");
        }
    }

    /**
     * Protokol s přílohou nese i dokumenty, na které se ověřovaný soubor odkazuje otisky
     * z eLegalizace — a to celý potvrzený řetěz.
     *
     * <p>Legalizační záznam sám o sobě není vším: odkazovaný dokument v něm není a nikdy nebude.
     * Protokol, který by nesl jen záznam, by tedy pořád vyžadoval druhý soubor odjinud, a smysl
     * přikládání („dá se ověřit znovu z jediného souboru“) by padl.
     */
    @Test
    @DisplayName("Do protokolu se přiloží i potvrzený řetěz dokumentů z eLegalizace")
    void theConfirmedLegalisationChainEndsUpInsideTheProtocol(@TempDir Path dir) throws Exception {
        requireFonts();
        cz.digitalnikontinuita.anep.core.LegalisationLinkLog.clear();

        Path zaznam = dir.resolve("zaznam.pdf");
        java.nio.file.Files.write(zaznam, "legalizační záznam".getBytes(StandardCharsets.UTF_8));
        Path legalizovany = dir.resolve("legalizovany.pdf");
        java.nio.file.Files.write(legalizovany, "legalizovaný dokument".getBytes(StandardCharsets.UTF_8));

        var link = new cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link(
                "legalisation-metadata.xml", "guid", "ORIGINAL", null,
                java.util.List.of(new cz.digitalnikontinuita.anep.core.LegalisationLinkInspector
                        .Digest("SHA-256", "abcd")));
        cz.digitalnikontinuita.anep.core.LegalisationLinkLog.record(zaznam.toFile(), link,
                legalizovany.toFile(),
                new cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Check(
                        cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Outcome.MATCH,
                        java.util.List.of(), null),
                null);

        File target = dir.resolve("protokol.pdf").toFile();
        PrintUtil.Outcome out = PrintUtil.writeProtocol(Source.ANEP.fo(), target,
                new PrintUtil.ProtocolOptions(true, false),
                PrintUtil.attachments(zaznam.toFile(), null), null);
        assertTrue(out.archival(), "Export se vzdal PDF/A, viz requireFonts()");

        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.Loader.loadPDF(target)) {
            var names = new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(
                    doc.getDocumentCatalog()).getEmbeddedFiles();
            assertNotNull(names, "protokol nenese strom vložených souborů");
            assertTrue(names.getNames().containsKey("zaznam.pdf"), names.getNames().keySet().toString());
            assertTrue(names.getNames().containsKey("legalizovany.pdf"),
                    "odkazovaný dokument v protokolu chybí: " + names.getNames().keySet());
        }
    }

    /**
     * The export overwrites whatever the reader picked, which is usually their previous export, so
     * a failure must leave that file as it was.
     *
     * <p>It could not before: the target was opened — and therefore truncated — before rendering
     * began, so anything failing along the way left an empty file behind, and the handler then
     * deleted it. Rendering into memory first is what makes the guarantee possible, and this is the
     * case that tells the two apart: the failure happens after the stream would have been opened.
     */
    @Test
    @DisplayName("Selhání během vykreslování nezničí soubor, který na tom místě už byl")
    void failedRenderLeavesTheExistingFileAlone(@TempDir Path dir) throws Exception {
        requireFonts();

        File target = dir.resolve("protokol.pdf").toFile();
        // Compared as bytes: a botched export leaves PDF fragments behind, and reading those back
        // as text fails with a decoding error instead of saying what actually went wrong.
        byte[] before = "předchozí export".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(target.toPath(), before);

        // Valid XML, invalid XSL-FO: fo:root has to contain fo:layout-master-set.
        byte[] brokenFo = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<fo:root xmlns:fo=\"http://www.w3.org/1999/XSL/Format\"/>")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> PrintUtil.renderPdfA(brokenFo, target),
                "vadné XSL-FO mělo vykreslení shodit");
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(target.toPath()),
                "neúspěšný export přepsal soubor, který tam už byl");
    }

    private void assertArchival(Source source, Path dir) throws Exception {
        requireFonts();

        File pdf = dir.resolve(source.name().toLowerCase() + ".pdf").toFile();
        assertTrue(PrintUtil.renderPdfA(source.fo(), pdf),
                "Export se nevzdal PDF/A jen kvůli chybějícímu písmu, viz requireFonts()");

        ValidationResult result = validate(pdf);
        assertTrue(result.isCompliant(),
                "veraPDF odmítl " + pdf.getName() + " (" + result.getPDFAFlavour().getId() + "): "
                        + failures(result));
    }

    /** Validates against the profile the file itself declares, not one we hand it. */
    private static ValidationResult validate(File pdf) throws Exception {
        VeraGreenfieldFoundryProvider.initialise();
        try (InputStream in = new FileInputStream(pdf);
             PDFAParser parser = Foundries.defaultInstance().createParser(in, PDFAFlavour.NO_FLAVOUR);
             PDFAValidator validator = Foundries.defaultInstance().createValidator(parser.getFlavour(), false)) {
            return validator.validate(parser);
        }
    }

    private static String failures(ValidationResult result) {
        return result.getTestAssertions().stream()
                .filter(a -> a.getStatus() == TestAssertion.Status.FAILED)
                .map(a -> a.getRuleId().getSpecification().getId() + " " + a.getRuleId().getClause()
                        + "-" + a.getRuleId().getTestNumber() + ": " + a.getMessage())
                .distinct()
                .collect(Collectors.joining("; "));
    }

    /**
     * Skips rather than fails where the substitute faces are missing. Their absence is a property
     * of the machine, not of the code under test — and the application handles it by saying the
     * export is not archival, which is the behaviour this test cannot then observe.
     */
    private static void requireFonts() {
        String systemRoot = System.getenv("SystemRoot");
        Assumptions.assumeTrue(systemRoot != null && new File(systemRoot, "Fonts/arial.ttf").isFile(),
                "Arial není v systému, PDF/A nelze vytvořit ani ověřit");
    }
}
