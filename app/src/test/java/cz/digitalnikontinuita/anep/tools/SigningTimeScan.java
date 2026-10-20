package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Podpisy, kterých se týká deklarovaný čas podpisu: situace 22 (atribut signing-time chybí nebo
 * je nečitelný — od 3. 10. 2026 jen poznámka) a 55 (deklarovaný čas mimo platnost certifikátu).
 *
 * <p>Každý soubor se ověří přísně, tedy bez ústupků — požadavek na signing-time je v politice
 * AnEP ztlumený trvale. U dotčených podpisů se vypíše formát, deklarovaný čas, zda ho AnEP našlo
 * v podpisu nečitelný, verdikt a situace, a navíc verdikt s tolerancí SHA-1 (oba podpisy
 * s nečitelným časem, které sbírky obsahují, jsou podepsané SHA-1).
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.tools.SigningTimeScan &lt;složka&gt;…</pre>
 */
public final class SigningTimeScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String a : args) roots.add(Path.of(a));

        AppSettings strict = strictSettings();
        AppSettings sha1 = strictSettings();
        sha1.setIgnoreSha1Deprecated(true);

        TrustConfig trust = new TrustConfig(strict);
        CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
        DocumentLoader loader = new DocumentLoader();
        ReportMapper mapper = new ReportMapper();

        int files = 0, sigs = 0, flagged = 0, failed = 0;
        Map<String, Integer> tally = new TreeMap<>();

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(p -> EXTENSIONS.stream().anyMatch(
                                e -> p.toString().toLowerCase(Locale.ROOT).endsWith(e)))
                        .sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    ValidationOutcome outcome = new ValidatorFactory(strict)
                            .validate(doc, null, verifier, null);
                    ValidationResult result = mapper.map(p.getFileName().toString(), null, outcome, strict);
                    ValidationResult withSha1 = null;
                    for (SignatureSummary s : result.getSignatures()) {
                        sigs++;
                        boolean s22 = s.getDetectedSituations().contains(22);
                        boolean s55 = s.getDetectedSituations().contains(55);
                        if (!s22 && !s55) continue;
                        flagged++;
                        if (withSha1 == null) {
                            withSha1 = mapper.map(p.getFileName().toString(), null,
                                    new ValidatorFactory(sha1).validate(
                                            loader.load(p.toFile()), null, verifier, null), sha1);
                        }
                        String id = s.getSignatureId();
                        SignatureSummary t = withSha1.getSignatures().stream()
                                .filter(x -> id != null && id.equals(x.getSignatureId()))
                                .findFirst().orElse(null);
                        SignatureWrapper sw = id == null ? null
                                : outcome.reports().getDiagnosticData().getSignatureById(id);
                        String verdict = s.getIndication()
                                + (s.getSubIndication() != null ? "/" + s.getSubIndication() : "");
                        String verdictSha1 = t == null ? "?" : t.getIndication()
                                + (t.getSubIndication() != null ? "/" + t.getSubIndication() : "");
                        tally.merge((s55 ? "S55" : s.isSigningTimeUnreadable() ? "S22 nečitelný" : "S22 chybí")
                                + "  " + s.getFormat() + "  " + verdict + "  (+SHA1 " + verdictSha1 + ")",
                                1, Integer::sum);
                        System.out.println(root.relativize(p)
                                + "  | formát=" + s.getFormat()
                                + "  claimed=" + (sw == null ? "?" : sw.getClaimedSigningTime())
                                + "  nečitelný=" + s.isSigningTimeUnreadable()
                                + "  | " + verdict + " S" + s.getDetectedSituations()
                                + " D" + s.getDetectedReasons()
                                + "  | +SHA1=" + verdictSha1
                                + (t == null ? "" : " D" + t.getDetectedReasons()));
                    }
                } catch (Exception | StackOverflowError e) {
                    failed++;
                    System.out.println(root.relativize(p) + "  !! " + e);
                }
            }
        }
        System.out.println();
        System.out.println("souborů " + files + ", prvků " + sigs + ", dotčených " + flagged
                + ", selhalo " + failed);
        tally.forEach((k, v) -> System.out.println(String.format("%5d  %s", v, k)));
        System.exit(0);
    }

    private static AppSettings strictSettings() {
        AppSettings s = AppSettings.load();
        for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) r.setEnabled(s, false);
        s.setTimestampStandardsMode(true);
        s.setUseCustomValidationTime(false);
        return s;
    }
}
