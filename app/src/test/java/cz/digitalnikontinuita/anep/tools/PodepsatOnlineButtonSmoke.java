package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.CustomTrustStore;
import cz.digitalnikontinuita.anep.core.PodepsatOnline;
import cz.digitalnikontinuita.anep.ui.TrustedCertificatesDialog;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Stiskne v okně Vlastní důvěryhodné certifikáty tlačítko „Vložit kořenový certifikát služby
 * Podepsat.online“ a vypíše, zda je kořen potom v úložišti — dvakrát, aby bylo vidět, že se
 * nezdvojí.
 *
 * <p><b>Zapisuje do vlastního úložiště</b> ({@code %APPDATA%\SignatureAnalyzer\trusted-certs}).
 * Pouštět s {@code APPDATA} přesměrovaným na kopii, ne nad skutečnými daty uživatele.
 */
public final class PodepsatOnlineButtonSmoke extends Application {

    @Override
    public void start(Stage stage) {
        System.out.println("před stiskem: " + count());
        Platform.runLater(() -> {
            try {
                Button b = find();
                System.out.println("tlačítko: " + (b != null ? b.getText() : "NENALEZENO"));
                if (b != null) {
                    b.fire();
                    System.out.println("po 1. stisku: " + count());
                    b.fire();
                    System.out.println("po 2. stisku: " + count());
                }
                // Odkazy „Co se stane?“: každý otevře vlastní okno s vysvětlením. Zavře se
                // dalším runLater, který poběží uvnitř vnořené smyčky showAndWait.
                for (javafx.scene.control.Hyperlink h : links()) {
                    Platform.runLater(() -> {
                        for (Window w : Window.getWindows().toArray(new Window[0])) {
                            if (w instanceof Stage s && "Co se stane?".equals(s.getTitle())) {
                                var pane = (javafx.scene.control.DialogPane) s.getScene().getRoot();
                                System.out.println("okno: " + pane.getHeaderText() + " → "
                                        + ((javafx.scene.control.Label) pane.getContent()).getText()
                                                .substring(0, 40) + "…");
                                s.close();
                            }
                        }
                    });
                    h.fire();
                }
            } finally {
                for (Window w : Window.getWindows().toArray(new Window[0])) {
                    if (w instanceof Stage s) s.close();
                }
            }
        });
        TrustedCertificatesDialog.show(null);
        Platform.exit();
    }

    private static long count() {
        return CustomTrustStore.listCertificates().stream()
                .filter(e -> PodepsatOnline.ROOT_SHA256.equals(java.util.HexFormat.of().formatHex(
                        e.certificate().getDigest(
                                eu.europa.esig.dss.enumerations.DigestAlgorithm.SHA256))))
                .count();
    }

    private static Button find() {
        for (Window w : Window.getWindows()) {
            if (w.getScene() == null) continue;
            Button b = find(w.getScene().getRoot());
            if (b != null) return b;
        }
        return null;
    }

    private static List<javafx.scene.control.Hyperlink> links() {
        List<javafx.scene.control.Hyperlink> out = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.getScene() != null) links(w.getScene().getRoot(), out);
        }
        return out;
    }

    private static void links(Node n, List<javafx.scene.control.Hyperlink> out) {
        if (n instanceof javafx.scene.control.Hyperlink h && "Co se stane?".equals(h.getText())) out.add(h);
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) links(c, out);
        }
    }

    private static Button find(Node n) {
        if (n instanceof Button b && b.getText().contains("Podepsat.online")) return b;
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Button b = find(c);
                if (b != null) return b;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
