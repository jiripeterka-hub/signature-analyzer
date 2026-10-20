package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationCourse;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jak často je revokační údaj, o který se opřel závěr, <b>starší než podpis sám</b>.
 *
 * <p>Otázka je z rozboru podvrhu {@code LT po revokaci later LTA timestamped.pdf}: certifikát byl
 * odvolán 30. 7. 2018, podepsáno bylo týž den v 11:03 a v dokumentu je podstrčena OCSP odpověď
 * z 29. 7. 11:43 se stavem GOOD. Odpověď o den starší než podpis nevypovídá o stavu certifikátu
 * v okamžiku podepsání vůbec nic — a přesto na ní stojí kladný verdikt, jakmile je zapnut ústupek
 * k expirovanému vydavateli. Kontrola čerstvosti v politice DSS to nezachytí (změřeno: i s
 * {@code RevocationFreshness Level="FAIL"} vyjde TOTAL_PASSED, protože o verdiktu rozhoduje
 * {@code RevocationIssuerNotExpired} na cestě past signature validation).
 *
 * <p>Jestli se z toho dá udělat varování, rozhoduje četnost: signál, který svítí u poloviny
 * sbírky, není signál. Sonda proto projde korpus a spočítá, u kolika podpisů je
 * {@code signingCertRevocation().issued()} před {@code getBestSignatureTime()} a o kolik.
 *
 * <p>Bez JavaFX — na tuhle otázku není potřeba stavět pohled, takže běh je o poznání rychlejší
 * než {@code ContinuityOfferScan}.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.StaleRevocationScan \
 *       -Dexec.args="-all -out D:\out\stale.txt D:\tmp"
 * </pre>
 */
public final class StaleRevocationScan {

    private StaleRevocationScan() { }

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        Path out = null;
        boolean all = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-all" -> all = true;
                case "-out" -> out = Path.of(args[++i]);
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty() || out == null) {
            System.out.println("usage: StaleRevocationScan [-all] -out <file> <folder>...");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(all);
        settings.setAcceptExpiredRevocationSources(all);
        settings.setIgnoreSha1Deprecated(all);
        settings.setAcceptPdfNotEtsi(all);

        CommonCertificateVerifier verifier;
        try {
            verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
            System.out.println("Duveryhodne seznamy: nacteny");
        } catch (Exception e) {
            verifier = new TrustConfig(settings).buildOfflineCertificateVerifier();
            System.out.println("Duveryhodne seznamy: NEDOSTUPNE");
        }
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();
        ReportMapper mapper = new ReportMapper();

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().forEach(files::add);
            }
        }
        System.out.println("### souboru: " + files.size());

        Files.createDirectories(out.toAbsolutePath().getParent());
        int sigs = 0, withBoth = 0, stale = 0, staleOverHour = 0, staleOverDay = 0, passed = 0,
            stalePassed = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# ustupky vsechny=" + all);
            int done = 0;
            for (Path p : files) {
                ValidationResult result;
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    result = mapper.map(p.getFileName().toString(), null,
                            factory.validate(doc, null, verifier, null), settings);
                } catch (Exception | StackOverflowError e) {
                    w.println("SKIP\t" + p + "\t" + e);
                    continue;
                }
                for (SignatureSummary sig : result.getSignatures()) {
                    sigs++;
                    boolean ok = "TOTAL_PASSED".equals(sig.getIndication());
                    if (ok) passed++;
                    ValidationCourse vc = sig.getValidationCourse();
                    ValidationCourse.RevocationFact f =
                            vc != null ? vc.signingCertRevocation() : null;
                    Instant bst = sig.getBestSignatureTime();
                    if (f == null || f.issued() == null || bst == null) continue;
                    withBoth++;
                    Duration d = Duration.between(f.issued(), bst);   // >0 => údaj starší než podpis
                    boolean isStale = d.toMillis() > 0;
                    if (isStale) {
                        stale++;
                        if (ok) stalePassed++;
                        if (d.toHours() >= 1) staleOverHour++;
                        if (d.toDays() >= 1) staleOverDay++;
                        w.println((ok ? "PASSED " : "jiny   ") + "\tstarsi o "
                                + fmt(d) + "\t" + f.origin()
                                + (f.revoked() ? " REVOKED" : "")
                                + (f.relaxedRule() != null ? " [" + f.relaxedRule() + "]" : "")
                                + "\tudaj=" + f.issued() + "\tBST=" + bst
                                + "\t" + p);
                    }
                }
                if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
                w.flush();
            }
            String sum = "podpisu=" + sigs + " (TOTAL_PASSED=" + passed + ")"
                    + ", s udajem i BST=" + withBoth
                    + ", udaj starsi nez podpis=" + stale
                    + " (z toho TOTAL_PASSED=" + stalePassed + ")"
                    + ", starsi o >=1 h=" + staleOverHour
                    + ", >=1 den=" + staleOverDay;
            w.println();
            w.println("# " + sum);
            System.out.println("### " + sum);
        }
    }

    private static String fmt(Duration d) {
        long days = d.toDays();
        if (days > 0) return days + " d";
        long h = d.toHours();
        if (h > 0) return h + " h";
        long m = d.toMinutes();
        if (m > 0) return m + " min";
        return d.toSeconds() + " s";
    }
}
