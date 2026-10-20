package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rozpoznání prostého skenu ({@link PdfScanDetector}) na dokumentech vyrobených v testu.
 */
class PdfScanDetectorTest {

    private static final String LINE = "Kupni smlouva o prevodu vlastnickeho prava k nemovitosti";
    private static final PDRectangle A4 = PDRectangle.A4;

    @Test
    @DisplayName("stránka s viditelným textem je textový dokument")
    void textPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> text(cs, RenderingMode.FILL));
            assertEquals(Kind.TEXT, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("obrázek přes celou stránku bez textu je prostý sken")
    void plainScan() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> fullImage(cs, d));
            page(doc, (cs, d) -> fullImage(cs, d));
            assertEquals(Kind.PLAIN_SCAN, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("sken z pruhů se sčítá do celé stránky")
    void stripedScan() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> {
                PDImageXObject img = image(d, false);
                float h = A4.getHeight() / 10;
                for (int i = 0; i < 10; i++) cs.drawImage(img, 0, i * h, A4.getWidth(), h);
            });
            assertEquals(Kind.PLAIN_SCAN, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("sken s neviditelným textem je sken s OCR")
    void invisibleOcr() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> { fullImage(cs, d); text(cs, RenderingMode.NEITHER); });
            assertEquals(Kind.SCAN_WITH_OCR, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("viditelný text zakrytý pozdějším neprůhledným obrázkem je sken s OCR")
    void textUnderOpaqueImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> { text(cs, RenderingMode.FILL); fullImage(cs, d); });
            assertEquals(Kind.SCAN_WITH_OCR, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("průhledný obrázek přes text text nezakrývá")
    void textUnderTransparentImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> {
                text(cs, RenderingMode.FILL);
                cs.drawImage(image(d, true), 0, 0, A4.getWidth(), A4.getHeight());
            });
            assertEquals(Kind.TEXT, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("text mimo obrázek zůstává textem, i když obrázek kreslí až po něm")
    void textBesideLaterImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> {
                text(cs, RenderingMode.FILL);                     // nahoře, y ≈ 800
                cs.drawImage(image(d, false), 0, 0, A4.getWidth(), A4.getHeight() * 0.7f);
            });
            assertEquals(Kind.TEXT, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("řádky jako drobné obrázky nejsou sken")
    void linesAsImages() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> {
                PDImageXObject img = image(d, false);
                for (int i = 0; i < 40; i++) cs.drawImage(img, 40, 60 + i * 18, 400, 8);
            });
            assertEquals(Kind.NO_TEXT_NOT_SCAN, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("prázdná stránka za skenem na výsledku nic nemění")
    void blankPageAfterScan() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> fullImage(cs, d));
            doc.addPage(new PDPage(A4));
            assertEquals(Kind.PLAIN_SCAN, PdfScanDetector.detect(doc));
        }
    }

    @Test
    @DisplayName("textová stránka vedle skenu dělá z dokumentu textový")
    void textAndScan() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, (cs, d) -> fullImage(cs, d));
            page(doc, (cs, d) -> text(cs, RenderingMode.FILL));
            assertEquals(Kind.TEXT, PdfScanDetector.detect(doc));
        }
    }

    // ------------------------------------------------------------------------------------

    private interface Content { void draw(PDPageContentStream cs, PDDocument doc) throws IOException; }

    private static void page(PDDocument doc, Content c) throws IOException {
        PDPage p = new PDPage(A4);
        doc.addPage(p);
        try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
            c.draw(cs, doc);
        }
    }

    private static void text(PDPageContentStream cs, RenderingMode mode) throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        cs.setRenderingMode(mode);
        cs.newLineAtOffset(40, 800);
        cs.showText(LINE);
        cs.endText();
    }

    private static void fullImage(PDPageContentStream cs, PDDocument doc) throws IOException {
        cs.drawImage(image(doc, false), 0, 0, A4.getWidth(), A4.getHeight());
    }

    private static PDImageXObject image(PDDocument doc, boolean alpha) throws IOException {
        BufferedImage bi = new BufferedImage(8, 8,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        return LosslessFactory.createFromImage(doc, bi);
    }
}
