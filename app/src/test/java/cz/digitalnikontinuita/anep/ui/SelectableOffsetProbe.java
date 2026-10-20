package cz.digitalnikontinuita.anep.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Hledá, čím je daný zbývající svislý posun textu uvnitř {@code ResultView.selectable()} proti
 * hornímu okraji prvku — tedy proč hodnota sedí níž než popisek vedle ní. Zkouší varianty
 * doplňkových stylů na vnitřní uzly skinu a u každé vypíše, kde text začíná.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.SelectableOffsetProbe
 * </pre>
 */
public class SelectableOffsetProbe extends Application {

    private static final String TEXT = "Pro zkušenější uživatele: lze využít ověřování k datu";

    /** Popis varianty a styly, které se dolepí na .scroll-pane a na .viewport. */
    private record Variant(String name, String scrollPane, String viewport) { }

    private static final Variant[] VARIANTS = {
        new Variant("pismo 11 px", "", ""),
        new Variant("pismo 12 px", "", ""),
        new Variant("pismo 13 px", "", ""),
        new Variant("pismo 12 px, dva radky", "", ""),
    };

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(6);
        TextArea[] areas = new TextArea[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) {
            String[] styles = {
                "-fx-text-fill: #111111; -fx-font-size: 11px;",
                "-fx-text-fill: #111111; -fx-font-size: 12px;",
                "-fx-text-fill: #111111; -fx-font-size: 13px;",
                "-fx-text-fill: #111111; -fx-font-size: 12px;",
            };
            TextArea ta = ResultView.selectable(i == 3 ? TEXT + " " + TEXT + " " + TEXT + " " + TEXT
                    + " " + TEXT + " " + TEXT + " " + TEXT : TEXT, styles[i]);
            areas[i] = ta;
            root.getChildren().add(ta);
        }
        Scene scene = new Scene(root, 900, 300);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            for (int i = 0; i < areas.length; i++) {
                Variant v = VARIANTS[i];
                if (!v.scrollPane().isEmpty()) {
                    for (Node n : areas[i].lookupAll(".scroll-pane")) {
                        n.setStyle(n.getStyle() + v.scrollPane());
                    }
                }
                if (!v.viewport().isEmpty()) {
                    for (Node n : areas[i].lookupAll(".viewport")) {
                        n.setStyle(n.getStyle() + v.viewport());
                    }
                }
            }
            Platform.runLater(() -> Platform.runLater(() -> {
                for (int i = 0; i < areas.length; i++) {
                    TextArea ta = areas[i];
                    double taY = ta.localToScene(ta.getLayoutBounds()).getMinY();
                    double textY = Double.NaN;
                    double textX = Double.NaN;
                    for (Node n : ta.lookupAll(".text")) {
                        textY = n.localToScene(n.getLayoutBounds()).getMinY();
                        textX = n.localToScene(n.getLayoutBounds()).getMinX();
                    }
                    System.out.printf("%-30s posun textu shora = %.2f px, zleva = %.2f px, vyska prvku = %.2f%n",
                            VARIANTS[i].name(), textY - taY,
                            textX - ta.localToScene(ta.getLayoutBounds()).getMinX(), ta.getHeight());
                }
                Platform.exit();
            }));
        });
    }
}
