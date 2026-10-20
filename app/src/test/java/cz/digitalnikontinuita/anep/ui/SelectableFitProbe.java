package cz.digitalnikontinuita.anep.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Změří řádek „popisek + hodnota", jaký staví AdvisorView: kolik místa si TextArea vyžádala,
 * jak vysoký je uvnitř ní skutečně vysázený text a kde začíná proti popisku. Projde přitom
 * celou škálu šířek okna, protože zalomení textu se s šířkou mění a chyba se nemusí projevit
 * u té jediné, na které se zrovna zkouší.
 *
 * <p>Vzniklo kvůli hlášení, že hodnotám v bloku „Zjištěné důvody" chybí spodek písmen a že
 * sedí níž než popisek. {@code ResultView.fitToContent} si výšku počítá vlastním měřidlem
 * {@code Text} ve výchozím písmu, kdežto aplikace sází Segoe UI z {@code app.css} — proto se
 * scéna sondy stylopisem obléká také.
 *
 * <h2>⚠ Počet hlášení „NEVEJDE SE" z projetí šířek <b>není opakovatelný</b></h2>
 *
 * <p>Změřeno 16. 9. 2026: čtyři běhy nad <b>týmž</b> kódem vydaly 6, 16, 30 a 3. Projetí totiž
 * po každém měření rovnou mění šířku, takže rozvržení nikdy nedojede — a co se stihne
 * přepočítat, závisí na tom, jak se zrovna poskládala kola smyčky událostí. Čísla z různých
 * běhů se proto <b>nedají porovnávat</b> a nedá se jimi doložit, že úprava
 * {@code ResultView.fitToContent} pomohla nebo uškodila; čtyři takové závěry po sobě musely
 * být odvolány.
 *
 * <p>Na otázku „vejde se to, nebo ne" odpovídá teprve <b>režim s jednou šířkou</b>: ten okno
 * zastaví a změří je, až když se rozvržení ustálí. Tak se ukázalo, že přetečení je jev
 * přechodný — při šířce 620 px, kterou projetí hlásilo jako vadnou, se po ustálení text vejde
 * s rezervou. Uživatel je tedy může zahlédnout jen během tažení za okraj okna.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.SelectableFitProbe
 *
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.SelectableFitProbe -Dexec.args="620"
 * </pre>
 */
public class SelectableFitProbe extends Application {

    private static final String[] TEXTS = {
        "4. Podpis (či pečeť) není opatřen časovým razítkem a je založen na certifikátu, který již"
            + " expiroval (tj. již skončila řádná doba jeho platnosti). To znamená ztrátu digitální"
            + " kontinuity, kterou již nejde obnovit.",
        "Pro zkušenější uživatele: lze využít možnost ověřování k zadanému datu a času"
            + " (lze zvolit v pokročilém nastavení)",
        "uznávaná elektronická značka dle původního zákona č. 227/2000 Sb. o elektronickém podpisu."
            + " Jde o starší českou právní kategorii, předcházející pečeti dle nařízení eIDAS.",
        "jednořádkový text bez zalomení",
    };

    private final TextArea[] areas = new TextArea[TEXTS.length];
    private final Label[] labels = new Label[TEXTS.length];
    private VBox root;
    private Stage stage;

    /** Šířka, na které se má okno zastavit a změřit se až po ustálení; 0 = projet celý rozsah. */
    private int holdWidth;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        for (String a : getParameters().getRaw()) {
            try {
                holdWidth = Integer.parseInt(a.trim());
            } catch (NumberFormatException ignored) {
                // Ostatní argumenty sonda nezná a nevadí jí to.
            }
        }
        root = new VBox(2);

        for (int i = 0; i < TEXTS.length; i++) {
            Label lbl = new Label("V čem je problém:");
            lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
            lbl.setMinWidth(170);
            lbl.setMaxWidth(170);
            lbl.setWrapText(true);
            TextArea val = ResultView.selectable(TEXTS[i],
                    "-fx-text-fill: #111111; -fx-font-size: 12px;");
            HBox.setHgrow(val, Priority.ALWAYS);
            HBox row = new HBox(8, lbl, val);
            row.setAlignment(Pos.TOP_LEFT);
            root.getChildren().add(row);
            areas[i] = val;
            labels[i] = lbl;
        }

