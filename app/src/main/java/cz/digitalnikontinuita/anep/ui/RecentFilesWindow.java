package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.RecentFiles;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Okénko s naposledy ověřovanými soubory — otevírá se pravým tlačítkem na ↺.
 *
 * <p>Okno, ne kontextová nabídka. Nabídka si brala písmo od tlačítka ↺ (26 px) a měnit její
 * velikost nejde; dlouhé cesty se tak nevešly a deset položek zabralo půl obrazovky (nález
 * uživatele 2. 10. 2026). Tohle okno má písmo aplikace, podklad v barvě jejího okna a dá se
 * roztáhnout; velikost si pamatuje do konce běhu.
 *
 * <p>Chová se ale jako nabídka: výběr položky (kliknutím nebo Enterem) okno zavře, a zavře se
 * i Escapem nebo kliknutím jinam.
 */
final class RecentFilesWindow {

    private static double lastWidth = 560;
    private static double lastHeight = 340;

    /** Poslední zjištěná dostupnost položky; do konce běhu, viz {@link #show}. */
    private static final Map<RecentFiles.Entry, Boolean> AVAILABLE = new ConcurrentHashMap<>();
    /** Položky, jejichž kontrola na pozadí ještě neskončila. */
    private static final Set<RecentFiles.Entry> PENDING = ConcurrentHashMap.newKeySet();
    /** Seznam v právě otevřeném okně ({@code null}, když žádné není); jen z vlákna JavaFX. */
    private static ListView<RecentFiles.Entry> openList;

    private RecentFilesWindow() { }

