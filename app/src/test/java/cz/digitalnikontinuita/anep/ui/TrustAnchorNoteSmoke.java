package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
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
 * Manual check that a document whose signing certificate is itself a trust anchor says so, in
 * both places a reader would look: the "Průběh ověřování" block of the Analýza tab and the expert
 * "Podrobný rozbor".
 *
 * <p>Written for a protocol sealed by I.CA's qualified validation service, whose sealing
 * certificate the Czech Trusted List carries as the service's own digital identity. DSS asks no
 * revocation question about a trust anchor, so every revocation list came back empty and both
 * screens simply left the sections out — indistinguishable from a revocation pass that never ran.
 *
 * <p>The entry point deliberately does not extend {@link Application}: with the JavaFX jars on a
 * plain {@code -cp} classpath rather than the module path, launching from an {@code Application}
 * subclass fails with "JavaFX runtime components are missing".
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.ui.TrustAnchorNoteSmoke &lt;signed-file&gt;</pre>
 */
public final class TrustAnchorNoteSmoke {

    private static File file;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: TrustAnchorNoteSmoke <signed-file>");
            System.exit(2);
        }
        file = new File(args[0]);
        Application.launch(Ui.class, args);
    }

    public static final class Ui extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        AppSettings settings = AppSettings.load();
        var doc = new DocumentLoader().load(file);
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        ValidationResult result = new ReportMapper().map(file.getName(), "PDF", outcome, settings,
                java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

        AdvisorView advisor = new AdvisorView();
        DetailedReportView detailed = new DetailedReportView();
        stage.setScene(new Scene(advisor.getRoot(), 900, 700));
        stage.show();
        advisor.show(result);
        detailed.show(result);

        Platform.runLater(() -> {
            List<String> advisorTexts = new ArrayList<>();
            collect(advisor.getRoot(), advisorTexts);
            List<String> detailedTexts = new ArrayList<>();
            collect(detailed.getRoot(), detailedTexts);

            System.out.println("--- Analýza: řádky o nezjišťované revokaci ---");
            advisorTexts.stream().filter(t -> t.contains("nezjišť") || t.contains("kotvou důvěry"))
                    .forEach(t -> System.out.println("  " + t));
            System.out.println("--- Podrobný rozbor: totéž ---");
            detailedTexts.stream().filter(t -> t.contains("nezjišť") || t.contains("kotvou důvěry"))
                    .forEach(t -> System.out.println("  " + t));
            System.out.println("--- Podrobný rozbor: název služby ---");
            detailedTexts.stream().filter(t -> t.contains("QVerify") || t.contains("Název služby"))
                    .forEach(t -> System.out.println("  " + t));

            Platform.exit();
            System.exit(0);
        });
    }
    }

    /** Every text node, collapsed blocks included — the point is what was built, not what shows. */
    private static void collect(Parent parent, List<String> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
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
