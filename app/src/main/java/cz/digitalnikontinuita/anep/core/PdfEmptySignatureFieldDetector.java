package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects PDF signature fields that are prepared but not yet filled — an AcroForm field of type
 * {@code /Sig} with no {@code /V}. Such a field is an invitation to sign left in the document by
 * whoever prepared it, and nothing in DSS reports it: DSS validates signatures, and here there
 * is none, so the whole finding would otherwise be lost.
 *
 * <p>The field also decides in advance what the future signature will look like — see
 * {@link PdfFieldVisibility}, which computes that from the annotation flags and the widget
 * rectangle and is shared with the already-signed case.
 *
 * <p>Deliberately <b>not</b> used as evidence: the appearance stream. An empty field carries
 * Adobe's placeholder {@code % DSBlank} (a comment, so nothing is drawn), which is the normal
 * state — the viewer generates the real appearance at signing time, replacing it with a
 * {@code /FRM Do} reference to the form it builds. A blank stream therefore says nothing about
 * whether the finished signature will be visible; only {@code /F} and {@code /Rect} do.
 */
public final class PdfEmptySignatureFieldDetector {

    /** Field flags (/Ff), table 227 — shared by every field type. */
    private static final int FF_REQUIRED = 1 << 1; // 2

    /**
     * One prepared, unsigned signature field.
     *
     * @param fieldName  the field's fully qualified name, as the signing tool will show it
     * @param page       1-based page carrying the widget; 0 when it belongs to no page
     * @param visibility what the finished signature will look like
     * @param required   the field is marked {@code Required} in {@code /Ff}
     * @param lock       zámek, který pole položí, až ho někdo podepíše; null, když žádný nenese
     */
    public record EmptyField(
        String fieldName,
        int page,
        PdfFieldVisibility visibility,
        boolean required,
        cz.digitalnikontinuita.anep.model.SignatureSummary.PdfLock lock
    ) {}

    private PdfEmptySignatureFieldDetector() {}

