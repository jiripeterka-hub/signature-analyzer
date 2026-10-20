package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.CertificateList;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.TBSCertList;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Prohlížeč seznamu CRL: hlavička o seznamu a tabulka odvolaných certifikátů.
 *
 * <p>Otevírá se <b>na záznamu certifikátu, o který jde</b> — a to je celý důvod, proč nestačí
 * textový náhled z prohlížeče kontejnerů. Reálná CRL má tisíce záznamů; čtenář, který se ptá
 * „je můj certifikát na seznamu, a od kdy", by je jinak procházel očima. Není-li na seznamu,
 * otevře se na prvním záznamu a řekne to nad tabulkou.
 *
 * <p><b>Hledání přibylo 15. 9. 2026</b>, když se seznam začal otvírat i přímo vloženým souborem.
 * Z odkazu v Analýze přichází {@code highlight} a okno se na hledaném záznamu otevře samo;
 * vložený soubor žádný nemá a čtenář by zůstal u tisíců řádků bez záchytného bodu.
 *
 * <p>Čte se přes {@link CertificateList}, ne přes {@code X509CRLHolder} — ze stejného důvodu,
 * jaký je popsaný u {@code ContainerInspector.describeCrl}: holder si v konstruktoru rozebírá
 * rozšíření issuing distribution point a při jeho vadě odmítne celý soubor, což u starého
 * materiálu znamená nezobrazit nic kvůli údaji, na který se nikdo neptal.
 */
public final class CrlView {

    private CrlView() { }

    /** Jeden řádek tabulky. Serial se drží i jako číslo, aby šel porovnat s hledaným. */
    public record Entry(BigInteger serial, String serialHex, String revokedAt, String reason) { }

    /**
     * @param der            bajty seznamu CRL
     * @param highlight      sériové číslo certifikátu, na jehož záznamu se má okno otevřít;
     *                       {@code null} nebo nenalezené znamená první záznam
     * @param issuerCertDer  certifikát vydavatele seznamu pro odkaz „Ukaž“, nebo {@code null}
     * @param owner          okno, nad kterým se má dialog otevřít
     */
    public static void show(byte[] der, BigInteger highlight, byte[] issuerCertDer, Stage owner) {
        CertificateList crl;
        try {
            crl = CertificateList.getInstance(ASN1Primitive.fromByteArray(der));
            if (crl.getIssuer() == null || crl.getThisUpdate() == null) throw new IllegalStateException();
        } catch (Exception e) {
            UiDialogs.error("Seznam CRL se nepodařilo přečíst",
                    "Data nemají podobu seznamu CRL, nebo jsou poškozená.");
            return;
        }

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        VBox head = new VBox(2);
        head.getChildren().add(headRow("Vydavatel:", String.valueOf(crl.getIssuer()), issuerCertDer,
                owner));
        head.getChildren().add(headRow("Vydáno (thisUpdate):",
                TimeUtil.format(crl.getThisUpdate().getDate()), null, owner));
        head.getChildren().add(headRow("Příští vydání (nextUpdate):",
                crl.getNextUpdate() == null ? "— seznam neuvádí"
                        : TimeUtil.format(crl.getNextUpdate().getDate()), null, owner));
        if (crl.getSignatureAlgorithm() != null) {
            // Jméno algoritmu, ne jeho OID — a týmž převodem, jakým je vypisuje textový rozbor
            // seznamu v ContainerInspector. Neznámé OID projde beze změny, takže se nic neztratí.
            head.getChildren().add(headRow("Algoritmus podpisu:",
                    ContainerInspector.oidLabel(crl.getSignatureAlgorithm().getAlgorithm().getId()),
                    null, owner));
        }
        String number = crlNumber(crl);
        if (number != null) head.getChildren().add(headRow("Číslo seznamu:", number, null, owner));
        root.getChildren().add(head);

        List<Entry> entries = entries(crl);
        Label count = new Label(countText(entries.size(), entries.size(), true));
        count.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #111111;");
        root.getChildren().add(count);

        int index = indexOf(entries, highlight);
        if (highlight != null) {
            Label note = new Label(index >= 0
                    ? "Otevřeno na záznamu certifikátu " + hex(highlight) + "."
                    : "Certifikát " + hex(highlight) + " na tomto seznamu není.");
            note.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                    + (index >= 0 ? "#cc0000" : "#1a7a1a") + ";");
            root.getChildren().add(note);
        }

        ObservableList<Entry> all = FXCollections.observableArrayList(entries);
        FilteredList<Entry> shown = new FilteredList<>(all, e -> true);

        TextField search = new TextField();
        search.setPromptText("Hledat sériové číslo — šestnáctkově i desítkově,"
                + " na mezerách a dvojtečkách nezáleží");
        search.setDisable(entries.isEmpty());
        search.textProperty().addListener((o, was, now) -> {
            shown.setPredicate(predicateFor(now));
            count.setText(countText(entries.size(), shown.size(), isBlank(now)));
        });
        root.getChildren().add(search);

