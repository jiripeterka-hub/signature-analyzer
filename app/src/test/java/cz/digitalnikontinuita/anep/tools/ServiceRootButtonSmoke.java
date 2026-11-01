package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.BankIdSign;
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
 * Stiskne v okně Vlastní důvěryhodné certifikáty tlačítka, která vkládají kořen podepisovací
 * služby (Podepsat.online, BankID Sign), a vypíše, zda je kořen potom v úložišti — každé
 * dvakrát, aby bylo vidět, že se nezdvojí. Pak otevře a zavře všechna okna „Co se stane?“.
 *
 * <p><b>Zapisuje do vlastního úložiště</b> ({@code %APPDATA%\SignatureAnalyzer\trusted-certs}).
 * Pouštět s {@code APPDATA} přesměrovaným na kopii, ne nad skutečnými daty uživatele.
 */
public final class ServiceRootButtonSmoke extends Application {

    private record Service(String buttonText, String rootSha256) { }

    private static final List<Service> SERVICES = List.of(
            new Service("Podepsat.online", PodepsatOnline.ROOT_SHA256),
            new Service("BankID Sign", BankIdSign.ROOT_SHA256));

    @Override
    public void start(Stage stage) {
        Platform.runLater(() -> {
            try {
                for (Service service : SERVICES) {
                    System.out.println(service.buttonText() + " — před stiskem: "
                            + count(service.rootSha256()));
                    Button b = find(service.buttonText());
                    System.out.println("  tlačítko: " + (b != null ? b.getText() : "NENALEZENO"));
                    if (b == null) continue;
                    b.fire();
                    System.out.println("  po 1. stisku: " + count(service.rootSha256()));
                    b.fire();
                    System.out.println("  po 2. stisku: " + count(service.rootSha256()));
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

    private static long count(String sha256) {
        return CustomTrustStore.listCertificates().stream()
                .filter(e -> sha256.equals(java.util.HexFormat.of().formatHex(
                        e.certificate().getDigest(
                                eu.europa.esig.dss.enumerations.DigestAlgorithm.SHA256))))
                .count();
    }

    private static Button find(String textFragment) {
        for (Window w : Window.getWindows()) {
            if (w.getScene() == null) continue;
            Button b = find(w.getScene().getRoot(), textFragment);
            if (b != null) return b;
        }
        return null;
    }

    private static Button find(Node n, String textFragment) {
        if (n instanceof Button b && b.getText().contains(textFragment)) return b;
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Button b = find(c, textFragment);
                if (b != null) return b;
            }
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

    public static void main(String[] args) {
        launch(args);
    }
}