        Scene scene = new Scene(root, 960, 400);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> Platform.runLater(() -> {
            TextArea ta = areas[0];
            System.out.println("-- kde co zacina (minY ve scene) --");
            System.out.printf("popisek   %.2f%n",
                    labels[0].localToScene(labels[0].getLayoutBounds()).getMinY());
            System.out.printf("TextArea     y=%.2f v=%.2f  x=%.2f%n",
                    ta.localToScene(ta.getLayoutBounds()).getMinY(), ta.getHeight(),
                    ta.localToScene(ta.getLayoutBounds()).getMinX());
            for (String sel : new String[] {".scroll-pane", ".viewport", ".content", ".text"}) {
                for (Node n : ta.lookupAll(sel)) {
                    System.out.printf("%-12s y=%.2f v=%.2f  x=%.2f%n", sel,
                            n.localToScene(n.getLayoutBounds()).getMinY(),
                            n.getLayoutBounds().getHeight(),
                            n.localToScene(n.getLayoutBounds()).getMinX());
                }
            }
            if (holdWidth > 0) hold(holdWidth);
            else sweep(600);
        }));
    }

    /**
     * Nastaví jednu šířku a změří ji <b>až po ustálení</b>, ne hned po dvou kolech jako
     * {@link #sweep}.
     *
     * <p>Kvůli jediné otázce, na kterou sweep odpovědět neumí: hlásí-li nějaká šířka „nevejde
     * se", je to trvalý stav, nebo jen okamžik, než se dopočítá výška? Sweep totiž po každém
     * měření rovnou mění šířku, takže rozvržení nikdy nedojede do konce.
     *
     * <pre>
     *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test      *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.SelectableFitProbe      *       -Dexec.args="620"
     * </pre>
     */
    private void hold(int width) {
        stage.setWidth(width);
        after(40, () -> {
            System.out.println("-- po ustaleni na sirce " + width + " --");
            for (int i = 0; i < areas.length; i++) {
                TextArea ta = areas[i];
                double textH = 0;
                for (Node n : ta.lookupAll(".text")) textH += n.getLayoutBounds().getHeight();
                double spare = ta.getHeight() - textH;
                System.out.printf("[%d] TextArea=%6.2f text=%6.2f rezerva=%6.2f%s%n",
                        i, ta.getHeight(), textH, spare, spare < 2 ? "  <<< NEVEJDE SE" : "");
            }
            Platform.exit();
        });
    }

    private static void after(int rounds, Runnable task) {
        if (rounds <= 0) task.run();
        else Platform.runLater(() -> after(rounds - 1, task));
    }

    /** Projde šířky okna po pěti pixelech a u každé se podívá, jestli se text vejde. */
    private void sweep(int width) {
        if (width > 1000) {
            System.out.println("--- hotovo");
            Platform.exit();
            return;
        }
        stage.setWidth(width);
        // dvě kola: první dojede rozvržení, druhé už vidí přepočtené výšky
        Platform.runLater(() -> Platform.runLater(() -> {
            for (int i = 0; i < areas.length; i++) {
                TextArea ta = areas[i];
                double textH = 0;
                double firstTextY = Double.NaN;
                for (Node n : ta.lookupAll(".text")) {
                    textH += n.getLayoutBounds().getHeight();
                    double y = n.localToScene(n.getLayoutBounds()).getMinY();
                    if (Double.isNaN(firstTextY) || y < firstTextY) firstTextY = y;
                }
                double labelY = labels[i].localToScene(labels[i].getLayoutBounds()).getMinY();
                double spare = ta.getHeight() - textH;
                String flag = spare < 2 ? "  <<< NEVEJDE SE" : "";
                if (spare < 2 || width % 100 == 0) {
                    System.out.printf(
                            "sirka=%4d [%d] TextArea=%6.2f text=%6.2f rezerva=%6.2f posun=%.2f%s%n",
                            width, i, ta.getHeight(), textH, spare, firstTextY - labelY, flag);
                }
            }
            sweep(width + 5);
        }));
    }
}
