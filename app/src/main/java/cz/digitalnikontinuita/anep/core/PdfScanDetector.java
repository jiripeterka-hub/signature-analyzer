package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Je PDF prostým skenem — tedy jen obrázky stránek bez textové vrstvy? Potřebuje to blok
 * o vkladové listině: katastr prosté skeny nepřijímá.
 *
 * <p><b>Po stránkách, jen obsah stránky.</b> Anotace se nepočítají, takže podpisová vizualizace
 * s textem sken za textový dokument nevydá. Stránka je skenem, když ji obrázky pokrývají
 * z valné části a nemá text; pokrytí se <b>sčítá</b> přes všechny obrázky, protože některé
 * skenery (Konica Minolta) skládají stránku z 20–27 pruhů.
 *
 * <p><b>OCR se pozná dvojím způsobem.</b> Buď je text neviditelný (režim vykreslení 3 nebo 7),
 * nebo je viditelný, ale leží pod pozdějším <b>neprůhledným</b> obrázkem — tak vypadají
 * anonymizované smlouvy, kde je celá strana JPEG a pod ním rozpoznaný text. Pravidlo „text
 * nakreslený před velkým obrázkem“ bez ohledu na polohu a průhlednost dávalo desítky falešných
 * poplachů (mapy, začernění, grafické pozadí); proto se zkoumá, zda obrázek text opravdu kryje.
 *
 * <p><b>Změřeno 29. 9. 2026</b> na 1 241 PDF ze tří sbírek: 42 prostých skenů, 3 skeny s OCR,
 * 5 dokumentů bez textu, které skenem nejsou (výkresy s textem v křivkách, dokument z Wordu
 * s každým řádkem jako obrázkem). Klasifikace ověřena vykreslením u přes dvaceti dokumentů.
 * Podle producenta souboru se soudit nedá: ABBYY FineReader Server vydal i čistý obrázek bez OCR.
 */
public final class PdfScanDetector {

    private PdfScanDetector() {}

    /** Co dokument z hlediska textové vrstvy je. */
    public enum Kind {
        /** Aspoň jedna stránka má viditelný text. */
        TEXT,
        /** Sken, ale s rozpoznaným textem (OCR) — neviditelným nebo pod obrázkem stránky. */
        SCAN_WITH_OCR,
        /** Nemá text, a přesto to není sken: výkres s textem v křivkách, text jako obrázky. */
        NO_TEXT_NOT_SCAN,
        /** Jen obrázky stránek bez textové vrstvy. */
        PLAIN_SCAN
    }

    /** Pokrytí stránky obrázky, od kterého se stránka bez textu bere za sken. */
    static final double SCAN_COVER = 0.6;
    /** Kolik znaků je „text“ — méně bývá číslo stránky nebo zbloudilá značka. */
    static final int MIN_GLYPHS = 20;

    /** Posouzení dokumentu, nebo {@code null}, když se ho nepodaří otevřít či projít. */
    public static Kind detect(PdfSource source, char[] password) {
        if (source == null || !source.isPdf()) return null;
        try (PDDocument doc = source.open(password)) {
            return detect(doc);
        } catch (Exception e) {
            return null;
        }
    }

    static Kind detect(PDDocument doc) throws IOException {
        boolean ocr = false, scan = false, other = false;
        for (int i = 1; i <= doc.getNumberOfPages(); i++) {
            PageKind k = page(doc, i);
            switch (k) {
                case TEXT -> { return Kind.TEXT; }   // stačí jediná: dál není co zjišťovat
                case OCR -> ocr = true;
                case SCAN -> scan = true;
                case OTHER -> other = true;
                case BLANK -> { }
            }
        }
        if (ocr) return Kind.SCAN_WITH_OCR;
        if (scan && !other) return Kind.PLAIN_SCAN;
        return Kind.NO_TEXT_NOT_SCAN;
    }

    enum PageKind { TEXT, OCR, SCAN, OTHER, BLANK }

    static PageKind page(PDDocument doc, int pageNo) throws IOException {
        PageScan s = new PageScan();
        s.setStartPage(pageNo);
        s.setEndPage(pageNo);
        s.writeText(doc, java.io.Writer.nullWriter());
        return s.kind();
    }

