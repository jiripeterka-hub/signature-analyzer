package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.time.Duration;
import java.time.Instant;

/**
 * Headless startup/validation profiler: prints how long each phase of a cold start costs and
 * how much of the first validation is one-off warm-up (class loading, JAXB contexts, JIT)
 * rather than real work. Run the same document three times — the delta between run 1 and
 * runs 2/3 is the warm-up cost a background pre-warm could hide.
 *
 * <pre>
 *   java -cp "app/target/test-classes;app/target/classes;&lt;cp&gt;" \
 *        cz.digitalnikontinuita.anep.tools.StartupBench D:\some-signed.pdf
 * </pre>
 */
public final class StartupBench {

    private static final Instant PROCESS_START = ProcessHandle.current().info().startInstant()
            .orElseGet(Instant::now);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: StartupBench <signed-file> [runs]");
            System.exit(2);
        }
        File file = new File(args[0]);
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        boolean warm = java.util.Arrays.asList(args).contains("-warm");

        mark("JVM up, main() entered");

        if (java.util.Arrays.asList(args).contains("-dump")) {
            Thread main = Thread.currentThread();
            Thread sampler = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(400); } catch (InterruptedException e) { return; }
                    StackTraceElement[] st = main.getStackTrace();
                    StringBuilder sb = new StringBuilder("[stack] " + main.getState() + "\n");
                    for (int i = 0; i < Math.min(12, st.length); i++) sb.append("    ").append(st[i]).append('\n');
                    System.err.print(sb);
                }
            }, "stack-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }

        AppSettings settings = AppSettings.load();
        mark("AppSettings.load()");

        TrustConfig trustConfig = new TrustConfig(settings);
        DocumentLoader documentLoader = new DocumentLoader();
        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        ReportMapper reportMapper = new ReportMapper();
        mark("core objects constructed");

        if (warm) {
            // Mirrors the app: warm-up runs in the background while the trust lists load.
            cz.digitalnikontinuita.anep.core.WarmupService.start(settings, Thread.currentThread().getContextClassLoader());
        }

        CommonCertificateVerifier verifier = trustConfig.buildLotlCertificateVerifier();
        mark("buildLotlCertificateVerifier()");
        if (warm) {
            Thread.sleep(1500); // let the background warm-up finish, as it would while the user picks a file
            mark("warm-up window elapsed");
        }

        for (int i = 1; i <= runs; i++) {
            long t0 = System.currentTimeMillis();
            DSSDocument doc = documentLoader.load(file);
            long tLoad = System.currentTimeMillis();
            ValidationOutcome outcome = validatorFactory.validate(doc, null, verifier, null);
            long tValidate = System.currentTimeMillis();
            reportMapper.map(file.getName(), doc.getClass().getSimpleName(),
                    outcome, settings, java.util.Map.of(), java.util.List.of(), false,
                    java.util.Map.of());
            long tMap = System.currentTimeMillis();
            System.err.printf("[bench] run %d: load=%d ms  validate=%d ms  map=%d ms  total=%d ms%n",
                    i, tLoad - t0, tValidate - tLoad, tMap - tValidate, tMap - t0);
        }
        mark("done");
        System.exit(0);
    }

    private static void mark(String label) {
        System.err.printf("[bench] %6d ms  %s%n",
                Duration.between(PROCESS_START, Instant.now()).toMillis(), label);
    }
}
