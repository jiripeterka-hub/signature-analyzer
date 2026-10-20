package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Kolik prvků ve sbírce se dotýká rozlišení, které závěr v Analýze nově dělá: „vznikl až po expiraci"
 * proti „nepodařilo se prokázat, že vznikl ještě za platnosti".
 *
 * <p>Rozhoduje táž podmínka jako v {@code AdvisorView.unprovenTimestampsWithinValidity}: razítko,
 * které neobstálo a nese čas ještě z doby platnosti podpisového certifikátu. Sonda ji počítá
 * znovu, protože ta metoda je privátní — a protože počítat ji vedle sebe je způsob, jak ověřit,
 * že se opravdu ptá na to, na co si myslím.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.PostExpiryConclusionScan &lt;složka&gt;…</pre>
 */
public final class PostExpiryConclusionScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String a : args) roots.add(Path.of(a));
        if (roots.isEmpty()) {
            System.err.println("usage: PostExpiryConclusionScan <folder> [<folder>...]");
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

        int sigs = 0, s25 = 0, changed = 0, noTimestampExpired = 0;
        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(PostExpiryConclusionScan::isCandidate)
                        .sorted().toList();
            }
            for (Path p : candidates) {
                try {
                    DSSDocument doc = documentLoader.load(p.toFile());
                    ValidationOutcome outcome =
                            validatorFactory.validate(doc, null, verifier, null);
                    ValidationResult result = reportMapper.map(
                            p.getFileName().toString(), null, outcome, settings);
                    for (SignatureSummary sig : result.getSignatures()) {
                        sigs++;
                        Instant certUntil = sig.getCertNotAfter();
                        boolean expired = certUntil != null
                                && !certUntil.isAfter(Instant.now());
                        List<SignatureSummary.TimestampEntry> all = sig.getAllTimestamps();
                        if (all != null && all.isEmpty() && expired) {
                            noTimestampExpired++;
                            System.out.println("  bezRazitka  " + root.relativize(p)
                                    + "   [" + sig.getIndication()
                                    + (sig.getSubIndication() != null
                                            ? "/" + sig.getSubIndication() : "") + "]");
                        }
                        if (!sig.getDetectedSituations().contains(25)) continue;
                        s25++;
                        List<SignatureSummary.TimestampEntry> unproven = certUntil == null
                                || all == null ? List.of()
                                : all.stream().filter(t -> !t.passed())
                                     .filter(t -> t.time() != null
                                             && !t.time().isAfter(certUntil))
                                     .toList();
                        if (!unproven.isEmpty()) changed++;
                        System.out.println((unproven.isEmpty() ? "  beze zmeny  " : "* ZMENA     ")
                                + root.relativize(p)
                                + "   razitek=" + all.size()
                                + " neobstalych-za-platnosti=" + unproven.size()
                                + " situace=" + sig.getDetectedSituations()
                                + " duvody=" + sig.getDetectedReasons());
                    }
                } catch (Exception e) {
                    System.out.println("  CHYBA       " + root.relativize(p) + "  " + e);
                }
            }
        }
        System.out.println("---");
        System.out.println("podpisu=" + sigs + "  se situaci 25=" + s25
                + "  zmeneny zaver=" + changed
                + "  bez razitka a po expiraci=" + noTimestampExpired);
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
