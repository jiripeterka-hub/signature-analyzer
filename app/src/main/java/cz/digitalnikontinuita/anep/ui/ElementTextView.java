package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * Okno, které ukáže certifikát nebo odpověď OCSP vypsané ve slovech — a umí je uložit.
 *
 * <p>Text dodává {@code ContainerInspector} — týž, ze kterého čte náhledový panel prohlížeče
 * kontejnerů. Je to záměr, ne úspora: tentýž prvek se má v celé aplikaci číst stejně, ať se na
 * něj uživatel dostane odkudkoli. Vlastní okno místo systémového prohlížeče proto, že systémový
 * o odpovědi OCSP neví vůbec a u certifikátu ukazuje jinou sadu polí, než na kterou se čtenář
 * v tomhle nástroji ptá.
 *
 * <h2>Proč dva sloupce, a ne jeden blok textu</h2>
 *
 * <p>Do 16. 9. 2026 to byla jedna {@code TextArea} bez zalamování. Sloupce v ní srovnávalo
 * neproporcionální písmo a mezery, jenže dlouhá hodnota — DN předmětu, výčet distribučních bodů
 * CRL — pak z okna vybíhala a čtenář ji doháněl vodorovným posuvníkem. Nález uživatele.
 *
 * <p>Zalomit ji uvnitř jediné {@code TextArea} nešlo pořádně: pokračování se láme k levému
 * okraji, tedy pod popisek, a řádek s hodnotou pak vypadá jako další položka. Popisek a hodnota
 * mají proto vlastní sloupce mřížky; hodnota se v tom svém zalomí a odsazení drží mřížka, ne
 * mezery.
 *
 * <p><b>Kopírovat jde dál obojí.</b> Hodnota sedí v {@link ResultView#selectable} — průhledné
 * needitovatelné {@code TextArea} —, takže se dá označit myší a vzít přes Ctrl+C, a to samotná,
 * bez popisku a bez sousedních řádků. Celý výpis najednou vydá tlačítko „Kopírovat vše"; dřív to
 * obstarávalo Ctrl+A v jediném poli.
 *
 * <p>Nemodální schválně: uživatel si otevře certifikát a chce se přitom dál pohybovat po Analýze.
 */
public final class ElementTextView {

    private ElementTextView() { }

    /** Neproporcionální písmo drží šestnáctkové výpisy a DN čitelné — jako v celém prohlížeči. */
    private static final String MONO =
            "-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 12px;";

    /**
     * @param title       nadpis okna
     * @param text        popis prvku ve slovech
     * @param der         bajty prvku pro tlačítko „Uložit…“; {@code null} tlačítko vynechá
     * @param defaultName předvyplněné jméno souboru
     * @param filterDesc  popis typu souboru
     * @param filterExt   maska typu souboru
     */
    public static void show(String title, String text, byte[] der, String defaultName,
                            String filterDesc, String filterExt, Stage owner) {
        if (text == null || text.isBlank()) {
            UiDialogs.error(title, "Prvek se nepodařilo přečíst — data nemají"
                    + " očekávanou podobu, nebo jsou poškozená.");
            return;
        }

        ScrollPane scroll = new ScrollPane(grid(text));
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Stage stage = new Stage();
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Button copyAll = new Button("Kopírovat vše");
        copyAll.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        buttons.getChildren().add(copyAll);

        if (der != null && der.length > 0) {
            // Druhý názor na týž prvek: jiná sada polí, jiné pojmenování, a u certifikátu i cesta
            // k tomu, co tahle aplikace nedělá — nainstalovat jej do systémového úložiště.
            // U odpovědi OCSP se tlačítko nenabízí, systém ten typ nezná; viz hasSystemViewer().
            if (UiDialogs.hasSystemViewer(filterExt)) {
                Button system = new Button("Otevřít v systémovém prohlížeči");
                system.setOnAction(e ->
                        UiDialogs.openInSystemViewer(der, defaultName, filterExt, stage));
                buttons.getChildren().add(system);
            }
            Button save = new Button("Uložit…");
            save.setOnAction(e -> UiDialogs.saveBytes(der, defaultName, filterDesc, filterExt,
                    stage));
            buttons.getChildren().add(save);
        }
        Button close = new Button("Zavřít");
        close.setOnAction(e -> stage.close());
        buttons.getChildren().add(close);

        VBox root = new VBox(10, scroll, buttons);
        root.setPadding(new Insets(12));
        stage.setTitle(title);
        if (owner != null) stage.initOwner(owner);
        stage.setScene(new Scene(root, 860, 620));
        stage.show();
    }

    /**
     * Výpis jako mřížka: popisek vlevo, hodnota vpravo a zalomená.
     *
     * <p>Sloupec s popiskem se roztáhne na ten nejdelší a dál neroste; celou zbylou šířku dostane
     * hodnota, protože právě ona se má kam lámat. Řádek bez popisku — nadpis bloku — jde přes oba
     * sloupce, prázdná řádka se stane mezerou.
     */
    static GridPane grid(String text) {
        GridPane pane = new GridPane();
        pane.setHgap(10);
        pane.setVgap(2);
        pane.setPadding(new Insets(2, 8, 2, 2));

        ColumnConstraints labels = new ColumnConstraints();
        labels.setHgrow(Priority.NEVER);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setFillWidth(true);
        // Bez tohohle si sloupec s hodnotou drží šířku nejdelšího nezalomeného řádku a mřížka
        // vyjede z okna dřív, než se text stihne zalomit.
        values.setMinWidth(10);
        pane.getColumnConstraints().addAll(labels, values);

        List<ContainerInspector.TextRow> rows = ContainerInspector.splitRows(text);
        int r = 0;
        for (ContainerInspector.TextRow row : rows) {
            if (!row.hasLabel()) {
                if (row.value().isBlank()) {
                    Region gap = new Region();
                    gap.setMinHeight(8);
                    pane.add(gap, 0, r++, 2, 1);
                } else {
                    Label header = new Label(row.value());
                    header.setStyle(MONO + " -fx-font-weight: bold; -fx-text-fill: #111111;");
                    pane.add(header, 0, r++, 2, 1);
                }
                continue;
            }
            Label name = new Label(row.indent() + row.label());
            name.setStyle(MONO + " -fx-text-fill: #444444;");
            name.setMinWidth(Region.USE_PREF_SIZE);
            GridPane.setValignment(name, VPos.TOP);
            pane.add(name, 0, r);

            javafx.scene.control.TextArea value = ResultView.selectable(row.value(), MONO);
            GridPane.setValignment(value, VPos.TOP);
            pane.add(value, 1, r++);
        }
        return pane;
    }
}
