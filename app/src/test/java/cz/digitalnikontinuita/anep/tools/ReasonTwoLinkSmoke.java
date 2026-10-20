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
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Odkaz „Podrobněji …“ v radě u důvodu 2 — a to stiskem, ne pohledem: sonda ho najde v odstavci
 * s radou, stiskne jej a vypíše, co se objevilo v okně, které se otevřelo. Nakreslený odkaz ještě
 * neznamená, že za ním něco je.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.ReasonTwoLinkSmoke &lt;soubor s důvodem 2&gt;</pre>
 */
public final class ReasonTwoLinkSmoke extends Application {

    private static File file;
    private static final List<String> dialogTexts = new ArrayList<>();

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: ReasonTwoLinkSmoke <signed-file>");
            System.exit(2);
        }
        file = new File(args[0]);
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        AppSettings settings = AppSettings.load();
        var doc = new DocumentLoader().load(file);
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        ValidationResult result = new ReportMapper().map(file.getName(), "x", outcome, settings,
                java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

        AdvisorView view = new AdvisorView();
        view.setAppSettings(settings);
        view.setLenientVerifyCallback(() -> System.out.println("[sonda] tolerantní ověření"));
        // Bez něj se odkazy na Nastavení uvnitř rady nepostaví a odstavec zůstane TextArea.
        view.setSettingsOpener(advanced -> System.out.println("[sonda] Nastavení, pokročilé=" + advanced));
        view.setTimestampStandardsMode(settings.isTimestampStandardsMode());
        stage.setScene(new Scene(view.getRoot(), 900, 700));
        stage.show();
        view.show(result);

        Platform.runLater(() -> {
            List<TextFlow> flows = new ArrayList<>();
            collectFlows(view.getRoot(), flows);
            TextFlow solution = null;
            for (TextFlow f : flows) {
                if (plain(f).contains("Pokud je možné získat použitelný CRL seznam")) solution = f;
            }
            if (solution == null) {
                System.out.println("CHYBA: odstavec s radou u důvodu 2 nenalezen");
                quit(1);
                return;
            }
            System.out.println("--- odstavec s radou (běh po běhu) ---");
            for (Node n : solution.getChildrenUnmodifiable()) {
                if (n instanceof Text t) System.out.println("  text: " + t.getText().replace("\n", "\\n"));
                else if (n instanceof Hyperlink h) System.out.println("  ODKAZ: " + h.getText());
            }

            List<Node> kids = new ArrayList<>(solution.getChildrenUnmodifiable());
            Node last = kids.get(kids.size() - 1);
            if (!(last instanceof Hyperlink h) || !h.getText().startsWith("Podrobněji")) {
                System.out.println("CHYBA: odstavec nekončí odkazem Podrobněji");
                quit(1);
                return;
            }

            // Zavře okno, které stisk otevře — a cestou si opíše, co v něm stálo. Běží uvnitř
            // vnořené smyčky, kterou spustí showAndWait(), takže se to stihne dřív, než se
            // stisk vrátí.
            Platform.runLater(() -> {
                for (Window w : new ArrayList<>(Window.getWindows())) {
                    if (w == stage || w.getScene() == null) continue;
                    collectTexts(w.getScene().getRoot(), dialogTexts);
                    w.hide();
                }
            });
            h.fire();

            System.out.println("--- co ukázalo okno za odkazem ---");
            dialogTexts.forEach(t -> System.out.println("  " + t));
            boolean ok = dialogTexts.stream().anyMatch(t -> t.contains("Potřebné CRL musí být vydáno"))
                    && dialogTexts.stream().anyMatch(t -> t.contains("Kde stáhnout historické CRL"));
            System.out.println(ok ? "ODKAZ OK" : "ODKAZ CHYBA");
            quit(ok ? 0 : 1);
        });
    }

    private static void quit(int code) {
        Platform.exit();
        System.exit(code);
    }

    private static String plain(TextFlow f) {
        StringBuilder sb = new StringBuilder();
        for (Node n : f.getChildrenUnmodifiable()) {
            if (n instanceof Text t) sb.append(t.getText());
            else if (n instanceof Hyperlink h) sb.append(h.getText());
        }
        return sb.toString();
    }

    private static void collectFlows(Parent parent, List<TextFlow> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof TextFlow tf) { out.add(tf); continue; }
            if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() instanceof Parent p) {
                collectFlows(p, out);
            } else if (n instanceof Parent p) {
                collectFlows(p, out);
            }
        }
    }

    private static void collectTexts(Parent parent, List<String> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()) out.add(l.getText());
            else if (n instanceof Hyperlink h && h.getText() != null) out.add("[odkaz] " + h.getText());
            else if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() instanceof Parent p) {
                collectTexts(p, out);
            } else if (n instanceof Parent p) {
                collectTexts(p, out);
            }
        }
    }
}
