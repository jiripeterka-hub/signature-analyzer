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
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Asks what situation 43 still adds now that DSS honours sunset dates itself.
 *
 * <p>S43 was written as a stand-in: DSS, configured without a trust anchor validity predicate,
 * returned TOTAL_PASSED for a signature whose decisive time-stamp came from a withdrawn service,
 * and the application overrode it. With the predicate in place DSS runs the standard's own machinery
 * — Validation Time Sliding starting at the sunset date, with past validation on top — which can
 * legitimately still pass such a signature when a proof of existence predates the withdrawal. Where
 * it does, the override is stricter than the standard S43 cites, which is the opposite of its stated
 * intent.
 *
 * <p>For every signature this reports what DSS concluded and whether S43 fired, so the overlap can
 * be read off rather than guessed at.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class S43SunsetScan {

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        List<String> extensions = new ArrayList<>(
                List.of(".pdf", ".zfo", ".p7s", ".asice", ".asics", ".sce"));
        boolean verbose = false;
        int threads = 8;
        boolean noCache = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-v" -> verbose = true;
                case "-threads" -> threads = Integer.parseInt(args[++i]);
                case "-nocache" -> noCache = true;
                case "-ext" -> {
                    extensions.clear();
                    for (String e : args[++i].split(",")) {
                        String x = e.trim().toLowerCase(Locale.ROOT);
                        extensions.add(x.startsWith(".") ? x : "." + x);
                    }
                }
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: S43SunsetScan [-v] [-threads N] [-nocache] [-ext pdf,zfo] <folder>…");
            return;
        }

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        // The trust source is shared — its accessors are synchronized and nothing here writes to
        // it — while every thread gets its own factory, mapper and loader, none of which are
        // written for concurrent use. The work is network-bound anyway: a sequential run leaves
        // the processor at well under one percent, waiting on one CRL or OCSP round trip at a
        // time, so the threads spend their lives blocked rather than competing.
        CommonCertificateVerifier verifier = trustConfig.buildLotlCertificateVerifier();
        if (!noCache) cacheRevocationLookups(verifier);

        AtomicInteger files = new AtomicInteger();
        AtomicInteger signatures = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        int s43 = 0;
        Map<String, Integer> dssWhenS43 = new TreeMap<>();
        List<String> passedAnyway = new ArrayList<>();
        List<String> hits = new ArrayList<>();

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.toString().toLowerCase(Locale.ROOT);
                            return extensions.stream().anyMatch(n::endsWith);
                        })
                        .sorted().toList();
            }
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<List<String>>> futures = new ArrayList<>();
            for (Path p : candidates) {
                futures.add(pool.submit(scanOne(p, settings, verifier, signatures, failed, verbose)));
            }
            pool.shutdown();
            for (Future<List<String>> f : futures) {
                files.incrementAndGet();
                for (String line : f.get()) {
                    // "indication\tfilename" — assembled per file, tallied here on one thread.
                    int tab = line.indexOf('\t');
                    String dss = line.substring(0, tab);
                    String name = line.substring(tab + 1);
                    s43++;
                    dssWhenS43.merge(dss, 1, Integer::sum);
                    hits.add(name + "  " + dss);
                    if ("TOTAL_PASSED".equals(dss)) passedAnyway.add(name);
                }
            }
        }

        if (verbose) hits.forEach(h -> System.out.println("   S43: " + h));
        System.out.println("=".repeat(100));
        System.out.println("souborů: " + files.get() + ", podpisů: " + signatures.get()
                + ", nepodařilo se ověřit: " + failed.get());
        System.out.println("podpisů, na kterých S43 hlásí vyřazenou razítkovací službu: " + s43);
        System.out.println();
        System.out.println("co u nich říká DSS (už se sunsetem):");
        dssWhenS43.forEach((k, v) -> System.out.printf("   %-50s %d%n", k, v));
        if (!passedAnyway.isEmpty()) {
            System.out.println();
            System.out.println("DSS je se sunsetem přesto uznal — tady S43 přitvrzuje nad rámec normy:");
            passedAnyway.forEach(f -> System.out.println("   " + f));
        }
        System.out.println("=".repeat(100));
    }

    /**
     * Points revocation and issuer lookups at a disk cache shared by every thread.
     *
     * <p>A corpus is not a random sample of the world's certificates: the same few authorities sign
     * almost everything in it, so without a cache the same handful of CRLs — hundreds of kilobytes
     * each — are fetched once per document. The cache is keyed by URL, so it collapses that to one
     * fetch per authority for the whole run.
     *
     * <p>Sound for a scan, not for the application: a revocation list held for the length of a run
     * is exactly what a validator must not do when someone asks about one document. Hence a tool,
     * and hence {@code -nocache} to check a result against live fetches.
     */
    private static void cacheRevocationLookups(CommonCertificateVerifier verifier) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "anep-scan-revocation-cache");
        FileCacheDataLoader cached = new FileCacheDataLoader(new CommonsDataLoader());
        cached.setFileCacheDirectory(dir);
        cached.setCacheExpirationTime(6L * 60 * 60 * 1000);
        verifier.setCrlSource(new OnlineCRLSource(cached));
        verifier.setOcspSource(new OnlineOCSPSource(cached));
        verifier.setAIASource(new DefaultAIASource(cached));
        System.err.println("[scan] revokační data se cachují v " + dir);
    }

    /**
     * Validates one file and returns a {@code indication\tfilename} line per signature on which
     * situation 43 fired. Each call builds its own factory, mapper and loader, so nothing but the
     * trust source inside the verifier is shared across threads.
     */
    private static Callable<List<String>> scanOne(Path p, AppSettings settings,
                                                  CommonCertificateVerifier verifier,
                                                  AtomicInteger signatures, AtomicInteger failed,
                                                  boolean verbose) {
        return () -> {
            List<String> found = new ArrayList<>();
            try {
                DSSDocument doc = new DocumentLoader().load(p.toFile());
                ValidationOutcome outcome = new ValidatorFactory(settings)
                        .validate(doc, null, verifier, null);
                ValidationResult result = new ReportMapper()
                        .map(p.getFileName().toString(), null, outcome, settings);
                for (SignatureSummary sig : result.getSignatures()) {
                    signatures.incrementAndGet();
                    if (!sig.getDetectedSituations().contains(43)) continue;
                    String dss = sig.getIndication()
                            + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "");
                    found.add(dss + "\t" + p.getFileName());
                }
            } catch (Exception | StackOverflowError e) {
                failed.incrementAndGet();
                if (verbose) System.out.println("!! " + p.getFileName() + "  " + e);
            }
            return found;
        };
    }
}
