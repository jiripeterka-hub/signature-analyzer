package cz.digitalnikontinuita.anep.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Okno se záznamem o úkonu, které se otevře, jakmile doběhne ověření výsledného souboru.
 *
 * <p>Okno, ne vlastní záložka: záznam mluví o dvou souborech a o přechodu mezi nimi, kdežto
 * záložky hlavního okna mluví o jednom. Od 4. 10. 2026 je to vstupní soubor — výsledný se ověřuje
 * na pozadí — a do hlavního okna se výsledný dostane tlačítkem „Otevřít výsledný soubor v AnEP“
 * (pokyn uživatele).
 *
 * <p>Uložení je volba, ne povinnost (návrh z 4. 10. 2026: „nepovinně“). Razítko se nabízí
 * zaškrtávátkem přímo v okně, ne dialogem protokolů: ten se ptá i na vložení ověřovaného souboru,
 * a to by tu nafouklo záznam o celý výsledný dokument — vazbu na něj přitom nesou otisky.
 */
final class ActionRecordWindow {

    private ActionRecordWindow() { }

    static Stage show(Window owner, ActionRecord record, PrintUtil.ProtocolExtras extras) {
        return show(owner, record, extras, null);
    }

    /**
     * @param openResult otevře výsledný soubor v hlavním okně; {@code null} tlačítko nepřidá
     *                   (sonda)
     */
    static Stage show(Window owner, ActionRecord record, PrintUtil.ProtocolExtras extras,
                      Runnable openResult) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle(record.title());

        ScrollPane scroll = new ScrollPane(record.content());
        scroll.setFitToWidth(true);

        String tsa = extras != null ? extras.tsaName() : null;
        boolean canStamp = tsa != null && !tsa.isBlank();
        CheckBox stamp = new CheckBox("Opatřit záznam časovým razítkem");
        stamp.setDisable(!canStamp);
        if (!canStamp) stamp.setText("Opatřit záznam časovým razítkem (není vybrán zdroj)");

        Button save = new Button("Uložit záznam o úkonu…");
        save.setDefaultButton(true);
        save.setOnAction(e -> save(stage, record, stamp.isSelected() && canStamp, extras));
        Button close = new Button("Zavřít");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(10, stamp, gap);
        if (openResult != null) {
            Button open = new Button("Otevřít výsledný soubor v AnEP");
            open.setOnAction(e -> openResult.run());
            bar.getChildren().add(open);
        }
        bar.getChildren().addAll(save, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 12, 10, 12));

        BorderPane root = new BorderPane(scroll);
        root.setBottom(new javafx.scene.layout.VBox(reminderRow(stage, record), bar));
        // Výška na sbalený obsah: úvodní věta a pět záhlaví. Rozbalený blok se posouvá
        // a okno jde zvětšit; prázdná polovina okna pod sbalenými bloky působila nedodělaně.
        // O 40 bodů víc od 4. 10. 2026 kvůli řádku s připomínkou do kalendáře.
        stage.setScene(new Scene(root, 820, 480));
        stage.show();
        return stage;
    }

    /**
     * Řádek „Připomínka do kalendáře“: výběr předstihu a uložení souboru .ics (pokyn uživatele
     * 4. 10. 2026). Termínem je datum z úvodní věty záznamu ({@link ActionRecord#verifiableUntil});
     * když ho záznam nemá, řádek to řekne a tlačítko je vypnuté.
     */
    private static HBox reminderRow(Stage stage, ActionRecord record) {
        java.time.Instant deadline = record.verifiableUntil();
        String name = record.output != null ? record.output.name() : null;
        // Plná cesta k výslednému souboru, je-li známa (pokyn uživatele 5. 10. 2026).
        File output = record.output == null ? null
                : record.output.path() != null ? new File(record.output.path())
                : new File(record.output.name());
        HBox row = CalendarReminder.row(CalendarReminder.fileName(name),
                days -> CalendarReminder.afterAction(record.mode.noun, output, deadline, days,
                        java.time.Instant.now()));
        if (deadline == null) {
            row.setDisable(true);
            ((javafx.scene.control.Label) row.getChildren().get(0)).setText(
                    "Připomínka do kalendáře: datum, do kdy je dokument ověřitelný,"
                    + " se stanovit nepodařilo");
        }
        row.setPadding(new Insets(8, 12, 4, 12));
        return row;
    }

    /**
     * Uloží záznam jako protokol PDF/A — stejnou sazbou a stejným zápisem jako ostatní protokoly,
     * včetně náhradního běžného PDF, nedá-li se PDF/A vytvořit.
     *
     * <p>Jméno nese předponu {@code ZU} vedle {@code ZP}, {@code RP} a {@code DA} ostatních
     * protokolů a za ní jméno výsledného souboru, o kterém záznam vypovídá.
     */
    static void save(Stage owner, ActionRecord record, boolean timestamp,
                     PrintUtil.ProtocolExtras extras) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Uložit záznam o úkonu");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PDF soubory (*.pdf)", "*.pdf"));
        String outName = record.output != null ? record.output.name() : null;
        chooser.setInitialFileName(PrintUtil.protocolFileName("ZU",
                outName != null ? new File(outName) : null));
        File target = chooser.showSaveDialog(owner);
        if (target == null) return;

        String fo = foOf(record);
        Thread worker = new Thread(() -> {
            try {
                PrintUtil.Outcome outcome = write(fo, target, timestamp, extras);
                if (!outcome.archival()) Platform.runLater(() -> PrintUtil.showNotPdfA(owner));
                if (outcome.stampFailure() != null) {
                    Platform.runLater(() -> PrintUtil.showStampFailure(owner,
                            outcome.stampFailure()));
                }
            } catch (Exception ex) {
                Platform.runLater(() -> PrintUtil.showExportFailure(owner, ex, target));
            }
        }, "action-record-export");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Sazba záznamu. Prochází scénu, takže na vlákně JavaFX; z nového stromu, ne z toho v okně —
     * uzel smí mít jen jednoho rodiče a okno ho dál ukazuje.
     */
    static String foOf(ActionRecord record) {
        return PrintUtil.buildFo(record.content(), null, null, record.title(), null, null);
    }

    /** Zápis do souboru, mimo vlákno JavaFX. Odděleno od výběru souboru kvůli sondě. */
    static PrintUtil.Outcome write(String fo, File target, boolean timestamp,
                                   PrintUtil.ProtocolExtras extras) throws Exception {
        Thread.currentThread().setContextClassLoader(
                org.apache.fop.apps.FopFactory.class.getClassLoader());
        return PrintUtil.writeProtocol(fo.getBytes(StandardCharsets.UTF_8), target,
                new PrintUtil.ProtocolOptions(false, timestamp), List.of(), extras);
    }
}