    /** Průchod obsahem jedné stránky. Text se nevypisuje, jen počítají glyfy a obrázky. */
    private static final class PageScan extends PDFTextStripper {

        int visible, invisible, blanks, pathSegs, images;
        double imgCover;
        PDRectangle box;
        final List<float[]> glyphs = new ArrayList<>();
        final BitSet covered = new BitSet();

        PageScan() throws IOException { super(); }

        @Override
        protected void startPage(PDPage page) {
            box = page.getCropBox();
        }

        @Override
        protected void showGlyph(Matrix trm, PDFont font, int code, Vector disp) throws IOException {
            RenderingMode m = getGraphicsState().getTextState().getRenderingMode();
            boolean inv = m == RenderingMode.NEITHER || m == RenderingMode.NEITHER_CLIP;
            String u = font.toUnicode(code);
            if (u != null && u.isBlank()) {
                if (!inv) blanks++;
            } else if (inv) {
                invisible++;
            } else {
                visible++;
                glyphs.add(new float[] { trm.getTranslateX(), trm.getTranslateY() });
            }
            super.showGlyph(trm, font, code, disp);
        }

        @Override
        protected void processOperator(Operator op, List<COSBase> operands) throws IOException {
            switch (op.getName()) {
                case "m", "l", "c", "v", "y", "re" -> pathSegs++;
                case "BI" -> image(op.getImageParameters() == null
                        || !op.getImageParameters().getBoolean(COSName.IM, false));
                case "Do" -> {
                    if (!operands.isEmpty() && operands.get(0) instanceof COSName name
                            && getResources() != null) {
                        PDXObject x = getResources().getXObject(name);
                        if (x instanceof PDImageXObject img) image(opaque(img, getGraphicsState()));
                    }
                }
                default -> { }
            }
            super.processOperator(op, operands);
        }

        private static boolean opaque(PDImageXObject img, PDGraphicsState gs) {
            return !img.isStencil()
                    && !img.getCOSObject().containsKey(COSName.SMASK)
                    && !img.getCOSObject().containsKey(COSName.MASK)
                    && gs.getNonStrokeAlphaConstant() >= 1.0
                    && gs.getSoftMask() == null;
        }

        private void image(boolean opaque) {
            if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) return;
            images++;
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (float[] p : new float[][] { {0, 0}, {1, 0}, {0, 1}, {1, 1} }) {
                var q = ctm.transformPoint(p[0], p[1]);
                x0 = Math.min(x0, q.x); x1 = Math.max(x1, q.x);
                y0 = Math.min(y0, q.y); y1 = Math.max(y1, q.y);
            }
            double ix = Math.max(0, Math.min(x1, box.getUpperRightX()) - Math.max(x0, box.getLowerLeftX()));
            double iy = Math.max(0, Math.min(y1, box.getUpperRightY()) - Math.max(y0, box.getLowerLeftY()));
            imgCover = Math.min(1, imgCover + ix * iy / (box.getWidth() * (double) box.getHeight()));
            if (!opaque) return;
            for (int i = 0; i < glyphs.size(); i++) {
                float[] g = glyphs.get(i);
                if (g[0] >= x0 && g[0] <= x1 && g[1] >= y0 && g[1] <= y1) covered.set(i);
            }
        }

        PageKind kind() {
            int shown = visible - covered.cardinality();
            // Menší list naskenovaný na větší stránku pokryje jen část; pár obrázků přes aspoň
            // třetinu stránky bez textu je proto také sken. Mnoho drobných obrázků ne — tak
            // vypadá dokument, kde je každý řádek textu obrázkem.
            boolean scanned = imgCover >= SCAN_COVER || imgCover >= 0.3 && images <= 3;
            if (scanned && shown < MIN_GLYPHS) {
                if (invisible >= MIN_GLYPHS || covered.cardinality() >= MIN_GLYPHS) return PageKind.OCR;
                return PageKind.SCAN;
            }
            // Text, i když se z něj nedá vytáhnout Unicode: pořád je to text, ne obrázek.
            if (shown >= MIN_GLYPHS || blanks >= 50 && blanks > 4 * visible) return PageKind.TEXT;
            if (invisible >= MIN_GLYPHS) return PageKind.OCR;
            if (images > 0 || pathSegs >= 50 || visible > 0) return PageKind.OTHER;
            return PageKind.BLANK;
        }
    }
}