        // Nad filtrem ještě řazení, a to je oprava regrese: {@code FilteredList} předaný
        // tabulce přímo se řadit nedá — kliknutí do záhlaví sloupce neudělá nic. Před zavedením
        // hledání tu byl obyčejný {@code ObservableList}, který si {@code TableView} řadí samo,
        // takže se řazení ztratilo, aniž by to co ohlásilo. Nález uživatele 15. 9. 2026.
        //
        // Vazba komparátoru je na tom to podstatné: bez ní {@code SortedList} o kliknutí do
        // záhlaví neví a tabulka zůstane neřazená stejně jako bez něj.
        SortedList<Entry> sorted = new SortedList<>(shown);
        TableView<Entry> table = new TableView<>(sorted);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        TableColumn<Entry, String> cSerial = new TableColumn<>("Sériové číslo");
        cSerial.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().serialHex()));
        cSerial.setPrefWidth(260);
        TableColumn<Entry, String> cDate = new TableColumn<>("Revokován");
        cDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().revokedAt()));
        cDate.setPrefWidth(300);
        TableColumn<Entry, String> cReason = new TableColumn<>("Důvod");
        cReason.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().reason()));
        cReason.setPrefWidth(220);
        table.getColumns().addAll(List.of(cSerial, cDate, cReason));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Seznam neuvádí žádný revokovaný certifikát."));
        VBox.setVgrow(table, Priority.ALWAYS);
        root.getChildren().add(table);

        Stage stage = new Stage();
        // Systémové okno je druhý názor na týž seznam; co v něm uživatel uvidí, se od zdejší
        // tabulky liší, a právě proto tam tlačítko je (přání uživatele, 20. 9. 2026).
        Button system = new Button("Otevřít v systémovém prohlížeči");
        system.setOnAction(e -> UiDialogs.openInSystemViewer(der, "seznam", "*.crl", stage));
        Button save = new Button("Uložit seznam…");
        save.setOnAction(e -> UiDialogs.saveBytes(der, "seznam.crl", "CRL seznam (*.crl)",
                "*.crl", stage));
        Button close = new Button("Zavřít");
        close.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, system, save, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(buttons);

        stage.setTitle("Seznam CRL");
        stage.initModality(Modality.NONE);
        if (owner != null) stage.initOwner(owner);
        stage.setScene(new Scene(root, 900, 620));
        stage.show();

        // Až po zobrazení: scrollTo() na nevykresleném TableView neudělá nic, protože virtuální
        // tok ještě nezná výšku řádku. Ověřeno stiskem, ne vykreslením — viz poznámka o JavaFX
        // ovládacích prvcích.
        if (!entries.isEmpty()) {
            // Podle záznamu, ne podle indexu do původního seznamu. Jakmile nad ním stojí filtr
            // a řazení, index už neukazuje na týž řádek — při otevření ještě ano, ale spoléhat
            // se na to znamená nechat past na příště.
            Entry target = entries.get(index >= 0 ? index : 0);
            javafx.application.Platform.runLater(() -> {
                int row = table.getItems().indexOf(target);
                if (row < 0) return;
                table.getSelectionModel().select(row);
                table.scrollTo(Math.max(0, row - 3));
            });
        }
    }

    /** Kolik záznamů seznam nese, a kolik z nich je právě vidět. */
    private static String countText(int total, int visible, boolean unfiltered) {
        if (total == 0) return "Seznam neuvádí žádný revokovaný certifikát.";
        if (unfiltered) return "Revokovaných certifikátů: " + total;
        return "Revokovaných certifikátů: " + total + " — hledání vyhověl" + (visible == 1
                ? "o " + visible + " záznam" : visible >= 2 && visible <= 4
                ? "y " + visible + " záznamy" : "o " + visible + " záznamů");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Co se počítá za shodu při hledání sériového čísla.
     *
     * <p>Čtenář to číslo odněkud opíse — z certifikátu, z protokolu, z jiného nástroje — a každý
     * je píše jinak: {@code 00 B3 14 0E}, {@code b3140e}, {@code B3:14:0E}, nebo desítkově.
     * Porovnává se proto obojí: šestnáctkový zápis očištěný od oddělovačů a úvodních nul,
     * a desítková hodnota. Hledá se podřetězcem, ne od začátku: kdo si pamatuje jen konec čísla,
     * najde ho taky.
     */
    static java.util.function.Predicate<Entry> predicateFor(String needle) {
        if (isBlank(needle)) return e -> true;
        String hex = normalizeHex(needle);
        String plain = needle.strip();
        return e -> (!hex.isEmpty() && normalizeHex(e.serialHex()).contains(hex))
                || (e.serial() != null && e.serial().toString().contains(plain));
    }

    /** Šestnáctkový zápis bez oddělovačů, velkými písmeny a bez úvodních nul. */
    private static String normalizeHex(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.digit(c, 16) >= 0) sb.append(Character.toUpperCase(c));
        }
        int i = 0;
        while (i < sb.length() - 1 && sb.charAt(i) == '0') i++;
        return sb.substring(i);
    }

    /** Řádek hlavičky; s nenulovým certifikátem přibude odkaz „Ukaž“ na vydavatele. */
    private static HBox headRow(String label, String value, byte[] certDer, Stage owner) {
        Label l = new Label(label);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #111111;");
        l.setMinWidth(190);
        l.setMaxWidth(190);
        l.setWrapText(true);
        // Ne Label: popisek se v HBoxu měří na jednu řádku, takže dlouhé jméno vydavatele nezalomí,
        // nýbrž ořízne třemi tečkami a odkaz „Ukaž“ vytlačí z okna. {@code selectable()} je táž
        // TextArea, jakou aplikace používá všude, kde má být dlouhá hodnota čitelná i kopírovatelná.
        javafx.scene.control.TextArea v = ResultView.selectable(value != null ? value : "—",
                "-fx-font-size: 12px; -fx-text-fill: #111111;");
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox row = new HBox(8, l, v);
        row.setAlignment(Pos.TOP_LEFT);
        if (certDer != null && certDer.length > 0) {
            Hyperlink show = new Hyperlink("Ukaž");
            show.setStyle("-fx-font-size: 11px; -fx-text-fill: #0a5ca8;"
                    + " -fx-border-color: transparent; -fx-background-color: transparent;");
            show.setOnAction(e -> {
                show.setVisited(false);
                ElementTextView.show("Certifikát vydavatele seznamu CRL",
                        cz.digitalnikontinuita.anep.core.ContainerInspector.describeCertificate(certDer),
                        certDer, "certifikat.cer", "Certifikát (*.cer)", "*.cer", owner);
            });
            row.getChildren().add(show);
        }
        return row;
    }

    private static List<Entry> entries(CertificateList crl) {
        List<Entry> out = new ArrayList<>();
        TBSCertList.CRLEntry[] revoked;
        try {
            revoked = crl.getRevokedCertificates();
        } catch (Exception e) {
            return out;
        }
        if (revoked == null) return out;
        for (TBSCertList.CRLEntry e : revoked) {
            BigInteger serial = e.getUserCertificate().getValue();
            out.add(new Entry(serial, hex(serial),
                    TimeUtil.format(e.getRevocationDate().getDate()), reason(e.getExtensions())));
        }
        return out;
    }

    private static int indexOf(List<Entry> entries, BigInteger serial) {
        if (serial == null) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (serial.equals(entries.get(i).serial())) return i;
        }
        return -1;
    }

    /** Společný zápis čísla: šestnáctkově, po celých bajtech, s předponou {@code 0x}. */
    private static String hex(BigInteger value) {
        return cz.digitalnikontinuita.anep.core.ContainerInspector.hexNumber(value);
    }

    private static String crlNumber(CertificateList crl) {
        try {
            Extensions ext = crl.getTBSCertList().getExtensions();
            if (ext == null) return null;
            var e = ext.getExtension(org.bouncycastle.asn1.x509.Extension.cRLNumber);
            if (e == null) return null;
            return org.bouncycastle.asn1.ASN1Integer.getInstance(
                    org.bouncycastle.asn1.ASN1Primitive.fromByteArray(
                            e.getExtnValue().getOctets())).getValue().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String reason(Extensions ext) {
        try {
            if (ext == null) return "";
            var e = ext.getExtension(org.bouncycastle.asn1.x509.Extension.reasonCode);
            if (e == null) return "";
            int code = org.bouncycastle.asn1.ASN1Enumerated.getInstance(
                    org.bouncycastle.asn1.ASN1Primitive.fromByteArray(
                            e.getExtnValue().getOctets())).getValue().intValue();
            // Táž jména, jaká pro tytéž kódy používá zbytek aplikace — týž důvod se má číst
            // stejně v prohlížeči kontejnerů, v Detailním rozboru i tady.
            return switch (code) {
                case 0  -> "Neurčeno (unspecified)";
                case 1  -> "Kompromitace klíče (keyCompromise)";
                case 2  -> "Kompromitace CA (cACompromise)";
                case 3  -> "Změna příslušnosti (affiliationChanged)";
                case 4  -> "Nahrazen (superseded)";
                case 5  -> "Ukončena operace (cessationOfOperation)";
                case 6  -> "Pozastaven (certificateHold)";
                case 8  -> "Odblokován (removeFromCRL)";
                case 9  -> "Odvolána výjimka (privilegeWithdrawn)";
                case 10 -> "Kompromitace AA (aACompromise)";
                default -> "Kód " + code;
            };
        } catch (Exception e) {
            return "";
        }
    }
}
