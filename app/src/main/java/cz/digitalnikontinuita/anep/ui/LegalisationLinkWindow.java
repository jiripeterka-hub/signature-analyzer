package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Check;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Digest;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.DigestCheck;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Outcome;
import cz.digitalnikontinuita.anep.core.PdfSource;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Checks the reference a legalised document carries to the document it was made from.
 *
 * <p>eLegalizace at CzechPOINT does not put the referenced document into the file — only its
 * digests, in an attachment. So the reference cannot be confirmed from the verified document
 * alone, however thoroughly it is validated; the other document has to be laid next to it. This
 * window is where that happens, and it opens by itself after verification because a reader who
 * does not know the attachment is there would never think to ask.
 *
 * <p>The check is about the link, not about signatures: agreeing digests say the two files belong
 * together and nothing whatever about whether either of them is validly signed. That is why this
 * stays a window of its own rather than becoming a line in the result.
 *
 * <p>The referenced document may carry a reference of its own — a legalisation record points at
 * the legalised document, which can itself have been made from a third one — so a checked file
 * that turns out to hold the same attachment starts the same window again.
 */
public final class LegalisationLinkWindow {

    /** Dictated wording; the heading of the window and the whole of its explanation. */
    static final String INTRO =
            "V předloženém dokumentu je obsažen odkaz na jiný dokument, způsobem který se používá "
            + "při eLegalizaci na CzechPOINTu (dle § 6 odst. 1 písm. a) zákona č. 12/2020 Sb. "
            + "o právu na digitální služby). Pokud chcete zkontrolovat správnost tohoto odkazu, "
            + "vložte níže ten dokument, na který by mělo být odkazováno.";

    private static final String ZONE_IDLE =
            "-fx-border-color: #aaaaaa; -fx-border-radius: 4; -fx-border-style: dashed; "
            + "-fx-background-radius: 4; -fx-background-color: #f8f8f8;";
    private static final String ZONE_HOVER =
            "-fx-border-color: #0078d7; -fx-border-radius: 4; -fx-border-style: solid; "
            + "-fx-background-radius: 4; -fx-background-color: #e6f2ff;";
    private static final String DROP_PLACEHOLDER =
            "Přetáhněte sem dokument PDF, nebo klikněte pro výběr";

    // The three colours the result cards use for their status stripe, and for the same reason:
    // the reader has just seen them there and should not have to learn a second palette.
    static final String GREEN = "#1a7a1a";
    static final String RED = "#cc0000";
    static final String AMBER = "#9a6700";

    /**
     * One window per document. Verifying the same file twice — which a re-check under relaxed
     * settings does by itself — would otherwise stack windows the reader never asked for.
     */
    private static final Map<String, Stage> OPEN = new HashMap<>();

    private LegalisationLinkWindow() { }

    /**
     * Opens the window for a document whose attachment holds a reference.
     *
     * @param sourceDoc the document the reference was found in; its name and folder are used
     * @param link      what its attachment says
     */
    public static void show(Window owner, File sourceDoc, Link link) {
        if (link == null || sourceDoc == null) return;

        String key = sourceDoc.getAbsolutePath();
        Stage already = OPEN.get(key);
        if (already != null && already.isShowing()) {
            already.toFront();
            already.requestFocus();
            return;
        }

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("Kontrola odkazu na jiný dokument — " + sourceDoc.getName());
        OPEN.put(key, stage);
        stage.setOnHidden(e -> OPEN.remove(key, stage));

        ScrollPane scroll = new ScrollPane(new Panel(stage, sourceDoc, link).content());
        scroll.setFitToWidth(true);
        stage.setScene(new Scene(scroll, 780, 560));
        stage.show();
    }

    /**
     * The window's contents and the one piece of state they share — which file the reader has put
     * in. A class rather than a pile of static methods so that a smoke can do what the reader
     * does: hand it a file, press the button, and look at what came out.
     */
    static final class Panel {

        private final Window owner;
        private final File sourceDoc;
        private final Link link;

        private final Label dropHint = new Label(DROP_PLACEHOLDER);
        private final Button verifyButton = new Button("Ověřit otisky");
        private final VBox results = new VBox(8);
        private final VBox content;

        /** The document the reader has put in, or null while none has been. */
        private File candidate;

        /** Set by the smoke; called on the UI thread once a check has been rendered. */
        private Runnable afterCheck;

