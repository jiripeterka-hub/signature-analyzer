package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vlastní porovnání podepsané a dnešní podoby — pole a anotace, které DSS ve vizuálním
 * porovnání nevidí.
 *
 * <p>Vzniklo 14. 11. 2026 se třídou. Zkouší se nad skutečnými přírůstkovými aktualizacemi
 * vyrobenými PDFBoxem: „podepsanou" podobou je prefix souboru před přírůstkem, přesně tak, jak
 * ji vymezuje {@code /ByteRange}. Podpis sám k tomu potřeba není — třída se na něj neptá.
 * Sbírky nesou jediný soubor s viditelnou anotací za posledním prvkem a žádný s vyplněným
 * polem (změřeno sondou {@code TailAnnotationFormScan}), proto sestavené vzorky.
 */
class PostSignatureEditsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Razítková anotace přidaná po podpisu je viditelná a stojí za řeč")
    void anAddedStampIsNotable() throws Exception {
        PostSignatureEdits e = afterIncrement(base(false), doc -> {
            PDAnnotationRubberStamp stamp = new PDAnnotationRubberStamp();
            stamp.setRectangle(new PDRectangle(72, 470, 451, 50));
            stamp.setPrinted(true);
            addAnnotation(doc, stamp);
        });
        assertNotNull(e);
        assertTrue(e.notable());
        assertEquals(1, e.visibleAnnotations().size());
        PostSignatureEdits.AnnotationEdit a = e.visibleAnnotations().get(0);
        assertEquals("Stamp", a.kind());
        assertEquals(1, a.page());
        assertEquals(PostSignatureEdits.Action.ADDED, a.action());
        assertEquals(List.of(1), e.visiblePages());
    }

    /**
     * Neviditelné změny se počítají, ale nad karty nepatří — sondou se ve sbírkách objevily
     * dvakrát a šlo jen o odkazy.
     */
    @Test
    @DisplayName("Přidaný odkaz se započte jako neviditelný a nad karty nepatří")
    void anAddedLinkIsNotNotable() throws Exception {
        PostSignatureEdits e = afterIncrement(base(false), doc -> {
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 72, 100, 20));
            addAnnotation(doc, link);
        });
        assertNotNull(e);
        assertFalse(e.notable());
        assertEquals(1, e.hiddenAnnotationCount());
        assertTrue(e.visiblePages().isEmpty());
    }

    @Test
    @DisplayName("Formulář podepsaný prázdný a potom vyplněný: pole se jmenuje i se stranou")
    void aFilledFieldIsNotable() throws Exception {
        PostSignatureEdits e = afterIncrement(base(true), this::fillJmeno);
        assertNotNull(e);
        assertTrue(e.notable());
        assertEquals(1, e.fields().size());
        assertEquals("Jmeno", e.fields().get(0).name());
        assertEquals("Tx", e.fields().get(0).type());
        assertFalse(e.fields().get(0).added());
        assertEquals(1, e.fields().get(0).page());
        assertTrue(e.visibleAnnotations().isEmpty(), "Vzhled pole se jako anotace nepočítá");
    }

    @Test
    @DisplayName("Přírůstek, který na polích a anotacích nic nemění, nehlásí nic")
    void anUntouchedDocumentReportsNothing() throws Exception {
        PostSignatureEdits e = afterIncrement(base(true), doc ->
                doc.getDocumentInformation().setTitle("jen metadata"));
        assertNotNull(e);
        assertFalse(e.notable());
        assertTrue(e.annotations().isEmpty());
        assertTrue(e.fields().isEmpty());
    }

    @Test
    @DisplayName("Nečitelný soubor či rozsah mimo soubor: mlčí se")
    void unreadableInputGivesNull() throws Exception {
        File f = dir.resolve("x.pdf").toFile();
        Files.write(f.toPath(), base(false));
        assertNull(PostSignatureEdits.read(f, f.length()), "Za prvkem nic není");
        assertNull(PostSignatureEdits.read(f, 0));
        File junk = dir.resolve("junk.pdf").toFile();
        Files.write(junk.toPath(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        assertNull(PostSignatureEdits.read(junk, 4));
    }

    // ------------------------------------------------------------------ přípravek

    /** Objekty, které přírůstek musí zapsat (PDFBox je jinak neuloží). */
    private final Set<COSDictionary> updated = new HashSet<>();

    private PostSignatureEdits afterIncrement(byte[] signed, Consumer<PDDocument> change)
            throws Exception {
        byte[] all = incremented(signed, change);
        File f = dir.resolve("edited.pdf").toFile();
        Files.write(f.toPath(), all);
        return PostSignatureEdits.read(f, signed.length);
    }

    private byte[] incremented(byte[] signed, Consumer<PDDocument> change) throws Exception {
        updated.clear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(signed)) {
            change.accept(doc);
            updated.add(doc.getPage(0).getCOSObject());
            updated.add(doc.getDocumentInformation().getCOSObject());
            // Zapíše původní bajty beze změny a za ně přírůstek — prefix je tedy přesně
            // ta „podepsaná" podoba.
            doc.saveIncremental(out, updated);
        }
        return out.toByteArray();
    }

    private void fillJmeno(PDDocument doc) {
        try {
            PDTextField f = (PDTextField) doc.getDocumentCatalog().getAcroForm().getField("Jmeno");
            f.setValue("Jan Novák");
            updated.add(f.getCOSObject());
            for (PDAnnotationWidget w : f.getWidgets()) updated.add(w.getCOSObject());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Nález z 14. 11. 2026: prohlášení podepsané prázdné, vyplněné a potom orazítkované
     * archivním razítkem. Za posledním prvkem nic není, ale vyplnění leží mezi podpisem
     * a razítkem — a patří na kartu podpisu.
     */
    @Test
    @DisplayName("Vyplnění mezi podpisem a razítkem se přiřadí podpisu a jmenuje razítko")
    void fillBetweenSignatureAndTimestamp() throws Exception {
        byte[] blank = base(true);
        byte[] filled = incremented(blank, this::fillJmeno);
        File f = dir.resolve("between.pdf").toFile();
        Files.write(f.toPath(), filled);

        var dd = data(element(false, "SIG-1", blank.length), element(true, "TS-1", filled.length));
        java.util.Map<String, PostSignatureEdits.Interim> gaps = PostSignatureEdits.interim(dd, f);

        assertEquals(Set.of("SIG-1"), gaps.keySet(), "Jen úsek za podpisem; za razítkem nic není");
        PostSignatureEdits.Interim g = gaps.get("SIG-1");
        assertEquals("TS-1", g.nextId());
        assertEquals(PostSignatureEdits.CoveredBy.RAZITKO, g.nextKind());
        assertEquals("Jmeno", g.edits().fields().get(0).name());
    }

    @Test
    @DisplayName("Jediný kryjící prvek: mezi prvky není co porovnávat")
    void singleElementHasNoInterim() throws Exception {
        byte[] blank = base(true);
        File f = dir.resolve("single.pdf").toFile();
        Files.write(f.toPath(), incremented(blank, this::fillJmeno));
        assertTrue(PostSignatureEdits.interim(data(element(false, "SIG-1", blank.length)), f)
                .isEmpty(), "To je úsek za posledním prvkem — o něm mluví blok nad kartami");
    }

    private static eu.europa.esig.dss.diagnostic.DiagnosticData data(Object... elements) {
        var xml = new eu.europa.esig.dss.diagnostic.jaxb.XmlDiagnosticData();
        for (Object e : elements) {
            if (e instanceof eu.europa.esig.dss.diagnostic.jaxb.XmlSignature s) xml.getSignatures().add(s);
            if (e instanceof eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp t) xml.getUsedTimestamps().add(t);
        }
        return new eu.europa.esig.dss.diagnostic.DiagnosticData(xml);
    }

    /** Prvek, jehož {@code /ByteRange} končí na {@code end}. */
    private static Object element(boolean timestamp, String id, long end) {
        var br = new eu.europa.esig.dss.diagnostic.jaxb.XmlByteRange();
        br.setValid(true);
        br.getValue().add(java.math.BigInteger.ZERO);
        br.getValue().add(java.math.BigInteger.valueOf(10));
        br.getValue().add(java.math.BigInteger.valueOf(end - 5));
        br.getValue().add(java.math.BigInteger.valueOf(5));
        var dict = new eu.europa.esig.dss.diagnostic.jaxb.XmlPDFSignatureDictionary();
        dict.setSignatureByteRange(br);
        var rev = new eu.europa.esig.dss.diagnostic.jaxb.XmlPDFRevision();
        rev.setPDFSignatureDictionary(dict);
        if (timestamp) {
            var t = new eu.europa.esig.dss.diagnostic.jaxb.XmlTimestamp();
            t.setId(id);
            t.setPDFRevision(rev);
            return t;
        }
        var s = new eu.europa.esig.dss.diagnostic.jaxb.XmlSignature();
        s.setId(id);
        s.setPDFRevision(rev);
        return s;
    }

    private void addAnnotation(PDDocument doc, PDAnnotation a) {
        try {
            PDPage page = doc.getPage(0);
            List<PDAnnotation> list = page.getAnnotations();
            list.add(a);
            page.setAnnotations(list);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Jednostránkový dokument, případně s prázdným textovým polem „Jmeno". */
    private static byte[] base(boolean withForm) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            if (withForm) {
                PDAcroForm form = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(form);
                PDResources res = new PDResources();
                res.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"),
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                form.setDefaultResources(res);
                form.setDefaultAppearance("/Helv 10 Tf 0 g");
                PDTextField field = new PDTextField(form);
                field.setPartialName("Jmeno");
                PDAnnotationWidget w = field.getWidgets().get(0);
                w.setRectangle(new PDRectangle(72, 700, 200, 20));
                w.setPage(page);
                w.setPrinted(true);
                List<PDAnnotation> annots = new java.util.ArrayList<>(page.getAnnotations());
                annots.add(w);
                page.setAnnotations(annots);
                form.getFields().add(field);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
