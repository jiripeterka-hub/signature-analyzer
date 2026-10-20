package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts the visual appearance of PDF signature fields as PNG images.
 *
 * Each PDF signature annotation can optionally carry an appearance stream (/AP /N)
 * that is rendered by PDF viewers as the visible signature image (e.g. a hand-written
 * signature, a company stamp, or a graphical badge). This class detects such appearances
 * and renders them by rendering the page region occupied by the annotation.
 */
public final class PdfSignatureAppearanceExtractor {

    private static final float RENDER_SCALE = 2.0f; // 144 DPI — crisp on HiDPI screens

    /**
     * Dvojnásobek pro vizualizaci, kterou v dokumentu nikdo neuvidí.
     *
     * <p>Taková vizualizace zůstává ve výchozím políčku — velikost se u něčeho neviditelného
     * nemá proč měnit. Změřeno: všech 46 v korpusu má 150 × 32 bodu a text je v nich namačkaný
     * tak, že se v běžné velikosti přečíst nedá. Vykreslí se proto rovnou ve dvojnásobném
     * rozlišení, ne že by se dodatečně zvětšil hotový snímek: roztažená bitmapa by byla dvakrát
     * větší a pořád stejně nečitelná.</p>
     */
    private static final float RENDER_SCALE_NEVER_SHOWN = 4.0f;

    private PdfSignatureAppearanceExtractor() {}

    /**
     * Opens the PDF and extracts the rendered appearance for each signature field that
     * has a non-empty normal appearance stream.
     *
     * @param source   the PDF being validated; non-PDF content is silently ignored
     * @param password decryption password, or {@code null} if not password-protected
     * @return map from PDF field name to its appearance; empty when none found or on error
     */
    public static Map<String, Appearance> extract(PdfSource source, char[] password) {
        if (source == null || !source.isPdf()) {
            return Map.of();
        }
        Map<String, Appearance> result = new HashMap<>();
        try (PDDocument doc = source.open(password)) {
            if (doc.getDocumentCatalog().getAcroForm() == null) return result;
            PDFRenderer renderer = new PDFRenderer(doc);
            for (PDField field : doc.getDocumentCatalog().getAcroForm().getFields()) {
                collectFromField(doc, renderer, field, result);
            }
        } catch (Exception e) {
            // Silently return what we collected so far; PDF parsing failures are non-fatal here
        }
        return result;
    }

    /**
     * The picture a signature shows in the document, and whether the reader gets to see it.
     *
     * @param png        the rendered appearance; an entry exists only where there is one
     * @param visibility whether it reaches the screen and the printer — a signature can carry a
     *                   perfectly good picture that no viewer ever draws
     */
    public record Appearance(byte[] png, PdfFieldVisibility visibility) {}

    private static void collectFromField(PDDocument doc, PDFRenderer renderer,
                                         PDField field, Map<String, Appearance> result) {
        if (field instanceof PDSignatureField sigField) {
            // Jen pole, ve kterém opravdu podpis je. Prázdné pole vlastní vzhled nemá — nese
            // Adobe placeholder „% DSBlank", který se nakreslí jako nic, takže výřez ukáže obsah
            // stránky pod polem. Kdyby se takový výřez uložil, byl by to obrázek dokumentu
            // vydávaný za vizualizaci podpisu. Nikdo si ho sice nevyzvedne (mapa se prohledává
            // podle jmen polí, která DSS hlásí jako podpisy, a osiřelé podpisy taky hodnotu
            // mají), ale od chvíle, kdy se kvůli skrytým podpisům příznaky před vykreslením
            // nulují, by to už nebyla jen zbytečná práce.
            if (sigField.getSignature() == null) return;

            String name = sigField.getFullyQualifiedName();
            if (name == null) name = sigField.getPartialName();
            if (name == null || result.containsKey(name)) return;

            for (PDAnnotationWidget widget : sigField.getWidgets()) {
                // Read the flags before rendering — rendering clears them on the in-memory copy.
                PdfFieldVisibility visibility =
                        PdfEmptySignatureFieldDetector.visibilityOf(widget);
                byte[] png = renderWidgetAppearance(doc, renderer, widget, visibility);
                if (png != null) {
                    result.put(name, new Appearance(png, visibility));
                    // Alias pod vlastním jménem pole: DSS hlásí /T, kdežto klíč výš je plné
                    // jméno ze stromu formuláře. Bez aliasu zůstal podpis ze zanořeného pole
                    // bez vizualizace a ta připadla přízraku — dokument s doložkou, 11. 9. 2026.
                    String vlastni = sigField.getPartialName();
                    if (vlastni != null && !vlastni.equals(name)) {
                        result.putIfAbsent(vlastni, new Appearance(png, visibility));
                    }
                    break; // first widget with a valid appearance is enough
                }
            }
        } else if (field instanceof PDNonTerminalField group) {
            // Recurse into non-terminal fields (groups) — signature fields are almost always
            // top-level, but this handles pathological nested AcroForms.
            try {
                List<PDField> kids = group.getChildren();
                if (kids != null) {
                    for (PDField kid : kids) collectFromField(doc, renderer, kid, result);
                }
            } catch (Exception ignored) {}
        }
    }

