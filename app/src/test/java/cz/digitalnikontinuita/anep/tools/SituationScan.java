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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Headless batch scan: validates every signed file under the given folders and prints the DSS
 * indication together with the situations AnEP derived from it.
 *
 * <p>Written to answer questions of the form "does situation X still fire anywhere?" — running the
 * real pipeline over a corpus is the only way to tell, since the situations depend on live
 * revocation data and on the Trusted List state. Prints one line per signature plus a tally, and
 * lists the files behind each situation given on the command line via {@code -watch}.
 *
 * <pre>
 *   mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.SituationScan \
 *       -Dexec.args="-watch 28,29 \"D:\\path\\to\\corpus\""
 * </pre>
 */
public final class SituationScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    /** What the walk accepts — {@link #EXTENSIONS} unless {@code -ext} narrows it. */
    private static List<String> extensions = EXTENSIONS;

    public static void main(String[] args) throws Exception {
        List<Integer> watched = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        boolean newerOcsp = false;
        boolean expiredRevSources = false;
        boolean tolerateSha1 = false;
        boolean strict = false;
        // Situace samy o sobě neřeknou, co z nich čtenář uvidí — mezi nimi a kartou stojí ještě
        // odvození důvodů a jejich potlačovací tabulka. Bez tohoto přepínače nešlo na sbírce
        // změřit, vedle čeho nový důvod přistane a co komu bere.
        boolean withReasons = false;
        boolean withDeadlines = false;
        // Razítka podle řádné doby platnosti jejich certifikátů, ne podle standardů. Jediný režim,
        // ve kterém se vůbec ukazuje důvod 5 — bez toho přepínače se na sbírce nedal změřit.
        boolean timestampsByCert = false;
        // Ukáže, u kterých podpisů by Analýza nabídl blok "Kde je šance na úspěšné
        // ověření". Predikát je čistá funkce nálezu, takže ho lze na sbírce změřit,
        // aniž by se stavěly karty.
        boolean withOtherVal = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-watch" -> {
                    if (i + 1 < args.length) {
                        for (String s : args[++i].split(",")) watched.add(Integer.parseInt(s.trim()));
                    }
                }
                // The two lenient settings, so a run can reproduce what the user sees after
                // ticking them — situations depend on them and the saved settings are strict.
                case "-newerOcsp" -> newerOcsp = true;
                case "-expiredRev" -> expiredRevSources = true;
                case "-sha1" -> tolerateSha1 = true;
                // Forces both off regardless of what is saved — the app may be running and
                // rewriting settings.properties while a scan is in flight.
                case "-strict" -> strict = true;
                case "-reasons" -> withReasons = true;
                // Lhůta pro uchování je model, ne text — a mění se s pravidlem o pokrytí
                // revokačními údaji, které situace ani důvody neukáží.
                case "-lhuty" -> withDeadlines = true;
                case "-tscert" -> timestampsByCert = true;
                case "-otherval" -> withOtherVal = true;
                // Narrows the walk to the given extensions, so a change that can only affect one
                // family of formats can be measured against a before/after run in minutes instead
                // of hours: "-ext asice,asics,sce,scs" for the ASiC containers.
                case "-ext" -> {
                    if (i + 1 < args.length) {
                        extensions = new ArrayList<>();
                        for (String s : args[++i].split(",")) {
                            String e = s.trim().toLowerCase(Locale.ROOT);
                            extensions.add(e.startsWith(".") ? e : "." + e);
                        }
                    }
                }
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: SituationScan [-watch 28,29] [-reasons] [-lhuty]"
                    + " [-tscert] [-otherval] <folder> [<folder>...]");
            return;
        }

        AppSettings settings = AppSettings.load();
        if (strict) {
            settings.setAcceptNewerOcsp(false);
            settings.setAcceptExpiredRevocationSources(false);
            // PDF-NOT-ETSI too, or a scan silently depends on whatever is saved: with it on,
            // situation 17 never fires and S34 never fires either, and two runs of the same
            // corpus stop being comparable.
            settings.setAcceptPdfNotEtsi(false);
        }
        if (newerOcsp) settings.setAcceptNewerOcsp(true);
        if (expiredRevSources) settings.setAcceptExpiredRevocationSources(true);
        if (tolerateSha1) settings.setIgnoreSha1Deprecated(true);
        if (timestampsByCert) settings.setTimestampStandardsMode(false);
        System.out.println("acceptNewerOcsp=" + settings.isAcceptNewerOcsp()
                + ", acceptExpiredRevocationSources=" + settings.isAcceptExpiredRevocationSources()
                + ", acceptPdfNotEtsi=" + settings.isAcceptPdfNotEtsi()
                + ", timestampStandardsMode=" + settings.isTimestampStandardsMode());
        TrustConfig trustConfig = new TrustConfig(settings);
        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        ReportMapper reportMapper = new ReportMapper();
        DocumentLoader documentLoader = new DocumentLoader();

        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Trusted Lists: loaded");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Trusted Lists: UNAVAILABLE (" + e + ") — running offline,"
                    + " qualification-dependent situations will be unreliable");
        }

        Map<Integer, List<String>> byS = new TreeMap<>();
        int files = 0, sigs = 0, failed = 0;

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(SituationScan::isCandidate)
                        .sorted()
                        .toList();
            }
            for (Path p : candidates) {
                files++;
                try {
                    DSSDocument doc = documentLoader.load(p.toFile());
                    ValidationOutcome outcome = validatorFactory.validate(doc, null, verifier, null);
                    ValidationResult result = reportMapper.map(
                            p.getFileName().toString(), null, outcome, settings);
                    for (SignatureSummary sig : result.getSignatures()) {
                        sigs++;
                        String line = rel(root, p) + "  [" + sig.getIndication()
                                + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                                + "]  " + sig.getFormat() + "  situace=" + sig.getDetectedSituations()
                                + (withReasons ? "  důvody=" + sig.getDetectedReasons() : "")
                                + (withDeadlines ? "  lhůta="
                                        + (sig.getAugmentationDeadline() == null ? "—"
                                                : sig.getAugmentationDeadline().when())
                                        + "  nedoloženo="
                                        + (sig.getUnprovenRevocationDeadline() == null ? "—"
                                                : sig.getUnprovenRevocationDeadline().when())
                                        : "")
                                + (withOtherVal ? "  jinoved="
                                        + (cz.digitalnikontinuita.anep.ui.AdvisorView
                                                .shouldOfferOtherValidators(sig) ? "ANO" : "NE")
                                        + "  sunset="
                                        + (cz.digitalnikontinuita.anep.ui.AdvisorView
                                                .shouldWarnSunsetOnly(sig,
                                                        cz.digitalnikontinuita.anep.ui.AdvisorView
                                                                .displayedIndication(sig))
                                                ? "ANO" : "NE") : "")
                                + (sig.isTrustedViaCustomStore() ? "  [vlastní kotva důvěry]" : "");
                        System.out.println(line);
                        for (Integer s : sig.getDetectedSituations()) {
                            byS.computeIfAbsent(s, k -> new ArrayList<>()).add(rel(root, p));
                        }
                    }
                } catch (Exception | StackOverflowError e) {
                    failed++;
                    System.out.println(rel(root, p) + "  !! " + e);
                }
            }
        }

        System.out.println();
        System.out.println("Souborů: " + files + ", podpisů: " + sigs + ", chyb: " + failed);
        System.out.println("Četnost situací:");
        byS.forEach((s, list) -> System.out.println("  S" + s + ": " + list.size()));

        for (Integer w : watched) {
            List<String> hits = byS.getOrDefault(w, List.of());
            System.out.println();
            System.out.println("S" + w + " — " + hits.size() + " výskytů");
            hits.stream().distinct().forEach(f -> System.out.println("    " + f));
        }
    }

    private static boolean isCandidate(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(name::endsWith);
    }

    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString();
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    private SituationScan() { }
}
