package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dopad přenosu důkazu existence z pozdějšího podpisu na odhad pro CzechPOINT — před a po.
 *
 * <p>Vznikla 11. 11. 2026: CzechPOINT přenáší důkaz existence z pozdějších podpisů a jejich
 * razítek na dřívější podpisy a pečeti (údaj uživatele) a odhad s tím do té doby nepočítal.
 * U každého prvku, který kryje pozdější podpis s razítkem, sonda spočítá odhad pro CzechPOINT
 * a nabídku „Kde je šance na úspěšné ověření“ dvakrát: s kryjícími razítky a bez nich (tak, jak
 * to vycházelo před změnou). Vypisuje jen prvky, kterých se přenos může týkat; jen soubory PDF,
 * jinde se přenos nepočítá.
 *
 * <pre>java -cp … CoveringProofScan &lt;výstup.tsv&gt; &lt;složka&gt;…</pre>
 */
public final class CoveringProofScan {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<Path> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            try (var walk = Files.walk(Path.of(args[i]))) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                                .endsWith(".pdf"))
                        .sorted().forEach(files::add);
            }
        }
        AppSettings settings = AppSettings.load();
        TrustConfig trust = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();
        ReportMapper mapper = new ReportMapper();
        Map<String, Integer> counts = new TreeMap<>();

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("soubor\tprvek\tformat\tverdikt\tcertDo\tvlastnichRazitek\tkryjicich"
                    + "\tpred\tpredDo\tpo\tpoDo\tnabidkaPred\tnabidkaPo");
            System.out.println("### souboru: " + files.size());
            int done = 0;
            for (Path p : files) {
                ValidationResult result;
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    var outcome = factory.validate(doc, null, verifier, null);
                    result = mapper.map(p.getFileName().toString(), null, outcome, settings);
                } catch (Exception | StackOverflowError e) {
                    counts.merge("soubor preskocen", 1, Integer::sum);
                    if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
                    continue;
                }
                counts.merge("soubor overen", 1, Integer::sum);
                for (SignatureSummary sig : result.getSignatures()) {
                    counts.merge("prvek", 1, Integer::sum);
                    List<TimestampEntry> covering = sig.getCoveringTimestamps();
                    if (covering.isEmpty()) continue;
                    counts.merge("prvek s kryjicim razitkem", 1, Integer::sum);
                    String verdict = AdvisorView.displayedIndication(sig);

                    ContinuityHorizon.Horizon after = ContinuityHorizon.tolerant(sig, verdict);
                    boolean offerAfter = AdvisorView.shouldOfferOtherValidators(sig);
                    sig.setCoveringTimestamps(List.of());
                    ContinuityHorizon.Horizon before = ContinuityHorizon.tolerant(sig, verdict);
                    boolean offerBefore = AdvisorView.shouldOfferOtherValidators(sig);
                    sig.setCoveringTimestamps(covering);

                    boolean horizonChanged = before.kind() != after.kind()
                            || !java.util.Objects.equals(before.when(), after.when());
                    counts.merge("odhad " + verdict + ": " + before.kind() + " -> " + after.kind()
                            + (horizonChanged ? " (zmena)" : " (stejne)"), 1, Integer::sum);
                    if (offerBefore != offerAfter) {
                        counts.merge("nabidka " + verdict + ": " + offerBefore + " -> "
                                + offerAfter, 1, Integer::sum);
                    }
                    if (horizonChanged || offerBefore != offerAfter) {
                        w.println(p + "\t" + sig.getSignatureId() + "\t" + sig.getFormat()
                                + "\t" + verdict + "\t" + day(sig.getCertNotAfter())
                                + "\t" + sig.getAllTimestamps().size() + "\t" + covering.size()
                                + "\t" + before.kind() + "\t" + day(before.when())
                                + "\t" + after.kind() + "\t" + day(after.when())
                                + "\t" + offerBefore + "\t" + offerAfter);
                    }
                }
                w.flush();
                if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
            }
            w.println();
            counts.forEach((k, v) -> w.println("#POCET\t" + k + "\t" + v));
        }
        counts.forEach((k, v) -> System.out.println("#POCET\t" + k + "\t" + v));
        System.out.println("### hotovo");
        // Výslovně: vlákna DSS a JavaFX jinak drží proces při ukončování.
        Runtime.getRuntime().halt(0);
    }

    private static String day(Instant t) {
        return t == null ? "-" : t.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
