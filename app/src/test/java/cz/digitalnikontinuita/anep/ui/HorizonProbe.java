package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Horizon;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Obě čtení horizontu pro jeden soubor, vedle sebe, i s lhůtou, kterou z nich blok „Do kdy je
 * třeba něco udělat?" poskládá.
 *
 * <p>Vzniklo nad přijatou zprávou z ISDS, kde nadpis bloku uváděl datum
 * z tolerantního čtení a věta pod ním zdůvodnění ze zdejšího. Rozdíl obou dat je přesně to,
 * co takový rozpor prozradí, a jinak než výpisem se k němu dostat nedá — blok se skládá
 * v {@code ContinuityElementBlocks} a do protokolu nejde.
 *
 * <p>V balíčku {@code ui}, protože {@code salvageableUntil} je balíčkově viditelná.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.HorizonProbe \
 *       -Dexec.args="D:\cesta\k\souboru.zfo"
 * </pre>
 */
public final class HorizonProbe {

    private HorizonProbe() { }

    /** Texty bloku, řádek po řádku — snímek by tu byl zbytečně drahý. */
    private static void printLabels(javafx.scene.Node n, String indent) {
        if (n instanceof javafx.scene.control.Labeled l && l.getText() != null
                && !l.getText().isBlank()) {
            System.out.println(indent + l.getText());
        }
        if (n instanceof javafx.scene.text.TextFlow tf) {
            StringBuilder sb = new StringBuilder();
            for (javafx.scene.Node c : tf.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
            }
            if (sb.length() > 0) System.out.println(indent + sb);
            return;
        }
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) printLabels(c, indent);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: HorizonProbe <file>");
            return;
        }
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        File f = new File(args[0]);
        DSSDocument doc = new DocumentLoader().load(f);
        ValidationResult result = new ReportMapper().map(f.getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings);

        ContinuityHorizon.Run run = new ContinuityHorizon.Run(
                settings.isTimestampStandardsMode(),
                ReportMapper.Relaxation.enabledIn(settings).stream()
                        .map(ReportMapper.Relaxation::settingName).toList(),
                null);

        // Který úkon předstírat: uchování (výchozí), prodloužení, přerazítkování, nebo „fix" =
        // jen zafixování, kdy žádný ze tří na prvek nedosáhne. Text bloku na tom závisí.
        String mode = args.length > 1 ? args[1] : "preserve";
        ContinuityElementBlocks.Gates gates = new ContinuityElementBlocks.Gates(
                "preserve".equals(mode), "prolong".equals(mode), "restamp".equals(mode), true);
        System.out.println("úkon: " + mode);

        // Konec platnosti certifikátu vybraného zdroje razítek. Bez něj sonda mlčí o tom, jestli
        // zásah vůbec něco přinese — a právě ta věta se s pobídkou ke spěchu potkává v jednom
        // odstavci. Zadává se v ISO („2032-05-02T08:33:06Z“), protože zjistit ho po síti by
        // znamenalo vydat si o razítko navíc.
        java.util.Date tsaUntil = args.length > 2 ? java.util.Date.from(Instant.parse(args[2]))
                                                  : null;
        System.out.println("zdroj razítek platí do: " + tsaUntil);

        // Toolkit jednou, před smyčkou: Platform.startup() volané podruhé skončí
        // IllegalStateException, takže uvnitř smyčky by sonda spadla na druhém podpisu.
        javafx.application.Platform.startup(() -> { });

        for (SignatureSummary sig : result.getSignatures()) {
            String verdict = AdvisorView.displayedIndication(sig);
            Horizon anep = ContinuityHorizon.anep(sig, verdict, run);
            Horizon tolerant = ContinuityHorizon.tolerant(sig, verdict);
            Instant deadline = ContinuityElementBlocks.salvageableUntil(anep, tolerant);
            System.out.println();
            System.out.println("prvek " + sig.getSignatureId() + "  " + verdict
                    + "  situace=" + sig.getDetectedSituations());
            System.out.println("   anep     : " + anep.kind() + "  " + anep.when()
                    + "  limit=" + anep.limit());
            System.out.println("   tolerant : " + tolerant.kind() + "  " + tolerant.when()
                    + "  limit=" + tolerant.limit());
            System.out.println("   lhůta    : " + deadline);
            System.out.println("   → lhůta pochází z: "
                    + (deadline == null ? "—"
                       : deadline.equals(tolerant.when()) && !deadline.equals(anep.when())
                            ? "TOLERANTNÍHO čtení" : "zdejšího čtení"));
            List<String> dl = List.of(
                    "augmentationDeadline=" + sig.getAugmentationDeadline(),
                    "unprovenRevocationDeadline=" + sig.getUnprovenRevocationDeadline());
            dl.forEach(x -> System.out.println("   " + x));

            // A teď věta, kterou z toho blok poskládá — kvůli ní to celé je.
            final Object lock = new Object();
            final boolean[] done = { false };
            javafx.application.Platform.runLater(() -> {
                try {
                    javafx.scene.layout.VBox block = ContinuityElementBlocks.build(
                            sig, verdict, run, false,
                            gates,
                            null, true,
                            ContinuityElementBlocks.Source.named("I.CA", tsaUntil), null);
                    System.out.println("   --- blok ---");
                    printLabels(block, "   ");
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                }
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            });
            synchronized (lock) {
                while (!done[0]) lock.wait();
            }
        }
        javafx.application.Platform.exit();
    }
}
