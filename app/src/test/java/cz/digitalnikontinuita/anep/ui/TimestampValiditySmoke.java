package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual check that the expert "Detailní rozbor" tab says the same thing about a time-stamp as
 * the rest of the application: prints every "Celková platnost" and "Platnost ověřována k" row it
 * builds, next to the conclusion DSS reached for that same token.
 *
 * <p>Written after the tab reported "Ano" for two time-stamps every other tab had refused. It
 * derived the row from diagnostic data alone — imprint intact, signature intact, chain trusted —
 * and none of those facts carries a sunset, an obsolete algorithm or a missing proof of existence.
 *
 * <p>Point it at a document with time-stamps from a withdrawn service. Add {@code -sha1} to
 * tolerate SHA-1, and set {@code APPDATA} to a directory with an empty {@code trusted-certs} to
 * take pinned roots out of the picture:
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.ui.TimestampValiditySmoke &lt;signed-file&gt; [-sha1]</pre>
 */
public final class TimestampValiditySmoke {

    private static File file;
    private static boolean tolerateSha1;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: TimestampValiditySmoke <signed-file> [-sha1]");
            System.exit(2);
        }
        file = new File(args[0]);
        for (String a : args) if ("-sha1".equals(a)) tolerateSha1 = true;
        Application.launch(Ui.class, args);
    }

    public static final class Ui extends Application {

        @Override
        public void start(Stage stage) throws Exception {
            AppSettings settings = AppSettings.load();
            settings.setIgnoreSha1Deprecated(tolerateSha1);
            var doc = new DocumentLoader().load(file);
            var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
            var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
            ValidationResult result = new ReportMapper().map(file.getName(), "PDF", outcome, settings,
                    java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());
            var simple = outcome.reports().getSimpleReport();

            DetailedReportView detailed = new DetailedReportView();
            detailed.setTimestampStandardsMode(settings.isTimestampStandardsMode());
            AdvisorView advisor = new AdvisorView();
            advisor.setTimestampStandardsMode(settings.isTimestampStandardsMode());
            ContinuityView continuity = new ContinuityView();
            advisor.setContinuityView(continuity);
            // Bez zdrojového souboru se brány úkonů nerozsvítí a blok „Co nabízí AnEP a do kdy
            // je třeba toho využít" se nepostaví — sonda pak mlčí o něčem, co v aplikaci vidět je.
            advisor.setSourceFile(file, null, null);
            advisor.setAppSettings(settings);
            advisor.setFixationService(new cz.digitalnikontinuita.anep.core.FixationService(
                    new java.util.concurrent.atomic.AtomicReference<>(verifier)));
            stage.setScene(new Scene(detailed.getRoot(), 900, 700));
            stage.show();
            detailed.show(result);
            advisor.show(result);

            Platform.runLater(() -> {
                System.out.println();
                System.out.println("ignoreSha1Deprecated=" + settings.isIgnoreSha1Deprecated());
                System.out.println("--- co o razítkách říká DSS ---");
                for (TimestampWrapper ts : outcome.reports().getDiagnosticData().getTimestampList()) {
                    System.out.printf("  %s  %s%s%n", ts.getProductionTime(),
                            simple.getIndication(ts.getId()),
                            simple.getSubIndication(ts.getId()) != null
                                    ? "/" + simple.getSubIndication(ts.getId()) : "");
                }
                for (var sig : result.getSignatures()) {
                    System.out.println("--- co o nich říká Analýza ---");
                    System.out.println("  verdikt " + sig.getIndication()
                            + "  situace " + sig.getDetectedSituations()
                            + "  vlastníKotva=" + sig.isTrustedViaCustomStore()
                            + " (" + sig.getCustomTrustAnchorName() + ")");
                    if (sig.getWithdrawnTsaDetail() != null) {
                        System.out.println("  S43/49: " + sig.getWithdrawnTsaDetail());
                    }
                    if (sig.getProofCarrierDetail() != null) {
                        System.out.println("  S50:    " + sig.getProofCarrierDetail());
                    }
                }
                List<String> advisorTexts = new ArrayList<>();
                collect(advisor.getRoot(), advisorTexts);
                System.out.println("--- Detailní rozbor: odznaky kotev ---");
                collect(detailed.getRoot(), new ArrayList<>());
                List<String> badgeTexts = new ArrayList<>();
                collect(detailed.getRoot(), badgeTexts);
                badgeTexts.stream().filter(x -> x.startsWith("kotva důvěry"))
                        .collect(java.util.stream.Collectors.groupingBy(x -> x,
                                java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                        .forEach((k, v) -> System.out.println("  " + v + "x  " + k));
                System.out.println("--- Analýza: řádky o vlastní kotvě ---");
                advisorTexts.stream()
                        .filter(x -> x.contains("úložiště") || x.contains("kotv")
                                || x.contains("ručně"))
                        .forEach(x -> System.out.println("  " + x));
                List<String> continuityTexts = new ArrayList<>();
                collect(continuity.getRoot(), continuityTexts);
                System.out.println("--- Digitální kontinuita: co by cizí validátor potřeboval ---");
                continuityTexts.stream()
                        .filter(x -> System.getProperty("probe.allcontinuity") != null
                                || x.contains("pravidlo") || x.contains("politik")
                                || x.contains("nemá kde vzít") || x.contains("ověřit nelze"))
                        .forEach(x -> System.out.println("  " + x));
                List<String> texts = new ArrayList<>();
                collect(detailed.getRoot(), texts);
                System.out.println("--- co o nich říká Detailní rozbor ---");
                for (int i = 0; i < texts.size(); i++) {
                    String t = texts.get(i);
                    if (t.equals("Celková platnost") || t.equals("Platnost ověřována k")) {
                        System.out.printf("  %-22s %s%n", t,
                                i + 1 < texts.size() ? texts.get(i + 1) : "?");
                    }
                }
                Platform.exit();
                System.exit(0);
            });
        }
    }

    /**
     * Every text node, collapsed blocks included — the point is what was built, not what shows.
     *
     * <p>TextFlow se sbírá jako celek. Bez toho sonda minula všechno, co staví {@code boldMarkup}
     * a {@code boldBullet} — tedy skoro celý blok „Průběh ověřování" — a chybějící řádek vypadal
     * jako nález o aplikaci.
     */
    private static void collect(Parent parent, List<String> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof javafx.scene.text.TextFlow tf) {
                // Text runs se slepí do jednoho řádku, ostatní děti (odznaky Label, odkazy)
                // se sbírají samostatně — jsou to vlastní údaje, ne pokračování věty.
                StringBuilder sb = new StringBuilder();
                for (Node run : tf.getChildrenUnmodifiable()) {
                    // Label je taky Parent, takže pořadí větví rozhoduje: nejdřív se z něj musí
                    // přečíst text, jinak se do něj sonda zanoří a v nezobrazené scéně nenajde nic.
                    if (run instanceof javafx.scene.text.Text t) sb.append(t.getText());
                    else if (run instanceof Label bl && bl.getText() != null) out.add(bl.getText());
                    else if (run instanceof Parent p) collect(p, out);
                }
                if (!sb.toString().isBlank()) out.add(sb.toString());
            } else if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
                out.add(l.getText());
            } else if (n instanceof TextArea t && t.getText() != null && !t.getText().isBlank()) {
                out.add(t.getText());
            } else if (n instanceof javafx.scene.control.ScrollPane sp
                    && sp.getContent() instanceof Parent p) {
                collect(p, out);
            } else if (n instanceof Parent p) {
                collect(p, out);
            }
        }
    }
}