        Panel(Window owner, File sourceDoc, Link link) {
            this.owner = owner;
            this.sourceDoc = sourceDoc;
            this.link = link;

            Label intro = new Label(INTRO);
            intro.setWrapText(true);
            intro.setStyle("-fx-font-size: 13px; -fx-text-fill: #222222;");

            VBox reference = new VBox(2);
            reference.setPadding(new Insets(8, 12, 8, 12));
            reference.setStyle("-fx-background-color: #f2f6fa; -fx-background-radius: 4; "
                    + "-fx-border-color: #cdd8e3; -fx-border-radius: 4;");
            reference.getChildren().add(detail("Odkaz je obsažen v dokumentu", sourceDoc.getName()));
            if (link.attachmentName() != null) {
                reference.getChildren().add(detail("Příloha s odkazem", link.attachmentName()));
            }
            if (link.targetName() != null) {
                reference.getChildren().add(detail("Název odkazovaného dokumentu", link.targetName()));
            }
            if (link.guid() != null) {
                reference.getChildren().add(detail("Identifikátor (guid)", link.guid()));
            }
            if (link.type() != null) reference.getChildren().add(detail("Typ záznamu", link.type()));
            reference.getChildren().add(detail("Uvedené otisky",
                    link.digests().stream().map(Digest::function).collect(Collectors.joining(", "))));

            Label dropTitle = new Label("Odkazovaný dokument");
            dropTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #222222;");
            dropHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
            dropHint.setWrapText(true);
            VBox dropZone = new VBox(4, dropTitle, dropHint);
            dropZone.setPadding(new Insets(10, 12, 10, 12));
            dropZone.setStyle(ZONE_IDLE);
            dropZone.setCursor(Cursor.HAND);
            dropZone.setOnMouseClicked(e -> choose());
            dropZone.setOnDragOver(e -> {
                if (e.getDragboard().hasFiles()) {
                    dropZone.setStyle(ZONE_HOVER);
                    e.acceptTransferModes(TransferMode.COPY);
                }
                e.consume();
            });
            dropZone.setOnDragExited(e -> dropZone.setStyle(ZONE_IDLE));
            dropZone.setOnDragDropped(e -> {
                var db = e.getDragboard();
                boolean ok = db.hasFiles() && !db.getFiles().isEmpty();
                // One document is asked for and one is checked; further files in the same drop
                // have nowhere to go, so taking the first is the whole of what can be meant.
                if (ok) accept(db.getFiles().get(0));
                dropZone.setStyle(ZONE_IDLE);
                e.setDropCompleted(ok);
                e.consume();
            });

            verifyButton.setDisable(true);
            verifyButton.setOnAction(e -> verify());
            HBox buttons = new HBox(8, verifyButton);
            buttons.setAlignment(Pos.CENTER_LEFT);

            content = new VBox(12, intro, reference, dropZone, buttons, new Separator(), results);
            content.setPadding(new Insets(14));
        }

        VBox content() {
            return content;
        }

        VBox results() {
            return results;
        }

        Button verifyButton() {
            return verifyButton;
        }

        void afterCheck(Runnable runnable) {
            this.afterCheck = runnable;
        }

