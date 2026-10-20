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
 * Komu se změnila překážka tím, že se samostatné časové razítko přestalo považovat za neukotvené.
 *
 * <p>Staré pravidlo se od nového liší <b>jedinou</b> podmínkou: {@code preservationAddsSomething()}
 * se ptalo na best-signature-time, který samostatné razítko z povahy věci nemá, takže u něj vyšlo
 * vždy {@code false}. Sken proto nemusí pouštět obojí — stačí dopočítat tu jednu odchylku:
 * prvek typu {@code TimeStampToken} bez situací 33 a 45 a bez {@code preservationWorthwhile}
 * dřív spadl do poslední větve a dostal větu o chybějících revokačních informacích.
 *
 * <p>Ta věta byla u razítka, jehož certifikát je sám kotvou důvěry, tvrzením naopak: u kotvy se
 * revokace nezjišťuje, takže chybět nemůže. Sken ukazuje, kolika prvků se to týkalo a co jim
 * brána říká teď.
 *
 * <p><b>Hlásí i plané poplachy a je to v jeho povaze.</b> Dopočet té odchylky předpokládá, že se
 * brána k poslední větvi vůbec dostala — jenže vrací se i dřív, u prvku s porušeným obsahem
 * (situace 1), u neúspěšného ověření a u situace 20. Takový prvek dostane od staré i nové brány
 * touž odpověď, a přesto tu vyjde jako „změněný". Poznají se snadno: nová odpověď u nich není
 * {@code null}, nýbrž věta z některé dřívější větve. Na vzorku 211 dokumentů to byly 2 nálezy
 * z 13 — obojí {@code INDETERMINATE}, tedy návrat hned z druhé podmínky.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.StandaloneTimestampOfferScan &lt;seznam&gt;</pre>
 */
public final class StandaloneTimestampOfferScan {

    private static final String OLD = "chybí revokační informace, které už nelze získat";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: StandaloneTimestampOfferScan <seznam-cest.txt>");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]));
        TreeMap<String, Integer> newObstacles = new TreeMap<>();
        int files = 0, standalone = 0, changed = 0;

        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p.trim());
            if (!f.isFile()) continue;
            files++;
            if (files % 20 == 0) {
                System.out.printf("[%d] samostatných razítek %d, změněných %d%n",
                        files, standalone, changed);
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
                    if (!"TimeStampToken".equals(sig.getFormat())) continue;
                    standalone++;
                    var s = sig.getDetectedSituations();
                    boolean oldCovered = s.contains(33) || s.contains(45)
                            || sig.isPreservationWorthwhile();
                    if (oldCovered) continue;          // stará i nová brána říkají totéž
                    changed++;
                    String now = AdvisorView.continuityObstacle(sig, java.util.Set.of());
                    newObstacles.merge(String.valueOf(now), 1, Integer::sum);
                    System.out.printf("ZMĚNA  %-58s %s → %s%n",
                            f.getName().length() > 58 ? f.getName().substring(0, 58) : f.getName(),
                            "„" + OLD + "“", now == null ? "bez překážky" : "„" + now + "“");
                    System.out.flush();
                }
            } catch (Exception e) {
                System.out.println("CHYBA  " + f.getName() + " : " + e);
            }
        }
        System.out.println();
        System.out.printf("HOTOVO: souborů %d, samostatných razítek %d, změněných %d%n",
                files, standalone, changed);
        newObstacles.forEach((k, v) -> System.out.printf("   %-70s %d%n", k, v));
    }
}
