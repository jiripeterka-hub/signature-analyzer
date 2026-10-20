package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.FixationService.Reason;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Offered when AnEP cannot read the format at all. Validation is off the table, but fixing the
 * bytes in time is not, and that is worth something on its own — so instead of a dead end with an
 * error in the status line, the file gets this window.
 *
 * <p>It also takes further files. The material an expert is handed rarely arrives as one file: a
 * contract with its annexes, a message with what came attached to it. Stamping those separately
 * would fix each at its own moment and say nothing about them belonging together, so they go into
 * one package.
 */
public final class ForensicPackageDialog {

    /** Window width minus its padding — the width the wrapping paragraphs have to respect. */
    private static final double CONTENT_WIDTH = 584;

    /** Border the list wears while files are held over it, in the slate of the create button. */
    private static final String LIST_DROP_HOVER =
            "-fx-border-color: #455a64; -fx-border-width: 2; -fx-border-radius: 3;";

    private ForensicPackageDialog() { }

    public static void show(Window owner, List<File> initialFiles, String unreadableName,
                            Reason reason, FixationService service, AppSettings settings) {
        Stage stage = new Stage();
        stage.setTitle("Forenzní balíček");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(true);

        VBox root = new VBox(12);
        root.setPadding(new Insets(18));
        root.setPrefWidth(620);
        root.setPrefHeight(460);

        File subject = initialFiles.isEmpty() ? null : initialFiles.get(0);
        String format = formatName(unreadableName);
        String headlineText;
        String lead;
        switch (reason) {
            case OFFICE_UNSIGNED -> {
                headlineText = "Soubor \"" + unreadableName + "\" není podepsaný";
                lead = "Podepsané dokumenty " + format + " aplikace zpracuje, v tomto ale žádný"
                        + " podpis nenašla — není tedy co ověřovat ani rozebírat.";
            }
            case OFFICE_SIGNED -> {
                headlineText = "Podpis v souboru \"" + unreadableName + "\" aplikace neověřuje";
                lead = "Podpis v dokumentu " + format + " aplikace jen rozebrala; jeho platnost"
                        + " ověřit nelze.";
            }
            default -> {
                headlineText = "Formát souboru \"" + unreadableName + "\" aplikace nepodporuje";
                lead = "Ověřit podpis v něm tedy nelze.";
            }
        }

        Label headline = new Label(headlineText);
        headline.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #111111;");
        wrapping(headline);

        Label explain = new Label(
                lead + " Můžete z něj ale vytvořit forenzní balíček:"
                + " soubor se beze změny zabalí do kontejneru ASiC a opatří časovým razítkem."
                + " Razítko doloží, že soubor v této podobě existoval v tuto chvíli — nic víc,"
                + " a nic o platnosti podpisu.\n\n"
                + "Do jednoho balíčku můžete přidat i další soubory, patří-li k sobě — tlačítkem"
                + " níže, nebo je přetáhněte do seznamu.");
        explain.setStyle("-fx-text-fill: #444444;");
        wrapping(explain);

        ListView<File> list = new ListView<>();
        list.getItems().addAll(initialFiles);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(File f, boolean empty) {
                super.updateItem(f, empty);
                if (empty || f == null) { setText(null); return; }
                setText(f.getName() + "   (" + ContainerInspector.formatSize(f.length()) + ")");
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Button addBtn = new Button("Přidat soubory…");
        addBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Přidat soubory do balíčku");
            File first = list.getItems().isEmpty() ? null : list.getItems().get(0);
            if (first != null && first.getParentFile() != null && first.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(first.getParentFile());
            }
            addFiles(list, chooser.showOpenMultipleDialog(stage));
        });

        Button removeBtn = new Button("Odebrat");
        removeBtn.setOnAction(e ->
                list.getItems().removeAll(new ArrayList<>(list.getSelectionModel().getSelectedItems())));

        HBox listButtons = new HBox(8, addBtn, removeBtn);

        // One flag drives everything that has to change while the package is being built. It has to
        // be a property and not a set of setDisable() calls: the buttons' disable state is bound to
        // the selection and to the list being non-empty, and a bound property refuses to be set —
        // "Button.disable : A bound value cannot be set." was thrown inside the click handler right
        // after the spinner appeared, so the task was never even created and the window sat there
        // spinning for good.
        javafx.beans.property.BooleanProperty busy =
                new javafx.beans.property.SimpleBooleanProperty(false);

