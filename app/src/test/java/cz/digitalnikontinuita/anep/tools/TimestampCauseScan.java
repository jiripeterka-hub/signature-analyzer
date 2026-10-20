package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * S jakou příčinou neobstojí časová razítka napříč sbírkou — a jak častá která je.
 *
 * <p>Podklad k závorce u řádku „platnost razítka se nepodařilo ověřit" v Rozšířeném přehledu.
 * Znení má smysl psát jen k příčinám, které se opravdu vyskytují; {@code OTHER} navíc říká, kolik
 * razítek by zůstalo bez závorky.
 *
 * <p>Vypíše i to, zda je u prvku indikována situace 43 (odebraný kvalifikovaný status služby) —
 * uživatel ji uvádí jako jeden z důvodů, které chce v závorce vidět, a výčet
 * {@code TimestampFailure} ji nezná.
 *
 * <pre>java -cp … TimestampCauseScan &lt;složka&gt;…</pre>
 */
public final class TimestampCauseScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String a : args) roots.add(Path.of(a));
        if (roots.isEmpty()) {
            System.err.println("usage: TimestampCauseScan <složka>…");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setAcceptPdfNotEtsi(false);

        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        var mapper = new ReportMapper();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        TreeMap<String, Integer> tally = new TreeMap<>();
        TreeMap<String, String> sample = new TreeMap<>();
        int failed = 0, all = 0;

        for (Path root : roots) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).filter(TimestampCauseScan::isCandidate)
                        .sorted().toList();
            }
            for (Path p : files) {
                try {
                    var doc = loader.load(p.toFile());
                    var outcome = factory.validate(doc, null, verifier, null);
                    ValidationResult result =
                            mapper.map(p.getFileName().toString(), null, outcome, settings);
                    for (SignatureSummary sig : result.getSignatures()) {
                        boolean withdrawn = sig.getDetectedSituations().contains(43);
                        for (TimestampEntry t : sig.getAllTimestamps()) {
                            all++;
                            if (t.passed()) continue;
                            failed++;
                            String key = String.valueOf(t.failureCause())
                                    + (withdrawn ? "  + S43" : "");
                            tally.merge(key, 1, Integer::sum);
                            sample.putIfAbsent(key, root.relativize(p).toString());
                        }
                    }
                } catch (Exception ignored) {
                    // Sonda se ptá na příčiny, ne na načítání.
                }
            }
        }
        tally.forEach((k, n) -> System.out.println(
                String.format("%5d  %-28s %s", n, k, sample.get(k))));
        System.out.println("---");
        System.out.println("razítek celkem=" + all + "  neobstálo=" + failed);
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