    private static byte[] renderWidgetAppearance(PDDocument doc, PDFRenderer renderer,
                                                  PDAnnotationWidget widget,
                                                  PdfFieldVisibility visibility) {
        try {
            PDAppearanceDictionary ap = widget.getAppearance();
            if (ap == null) return null;
            PDAppearanceEntry normal = ap.getNormalAppearance();
            if (normal == null) return null;

            // Ignore sub-dictionaries (state-dependent appearances) — use the stream directly
            if (!normal.isStream()) return null;
            PDAppearanceStream stream = normal.getAppearanceStream();
            if (stream == null) return null;

            // Reject trivially empty streams (no visible content)
            long streamLen;
            try { streamLen = stream.getStream().getLength(); } catch (Exception e) { streamLen = -1; }
            if (streamLen == 0) return null;

            PDRectangle widgetRect = widget.getRectangle();
            if (widgetRect == null || widgetRect.getWidth() <= 1 || widgetRect.getHeight() <= 1) return null;

            int pageIndex = findPageIndex(doc, widget);
            if (pageIndex < 0) return null;

            // PDFBox's page drawer honours Hidden and NoView and simply skips such a widget, so
            // the crop below would come back white and the appearance would be thrown away as
            // blank. That is the wrong answer twice over: the picture exists, and for a
            // print-only signature it is exactly what comes out on paper — the reader needs to
            // see what they are being told about. Clearing the flags for the duration of the
            // render is safe because the document is an in-memory copy that is never saved.
            // Measured on prazdny_podpis_neviditelny_tisknuty_podepsany.pdf (/F 164): as-is the
            // crop is blank, and PDFRenderer.setAnnotationsFilter does not help — the check sits
            // in the drawer, not in the filter.
            float scale = visibility != null && visibility.neverShown()
                    ? RENDER_SCALE_NEVER_SHOWN : RENDER_SCALE;
            int savedFlags = widget.getAnnotationFlags();
            widget.setAnnotationFlags(FLAG_PRINT_ONLY);
            try {
                return renderCrop(doc, renderer, widgetRect, pageIndex, scale);
            } finally {
                widget.setAnnotationFlags(savedFlags);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code Print} alone — visible to the drawer, with no Hidden or NoView to suppress it. */
    private static final int FLAG_PRINT_ONLY = 4;

    private static byte[] renderCrop(PDDocument doc, PDFRenderer renderer,
                                     PDRectangle widgetRect, int pageIndex, float scale) {
        try {
            BufferedImage pageImage = renderer.renderImage(pageIndex, scale);
            PDPage page = doc.getPage(pageIndex);
            // CropBox, ne MediaBox: renderer kreslí právě ten a liší-li se, byl by výřez posunutý
            // nebo úplně mimo obraz. Bez vlastního CropBoxu vrací PDFBox MediaBox, takže se pro
            // běžnou stránku nic nemění.
            PDRectangle box = page.getCropBox();
            int rotation = page.getRotation();

            // Compute crop rect accounting for page rotation applied by the renderer.
            // PDFRenderer already rotates the output image, but widgetRect is in native coordinates.
            int[] crop = computeCrop(box, widgetRect, rotation, scale);
            int cx = crop[0], cy = crop[1], cw = crop[2], ch = crop[3];

            // Clamp to image bounds
            cx = Math.max(0, cx);
            cy = Math.max(0, cy);
            cw = Math.min(cw, pageImage.getWidth() - cx);
            ch = Math.min(ch, pageImage.getHeight() - cy);
            if (cw <= 2 || ch <= 2) return null;

            BufferedImage cropped = pageImage.getSubimage(cx, cy, cw, ch);
            if (isBlankImage(cropped)) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Computes the crop rectangle in image pixels, accounting for the page rotation that
     * PDFRenderer applies to the output image. widgetRect is in native PDF coordinates
     * (origin bottom-left, Y up); the rendered image has origin top-left, Y down.
     *
     * <p><b>U 90° a 270° tu byly svislé souřadnice prohozené</b> — devadesátka počítala
     * {@code pw - x - w} a dvěstěsedmdesátka {@code x}, ač to patří obráceně. Výřez pak seděl
     * na správné straně obrazu, ale u opačného okraje, takže se místo vizualizace ukázal kus
     * dokumentu pod ní. Vodorovné souřadnice i obě velikosti byly správně, což to schovávalo:
     * výřez měl správný tvar a nebyl prázdný, takže prošel i kontrolou
     * {@link #isBlankImage}.
     *
     * <p>Ověřeno na mapovém podkladu s otočenou stránkou (jediný dokument
     * s otočenou stránkou mezi 5 592 podepsanými PDF korpusu — proto to tak dlouho vydrželo)
     * a na jeho kopiích přeuložených se všemi čtyřmi natočeními: u 0° a 180° dává vzorec totéž
     * co dřív, u 90° a 270° nově trefí vizualizaci. Viz {@code RotationCropProbe}.
     */
    static int[] computeCrop(PDRectangle box, PDRectangle widget,
                              int rotation, float scale) {
        // Souřadnice pole vůči počátku vykreslovaného boxu, ne vůči počátku soustavy: box nemusí
        // začínat v nule a obraz vždycky začíná svým levým dolním rohem.
        float x  = widget.getLowerLeftX() - box.getLowerLeftX();
        float y  = widget.getLowerLeftY() - box.getLowerLeftY();
        float w  = widget.getWidth();
        float h  = widget.getHeight();
        float pw = box.getWidth();
        float ph = box.getHeight();

        int ix, iy, iw, ih;
        switch (((rotation % 360) + 360) % 360) {
            case 90:
                // Obraz je po natočení široký ph a vysoký pw.
                ix = Math.round(y * scale);
                iy = Math.round(x * scale);
                iw = Math.round(h * scale);
                ih = Math.round(w * scale);
                break;
            case 180:
                ix = Math.round((pw - x - w) * scale);
                iy = Math.round(y * scale);
                iw = Math.round(w * scale);
                ih = Math.round(h * scale);
                break;
            case 270:
                // Obraz je po natočení široký ph a vysoký pw.
                ix = Math.round((ph - y - h) * scale);
                iy = Math.round((pw - x - w) * scale);
                iw = Math.round(h * scale);
                ih = Math.round(w * scale);
                break;
            default: // 0 or any other
                ix = Math.round(x * scale);
                iy = Math.round((ph - y - h) * scale);
                iw = Math.round(w * scale);
                ih = Math.round(h * scale);
                break;
        }
        return new int[]{ix, iy, iw, ih};
    }

    private static int findPageIndex(PDDocument doc, PDAnnotationWidget widget) {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                for (PDAnnotation ann : doc.getPage(i).getAnnotations()) {
                    if (ann.getCOSObject() == widget.getCOSObject()) return i;
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /** Returns true when every pixel in the image is white (or near-white), i.e. no visible content. */
    private static boolean isBlankImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w == 0 || h == 0) return true;
        // Sample at most 400 pixels across the image for speed
        int step = Math.max(1, (w * h) / 400);
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++, idx++) {
                if (idx % step != 0) continue;
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >>  8) & 0xff;
                int b =  rgb        & 0xff;
                // Non-white pixel found — image has content
                if (r < 240 || g < 240 || b < 240) return false;
            }
        }
        return true;
    }
}