    static void show(Window owner, double screenX, double screenY,
                     Consumer<RecentFiles.Entry> onChosen) {
        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Naposledy ověřované soubory");
        stage.initModality(Modality.NONE);
        if (owner != null) stage.initOwner(owner);

        ListView<RecentFiles.Entry> list = new ListView<>();
        // Podklad v barvě okna aplikace místo bílých řádků; -fx-background je barva, kterou
        // modena dává kořeni scény.
        list.setStyle("-fx-control-inner-background: -fx-background;"
                + " -fx-control-inner-background-alt: -fx-background;"
                + " -fx-background-color: -fx-box-border, -fx-background;"
                + " -fx-font-size: 12px;");
        Consumer<RecentFiles.Entry> pick = e -> {
            stage.close();
            onChosen.accept(e);
        };
        // Dostupnost souborů se zjišťuje na pozadí, každá položka ve vlastním vlákně. Dřív se
        // File.isFile() volalo při vykreslení řádku na vlákně JavaFX, a u cesty na nedostupný
        // síťový disk visí každé volání desítky sekund — aplikace zatuhla (nález uživatele
        // 9. 11. 2026). Zvolit se nedá jen položka, o které je známo, že chybí.
        //
        // Poslední zjištění se pamatuje do konce běhu (AVAILABLE): při dalším otevření okna se
        // položka hned ukáže tak, jak naposledy dopadla — nedostupná zašedle — a na pozadí se
        // zkouší znovu, kdyby se disk mezitím připojil. Bez toho se při každém otevření všechno
        // ukázalo jako dostupné a zešedlo až po desítkách sekund (týž den). Kontrola, která ještě
        // visí z dřívějška, se nezakládá podruhé; její výsledek dorazí do okna, které je otevřené.
        // Položka bez zjištění vypadá a chová se jako dostupná.
        list.setCellFactory(v -> new EntryCell(pick, AVAILABLE));
        Label empty = new Label("Zatím nebyl ověřen žádný soubor.");
        empty.setStyle("-fx-text-fill: #666666; -fx-font-size: 12px;");
        list.setPlaceholder(empty);
        list.getItems().setAll(RecentFiles.load());
        VBox.setVgrow(list, Priority.ALWAYS);
        openList = list;
        for (RecentFiles.Entry entry : List.copyOf(list.getItems())) {
            if (!PENDING.add(entry)) continue;
            Thread t = new Thread(() -> {
                boolean ok;
                try {
                    ok = entry.exists();
                } finally {
                    PENDING.remove(entry);
                }
                Platform.runLater(() -> {
                    AVAILABLE.put(entry, ok);
                    if (openList != null) openList.refresh();
                });
            }, "anep-recent-exists");
            t.setDaemon(true);
            t.start();
        }

        list.setOnKeyPressed(ev -> {
            RecentFiles.Entry e = list.getSelectionModel().getSelectedItem();
            if (ev.getCode() == KeyCode.ENTER && e != null
                    && AVAILABLE.getOrDefault(e, true)) pick.accept(e);
        });

        Button clear = new Button("Vymazat seznam");
        clear.setStyle("-fx-font-size: 12px;");
        clear.setDisable(list.getItems().isEmpty());
        clear.setOnAction(ev -> {
            RecentFiles.clear();
            list.getItems().clear();
            clear.setDisable(true);
        });
        Button close = new Button("Zavřít");
        close.setStyle("-fx-font-size: 12px;");
        close.setOnAction(ev -> stage.close());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(8, clear, gap, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, list, buttons);
        root.setPadding(new Insets(8));
        Scene scene = new Scene(root, lastWidth, lastHeight);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        scene.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) stage.close();
        });
        stage.setScene(scene);
        stage.setMinWidth(280);
        stage.setMinHeight(160);
        stage.setX(screenX);
        stage.setY(screenY);
        // Jako nabídka: kliknutí jinam okno zavře.
        stage.focusedProperty().addListener((o, was, now) -> {
            if (!now) stage.close();
        });
        stage.setOnHidden(ev -> {
            if (openList == list) openList = null;
            lastWidth = stage.getWidth();
            lastHeight = stage.getHeight();
        });
        stage.show();
        // Nesmí vylézt z obrazovky: u tlačítka v pravé části okna by se jinak otevřelo napůl mimo.
        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen
                .getScreensForRectangle(screenX, screenY, 1, 1).stream().findFirst()
                .orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
        if (stage.getX() + stage.getWidth() > bounds.getMaxX()) {
            stage.setX(Math.max(bounds.getMinX(), bounds.getMaxX() - stage.getWidth()));
        }
        if (stage.getY() + stage.getHeight() > bounds.getMaxY()) {
            stage.setY(Math.max(bounds.getMinY(), bounds.getMaxY() - stage.getHeight()));
        }
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
            list.requestFocus();
        }
    }

    /**
     * Řádek: jména souborů, pod nimi menším písmem složka. Chybějící soubor zašedle.
     * Dostupnost bere z mapy plněné na pozadí; souborového systému se sám nedotýká.
     */
    private static final class EntryCell extends ListCell<RecentFiles.Entry> {
        private final Map<RecentFiles.Entry, Boolean> available;

        EntryCell(Consumer<RecentFiles.Entry> pick, Map<RecentFiles.Entry, Boolean> available) {
            this.available = available;
            // Na řádku, ne na seznamu: kliknutí do prázdné části pod položkami by jinak vybralo
            // tu, která je zrovna označená.
            setOnMouseClicked(ev -> {
                RecentFiles.Entry e = getItem();
                if (ev.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && !isEmpty() && e != null && available.getOrDefault(e, true)) {
                    pick.accept(e);
                }
            });
        }

        @Override
        protected void updateItem(RecentFiles.Entry e, boolean isEmpty) {
            super.updateItem(e, isEmpty);
            if (isEmpty || e == null) {
                setGraphic(null);
                setText(null);
                setDisable(false);
                return;
            }
            boolean exists = available.getOrDefault(e, true);
            Label name = new Label(e.document().getName()
                    + (e.external() != null ? "  +  " + e.external().getName() : ""));
            name.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;"
                    + (exists ? "" : " -fx-text-fill: #999999;"));
            String folder = e.document().getParent() != null ? e.document().getParent() : "";
            if (e.external() != null && e.external().getParent() != null
                    && !e.external().getParent().equals(folder)) {
                folder += "  +  " + e.external().getParent();
            }
            Label where = new Label(exists ? folder : folder + "  (soubor už na tomto místě není)");
            where.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (exists ? "#666666" : "#999999") + ";");
            VBox box = new VBox(1, name, where);
            setText(null);
            setGraphic(box);
            setDisable(!exists);
        }
    }
}
