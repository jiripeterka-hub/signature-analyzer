package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
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
 * Manual check that the "Průběh ověřování" block really renders in the Analýza tab: validates a
 * document, builds an {@link AdvisorView} from the result, and prints every text node from that
 * block onwards, in the order the reader sees them.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.AdvisorCourseSmoke &lt;signed-file&gt;</pre>
 */
public final class AdvisorCourseSmoke extends Application {

    private static File file;
    private static boolean tolerant;
    private static boolean strictTimestamps;
    private static boolean customTime;
    private static File evidenceRecord;

    public static void main(String[] args) {
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-t".equals(a)) tolerant = true;
            else if ("-s".equals(a)) strictTimestamps = true;
            else if ("-c".equals(a)) customTime = true;
            else if ("-e".equals(a)) evidenceRecord = new File(args[++i]);
            else rest.add(a);
        }
        if (rest.isEmpty()) {
            System.err.println("usage: AdvisorCourseSmoke [-t] [-s] [-c] [-e <evidence-record>] <signed-file>");
            System.exit(2);
        }
        file = new File(rest.get(0));
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        AppSettings settings = AppSettings.load();
        if (tolerant) {
            // Táž čtveřice, kterou překlápí tlačítko „ověřit tolerantně" v MainView. Bez ní se
            // sonda k odrážkám o ústupcích v bloku „Způsob ověření" vůbec nedostane: nastavení
            // se schválně nepamatuje mezi spuštěními, takže je nelze podstrčit souborem.
            settings.setAcceptNewerOcsp(true);
            settings.setAcceptExpiredRevocationSources(true);
            settings.setIgnoreSha1Deprecated(true);
            settings.setAcceptPdfNotEtsi(true);
            System.out.println("[sonda] tolerantní režim zapnut");
        }
        if (strictTimestamps) {
            settings.setTimestampStandardsMode(false);
            System.out.println("[sonda] přísný režim časových razítek zapnut");
        }
        if (customTime) {
            settings.setUseCustomValidationTime(true);
            settings.setCustomValidationTime(java.time.LocalDateTime.now().minusYears(2));
            System.out.println("[sonda] zadaný čas ověření: "
                    + settings.getCustomValidationTime());
        }
        var doc = new DocumentLoader().load(file);
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = evidenceRecord == null
                ? new ValidatorFactory(settings).validate(doc, null, verifier, null)
                : new ValidatorFactory(settings).validate(doc, java.util.List.of(), verifier, null,
                        java.util.List.of(new eu.europa.esig.dss.model.FileDocument(evidenceRecord)));
        // Osiřelé podpisy stejnou cestou jako MainView (adbe.x509.rsa_sha1 ověřuje AnEP sám).
        var orphans = cz.digitalnikontinuita.anep.core.Pkcs1SignatureValidator.verifyAll(
                cz.digitalnikontinuita.anep.core.PdfOrphanSignatureDetector.detect(
                        cz.digitalnikontinuita.anep.core.PdfSource.of(file, doc), null),
                verifier, settings, outcome.reports().getDiagnosticData(),
                outcome.reports().getSimpleReport());
        ValidationResult result = new ReportMapper().map(file.getName(), "x", outcome, settings,
                java.util.Map.of(), orphans, false, java.util.Map.of());

        AdvisorView view = new AdvisorView();
        // Bez nastavení nemá blok „Způsob ověření“ jak poznat zapnuté tolerance ani přísný
        // režim razítek, a vypsal by se v podobě, kterou uživatel na obrazovce nikdy neuvidí.
        view.setAppSettings(settings);
        // Bez něj se část „Doporučení" vůbec nepostaví — tlačítko tolerantního ověření na tom
        // zpětném volání visí a v aplikaci ho dodává MainView.
        view.setLenientVerifyCallback(() -> System.out.println("[sonda] tolerantní ověření"));
        view.setTimestampStandardsMode(settings.isTimestampStandardsMode());
        view.setCustomValidationTime(settings.isUseCustomValidationTime()
                ? settings.getCustomValidationTime() : null);
        stage.setScene(new Scene(view.getRoot(), 900, 700));
        stage.show();
        view.show(result);

        Platform.runLater(() -> {
            List<String> collapsed = new ArrayList<>();
            collect(view.getRoot(), collapsed);
            System.out.println("--- karta ve výchozím stavu (viditelné texty) ---");
            collapsed.forEach(t -> System.out.println("  " + t));

            // Open every collapsed block, the way the reader would.
            List<javafx.scene.Node> headers = new ArrayList<>();
            collectBlockHeaders(view.getRoot(), headers);
            System.out.println("--- rozbalovacích bloků: " + headers.size() + " ---");
            headers.forEach(h -> h.getOnMouseClicked().handle(
                    new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                            0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, true, false, false, null)));

            List<String> expanded = new ArrayList<>();
            collect(view.getRoot(), expanded);
            System.out.println("--- po rozkliknutí: " + expanded.size()
                    + " textů (před: " + collapsed.size() + ") ---");
            for (String t : expanded) {
                if (!collapsed.contains(t)) System.out.println("  + " + t);
            }
            boolean ok = !headers.isEmpty() && expanded.size() > collapsed.size();
            System.out.println(ok ? "BLOKY OK" : "BLOKY CHYBA");
            Platform.exit();
            System.exit(ok ? 0 : 1);
        });
    }

    /**
     * The clickable headers of collapsible blocks: an HBox with a click handler and an arrow.
     *
     * <p><b>Šipka je tvar, ne znak.</b> Poznávalo se to podle {@code Label}u se znakem
     * „▸“/„▾“, jenže od 9. 9. 2026 kreslí {@code collapsibleBlock} trojúhelník jako
     * {@code Polygon} ve {@code StackPane}. Od té chvíle sonda nenašla ani jeden blok
     * a nad kartou, která je měla všechny, hlásila BLOKY CHYBA — totéž, co potkalo
     * {@code ContinuityConsistencyScan}.
     */
    private static void collectBlockHeaders(Parent parent, List<Node> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof javafx.scene.layout.HBox hb && hb.getOnMouseClicked() != null
                    && !hb.getChildren().isEmpty()
                    && hb.getChildren().get(0) instanceof javafx.scene.layout.StackPane arrow
                    && arrow.getChildren().size() == 1
                    && arrow.getChildren().get(0) instanceof javafx.scene.shape.Polygon) {
                out.add(n);
                continue;
            }
            if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() instanceof Parent p) {
                collectBlockHeaders(p, out);
            } else if (n instanceof Parent p) {
                collectBlockHeaders(p, out);
            }
        }
    }

    private static void collect(Parent parent, List<String> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (!n.isVisible()) continue; // collapsed content must not count as shown
            if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
                out.add(l.getText());
            } else if (n instanceof javafx.scene.text.TextFlow tf) {
                StringBuilder sb = new StringBuilder();
                for (Node run : tf.getChildrenUnmodifiable()) {
                    if (run instanceof javafx.scene.text.Text tx) sb.append(tx.getText());
                    else if (run instanceof javafx.scene.control.Hyperlink h) sb.append(h.getText());
                }
                if (!sb.toString().isBlank()) out.add(sb.toString());
            } else if (n instanceof javafx.scene.control.Button b && b.getText() != null
                    && !b.getText().isBlank()) {
                out.add("[tlačítko] " + b.getText());
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
