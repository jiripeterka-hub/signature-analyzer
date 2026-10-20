package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

/**
 * Prvky, jejichž ověření nedopadlo kladně a <b>nenese to žádný důvod</b> — tedy karty, na kterých
 * blok „Zjištěné důvody a doporučení" nemá co vysázet.
 *
 * <p>Do 14. 9. 2026 takový prvek dostal hlavičku „Aplikace detekovala následující důvod(y)
 * neúspěšného ověření:" a pod ní prázdno, protože se podmínka ptala na <b>situace</b>, kdežto
 * bloky staví <b>důvody</b> — a situací bez důvodu je dvacet ze čtyřiceti osmi. Sken měří, jak
 * často k tomu dochází a jaké situace to jsou; ty se hodí jako seznam kandidátů na vlastní důvod.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.ReasonlessCardScan &lt;seznam-cest.txt&gt;</pre>
 */
public final class ReasonlessCardScan {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ReasonlessCardScan <seznam-cest.txt>");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]));
        TreeMap<String, Integer> bySituations = new TreeMap<>();
        int files = 0, elements = 0, notPassed = 0, reasonless = 0;

        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p.trim());
            if (!f.isFile()) continue;
            files++;
            if (files % 25 == 0) {
                System.out.printf("[%d] prvků %d, nekladných %d, bez důvodu %d%n",
                        files, elements, notPassed, reasonless);
                System.out.flush();
            }
            try {
                AppSettings settings = AppSettings.load();
                var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
                var outcome = new ValidatorFactory(settings)
                        .validate(new DocumentLoader().load(f), null, verifier, null);
                var result = new ReportMapper().map(f.getName(), "x", outcome, settings,
                        java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

                for (SignatureSummary sig : result.getSignatures()) {
                    elements++;
                    if ("TOTAL_PASSED".equals(AdvisorView.displayedIndication(sig))) continue;
                    notPassed++;
                    boolean any = sig.getDetectedReasons().stream()
                            .anyMatch(ResultView.REASONS::containsKey);
                    if (any) continue;
                    reasonless++;
                    bySituations.merge(sig.getDetectedSituations().toString(), 1, Integer::sum);
                    System.out.printf("BEZ DŮVODU  %-52s %-18s situace=%s%n",
                            f.getName().length() > 52 ? f.getName().substring(0, 52) : f.getName(),
                            sig.getIndication() + "/" + sig.getSubIndication(),
                            sig.getDetectedSituations());
                    System.out.flush();
                }
            } catch (Exception e) {
                System.out.println("CHYBA  " + f.getName() + " : " + e);
            }
        }
        System.out.println();
        System.out.printf("HOTOVO: souborů %d, prvků %d, nekladných %d, z toho bez důvodu %d%n",
                files, elements, notPassed, reasonless);
        bySituations.forEach((k, v) -> System.out.printf("   situace %-28s %d%n", k, v));
    }
}
