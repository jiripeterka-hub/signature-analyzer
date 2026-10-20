package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Horizon;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Co změní přenos horizontů po řadě razítek ({@link ContinuityElementBlocks#coveredBy}).
 *
 * <p>U každého prvku spočítá horizonty AnEP a okruhu CzechPOINTu tak, jak se počítaly dřív
 * (každý prvek sám za sebe), a tak, jak se počítají teď, a vypíše ty, které se liší.
 * Vznikla 25. 9. 2026 nad dokumentem se čtyřmi razítky, u kterého razítko č. 2 vycházelo jako
 * ověřitelné „do 4. 10. 2019 — již uplynulo“, ač ho kryla souvislá řada až do roku 2030.
 *
 * <p>Ověřuje se podle standardů, bez ústupků; horizonty jsou tedy ty, které záložka ukáže po
 * běžném ověření.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.TimestampCoverageScan &lt;složka&gt;…</pre>
 */
public final class TimestampCoverageScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json",
                    ".tst");

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setIgnoreSha1Deprecated(false);
        settings.setAcceptPdfNotEtsi(false);
        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        var mapper = new ReportMapper();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        ContinuityHorizon.Run run = new ContinuityHorizon.Run(true, List.of(), null);

        int files = 0, docsMulti = 0, elements = 0, changedAnep = 0, changedTol = 0,
                worse = 0, errors = 0;
        for (String a : args) {
            Path root = Path.of(a);
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(p -> EXTENSIONS.stream().anyMatch(e -> p.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(e)))
                        .sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                try {
                    var outcome = factory.validate(loader.load(p.toFile()), null, verifier, null);
                    ValidationResult result = mapper.map(p.getFileName().toString(), null,
                            outcome, settings);
                    List<SignatureSummary> sigs = result.getSignatures();
                    if (sigs.size() < 2) continue;
                    docsMulti++;
                    List<ContinuityElementBlocks.Element> before = new ArrayList<>();
                    for (SignatureSummary sig : sigs) {
                        before.add(ContinuityElementBlocks.element(sig,
                                AdvisorView.displayedIndication(sig), run, false, true, null));
                    }
                    List<ContinuityElementBlocks.Element> after = AdvisorView.coverTimestamps(
                            sigs, before, result.getDiagnosticData());
                    for (int i = 0; i < sigs.size(); i++) {
                        elements++;
                        var b = before.get(i);
                        var n = after.get(i);
                        boolean da = !b.anep().equals(n.anep());
                        boolean dt = !b.tolerant().equals(n.tolerant());
                        if (!da && !dt) continue;
                        if (da) changedAnep++;
                        if (dt) changedTol++;
                        if ((da && worse(b.anep(), n.anep())) || (dt && worse(b.tolerant(), n.tolerant()))) {
                            worse++;
                        }
                        System.out.println(root.relativize(p) + "\tprvek " + (i + 1)
                                + "\t" + sigs.get(i).getFormat()
                                + "\tAnEP " + fmt(b.anep()) + (da ? " -> " + fmt(n.anep()) : " (beze změny)")
                                + "\tCzP " + fmt(b.tolerant())
                                + (dt ? " -> " + fmt(n.tolerant()) : " (beze změny)"));
                    }
                } catch (Exception e) {
                    errors++;
                    System.err.println(p + "  !! " + e);
                }
            }
        }
        System.out.println();
        System.out.println("souborů: " + files + ", s víc prvky: " + docsMulti
                + ", prvků v nich: " + elements + ", změněn horizont AnEP: " + changedAnep
                + ", CzechPOINT: " + changedTol + ", zhoršení: " + worse + ", chyb: " + errors);
    }

    /** Horší horizont po přenosu — nemá nastat, přenos bere jen lepší. */
    private static boolean worse(Horizon before, Horizon after) {
        if (before.reachable() && !after.reachable()) return true;
        return before.kind() == ContinuityHorizon.Kind.UNTIL
                && after.kind() == ContinuityHorizon.Kind.UNTIL
                && after.when().isBefore(before.when());
    }

    private static String fmt(Horizon h) {
        return h.kind() + (h.when() != null ? " " + h.when().toString().substring(0, 10) : "")
                + "/" + h.limit();
    }
}
