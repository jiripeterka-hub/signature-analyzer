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
 * Čím se v protokolu DSS vysvětlují prvky, které na kartě nemají žádný důvod.
 *
 * <p>Podklad pro rozhodnutí, jestli se dá důvod sestavit z protokolu: sbírá u každého takového
 * prvku <b>dílčí indikaci</b> a <b>klíče hlášení</b>, která k němu DSS vydalo. Z čeho není co
 * postavit, to se pozná tak, že se ve sbírce nevyskytne.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.ReasonlessCauseScan &lt;seznam-cest.txt&gt;</pre>
 */
public final class ReasonlessCauseScan {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ReasonlessCauseScan <seznam-cest.txt>");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]));
        TreeMap<String, Integer> bySub = new TreeMap<>();
        TreeMap<String, Integer> byKeys = new TreeMap<>();
        int files = 0, reasonless = 0;

        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p.trim());
            if (!f.isFile()) continue;
            files++;
            if (files % 25 == 0) {
                System.out.printf("[%d] bez důvodu %d%n", files, reasonless);
                System.out.flush();
            }
            try {
                AppSettings settings = AppSettings.load();
                var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
                var outcome = new ValidatorFactory(settings)
                        .validate(new DocumentLoader().load(f), null, verifier, null);
                var simple = outcome.reports().getSimpleReport();
                var result = new ReportMapper().map(f.getName(), "x", outcome, settings,
                        java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

                for (SignatureSummary sig : result.getSignatures()) {
                    if ("TOTAL_PASSED".equals(AdvisorView.displayedIndication(sig))) continue;
                    if (sig.getDetectedReasons().stream()
                            .anyMatch(ResultView.REASONS::containsKey)) continue;
                    reasonless++;
                    String sub = sig.getIndication() + "/" + sig.getSubIndication();
                    bySub.merge(sub, 1, Integer::sum);

                    // Klíče hlášení k tomu prvku — u samostatného razítka je jeho id v seznamu
                    // razítek, u podpisu v seznamu podpisů.
                    StringBuilder keys = new StringBuilder();
                    try {
                        simple.getAdESValidationErrors(sig.getSignatureId())
                                .forEach(m -> keys.append(m.getKey()).append(' '));
                        simple.getQualificationErrors(sig.getSignatureId())
                                .forEach(m -> keys.append(m.getKey()).append(' '));
                    } catch (Exception ignored) {
                        keys.append("(klíče nedostupné)");
                    }
                    byKeys.merge(keys.toString().trim(), 1, Integer::sum);
                    System.out.printf("BEZ DŮVODU  %-46s %-40s %s | situace=%s | %s%n",
                            f.getName().length() > 46 ? f.getName().substring(0, 46) : f.getName(),
                            sub, sig.getFormat(), sig.getDetectedSituations(), keys.toString().trim());
                    System.out.flush();
                }
            } catch (Exception e) {
                System.out.println("CHYBA  " + f.getName() + " : " + e);
            }
        }
        System.out.println();
        System.out.printf("HOTOVO: souborů %d, prvků bez důvodu %d%n", files, reasonless);
        System.out.println("-- podle dílčí indikace:");
        bySub.forEach((k, v) -> System.out.printf("   %-44s %d%n", k, v));
        System.out.println("-- podle klíčů hlášení:");
        byKeys.forEach((k, v) -> System.out.printf("   %-90s %d%n", k, v));
    }
}
