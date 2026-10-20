package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.validation.SignedDocumentValidator;

import java.io.File;
import java.nio.file.Files;

/**
 * Walks a PDF whose {@code %PDF-} header does not sit at byte zero through the whole of AnEP:
 * first what DSS makes of the file untouched, then what the app's own loader hands it, and finally
 * the verdict and the course of the verification the reader would see.
 *
 * <p>Written for a report that starts with a single {@code 0x0A} and was
 * reported as an unsupported format although every PDF reader opens it. DSS decides which
 * validator handles a document from its opening bytes, so the stray byte alone was enough; the
 * question this probe answers is whether the signature inside is otherwise sound, and whether the
 * cut {@link DocumentLoader} now makes is both enough and honestly reported.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why. From {@code app/}:
 *
 * <pre>
 *   java -cp "$(cat cp.txt);target/classes;target/test-classes" \
 *        cz.digitalnikontinuita.anep.tools.PdfPreambleProbe "D:\path\to\file.pdf"
 * </pre>
 */
public final class PdfPreambleProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: PdfPreambleProbe <file.pdf | folder>");
            return;
        }
        File f = new File(args[0]);
        if (f.isDirectory()) {
            scanFolder(f);
            return;
        }
        byte[] bytes = Files.readAllBytes(f.toPath());
        System.out.println("soubor        : " + f.getName() + " (" + bytes.length + " B)");

        System.out.print("DSS na souboru: ");
        try {
            System.out.println(SignedDocumentValidator.fromDocument(new FileDocument(f))
                    .getClass().getSimpleName());
        } catch (Exception e) {
            System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        DocumentLoader.Loaded loaded = new DocumentLoader().loadWithDetail(f);
        System.out.println("odříznuto     : " + loaded.strayPrefixBytes() + " B");

        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings)
                .validate(loaded.document(), null, verifier, null);
        ValidationResult result = new ReportMapper().map(f.getName(), "PDF", outcome, settings,
                java.util.Map.of(), java.util.List.of(), false, java.util.Map.of(),
                java.util.Map.of(), loaded.strayPrefixBytes());

        printVerdict(result);
    }

    /**
     * Loader-only sweep of a corpus: every file whose {@code %PDF-} header sits away from byte
     * zero, and what the loader decided about it. Nothing is validated, so this is fast and can
     * run over thousands of files.
     *
     * <p>The interesting column is the last one. A file DSS already understands — an enveloping
     * CAdES carries the signed PDF a few dozen bytes in, and so does a ZIP whose first entry is
     * one — must come back untouched; only a document nothing else explains may be cut.
     */
    private static void scanFolder(File root) throws Exception {
        DocumentLoader loader = new DocumentLoader();
        int[] counts = new int[2]; // 0 = header off zero, 1 = actually trimmed
        try (var paths = Files.walk(root.toPath())) {
            paths.filter(Files::isRegularFile).forEach(p -> {
                byte[] head;
                try {
                    try (var in = Files.newInputStream(p)) {
                        head = in.readNBytes(1029);
                    }
                } catch (Exception e) {
                    return;
                }
                int at = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("%PDF-");
                if (at <= 0) return;
                counts[0]++;
                int cut = loader.loadWithDetail(p.toFile()).strayPrefixBytes();
                if (cut > 0) counts[1]++;
                System.out.println(String.format("%5d  %-9s %s", at,
                        cut > 0 ? "ODŘÍZNUTO" : "ponecháno", p));
            });
        }
        System.out.println("---");
        System.out.println("hlavička mimo nulu: " + counts[0] + ", z toho odříznuto: " + counts[1]);
    }

    private static void printVerdict(ValidationResult result) {
        System.out.println("podpisů       : " + result.getSignatures().size());
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.println("  formát      : " + sig.getFormat());
            System.out.println("  výsledek    : " + sig.getIndication()
                    + (sig.getSubIndication() != null ? " / " + sig.getSubIndication() : ""));
            System.out.println("  právní typ  : " + sig.getLegalType());
            System.out.println("  podepsal    : " + sig.getSignerDisplayName());
            var vc = sig.getValidationCourse();
            if (vc != null) {
                System.out.println("  revokace využitá:");
                vc.revocationUsed().forEach(n -> System.out.println("    • " + n.text()));
                System.out.println("  revokace nevyužitá:");
                vc.revocationNotDecisive().forEach(n -> System.out.println("    • " + n.text()));
                System.out.println("  revokace nepoužitelná:");
                vc.revocationRejected().forEach(n -> System.out.println("    • " + n.text()));
                System.out.println("  revokace chybějící:");
                vc.revocationMissing().forEach(n -> System.out.println("    • " + n));
                System.out.println("  revokace se nezjišťovala:");
                vc.revocationNotApplicable().forEach(n -> System.out.println("    • " + n));
                System.out.println("  podmínky ověřování:");
                vc.contextNotes().forEach(n -> System.out.println("    • " + n));
            }
        }
    }
}
