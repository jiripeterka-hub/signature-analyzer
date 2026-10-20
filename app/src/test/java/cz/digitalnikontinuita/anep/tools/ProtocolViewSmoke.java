package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.ProtocolView;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.File;

/**
 * Manual smoke test for the lazily created protocol WebViews: validates a document, feeds the
 * result to a {@link ProtocolView}, and checks that no WebView exists until the tab is opened
 * and that one appears (with content) afterwards.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.ProtocolViewSmoke &lt;signed-file&gt;</pre>
 */
public final class ProtocolViewSmoke extends Application {

    private static File file;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: ProtocolViewSmoke <signed-file>");
            System.exit(2);
        }
        file = new File(args[0]);
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        AppSettings settings = AppSettings.load();
        DSSDocument doc = new DocumentLoader().load(file);
        var outcome = new ValidatorFactory(settings).validate(doc, null, new CommonCertificateVerifier(), null);
        ValidationResult result = new ReportMapper().map(file.getName(), doc.getClass().getSimpleName(),
                outcome, settings, java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

        ProtocolView view = new ProtocolView();
        stage.setScene(new Scene(view.getRoot(), 800, 600));
        stage.show();

        view.show(result);
        int before = countWebViews(view.getRoot());
        System.out.printf("WebViews before opening the tab: %d (expected 0)%n", before);

        view.onShown();
        // The WebView is created synchronously by onShown(); loading its HTML is asynchronous,
        // so give the engine a couple of pulses before looking at it.
        Platform.runLater(() -> Platform.runLater(() -> {
            int after = countWebViews(view.getRoot());
            WebView wv = findWebView(view.getRoot());
            String loc = wv != null ? String.valueOf(wv.getEngine().getLoadWorker().getState()) : "n/a";
            System.out.printf("WebViews after opening the tab: %d (expected 1), load state: %s%n", after, loc);
            boolean ok = before == 0 && after == 1;
            System.out.println(ok ? "SMOKE OK" : "SMOKE FAILED");
            Platform.exit();
            System.exit(ok ? 0 : 1);
        }));
    }

    private static int countWebViews(javafx.scene.Parent root) {
        int n = 0;
        for (javafx.scene.Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof WebView) n++;
            else if (child instanceof StackPane sp) n += countWebViews(sp);
            else if (child instanceof javafx.scene.control.TabPane tp) {
                for (javafx.scene.control.Tab t : tp.getTabs()) {
                    if (t.getContent() instanceof javafx.scene.Parent p) n += countWebViews(p);
                }
            } else if (child instanceof javafx.scene.Parent p) n += countWebViews(p);
        }
        return n;
    }

    private static WebView findWebView(javafx.scene.Parent root) {
        for (javafx.scene.Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof WebView wv) return wv;
            if (child instanceof javafx.scene.control.TabPane tp) {
                for (javafx.scene.control.Tab t : tp.getTabs()) {
                    if (t.getContent() instanceof javafx.scene.Parent p) {
                        WebView wv = findWebView(p);
                        if (wv != null) return wv;
                    }
                }
            } else if (child instanceof javafx.scene.Parent p) {
                WebView wv = findWebView(p);
                if (wv != null) return wv;
            }
        }
        return null;
    }
}
