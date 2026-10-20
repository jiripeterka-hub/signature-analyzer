package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.CustomCrlStore;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Lets the user add or remove CRL files from {@link CustomCrlStore}. Stored CRLs are used
 * automatically as a last-resort fallback when online revocation sources return nothing.
 */
public class CustomCrlDialog {

    public static void show(Window owner) {
        Stage stage = new Stage();
        stage.setTitle("Vlastní CRL seznamy");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        root.setPrefWidth(620);
        root.setPrefHeight(420);

        Label hint = new Label(
                "CRL seznamy v tomto úložišti se automaticky použijí jako záložní zdroj " +
                "revokačních informací — tehdy, kdy se nepodaří získat použitelné revokační " +
                "informace standardními prostředky (online OCSP ani online CRL). Platí jen " +
                "pro certifikát, jehož vydavatel odpovídá vydavateli daného CRL seznamu.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill:#666666;-fx-font-size:11px;");

        ListView<CustomCrlStore.Entry> list = new ListView<>();
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CustomCrlStore.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : CustomCrlStore.displayName(entry.crl()));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                CustomCrlStore.Entry sel = list.getSelectionModel().getSelectedItem();
                if (sel != null) openCrlViewer(sel.file());
            }
        });
        refreshList(list);

        Button btnAdd = new Button("Přidat CRL soubor...");
        btnAdd.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Vyberte soubor CRL");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("CRL seznamy (*.crl)", "*.crl"),
                    new FileChooser.ExtensionFilter("Všechny soubory", "*.*"));
            File chosen = chooser.showOpenDialog(stage);
            if (chosen == null) return;
            try {
                CustomCrlStore.addCrl(chosen);
                refreshList(list);
            } catch (Exception ex) {
                showError("CRL seznam se nepodařilo přidat:\n" + ex.getMessage());
            }
        });

        Button btnRemove = new Button("Odebrat vybraný");
        btnRemove.setOnAction(e -> {
            CustomCrlStore.Entry sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            try {
                CustomCrlStore.removeCrl(sel.file());
                refreshList(list);
            } catch (Exception ex) {
                showError("CRL seznam se nepodařilo odebrat:\n" + ex.getMessage());
            }
        });

        Button btnClose = new Button("Zavřít");
        btnClose.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, btnAdd, btnRemove, new javafx.scene.layout.Region(), btnClose);
        HBox.setHgrow(buttons.getChildren().get(2), Priority.ALWAYS);

        root.getChildren().addAll(hint, list, buttons);

        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    /**
     * Seznam CRL se otevírá ve <b>vlastním</b> prohlížeči, ne v systémovém: ten ukáže soubor,
     * kdežto čtenář se ptá na jeden z tisíců záznamů v něm. Vidí hlavičku seznamu i tabulku
     * odvolaných certifikátů a může seznam rovnou uložit.
     */
    private static void openCrlViewer(File crlFile) {
        try {
            CrlView.show(Files.readAllBytes(crlFile.toPath()), null, null, null);
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR,
                    "Seznam CRL se nepodařilo načíst: " + e.getMessage()).showAndWait();
        }
    }

    private static void refreshList(ListView<CustomCrlStore.Entry> list) {
        List<CustomCrlStore.Entry> entries = CustomCrlStore.listCrls();
        list.getItems().setAll(entries);
    }

    private static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
