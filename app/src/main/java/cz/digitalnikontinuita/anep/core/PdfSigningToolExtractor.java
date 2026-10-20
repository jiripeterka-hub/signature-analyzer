package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Identifies the software that produced each PDF signature, by reading the
 * {@code /Prop_Build} build-properties dictionary of the signature dictionary.
 *
 * <p>{@code /Prop_Build /App /Name} is the only field whose defined meaning is "the
 * application used to create the signature"; {@code /Prop_Build /Filter /Name} names the
 * security handler (almost always {@code Adobe.PPKLite}, whatever the tool), so it is not
 * usable as a tool identification and is deliberately ignored. When {@code /App /Name} is
 * absent the signing tool is genuinely unknown and no entry is produced — callers must not
 * substitute a guess.
 */
public final class PdfSigningToolExtractor {

    private static final COSName PROP_BUILD = COSName.getPDFName("Prop_Build");
    private static final COSName APP        = COSName.getPDFName("App");
    private static final COSName NAME       = COSName.getPDFName("Name");
    private static final COSName REX        = COSName.getPDFName("REx");

    private PdfSigningToolExtractor() {}

    /**
     * Opens the PDF and reads the producing application for every signature field that
     * declares one.
     *
     * @param source   the PDF being validated; non-PDF content is silently ignored
     * @param password decryption password, or {@code null} if not password-protected
     * @return map from PDF field name to a human-readable tool label (name plus version
     *         when the signature declares one); fields without {@code /Prop_Build /App /Name}
     *         are absent from the map
     */
    public static Map<String, String> extract(PdfSource source, char[] password) {
        if (source == null || !source.isPdf()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        try (PDDocument doc = source.open(password)) {
            if (doc.getDocumentCatalog().getAcroForm() == null) return result;
            for (PDField field : doc.getDocumentCatalog().getAcroForm().getFields()) {
                collectFromField(field, result);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void collectFromField(PDField field, Map<String, String> result) {
        if (field instanceof PDSignatureField sigField) {
            PDSignature sig = sigField.getSignature();
            if (sig == null) return;

            String name = sigField.getFullyQualifiedName();
            if (name == null) name = sigField.getPartialName();
            if (name == null || result.containsKey(name)) return;

            String tool = readSigningTool(sig);
            if (tool != null) {
                result.put(name, tool);
                // Týž alias jako u vizualizací — viz PdfSignatureAppearanceExtractor.
                String vlastni = sigField.getPartialName();
                if (vlastni != null && !vlastni.equals(name)) result.putIfAbsent(vlastni, tool);
            }
        } else if (field instanceof PDNonTerminalField group) {
            try {
                List<PDField> kids = group.getChildren();
                if (kids != null) {
                    for (PDField kid : kids) collectFromField(kid, result);
                }
            } catch (Exception ignored) {}
        }
    }

    /** Returns "<app name> <version>", "<app name>", or null when nothing is declared. */
    private static String readSigningTool(PDSignature sig) {
        try {
            COSDictionary propBuild = sig.getCOSObject().getCOSDictionary(PROP_BUILD);
            if (propBuild == null) return null;
            COSDictionary app = propBuild.getCOSDictionary(APP);
            if (app == null) return null;

            String appName = asText(app.getDictionaryObject(NAME));
            if (appName == null || appName.isBlank()) return null;

            String version = asText(app.getDictionaryObject(REX));
            return (version != null && !version.isBlank())
                    ? appName + " " + version.trim()
                    : appName;
        } catch (Exception e) {
            return null;
        }
    }

    /** /Name is written as a PDF name by most tools but as a string by some; accept both. */
    private static String asText(COSBase value) {
        if (value instanceof COSName n)   return n.getName().trim();
        if (value instanceof COSString s) return s.getString().trim();
        return null;
    }
}
