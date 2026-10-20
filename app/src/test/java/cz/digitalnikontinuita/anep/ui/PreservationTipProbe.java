package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;

import java.nio.file.Path;

/**
 * Prints the "Lhůta pro uchování" value and the text of its ⓘ bubble, as the Rozšířený přehled
 * actually renders them for a given file.
 *
 * <p>The wording depends on which revocation token carried the verdict, which no amount of reading
 * the code will settle for a particular document: it depends on the store's contents, on what the
 * document already holds, and on what the CA answers today. This drives the real view and reads the
 * tooltip back out of the scene graph.
 *
 * <pre>
 *   java --module-path &lt;javafx&gt; --add-modules javafx.controls,javafx.graphics,javafx.web \
 *        -cp ... cz.digitalnikontinuita.anep.ui.PreservationTipProbe &lt;soubor&gt; [&lt;soubor&gt;...]
 * </pre>
 */
public class PreservationTipProbe extends Application {

    @Override
    public void start(Stage stage) {
        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
        }

        for (String arg : getParameters().getRaw()) {
            Path p = Path.of(arg);
            System.out.println();
            System.out.println("### " + p.getFileName());
            try {
                DSSDocument doc = new DocumentLoader().load(p.toFile());
                var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
                ValidationResult result = new ReportMapper()
                        .map(p.getFileName().toString(), null, outcome, settings);

                ExtendedView view = new ExtendedView();
                view.setTrustListInfo(trustConfig.getLastSourceCheckTime(),
                        trustConfig.isNetworkRefreshInProgress(),
                        trustConfig.getLatestIssuedTrustList(),
                        trustConfig.getStaleTrustLists(),
                        trustConfig.getEmptyTerritories());
                view.show(result);
                // A Scene is needed for the CSS/layout pass the labels go through.
                new Scene(view.getRoot(), 900, 700);
                report(view.getRoot());
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
        }
        Platform.exit();
    }

    /** Finds each labelled row of interest and prints its value plus any tooltip on the row. */
    private static void report(Parent root) {
        for (String label : new String[]{"Využité revokační informace:", "Lhůta pro uchování:"}) {
            Node row = findRowByLabel(root, label);
            if (row == null) {
                System.out.println("  (řádek \"" + label + "\" se nezobrazuje)");
                continue;
            }
            System.out.println("  " + label + " " + rowValue(row, label));
            String tip = findTooltip(row);
            if (tip != null) {
                System.out.println("  ⓘ " + tip.replace("\n", "\n     "));
            }
        }
    }

    private static Node findRowByLabel(Node node, String labelText) {
        for (Node child : childrenOf(node)) {
            if (child instanceof Label l && labelText.equals(l.getText())) return node;
            Node found = findRowByLabel(child, labelText);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Children plus the content of the containers that keep theirs outside the child list until a
     * layout pass has run — a ScrollPane holds its content in a property, and the cards of the
     * Rozšířený přehled live inside one.
     */
    private static Iterable<Node> childrenOf(Node node) {
        if (node instanceof javafx.scene.control.ScrollPane sp) {
            return sp.getContent() != null ? java.util.List.of(sp.getContent()) : java.util.List.of();
        }
        if (node instanceof javafx.scene.control.TitledPane tp) {
            return tp.getContent() != null ? java.util.List.of(tp.getContent()) : java.util.List.of();
        }
        if (node instanceof Parent p) return p.getChildrenUnmodifiable();
        return java.util.List.of();
    }

    private static String rowValue(Node row, String skipLabel) {
        StringBuilder sb = new StringBuilder();
        collectText(row, skipLabel, sb);
        return sb.toString().trim();
    }

    private static void collectText(Node node, String skipLabel, StringBuilder sb) {
        if (node instanceof Label l && l.getText() != null
                && !skipLabel.equals(l.getText()) && !"ⓘ".equals(l.getText())) {
            sb.append(l.getText()).append(' ');
        }
        if (node instanceof javafx.scene.control.TextInputControl t) sb.append(t.getText()).append(' ');
        if (node instanceof javafx.scene.text.Text t) sb.append(t.getText()).append(' ');
        for (Node c : childrenOf(node)) collectText(c, skipLabel, sb);
    }

    /**
     * Tooltip.install() stores the tooltip in the node's property map under a key private to
     * JavaFX, not through Control.setTooltip — so Control.getTooltip() comes back null and the
     * property values have to be searched by type.
     */
    private static String findTooltip(Node node) {
        if (node.hasProperties()) {
            for (Object v : node.getProperties().values()) {
                if (v instanceof Tooltip t) return t.getText();
            }
        }
        if (node instanceof javafx.scene.control.Control c && c.getTooltip() != null) {
            return c.getTooltip().getText();
        }
        for (Node c : childrenOf(node)) {
            String t = findTooltip(c);
            if (t != null) return t;
        }
        return null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