        private void choose() {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Vyberte dokument, na který má být odkazováno");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PDF dokumenty", "*.pdf"));
            // The referenced document is nearly always a neighbour of the one referring to it.
            File dir = sourceDoc.getParentFile();
            if (dir != null && dir.isDirectory()) chooser.setInitialDirectory(dir);
            if (link.targetName() != null) chooser.setInitialFileName(link.targetName());
            File file = chooser.showOpenDialog(owner);
            if (file != null) accept(file);
        }

        /** Takes the file in, or says why it will not — the check is for PDF documents only. */
        void accept(File file) {
            results.getChildren().clear();
            if (file == null || !file.isFile()) return;
            if (!PdfSource.of(file).isPdf()) {
                candidate = null;
                verifyButton.setDisable(true);
                dropHint.setText(DROP_PLACEHOLDER);
                results.getChildren().add(stripe(AMBER, "Soubor " + file.getName()
                        + " není dokument PDF. Kontrola odkazu je určena jen pro dokumenty PDF."));
                return;
            }
            candidate = file;
            verifyButton.setDisable(false);
            dropHint.setText(file.getName() + "  (" + size(file.length()) + ")");
        }

        /**
         * Hashes on a background thread. These documents commonly sit in a synced folder, where
         * reading one takes long enough for a window that did it inline to be reported as frozen.
         */
        void verify() {
            final File checked = candidate;
            if (checked == null) return;
            results.getChildren().clear();
            Label working = new Label("Počítám otisky souboru " + checked.getName() + " …");
            working.setStyle("-fx-text-fill: #666666;");
            results.getChildren().add(working);
            verifyButton.setDisable(true);

            Task<Checked> task = new Task<>() {
                @Override protected Checked call() {
                    Check check = LegalisationLinkInspector.verify(link, checked);
                    // Read while we are off the UI thread anyway: whether the checked document
                    // carries a reference of its own decides what the window offers next.
                    return new Checked(check, LegalisationLinkInspector.read(checked, null));
                }
            };
            task.setOnSucceeded(e -> {
                verifyButton.setDisable(false);
                results.getChildren().clear();
                render(checked, task.getValue());
                if (afterCheck != null) afterCheck.run();
            });
            task.setOnFailed(e -> {
                verifyButton.setDisable(false);
                results.getChildren().clear();
                Throwable ex = task.getException();
                results.getChildren().add(stripe(RED, "Kontrolu se nepodařilo provést"
                        + (ex != null && ex.getMessage() != null ? ": " + ex.getMessage() : ".")));
                if (afterCheck != null) afterCheck.run();
            });
            Thread thread = new Thread(task, "legalisation-link-check");
            thread.setDaemon(true);
            thread.start();
        }

        private void render(File checked, Checked done) {
            Check check = done.check();
            // Zapsat dřív, než se cokoli vykreslí: blok „Odkazované dokumenty z eLegalizace“
            // v Rozšířeném přehledu čte právě odsud a okno se otevírá i před ověřením
            // samotného dokumentu, kdy karta ještě neexistuje.
            cz.digitalnikontinuita.anep.core.LegalisationLinkLog.record(
                    sourceDoc, link, checked, check, done.next());
            switch (check.outcome()) {
                case MATCH -> results.getChildren().add(stripe(GREEN,
                        "Otisky souhlasí — " + checked.getName()
                        + " je ten dokument, na který předložený dokument odkazuje."));
                case MISMATCH -> results.getChildren().add(stripe(RED,
                        "Otisky nesouhlasí — " + checked.getName()
                        + " není ten dokument, na který předložený dokument odkazuje."));
                case UNSUPPORTED -> results.getChildren().add(stripe(AMBER,
                        "Odkaz nebylo možné zkontrolovat: žádnou z uvedených hashovacích funkcí "
                        + "tato aplikace nezná."));
                case ERROR -> results.getChildren().add(stripe(RED,
                        "Soubor se nepodařilo přečíst"
                        + (check.error() != null ? ": " + check.error() : ".")));
            }

            if (check.outcome() == Outcome.MISMATCH) {
                // A failed check has to say what was compared, or the reader has no way to tell a
                // wrong file from a changed one — and those call for different next steps.
                Label note = new Label("Porovnávaly se otisky celého souboru, jak leží na disku. "
                        + "Nesouhlasí-li, je vložený soubor buď jiný dokument, nebo tentýž dokument "
                        + "po nějaké změně — i takové, která se na pohled neprojeví.");
                note.setWrapText(true);
                note.setStyle("-fx-font-size: 12px; -fx-text-fill: #555555;");
                results.getChildren().add(note);
            }

            for (DigestCheck d : check.digests()) results.getChildren().add(digestRow(d));

            if (done.next() == null) return;

            // The checked document carries a reference of its own. On a match the chain is real
            // and the next link opens by itself, exactly as the first one did after verification;
            // on a mismatch it stays behind a button, because a document that is not the one
            // referred to says nothing about where the chain goes on from here.
            if (check.outcome() == Outcome.MATCH) {
                show(owner, checked, done.next());
            } else {
                Button chain = new Button("Zkontrolovat odkaz v tomto dokumentu");
                chain.setOnAction(e -> show(owner, checked, done.next()));
                HBox row = new HBox(chain);
                row.setAlignment(Pos.CENTER_LEFT);
                results.getChildren().add(row);
            }
        }
    }

    private record Checked(Check check, Link next) { }

    /** One declared digest: whether it agreed, and with what. */
    private static VBox digestRow(DigestCheck d) {
        String verdict;
        String colour;
        if (!d.supported()) {
            verdict = "tuto hashovací funkci aplikace nezná — neporovnáno";
            colour = AMBER;
        } else if (d.matches()) {
            verdict = "souhlasí";
            colour = GREEN;
        } else {
            verdict = "nesouhlasí";
            colour = RED;
        }
        Label head = new Label(d.function() + ": " + verdict);
        head.setStyle("-fx-font-weight: bold; -fx-text-fill: " + colour + ";");

        VBox box = new VBox(2, head);
        box.getChildren().add(hex("v dokumentu uvedeno", d.expected()));
        // The computed value is worth showing only where it differs; printing a matching one
        // twice under itself is noise.
        if (d.supported() && !d.matches()) {
            box.getChildren().add(hex("spočteno ze souboru", d.actual()));
        }
        box.setPadding(new Insets(2, 0, 2, 0));
        return box;
    }

    private static VBox hex(String label, String value) {
        Label caption = new Label(label + ":");
        caption.setStyle("-fx-font-size: 11px; -fx-text-fill: #777777;");
        VBox box = new VBox(0, caption, ResultView.selectable(value == null ? "" : value,
                "-fx-font-size: 11px; -fx-font-family: 'Consolas', 'Courier New', monospace;"));
        box.setPadding(new Insets(0, 0, 0, 12));
        return box;
    }

    /** The wide coloured band the result cards use to state a verdict without being read. */
    static HBox stripe(String colour, String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        // A wrapping Label in an HBox needs a width to wrap against, or it stretches the row
        // instead of breaking the line.
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: white; -fx-font-size: 13px;");
        HBox.setHgrow(label, Priority.ALWAYS);
        HBox row = new HBox(label);
        row.setStyle("-fx-background-color: " + colour + "; -fx-padding: 8 12 8 12;");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static HBox detail(String label, String value) {
        Label name = new Label(label + ":");
        name.setStyle("-fx-font-size: 12px; -fx-text-fill: #555555;");
        name.setMinWidth(200);
        Label content = new Label(value);
        content.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
        HBox row = new HBox(8, name, content);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f kB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
