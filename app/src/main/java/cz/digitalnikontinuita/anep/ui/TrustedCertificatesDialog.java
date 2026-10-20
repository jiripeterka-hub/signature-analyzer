package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.CustomTrustStore;
import cz.digitalnikontinuita.anep.core.CzechCommercialCATrust;
import eu.europa.esig.dss.model.x509.CertificateToken;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * Lets the user add or remove certificates from the app's own trust store
 * ({@link CustomTrustStore}), used in addition to the EU Trusted Lists (LOTL) during
 * validation — e.g. for an internal/company CA, or any CA not covered by EUTL.
 */
public class TrustedCertificatesDialog {


    public static void show(Window owner) {
        Stage stage = new Stage();
        stage.setTitle("Vlastní důvěryhodné certifikáty");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        root.setPrefWidth(560);
        root.setPrefHeight(480);

        Label hint = new Label(
                "Certifikáty v tomto seznamu se při ověřování považují za důvěryhodné navíc " +
                "k důvěryhodným seznamům EU (EUTL) — např. interní firemní CA nebo jiná " +
                "autorita, která není součástí EUTL. Změny se projeví ihned, bez nutnosti " +
                "restartu aplikace.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill:#666666;-fx-font-size:11px;");

        ListView<CustomTrustStore.Entry> list = new ListView<>();
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CustomTrustStore.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    return;
                }
                String name = CustomTrustStore.displayName(entry.certificate());
                java.util.Date notAfter = entry.certificate().getCertificate().getNotAfter();
                setText(name + "   (platnost do "
                        + cz.digitalnikontinuita.anep.core.TimeFormat.full(notAfter) + ")");
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                CustomTrustStore.Entry sel = list.getSelectionModel().getSelectedItem();
                if (sel != null) openCertViewer(sel.certificate());
            }
        });
        refreshList(list);

        Button btnAdd = new Button("Přidat certifikát...");
        btnAdd.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Vyberte soubor s certifikátem");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Certifikáty (*.cer, *.crt, *.pem, *.der)",
                            "*.cer", "*.crt", "*.pem", "*.der"),
                    new FileChooser.ExtensionFilter("Všechny soubory", "*.*"));
            java.io.File chosen = chooser.showOpenDialog(stage);
            if (chosen == null) return;
            try {
                CustomTrustStore.addCertificate(chosen);
                refreshList(list);
            } catch (Exception ex) {
                showError("Certifikát se nepodařilo přidat:\n" + ex.getMessage());
            }
        });

        Button btnRemove = new Button("Odebrat vybraný");
        btnRemove.setOnAction(e -> {
            CustomTrustStore.Entry sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            try {
                CustomTrustStore.removeCertificate(sel.file());
                refreshList(list);
            } catch (Exception ex) {
                showError("Certifikát se nepodařilo odebrat:\n" + ex.getMessage());
            }
        });

        // Vkládají se vydávající autority, ne kořeny. Kotvou důvěry může být kterýkoli
        // certifikát, u něhož lze řetěz ukončit — nemusí být self-signed. Kořen by kryl i to,
        // co pod ním kdy vznikne, kdežto tohle je rozhodnutí důvěřovat jedné konkrétní
        // vydávající autoritě. Viz javadoc CzechCommercialCATrust.
        Button btnBulkImport = new Button("Vložit certifikáty komerčních autorit (I.CA, PostSignum, eIdentity, NCA, KB)");
        btnBulkImport.setOnAction(e -> {
            int failed = 0;
            for (CertificateToken issuingCa : CzechCommercialCATrust.loadCommercialIssuingCAs()) {
                try {
                    CustomTrustStore.addCertificateBytes(issuingCa.getCertificate().getEncoded());
                } catch (Exception ex) {
                    failed++;
                }
            }
            refreshList(list);
            if (failed > 0) {
                showError(failed + " certifikát(ů) se nepodařilo vložit — zkuste to prosím znovu.");
            }
        });
        Label bulkImportHint = new Label(
                "Vloží certifikáty vydávajících (mezilehlých) autorit komerčních linek I.CA, " +
                "PostSignum, eIdentity, NCA (SSSVD) a Komerční banky, jejichž komerční " +
                "(nekvalifikované) certifikáty nejsou " +
                "součástí EUTL. Vkládají se záměrně vydávající autority, ne kořeny: důvěra se tím " +
                "omezí právě na ně a nevztáhne se na vše ostatní pod týmž kořenem. U vložené " +
                "autority se ale přestane zjišťovat revokace a při její obměně je nutné seznam " +
                "doplnit. Lze spustit opakovaně — již vložené certifikáty se nezdvojí.");
        bulkImportHint.setWrapText(true);
        bulkImportHint.setStyle("-fx-text-fill:#666666;-fx-font-size:11px;");

        Button btnClose = new Button("Zavřít");
        btnClose.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, btnAdd, btnRemove, new javafx.scene.layout.Region(), btnClose);
        HBox.setHgrow(buttons.getChildren().get(2), Priority.ALWAYS);

        root.getChildren().addAll(hint, list, buttons,
                new javafx.scene.control.Separator(), btnBulkImport, bulkImportHint);

        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    /** Opens the certificate in the OS's standard certificate viewer (poklepání = double-click). */
    /** Vlastní prohlížeč, ne systémový — totéž zdůvodnění jako v Detailním rozboru. */
    private static void openCertViewer(CertificateToken cert) {
        byte[] der = cert != null ? cert.getEncoded() : null;
        if (der == null || der.length == 0) return;
        ElementTextView.show("Certifikát",
                cz.digitalnikontinuita.anep.core.ContainerInspector.describeCertificate(der),
                der, "certifikat.cer", "Certifikát (*.cer)", "*.cer", null);
    }

    private static void refreshList(ListView<CustomTrustStore.Entry> list) {
        List<CustomTrustStore.Entry> entries = CustomTrustStore.listCertificates();
        list.getItems().setAll(entries);
    }

    private static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
