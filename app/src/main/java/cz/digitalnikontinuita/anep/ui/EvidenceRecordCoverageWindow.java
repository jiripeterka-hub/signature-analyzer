package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.DocumentLoader;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.ReferenceValidation;
import eu.europa.esig.dss.spi.validation.analyzer.evidencerecord.EvidenceRecordAnalyzer;
import eu.europa.esig.dss.spi.validation.analyzer.evidencerecord.EvidenceRecordAnalyzerFactory;
import eu.europa.esig.dss.spi.x509.evidencerecord.EvidenceRecord;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Checks further documents against an evidence record that covers more than the one file being
 * verified, and offers to verify each of them in AnEP with the same record.
 *
 * <p>A group record protects several documents with one hash tree, but AnEP verifies one
 * document at a time — mixing several into a single result would leave the reader guessing which
 * authentication element belongs to which file. So the extra documents are checked here instead,
 * against the record's digests only, and anything the reader wants a full verdict on goes back
 * through the normal single-document flow with the same record attached.
 */
public final class EvidenceRecordCoverageWindow {

    /** Set by MainView: loads the given document plus the evidence record and verifies them. */
    private static BiConsumer<File, File> verifyInAnepHandler;

    public static void setVerifyInAnepHandler(BiConsumer<File, File> handler) {
        verifyInAnepHandler = handler;
    }

    private EvidenceRecordCoverageWindow() { }

    /**
     * @param recordFile   the evidence record the caller already had verified
     * @param checkedCount how many of the record's covered objects the main verification
     *                     actually accounted for. Deliberately a count of verified digests
     *                     rather than the length of the covered-object name list: DSS puts a
     *                     scope entry such as "Full PDF" into that list beside the file names,
     *                     so its size overstates how much was checked — which is exactly the
     *                     wrong direction for a number telling the reader what is left to do.
     */
    public static void show(Window owner, File recordFile, int checkedCount, int totalCovered) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("Kontrola dalších souborů oproti evidenčnímu záznamu");

        VBox results = new VBox(6);

        int outstanding = Math.max(0, totalCovered - checkedCount);
        Label intro = new Label(
                "Evidenční záznam " + recordFile.getName() + " kryje "
                + CzechPlural.objects(totalCovered) + "; při ověření dokumentu "
                + CzechPlural.form(checkedCount,
                        "byl zkontrolován", "byly zkontrolovány", "bylo zkontrolováno")
                + " " + CzechPlural.objects(checkedCount) + ", "
                + CzechPlural.form(outstanding, "zbývá", "zbývají", "zbývá")
                + " " + CzechPlural.objects(outstanding) + ".\n"
                + "Zde můžete předložit další soubory a nechat je porovnat s otisky v záznamu. "
                + "Kontroluje se pouze shoda otisku — o podpisech v těchto souborech to nevypovídá nic; "
                + "u kteréhokoli z nich lze plné ověření spustit tlačítkem vedle výsledku.");
        intro.setWrapText(true);
        intro.setStyle("-fx-text-fill: #333333;");

        Label dropTitle = new Label("Soubory ke kontrole");
        dropTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #222222;");
        Label dropHint = new Label(DROP_PLACEHOLDER);
        dropHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        dropHint.setWrapText(true);

        VBox dropZone = new VBox(4, dropTitle, dropHint);
        dropZone.setPadding(new Insets(8, 12, 8, 12));
        dropZone.setStyle(ZONE_IDLE);
        dropZone.setCursor(javafx.scene.Cursor.HAND);