        // Dropping onto the list does the same as the button. The main window takes files this way,
        // so a second window that only accepted them through a file chooser would be a step back.
        // Refused while the package is being built: the list is already snapshotted by then and a
        // file added now would look included when it is not.
        list.setOnDragOver(e -> {
            if (!busy.get() && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                list.setStyle(LIST_DROP_HOVER);
            }
            e.consume();
        });
        list.setOnDragExited(e -> {
            list.setStyle("");
            e.consume();
        });
        list.setOnDragDropped(e -> {
            boolean accepted = false;
            if (!busy.get() && e.getDragboard().hasFiles()) {
                addFiles(list, e.getDragboard().getFiles());
                accepted = true;
            }
            list.setStyle("");
            e.setDropCompleted(accepted);
            e.consume();
        });

        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(18, 18);
        progress.visibleProperty().bind(busy);
        progress.managedProperty().bind(busy);

        addBtn.disableProperty().bind(busy);
        removeBtn.disableProperty().bind(
                list.getSelectionModel().selectedItemProperty().isNull().or(busy));

        Button createBtn = new Button("Vytvořit balíček");
        createBtn.setDefaultButton(true);
        createBtn.setStyle(
                "-fx-font-weight: bold; -fx-text-fill: white;"
                + " -fx-background-color: linear-gradient(to bottom, #455a64, #263238);"
                + " -fx-border-color: #263238; -fx-border-radius: 5; -fx-background-radius: 5;"
                + " -fx-padding: 6 18 6 18; -fx-cursor: hand;");
        Button closeBtn = new Button("Zavřít");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, progress, spacer, closeBtn, createBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        // A package without a time-stamp is just a zip, so the button stays off until there is a
        // TSA to ask — and says which setting turns it on rather than failing at the last step.
        TsaProfile profile = settings.getSelectedTsa();
        Label noTsa = new Label("Není vybrána autorita časových razítek — nastavte ji v Nastavení,"
                + " oddíl Zdroj časových razítek.");
        noTsa.setStyle("-fx-text-fill: #8a6d00; -fx-font-size: 11px;");
        wrapping(noTsa);
        noTsa.setVisible(profile == null);
        noTsa.setManaged(profile == null);

        if (profile == null) {
            createBtn.setDisable(true);
        } else {
            createBtn.setOnAction(e ->
                    create(stage, list.getItems(), profile, subject, reason, service, busy, settings));
            createBtn.disableProperty().bind(
                    javafx.beans.binding.Bindings.isEmpty(list.getItems()).or(busy));
        }

        root.getChildren().addAll(headline, explain, list, listButtons, noTsa, actions);
        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    private static void create(Stage stage, List<File> files, TsaProfile profile,
                               File subject, Reason initialReason, FixationService service,
                               javafx.beans.property.BooleanProperty busy,
                               AppSettings settings) {
        DigestAlgorithm alg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());
        List<File> snapshot = new ArrayList<>(files);
        // Důvod platí pro předložený soubor. Odebral-li ho uživatel ze seznamu, o zbylých
        // souborech nic neříká a balíček pak nesmí tvrdit, že je „formát nepodporovaný“.
        boolean subjectIn = subject != null && snapshot.stream().anyMatch(f -> sameFile(f, subject));
        File packSubject = subjectIn ? subject : null;
        Reason reason = subjectIn ? initialReason : null;

        busy.set(true);

        Task<FixationService.FixationResult> task = new Task<>() {
            @Override protected FixationService.FixationResult call() {
                return service.fixFiles(snapshot, packSubject, reason, profile, alg);
            }
        };

