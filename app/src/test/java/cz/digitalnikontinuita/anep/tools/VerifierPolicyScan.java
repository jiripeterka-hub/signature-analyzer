package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.PDFRevisionWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Sken sbírky pro srovnání AnEP s politikami ověřování jiných služeb (SecuSign, QVerify).
 *
 * <p>Vznikl 4. 10. 2026. {@link SituationScan} vypíše verdikt, situace a důvody, ale ne to, v čem
 * se politiky ostatních ověřovatelů od AnEP liší podle srovnávací tabulky v
 * {@code docs/politika-overovani.md} (kap. 14): podpisovou politiku (QVerify ji v PAdES a CAdES
 * odmítá), změny PDF po podpisu (QVerify po posledním podpisu připouští jen validační data
 * a metadata), revokační údaj bez nextUpdate (QVerify bez něj neprojde), SHA-1 a deklarovaný čas.
 * Tahle sonda to vypíše na jednom řádku s verdiktem, aby šly rozchody spočítat bez druhého běhu.
 *
 * <p>Řádek je oddělený tabulátory:
 * soubor, verdikt, formát, kvalifikace, algoritmus otisku, politika, změny PDF, revokace bez
 * nextUpdate, signing-time, situace, důvody. Ověřuje se přísně (bez ústupků), jako {@code -strict}
 * u {@link SituationScan}.
 *
 * <pre>
 *   java -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.VerifierPolicyScan "D:\cesta\ke\sbirce" &gt; vystup.tsv
 * </pre>
 */
public final class VerifierPolicyScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: VerifierPolicyScan <folder> [<folder>...]");
            return;
        }
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setAcceptPdfNotEtsi(false);
        TrustConfig trustConfig = new TrustConfig(settings);
        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        ReportMapper reportMapper = new ReportMapper();
        DocumentLoader documentLoader = new DocumentLoader();
        CommonCertificateVerifier verifier = trustConfig.buildLotlCertificateVerifier();

        System.out.println(String.join("\t", "soubor", "verdikt", "format", "kvalifikace",
                "otisk", "politika", "zmenyPdf", "bezNextUpdate", "signingTime", "situace",
                "duvody"));
        int files = 0, failed = 0;
        for (String a : args) {
            Path root = Path.of(a);
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(VerifierPolicyScan::isCandidate).sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                String rel = root.relativize(p).toString();
                try {
                    DSSDocument doc = documentLoader.load(p.toFile());
                    ValidationOutcome outcome = validatorFactory.validate(doc, null, verifier, null);
                    ValidationResult result = reportMapper.map(
                            p.getFileName().toString(), null, outcome, settings);
                    DiagnosticData dd = outcome.reports().getDiagnosticData();
                    for (SignatureSummary sig : result.getSignatures()) {
                        SignatureWrapper sw = sig.getSignatureId() != null
                                ? dd.getSignatureById(sig.getSignatureId()) : null;
                        String qual = "";
                        try {
                            var q = outcome.reports().getSimpleReport()
                                    .getSignatureQualification(sig.getSignatureId());
                            qual = q != null ? q.name() : "";
                        } catch (Exception ignored) { }
                        System.out.println(String.join("\t", rel,
                                sig.getIndication() + (sig.getSubIndication() != null
                                        ? "/" + sig.getSubIndication() : ""),
                                String.valueOf(sig.getFormat()), qual,
                                sw != null && sw.getDigestAlgorithm() != null
                                        ? sw.getDigestAlgorithm().getName() : "",
                                sw != null && sw.isPolicyPresent()
                                        ? String.valueOf(sw.getPolicyId()) : "",
                                sw != null ? pdfChanges(sw) : "",
                                sw != null ? noNextUpdate(sw) : "",
                                sig.getSigningTime() != null ? "ano" : "ne",
                                String.valueOf(sig.getDetectedSituations()),
                                String.valueOf(sig.getDetectedReasons())));
                    }
                } catch (Exception | StackOverflowError e) {
                    failed++;
                    System.out.println(rel + "\t!! " + e);
                }
            }
        }
        System.out.println("# souborů: " + files + ", chyb: " + failed);
    }

    /** Druhy změn PDF, které DSS zjistil po revizi podpisu; prázdné, když žádné. */
    private static String pdfChanges(SignatureWrapper sw) {
        PDFRevisionWrapper rev = sw.getPDFRevision();
        if (rev == null) return "";
        List<String> out = new ArrayList<>();
        if (!rev.getPdfUndefinedChanges().isEmpty()) out.add("nezarazene");
        if (!rev.getPdfAnnotationChanges().isEmpty()) out.add("anotace");
        if (!rev.getPdfSignatureOrFormFillChanges().isEmpty()) out.add("podpis/formular");
        if (!rev.getPdfExtensionChanges().isEmpty()) out.add("rozsireni");
        if (!rev.getPdfVisualDifferenceConcernedPages().isEmpty()) out.add("vzhled");
        if (!rev.getPdfAnnotationsOverlapConcernedPages().isEmpty()) out.add("prekryv");
        return String.join(",", out);
    }

    /** Revokační údaje o podpisovém certifikátu bez nextUpdate: počet z celku, např. "1/2". */
    private static String noNextUpdate(SignatureWrapper sw) {
        if (sw.getSigningCertificate() == null) return "";
        List<CertificateRevocationWrapper> revs =
                sw.getSigningCertificate().getCertificateRevocationData();
        if (revs.isEmpty()) return "";
        long missing = revs.stream().filter(r -> r.getNextUpdate() == null).count();
        return missing + "/" + revs.size();
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