        Runnable pick = () -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Vyberte soubory kryté evidenčním záznamem");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Všechny soubory", "*.*"));
            // Start where the record itself lives: the files it covers are almost always its
            // neighbours, so the common case needs no navigation at all.
            File dir = recordFile.getParentFile();
            if (dir != null && dir.isDirectory()) chooser.setInitialDirectory(dir);
            List<File> chosen = chooser.showOpenMultipleDialog(stage);
            if (chosen != null) addAll(results, recordFile, chosen);
        };

        dropZone.setOnMouseClicked(e -> pick.run());
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
            if (ok) addAll(results, recordFile, db.getFiles());
            dropZone.setStyle(ZONE_IDLE);
            e.setDropCompleted(ok);
            e.consume();
        });

        VBox content = new VBox(10, intro, dropZone, new Separator(), results);
        content.setPadding(new Insets(12));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        stage.setScene(new Scene(scroll, 760, 460));
        stage.show();
    }

    private static final String ZONE_IDLE =
            "-fx-border-color: #aaaaaa; -fx-border-radius: 4; -fx-border-style: dashed; "
            + "-fx-background-radius: 4; -fx-background-color: #f8f8f8;";
    private static final String ZONE_HOVER =
            "-fx-border-color: #0078d7; -fx-border-radius: 4; -fx-border-style: solid; "
            + "-fx-background-radius: 4; -fx-background-color: #e6f2ff;";
    private static final String DROP_PLACEHOLDER =
            "Přetáhněte sem jeden či více souborů, nebo klikněte pro výběr";

    private static void addAll(VBox results, File recordFile, List<File> files) {
        for (File f : files) addOne(results, recordFile, f);
    }

    /**
     * Adds a row for {@code candidate} straight away and fills in the verdict once the check
     * finishes. Hashing happens on a background thread on purpose: the covered files are often
     * large and frequently sit in a synced folder, where reading one can take seconds — long
     * enough for a window that did it inline to be reported as not responding.
     */
    private static void addOne(VBox results, File recordFile, File candidate) {
        Label name = new Label(candidate.getName());
        name.setStyle("-fx-font-weight: bold;");
        name.setMinWidth(240);
        name.setWrapText(true);

        Label verdict = new Label("kontroluji…");
        verdict.setStyle("-fx-text-fill: #666666;");
        verdict.setWrapText(true);
        HBox.setHgrow(verdict, Priority.ALWAYS);

        HBox row = new HBox(8, name, verdict);
        row.setAlignment(Pos.CENTER_LEFT);
        results.getChildren().add(row);

        javafx.concurrent.Task<Match> task = new javafx.concurrent.Task<>() {
            @Override protected Match call() {
                return check(recordFile, candidate);
            }
        };
        task.setOnSucceeded(e -> {
            Match match = task.getValue();
            verdict.setText(match.text());
            verdict.setStyle("-fx-text-fill: " + match.colour() + ";");
            if (match.covered() && verifyInAnepHandler != null) {
                Button verify = new Button("Ověřit v AnEP");
                verify.setOnAction(ev -> verifyInAnepHandler.accept(candidate, recordFile));
                row.getChildren().add(verify);
            }
        });
        task.setOnFailed(e -> {
            verdict.setText("kontrolu se nepodařilo provést");
            verdict.setStyle("-fx-text-fill: #cc0000;");
        });
        Thread t = new Thread(task, "er-coverage-check");
        t.setDaemon(true);
        t.start();
    }

    private record Match(boolean covered, String text, String colour) { }

    /**
     * Runs the record against this one file and reports whether the file's digest is among the
     * ones the record protects. Uses the same DSS path as the main verification rather than
     * hashing by hand: the digest algorithm, and for XMLERS the canonicalisation, have to match
     * exactly what the record used, and DSS already knows both.
     */
    private static Match check(File recordFile, File candidate) {
        try {
            DocumentLoader loader = new DocumentLoader();
            DSSDocument recordDoc = loader.load(recordFile);
            DSSDocument candidateDoc = loader.load(candidate);

            EvidenceRecordAnalyzer analyzer = EvidenceRecordAnalyzerFactory.fromDocument(recordDoc);
            analyzer.setDetachedContents(List.of(candidateDoc));
            EvidenceRecord record = analyzer.getEvidenceRecord();

            List<ReferenceValidation> matched = new ArrayList<>();
            for (ReferenceValidation rv : record.getReferenceValidation()) {
                if (rv.getDocument() != null && rv.isFound() && rv.isIntact()) matched.add(rv);
            }
            if (!matched.isEmpty()) {
                return new Match(true, "kryto záznamem — otisk souhlasí", "#117733");
            }
            boolean anyFound = record.getReferenceValidation().stream().anyMatch(ReferenceValidation::isFound);
            if (anyFound) {
                return new Match(false,
                        "soubor byl nalezen, ale jeho otisk neodpovídá — obsah se od vytvoření záznamu změnil",
                        "#cc0000");
            }
            return new Match(false,
                    "tento soubor evidenční záznam nekryje — žádný z jeho otisků neodpovídá",
                    "#a06000");

        } catch (Exception ex) {
            return new Match(false, "kontrolu se nepodařilo provést: " + ex.getMessage(), "#cc0000");
        }
    }
}