        task.setOnSucceeded(e -> {
            busy.set(false);

            File firstFile = snapshot.get(0);
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Uložit forenzní balíček");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Kontejnery ASiC-E", "*.asice"));
            String origName = firstFile.getName();
            int dot = origName.lastIndexOf('.');
            String baseName = dot > 0 ? origName.substring(0, dot) : origName;
            // Stejné jméno jako u zafixování z Analýzy — typ zabaleného dokumentu a za ním datum,
            // do kdy platí certifikát razítka, tedy dokdy balíček obnovit. Viz
            // AdvisorView.fixationTypeMark() a fixationSuffix().
            //
            // Typ se bere z prvního souboru, jako celé jméno: balíček může nést i soubory dalších
            // typů, ale jméno má být krátké a řídí se tím, co je v balíčku první.
            //
            // Skutečnost z razítka zároveň opraví, co si o zdroji pamatuje nastavení; nedá-li se
            // z razítka přečíst, poslouží zapamatovaná hodnota, aby jméno o datum nepřišlo.
            java.util.Date fromStamp = task.getValue().tsaCertNotAfter();
            if (fromStamp != null) {
                settings.rememberTsaCertNotAfter(profile, fromStamp);
            }
            java.util.Date until = fromStamp != null ? fromStamp : profile.getCertNotAfter();
            chooser.setInitialFileName("FB_" + AdvisorView.fixationBaseName(baseName)
                    + AdvisorView.fixationTypeMark(origName)
                    + AdvisorView.fixationSuffix(until,
                            profile.getQuality() == TsaProfile.Quality.UNQUALIFIED)
                    + ".asice");
            if (firstFile.getParentFile() != null && firstFile.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(firstFile.getParentFile());
            }
            File dest = chooser.showSaveDialog(stage);
            if (dest == null) return;

            try (OutputStream os = Files.newOutputStream(dest.toPath())) {
                task.getValue().container().writeTo(os);
            } catch (Exception ex) {
                error(stage, "Nepodařilo se uložit forenzní balíček", ex.getMessage());
                return;
            }

            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle("Forenzní balíček");
            done.setHeaderText("Forenzní balíček byl vytvořen a opatřen časovým razítkem");
            done.setContentText("Obsahuje " + snapshot.size()
                    + (snapshot.size() == 1 ? " soubor" : snapshot.size() <= 4 ? " soubory" : " souborů")
                    + " beze změny, popis a soupis s otisky.\n\n"
                    + "Ověření neproběhlo" + (reason == null ? "." : switch (reason) {
                        case OFFICE_UNSIGNED -> " — dokument není podepsaný.";
                        case OFFICE_SIGNED -> " — podpis v tomto formátu aplikace neověřuje.";
                        default -> " — formát aplikace nepodporuje.";
                    }) + " Razítko dokládá, že"
                    + " soubory v této podobě existovaly v tuto chvíli, a nic dalšího.");
            done.initOwner(stage);
            done.showAndWait();
            stage.close();
        });

        task.setOnFailed(e -> {
            busy.set(false);
            Throwable ex = task.getException();
            error(stage, "Vytvoření forenzního balíčku selhalo",
                    ex != null ? ex.getMessage() : "Neznámá chyba");
        });

        Thread thread = new Thread(task, "forensic-package");
        thread.setDaemon(true);
        thread.start();
    }

    private static void error(Stage owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.initOwner(owner);
        alert.showAndWait();
    }

    /**
     * Makes a label wrap for real. {@code setWrapText(true)} alone is not enough inside a VBox: the
     * label's preferred height is computed from an unconstrained width, so it comes out one line
     * tall and the text is then ellipsised at the column the box happens to give it. Capping the
     * width is what makes the height follow the wrapping — measured here on the explanatory
     * paragraph, which showed as a single line ending in "…".
     */
    private static void wrapping(Label label) {
        label.setWrapText(true);
        label.setMaxWidth(CONTENT_WIDTH);
        label.setMinHeight(Region.USE_PREF_SIZE);
    }

    /**
     * Adds what is not in the list yet. The same file twice would go into the container twice; it
     * tolerates that, but the reader would only wonder which of the two is which.
     *
     * <p>Directories are skipped. A drop can carry one, and packaging a whole tree is a different
     * thing from packaging the files somebody chose — it would also quietly pull in whatever else
     * happens to sit in the folder.
     */
    private static void addFiles(ListView<File> list, List<File> files) {
        if (files == null) return;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            if (list.getItems().stream().noneMatch(x -> sameFile(x, f))) list.getItems().add(f);
        }
    }

    /** „ve formátu DOCX“ podle přípony; bez přípony jen „v tomto formátu“. */
    private static String formatName(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "v tomto formátu";
        return "ve formátu " + fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Exception e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }
}
