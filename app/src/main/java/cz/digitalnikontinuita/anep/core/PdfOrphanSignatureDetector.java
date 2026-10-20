package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects PDF signature fields that DSS does not process — typically because their
 * /SubFilter is not recognised by DSS (e.g. adbe.x509.rsa_sha1). Uses PDFBox to
 * scan AcroForm fields independently of DSS, so no information about the format
 * is required upfront.
 */
public final class PdfOrphanSignatureDetector {

    /**
     * @param byteRange rozsah bajtů podpisu jako „a,b,c,d", nebo {@code null}, když ho pole
     *                  neuvádí. Slouží k rozpoznání, že jde o týž podpis, který zpracovalo DSS:
     *                  jména polí se totiž u zanořených polí rozcházejí (DSS hlásí vlastní /T,
     *                  PDFBox plné jméno ze stromu formuláře), kdežto rozsah je jeden.
     */
    public record OrphanSignature(
        String fieldName,
        String subFilter,
        String signerName,
        Instant signingTime,
        String location,
        String byteRange,
        // Jen u adbe.x509.rsa_sha1: podpis přečtený a kryptograficky zkontrolovaný,
        // a po Pkcs1SignatureValidator.verifyAll() i s ověřeným certifikátem. Jinak null.
        Pkcs1PdfSignature pkcs1,
        Pkcs1SignatureValidator.Verification verification
    ) {
        public OrphanSignature(String fieldName, String subFilter, String signerName,
                               Instant signingTime, String location, String byteRange) {
            this(fieldName, subFilter, signerName, signingTime, location, byteRange, null, null);
        }

        public OrphanSignature withVerification(Pkcs1SignatureValidator.Verification v) {
            return new OrphanSignature(fieldName, subFilter, signerName, signingTime, location,
                    byteRange, pkcs1, v);
        }
    }

    private PdfOrphanSignatureDetector() {}

    /**
     * Returns all signature fields found in the PDF by PDFBox, regardless of SubFilter.
     * The caller (ReportMapper) then filters out any whose fieldName is already known to DSS.
     *
     * @param source   the PDF being validated; non-PDF content is silently ignored
     * @param password decryption password, or {@code null} if not protected
     */
    public static List<OrphanSignature> detect(PdfSource source, char[] password) {
        List<OrphanSignature> result = new ArrayList<>();
        if (source == null || !source.isPdf()) {
            return result;
        }
        try (PDDocument doc = source.open(password)) {
            if (doc.getDocumentCatalog().getAcroForm() == null) return result;
            byte[][] raw = new byte[1][];
            for (PDField field : doc.getDocumentCatalog().getAcroForm().getFields()) {
                collectFromField(field, result, source, raw);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** @param raw bajty souboru, načtené až při prvním podpisu, který je potřebuje */
    private static void collectFromField(PDField field, List<OrphanSignature> result,
                                         PdfSource source, byte[][] raw) {
        if (field instanceof PDSignatureField sigField) {
            PDSignature sig = sigField.getSignature();
            if (sig == null) return;

            String name = sigField.getFullyQualifiedName();
            if (name == null) name = sigField.getPartialName();
            if (name == null) name = "unknown-" + result.size();

            String subFilter  = sig.getSubFilter();
            String signerName = sig.getName();
            String location   = sig.getLocation();
            Instant signingTime = null;
            try {
                java.util.Calendar cal = sig.getSignDate();
                if (cal != null) signingTime = cal.toInstant();
            } catch (Exception ignored) {}

            String byteRange = null;
            try {
                int[] br = sig.getByteRange();
                if (br != null && br.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < br.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(br[i]);
                    }
                    byteRange = sb.toString();
                }
            } catch (Exception ignored) {}

            Pkcs1PdfSignature pkcs1 = null;
            if (Pkcs1PdfSignature.SUB_FILTER.equals(subFilter)) {
                try {
                    if (raw[0] == null) {
                        try (java.io.InputStream in = source.openRaw()) {
                            raw[0] = in.readAllBytes();
                        }
                    }
                    pkcs1 = Pkcs1PdfSignature.read(sig, raw[0]);
                } catch (Exception ignored) {}
            }
            result.add(new OrphanSignature(name, subFilter, signerName, signingTime, location,
                    byteRange, pkcs1, null));
        } else if (field instanceof PDNonTerminalField group) {
            try {
                List<PDField> kids = group.getChildren();
                if (kids != null) {
                    for (PDField kid : kids) collectFromField(kid, result, source, raw);
                }
            } catch (Exception ignored) {}
        }
    }
}
