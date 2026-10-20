package cz.digitalnikontinuita.anep.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;

/**
 * Vypíše, co stojí v okně „O aplikaci“ — řádek po řádku, bez otevírání okna.
 *
 * <p>Formulace jsou v tom okně to hlavní, a na ty se musí dát podívat jinak než snímkem
 * obrazovky. S argumentem {@code -show} se okno místo toho otevře, aby se dalo prohlédnout.
 */
public final class AboutDialogProbe {

    private AboutDialogProbe() { }

    public static void main(String[] args) throws Exception {
        boolean show = args.length > 0 && "-show".equals(args[0]);
        CountDownLatch done = new CountDownLatch(1);

        Platform.startup(() -> {
            if (show) {
                Stage owner = new Stage();
                owner.setWidth(600);
                owner.setHeight(400);
                owner.show();
                AboutDialog.show(owner);
                done.countDown();
            } else {
                javafx.scene.layout.VBox c = AboutDialog.buildContent();
                // Rolovaci plocha se tu sklada stejne jako v show(). Merit samotny obsah
                // nema smysl: prefWidth(-1) vraci sirku, jakou by mel bez zalomeni, tedy
                // 855 px - a prave proto se okno ridi viewportem, ne obsahem.
                javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(c);
                sp.setFitToWidth(true);
                sp.setPrefViewportWidth(500);
                sp.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
                javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(sp);
                new javafx.scene.Scene(root);
                root.applyCss();
                root.layout();
                System.out.printf("OKNO: sirka %.0f px, vyska obsahu %.0f px%n",
                        root.prefWidth(-1), c.prefHeight(500));
                print(c, 0);
                done.countDown();
            }
        });

        done.await();
        if (!show) Platform.exit();
    }

    private static void print(Node node, int depth) {
        String text = null;
        if (node instanceof Hyperlink h) text = "-> " + h.getText();
        else if (node instanceof Button b) text = "[" + b.getText() + "]";
        else if (node instanceof Label l) text = l.getText();
        if (text != null && !text.isBlank()) {
            System.out.println("  ".repeat(depth) + text);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) print(child, depth + 1);
        }
    }
}