    /**
     * Returns every signature field in the PDF that has no value yet, in AcroForm order.
     *
     * @param source   the document being validated; non-PDF content yields an empty list
     * @param password decryption password, or {@code null} if not protected
     */
    public static List<EmptyField> detect(PdfSource source, char[] password) {
        List<EmptyField> result = new ArrayList<>();
        if (source == null || !source.isPdf()) {
            return result;
        }
        try (PDDocument doc = source.open(password)) {
            if (doc.getDocumentCatalog().getAcroForm() == null) return result;
            for (PDField field : doc.getDocumentCatalog().getAcroForm().getFields()) {
                collectFromField(doc, field, result);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void collectFromField(PDDocument doc, PDField field, List<EmptyField> result) {
        if (field instanceof PDSignatureField sigField) {
            // A field with a value is a signature, and every other screen in the application is
            // already about those. Only the empty ones belong here.
            if (sigField.getSignature() != null) return;

            String name = sigField.getFullyQualifiedName();
            if (name == null) name = sigField.getPartialName();
            if (name == null) name = "bez názvu";

            boolean required = (sigField.getFieldFlags() & FF_REQUIRED) != 0;

            // The first widget decides, the same choice the appearance extractor makes. A
            // signature field with several widgets would be one signature shown in several
            // places; none of the reference documents has one, and picking the first keeps the
            // report to one line per field the signing tool will also show as one entry.
            cz.digitalnikontinuita.anep.model.SignatureSummary.PdfLock lock = readLock(sigField);

            PDAnnotationWidget widget = firstWidget(sigField);
            if (widget == null) {
                // No annotation at all — nothing to draw and nowhere to draw it.
                result.add(new EmptyField(name, 0, PdfFieldVisibility.none(), required, lock));
                return;
            }
            result.add(new EmptyField(name, findPageNumber(doc, widget),
                    visibilityOf(widget), required, lock));
        } else if (field instanceof PDNonTerminalField group) {
            try {
                List<PDField> kids = group.getChildren();
                if (kids != null) {
                    for (PDField kid : kids) collectFromField(doc, kid, result);
                }
            } catch (Exception ignored) {}
        }
    }

    /** Sdílené i s {@link PdfSignatureAppearanceExtractor}, aby obě cesty četly totéž. */
    static PdfFieldVisibility visibilityOf(PDAnnotationWidget widget) {
        PDRectangle rect = widget.getRectangle();
        float w = rect == null ? 0f : Math.abs(rect.getWidth());
        float h = rect == null ? 0f : Math.abs(rect.getHeight());
        return new PdfFieldVisibility(widget.getAnnotationFlags(), w, h);
    }

    /**
     * Zámek zapsaný do dosud nepodepsaného pole: {@code /Lock} podle ISO 32000-1, tabulka 233,
     * rozšířená v PDF 2.0 o entry {@code /P}.
     *
     * <p>Čte se přímo z PDF, protože DSS sem nedosáhne — jeho {@code PDFRevisionWrapper} popisuje
     * revizi vzniklou podpisem, a tady žádný podpis není. Přitom právě u prázdného pole je ten
     * údaj nejužitečnější: říká předem, co se s dokumentem stane, až ho někdo podepíše.
     *
     * <p>{@code /P} nese stejná čísla jako DocMDP (1 = žádné změny, 2 = vyplňování formulářů
     * a podpisy, 3 = navíc anotace); jméno se překládá na tytéž konstanty, jaké dává DSS, aby
     * všechny pohledy četly jeden slovník hodnot.
     */
    private static cz.digitalnikontinuita.anep.model.SignatureSummary.PdfLock readLock(
            PDSignatureField field) {
        try {
            org.apache.pdfbox.cos.COSDictionary lock = field.getCOSObject()
                    .getCOSDictionary(org.apache.pdfbox.cos.COSName.getPDFName("Lock"));
            if (lock == null) return null;

            org.apache.pdfbox.cos.COSName action =
                    lock.getCOSName(org.apache.pdfbox.cos.COSName.getPDFName("Action"));
            String scope = action != null ? action.getName().toUpperCase(java.util.Locale.ROOT) : null;

            List<String> fields = new ArrayList<>();
            org.apache.pdfbox.cos.COSArray names =
                    lock.getCOSArray(org.apache.pdfbox.cos.COSName.getPDFName("Fields"));
            if (names != null) {
                for (int i = 0; i < names.size(); i++) {
                    if (names.getObject(i) instanceof org.apache.pdfbox.cos.COSString s) {
                        fields.add(s.getString());
                    }
                }
            }

            String permission = switch (lock.getInt(org.apache.pdfbox.cos.COSName.P, -1)) {
                case 1 -> "NO_CHANGE_PERMITTED";
                case 2 -> "MINIMAL_CHANGES_PERMITTED";
                case 3 -> "CHANGES_PERMITTED";
                default -> null;
            };
            return new cz.digitalnikontinuita.anep.model.SignatureSummary.PdfLock(
                    scope, fields, permission);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PDAnnotationWidget firstWidget(PDSignatureField field) {
        try {
            List<PDAnnotationWidget> widgets = field.getWidgets();
            if (widgets != null && !widgets.isEmpty()) return widgets.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * The page the widget sits on, 1-based. {@code PDAnnotationWidget.getPage()} reads the
     * optional {@code /P} back-reference, which plenty of producers omit, so the pages are
     * searched instead — the same approach the appearance extractor takes.
     *
     * @return 0 when no page claims the widget
     */
    private static int findPageNumber(PDDocument doc, PDAnnotationWidget widget) {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                for (PDAnnotation ann : doc.getPage(i).getAnnotations()) {
                    if (ann.getCOSObject() == widget.getCOSObject()) return i + 1;
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }
}
