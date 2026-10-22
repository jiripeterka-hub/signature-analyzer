package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.core.TsaSigningKey;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TsaManagerDialog {

    private static final String SECTIGO_URL = "http://timestamp.sectigo.com/qualified";
    private static final String MORE_TSA_SOURCES_URL =
            "https://www.bajecnysvet.cz/navody/navod001.php";

    /**
     * @param timestamping služba, která umí zdroj vyzkoušet. Předává se sem, ačkoli dialog sám
     *                     žádná razítka nepřidává: sonda si od zdroje vyžádá razítko a to se pak
     *                     poměřuje s důvěryhodnými seznamy, k čemuž je potřeba ověřovatel
     *                     certifikátů — a ten drží právě tahle služba.
     */
    public static void show(Window owner, AppSettings settings, TimestampingService timestamping) {
        Stage stage = new Stage();
        stage.setTitle("Zdroje časových razítek (TSA)");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        // Okno jde zvětšit. Řádky zdrojů nesou od 18. 9. 2026 tři popisky pod sebou, z toho dva
        // zalamované, a pevná velikost u takového obsahu znamená, že co se nevejde, je pryč.
        stage.setResizable(true);

        // Work on copies; applied to settings only on "Uložit"
        List<TsaProfile> profiles = new ArrayList<>();
        settings.getTsaProfiles().forEach(p -> profiles.add(new TsaProfile(p)));
        int[] selectedIdx = {settings.getSelectedTsaIndex()};

        // Profile list container
        VBox profilesVBox = new VBox(4);
        profilesVBox.setPadding(new Insets(4, 4, 4, 4));
        ScrollPane scrollPane = new ScrollPane(profilesVBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(220);
        scrollPane.setStyle("-fx-background-color:transparent;-fx-border-color:#cccccc;");

        // Probíhající ověření nového zdroje. Stav se vede vlastností a tlačítka se na ni váží;
        // volat na svázanou disableProperty ještě setDisable() okno tiše zamkne.
        javafx.beans.property.BooleanProperty checking =
                new javafx.beans.property.SimpleBooleanProperty(false);
        Label checkStatus = new Label();
        checkStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#555555;");
        checkStatus.visibleProperty().bind(checking);
        checkStatus.managedProperty().bind(checking);

        // Rebuild closure (array enables self-reference in the lambda)
        Runnable[] refresh = {null};
        refresh[0] = () -> {
            profilesVBox.getChildren().clear();
            ToggleGroup group = new ToggleGroup();

            RadioButton rbNone = new RadioButton("Žádný — nepřidávat časová razítka");
            rbNone.setToggleGroup(group);
            rbNone.setStyle("-fx-font-style:italic;");
            rbNone.setSelected(selectedIdx[0] < 0 || selectedIdx[0] >= profiles.size());
            rbNone.setOnAction(e -> selectedIdx[0] = -1);
            profilesVBox.getChildren().add(rbNone);

            for (int i = 0; i < profiles.size(); i++) {
                profilesVBox.getChildren().add(new Separator());
                final int idx = i;
                TsaProfile p = profiles.get(i);

                RadioButton rb = new RadioButton();
                rb.setToggleGroup(group);
                rb.setSelected(selectedIdx[0] == i);
                rb.setOnAction(e -> selectedIdx[0] = idx);

                String displayName = p.getName().isBlank() ? "(bez názvu)" : p.getName();
                Label lblName = new Label(displayName);
                lblName.setStyle("-fx-font-weight:bold;");

                String detail = p.getUrl()
                        + "  •  " + authDescription(p);
                Label lblDetail = new Label(detail);
                lblDetail.setStyle("-fx-font-size:11px;-fx-text-fill:#555555;");
                wrapping(lblDetail, 380);

                VBox nameCol = new VBox(2, lblName, lblDetail);
                // Co test zdroje naměřil, vypsané celé (pokyn uživatele z 20. 10. 2026): dřív
                // byla v řádku jen nastavená funkce a výčet přijímaných jen v bublině editačního
                // okna, a jen když nějakou zdroj odmítl.
                for (String line : new String[]{hashesLine(p), signingLine(p)}) {
                    if (line == null) continue;
                    Label l = new Label(line);
                    l.setStyle("-fx-font-size:11px;-fx-text-fill:#555555;");
                    wrapping(l, 380);
                    nameCol.getChildren().add(l);
                }
                // Naměřená kvalifikovanost patří na řádek, ne do nápovědy: podle ní se uživatel
                // rozhoduje, který zdroj vybere, a nekvalifikovaný zdroj mění, co aplikace
                // o dokumentu po zásahu tvrdí. Mlčí se jen tam, kde se nic nenaměřilo.
                Label lblQuality = qualityLabel(p.getQuality());
                if (lblQuality != null) nameCol.getChildren().add(lblQuality);
                Label lblKey = keyWarningLabel(p, settings);
                if (lblKey != null) nameCol.getChildren().add(lblKey);
                HBox.setHgrow(nameCol, Priority.ALWAYS);

                Button btnEdit = new Button("Upravit");
                btnEdit.disableProperty().bind(checking);
                btnEdit.setOnAction(ev ->
                        showEditDialog(stage, p).ifPresent(edited -> {
                            boolean sameSource = p.sameEndpointAs(edited);
                            profiles.set(idx, edited);
                            refresh[0].run();
                            // Jiná adresa nebo jiné přihlášení znamená jiný protějšek, a všechno
                            // naměřené patřilo tomu předchozímu. Zdroj přepnutý z hesla na
                            // certifikát je právě tenhle případ a bez nového ověření by o něm
                            // nastavení tvrdilo, co platilo o něčem jiném.
                            if (!sameSource) {
                                checkNewProfile(stage, timestamping, edited, profiles,
                                        selectedIdx, refresh[0], checking, checkStatus);
                            }
                        })
                );

                Button btnRemove = new Button("Odebrat");
                btnRemove.disableProperty().bind(checking);
                btnRemove.setOnAction(ev -> {
                    profiles.remove(idx);
                    if (selectedIdx[0] == idx) selectedIdx[0] = -1;
                    else if (selectedIdx[0] > idx) selectedIdx[0]--;
                    refresh[0].run();
                });

                HBox row = new HBox(8, rb, nameCol, btnEdit, btnRemove);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(4, 0, 4, 0));
                profilesVBox.getChildren().add(row);
            }
        };
        refresh[0].run();

        // Add buttons
        Button btnAdd = new Button("Přidat vlastní...");
        btnAdd.setOnAction(e ->
                showEditDialog(stage, null).ifPresent(p -> {
                    profiles.add(p);
                    selectedIdx[0] = profiles.size() - 1;
                    refresh[0].run();
                    checkNewProfile(stage, timestamping, p, profiles, selectedIdx, refresh[0],
                            checking, checkStatus);
                })
        );

        Button btnAddSectigo = new Button("Přidat Sectigo Qualified");
        btnAddSectigo.setOnAction(e -> {
            if (alreadyListed(stage, profiles, SECTIGO_URL, "Sectigo Qualified")) return;
            TsaProfile sectigo = TsaProfile.sectigoQualified();
            profiles.add(sectigo);
            selectedIdx[0] = profiles.size() - 1;
            refresh[0].run();
            checkNewProfile(stage, timestamping, sectigo, profiles, selectedIdx, refresh[0],
                    checking, checkStatus);
        });

        // Testovací autorita ve dvou podobách, protože přihlášení certifikátem je u ní jiná
        // adresa (port 443 místo 444), ne jiné nastavení téhož zdroje. Menu místo dvou dalších
        // tlačítek: v řádku by jich bylo čtyři vedle sebe.
        MenuButton btnAddDemo = new MenuButton("Přidat PostSignum DEMO");
        MenuItem miDemoBasic = new MenuItem("…s jménem a heslem");
        miDemoBasic.setOnAction(e -> {
            TsaProfile demo = TsaProfile.postSignumDemoBasic();
            if (alreadyListed(stage, profiles, demo.getUrl(), demo.getName())) return;
            profiles.add(demo);
            selectedIdx[0] = profiles.size() - 1;
            refresh[0].run();
            checkNewProfile(stage, timestamping, demo, profiles, selectedIdx, refresh[0],
                    checking, checkStatus);
        });
        MenuItem miDemoCert = new MenuItem("…s klientským certifikátem…");
        miDemoCert.setOnAction(e -> {
            TsaProfile demo = TsaProfile.postSignumDemoCertificate();
            if (alreadyListed(stage, profiles, demo.getUrl(), demo.getName())) return;
            // Otevře se editační okno předvyplněné: certifikát a heslo k němu musí doplnit
            // uživatel a bez nich by zdroj neprošel ani ověřením, ani prvním použitím.
            showEditDialog(stage, demo, true).ifPresent(p -> {
                profiles.add(p);
                selectedIdx[0] = profiles.size() - 1;
                refresh[0].run();
                checkNewProfile(stage, timestamping, p, profiles, selectedIdx, refresh[0],
                        checking, checkStatus);
            });
        });
        btnAddDemo.getItems().addAll(miDemoBasic, miDemoCert);

        btnAdd.disableProperty().bind(checking);
        btnAddSectigo.disableProperty().bind(checking);
        btnAddDemo.disableProperty().bind(checking);

        // Návod s dalšími zdroji razítek, které lze přidat jako vlastní (16. 10. 2026, pokyn
        // uživatele). Odkaz, ne tlačítko: nic nepřidává, jen otevře stránku v prohlížeči.
        Hyperlink moreSources = new Hyperlink("Další zdroje razítek");
        Tooltip.install(moreSources, new Tooltip(MORE_TSA_SOURCES_URL));
        moreSources.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(MORE_TSA_SOURCES_URL));
            } catch (Exception ignored) {
                // Bez prohlížeče zbývá adresa v bublině.
            }
        });

        HBox addRow = new HBox(8, btnAdd, btnAddSectigo, btnAddDemo, moreSources, checkStatus);
        addRow.setAlignment(Pos.CENTER_LEFT);

        // Save / Cancel
        Button btnSave = new Button("Uložit");
        btnSave.setDefaultButton(true);
        btnSave.setStyle("-fx-font-size:12px;");
        // Dokud ověření běží, uložit nelze: teprve ono ví, jestli zdroj vůbec razítko vydá
        // a které otiskové funkce bere, a uložit ho dřív by znamenalo zapsat do nastavení
        // něco, o čem se za vteřinu ukáže, že tam nemá co dělat.
        btnSave.disableProperty().bind(checking);
        Button btnCancel = new Button("Zrušit");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> stage.close());
        btnSave.setOnAction(e -> {
            // Nově zadané zdroje jsou v tuhle chvíli už ověřené — proběhlo to hned při zadání.
            // Tohle jen přenese, co se ví o zdrojích, které se nezměnily.
            carryOverKnownExpiry(profiles, settings.getTsaProfiles());
            settings.setTsaProfiles(profiles);
            settings.setSelectedTsaIndex(selectedIdx[0]);
            try { settings.save(); } catch (Exception ex) { /* non-fatal */ }
            stage.close();
        });

        // Info line at top
        Label infoLabel = new Label(
                "Vyberte TSA server pro přidávání časových razítek. " +
                "Pro samotné ověřování podpisů toto nastavení není potřeba.");
        infoLabel.setStyle("-fx-font-size:11px;-fx-text-fill:#555555;");
        wrapping(infoLabel, 540);

        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        root.setMinWidth(600);
        root.getChildren().addAll(
                infoLabel,
                new Separator(),
                scrollPane,
                addRow,
                new Separator(),
                new HBox(8, btnSave, btnCancel)
        );

        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    /** Čím se zdroj přihlašuje, řečeno do řádku v seznamu. */
    private static String authDescription(TsaProfile p) {
        return switch (p.auth()) {
            case BASIC       -> "jméno a heslo";
            case CERTIFICATE -> "klientský certifikát";
            case NONE        -> "bez přihlášení";
        };
    }

    /**
     * Které hashovací funkce zdroj v žádosti přijímá a se kterou se ho aplikace ptá. Nastavená
     * funkce se uvádí jako „aplikace žádá s:“ (pokyn uživatele z 20. 10. 2026) — holé „žádá s“
     * se dalo číst jako vlastnost zdroje.
     *
     * <p>Odmítnuté se jmenují zvlášť: zkouší se jen SHA-512, SHA-384 a SHA-256 a bez nich by
     * výčet mohl vypadat jako úplný seznam toho, co autorita umí. U zdroje, u kterého test
     * neproběhl (založený dřív, než se to zjišťovalo), se to říká, místo aby se mlčelo.
     */
    static String hashesLine(TsaProfile p) {
        String used = "aplikace žádá s: " + TsaProfile.displayHash(p.getHashAlgorithm());
        List<String> ok = p.getSupportedHashes();
        if (ok.isEmpty()) {
            return "Hashovací funkce: přijímané zatím nezjištěny; " + used;
        }
        List<String> refused = new ArrayList<>(TsaProfile.allHashAlgorithms());
        refused.removeAll(ok);
        return "Přijímá hashovací funkce: "
                + String.join(", ", ok.stream().map(TsaProfile::displayHash).toList())
                + (refused.isEmpty() ? "" : " (odmítá "
                        + String.join(", ", refused.stream().map(TsaProfile::displayHash).toList())
                        + ")")
                + "; " + used;
    }

    /**
     * Čím autorita razítka podepisuje a dokdy platí její certifikát (konec platnosti doplněn
     * na pokyn uživatele z 20. 10. 2026); co se nezjistilo, vynechá se. {@code null}, když se
     * nezjistilo ani jedno.
     */
    static String signingLine(TsaProfile p) {
        List<String> parts = new ArrayList<>();
        TsaSigningKey key = p.getSigningKey();
        if (key != null) parts.add("Razítka podepisuje: " + key.describeSignature());
        if (p.getCertNotAfter() != null) {
            parts.add("Platnost certifikátu do: " + day(p.getCertNotAfter()));
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * Naměřená kvalifikovanost jako řádek pod adresou, nebo {@code null}, když se nic nenaměřilo.
     *
     * <p>Mlčení u {@code UNKNOWN} je záměr: „nezjištěno" by na řádku vypadalo jako vada zdroje,
     * ačkoli je to vada měření — zdroj založený dřív, než se to začalo zjišťovat, nebo nenačtené
     * důvěryhodné seznamy.
     */
    private static Label qualityLabel(TsaProfile.Quality quality) {
        if (quality == TsaProfile.Quality.UNKNOWN) return null;
        boolean qualified = quality == TsaProfile.Quality.QUALIFIED;
        Label l = new Label(qualified
                ? "Vydává kvalifikovaná časová razítka (ověřeno v důvěryhodných seznamech)"
                : "Vydává časová razítka, která nejsou kvalifikovaná — pro ostrý provoz se nehodí");
        l.setStyle("-fx-font-size:11px;-fx-text-fill:" + (qualified ? "#2e7d32" : "#a15c00") + ";");
        wrapping(l, 380);
        return l;
    }

    /** Je tenhle zdroj už v seznamu? Hlásí to a odpoví true, aby se volající mohl vrátit. */
    /**
     * Upozornění, že klíč, kterým autorita podepisuje razítka, přestane být podle knihovny DSS
     * spolehlivý dřív, než skončí její certifikát — nebo už přestal. Pokyn uživatele z 5. 10. 2026
     * po měření nad sbírkami: většina autorit podepisuje klíčem RSA o 2 048 bitech, jehož datum je
     * 1. 1. 2027, a jejich certifikáty platí do let 2028 až 2036.
     *
     * <p>Datum se bere z kryptografické sady politiky, podle které AnEP ověřuje, ne z vlastní
     * tabulky ({@link TsaSigningKey#reliableUntil}). Kde leží za poslední aktualizací sady, ověření
     * v AnEP na ně reaguje jen varováním (politika ověřování, oddíl 10) — a text to říká, aby
     * neslíbil konec ověřitelnosti, který v AnEP nenastane.
     *
     * <p>Mlčí, když klíč není zjištěný, když datum chybí, nebo když leží až za koncem certifikátu:
     * tam o konci rozhoduje certifikát a datum ve jménu souboru to už říká.
     *
     * @return popisek, nebo {@code null}, když není co říct
     */
    static Label keyWarningLabel(TsaProfile p, AppSettings settings) {
        String text = keyWarning(p, settings);
        if (text == null) return null;
        Label l = new Label("⚠ " + text);
        l.setStyle("-fx-font-size:11px;-fx-text-fill:#8a5300;");
        wrapping(l, 380);
        return l;
    }

    /** Text upozornění bez sazby — kvůli testu. */
    static String keyWarning(TsaProfile p, AppSettings settings) {
        TsaSigningKey key = p.getSigningKey();
        if (key == null || settings == null) return null;
        eu.europa.esig.dss.model.policy.ValidationPolicy policy;
        try {
            policy = cz.digitalnikontinuita.anep.core.ValidationPolicyFactory.build(settings);
        } catch (Exception e) {
            return null;
        }
        TsaSigningKey.Weakness weak = TsaSigningKey.weakness(p, policy);
        if (weak == null) return null;
        java.util.Date until = weak.until();
        java.util.Date certEnd = p.getCertNotAfter();
        boolean forecast = weak.forecast();
        String day = day(until);
        boolean past = until.before(new java.util.Date());
        // Původcem data je knihovna DSS, ne ETSI (pokyn uživatele z 22. 10. 2026): ETSI TS 119 312
        // to datum míní pro vydávání nových certifikátů TSA, kdežto výchozí politika DSS 6.5 ho
        // uplatňuje i na razítko vydané dřív, pokud jeho užití nedoloží pozdější razítko.
        String head = "Autorita podepisuje razítka algoritmem " + key.describe() + ". Knihovna"
                + " DSS, nad kterou AnEP ověřuje, ho " + (past ? "už od " : "od ") + day
                + " nepovažuje za spolehlivý"
                + (certEnd != null ? ", ačkoli certifikát autority platí do "
                        + day(certEnd)
                        : "") + ".";
        String effect = forecast
                ? " Ověření v AnEP na to zatím reaguje jen varováním; jiné validátory ale mohou"
                  + " takové razítko po tomto datu odmítnout."
                : " Ověření takové razítko po tomto datu nepřijme.";
        return head + effect + " Pro uchování se hodí zdroj s klíčem RSA aspoň 3 000 bitů"
                + " nebo ECDSA.";
    }

    /** „1. 1. 2027“ — den v pražském čase, bez hodin. */
    private static String day(java.util.Date d) {
        return java.time.format.DateTimeFormatter.ofPattern("d. M. yyyy")
                .format(d.toInstant().atZone(java.time.ZoneId.of("Europe/Prague")));
    }

    private static boolean alreadyListed(Stage owner, List<TsaProfile> profiles, String url,
                                         String name) {
        if (profiles.stream().noneMatch(p -> url.equals(p.getUrl()))) return false;
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("Duplicitní záznam");
        alert.setHeaderText(null);
        alert.setContentText("TSA server " + name + " je již v seznamu.");
        alert.showAndWait();
        return true;
    }

    /**
     * Přenese už známý konec platnosti na profily, které míří na tutéž autoritu.
     *
     * <p>Dialog pracuje s kopiemi, a kopie se pořizuje konstruktorem, který ty údaje sice nese —
     * jenže profil upravený v editačním okně vzniká z formulářových polí, tedy nový a prázdný.
     * Bez tohoto kroku by se po každé změně jména či otiskového algoritmu žádalo razítko znovu,
     * ačkoli se adresa nezměnila a odpověď by byla táž.
     */
    private static void carryOverKnownExpiry(List<TsaProfile> edited, List<TsaProfile> stored) {
        for (TsaProfile t : edited) {
            boolean complete = t.getCertNotAfter() != null && !t.getSupportedHashes().isEmpty()
                    && t.getQuality() != TsaProfile.Quality.UNKNOWN && t.getSigningKey() != null;
            if (complete) continue;
            for (TsaProfile old : stored) {
                if (!old.sameEndpointAs(t)) continue;
                if (t.getCertNotAfter() == null) t.setCertNotAfter(old.getCertNotAfter());
                if (t.getSupportedHashes().isEmpty()) {
                    t.setSupportedHashes(old.getSupportedHashes());
                }
                if (t.getQuality() == TsaProfile.Quality.UNKNOWN) {
                    t.setQuality(old.getQuality());
                }
                if (t.getSigningKey() == null) t.setSigningKey(old.getSigningKey());
                break;
            }
        }
    }

    /**
     * Ověří právě zadaný zdroj — hned, a než skončí, nejde uložit.
     *
     * <p>Ověření se dělá tak jako tak: sonda si vyžádá razítko, aby zjistila, dokdy platí
     * certifikát autority a které otiskové funkce bere. Dřív běželo až po uložení a na pozadí,
     * takže se uživatel o nefunkčním zdroji dozvěděl, teprve když už byl v nastavení zapsaný —
     * a vzápětí i to, že byl zase odebrán. Odpověď přitom rozhoduje, jestli ten zdroj do
     * nastavení vůbec patří, takže se na ni počká.
     *
     * <p>Od 18. 9. 2026 se z téhož razítka čte i to, zda je <b>kvalifikované</b>. Je to údaj,
     * který mění, co aplikace o dokumentu po zásahu tvrdí, takže se o něm uživatel dozví hned
     * a nahlas — ne až z popisku v seznamu, kam se taky zapíše.
     *
     * <p>Počká se ale bez zamrzlého okna. Práce běží na vlákně na pozadí, protože trvat může
     * i desítky vteřin (italské ministerstvo obrany si jednou vzalo 26 s), a rozhraní zatím
     * píše, co se děje. Tlačítka Uložit a Přidat jsou po tu dobu svázaná s vlastností
     * {@code checking}; volat na ně vedle toho setDisable() by okno tiše zamklo.
     *
     * <p>Zdroj, ze kterého nepřijde ani jedno razítko, se ze seznamu rovnou vyjme. Razítko by
     * nevydal, ani kdyby o ně někdo požádal, takže nechat ho tam znamená nechat uživatele, ať
     * na to přijde sám — nejspíš uprostřed uchování.
     */
    private static void checkNewProfile(Stage owner, TimestampingService timestamping,
                                        TsaProfile profile, List<TsaProfile> profiles,
                                        int[] selectedIdx, Runnable refresh,
                                        javafx.beans.property.BooleanProperty checking,
                                        Label status) {
        if (profile.getUrl() == null || profile.getUrl().isBlank()) return;

        String label = profile.getName().isBlank() ? profile.getUrl() : profile.getName();
        status.setText("Běží test zdroje časových razítek, prosím čekejte");
        checking.set(true);

        Thread worker = new Thread(() -> {
            TimestampingService.TsaProbe probe = null;
            String failure = null;
            try {
                probe = timestamping.probe(profile);
            } catch (Exception ex) {
                Throwable root = ex;
                while (root.getCause() != null) root = root.getCause();
                failure = root.getMessage() != null ? root.getMessage()
                        : root.getClass().getSimpleName();
            }
            final TimestampingService.TsaProbe result = probe;
            final String error = failure;
            javafx.application.Platform.runLater(() -> {
                checking.set(false);
                if (result != null) {
                    if (result.certNotAfter() != null) profile.setCertNotAfter(result.certNotAfter());
                    // Až po datu: setSupportedHashes smí přesadit i nastavenou funkci.
                    profile.setSupportedHashes(result.supportedHashes());
                    profile.setQuality(result.quality());
                    if (result.signingKey() != null) profile.setSigningKey(result.signingKey());
                    refresh.run();
                    if (result.quality() == TsaProfile.Quality.UNQUALIFIED) {
                        reportUnqualifiedSource(owner, label);
                    }
                    return;
                }
                // Index vybraného zdroje je pořadí v seznamu, takže odebráním položky před ním
                // by se výběr posunul na někoho jiného a razítka by se začala brát odjinud,
                // aniž by o tom kdokoli věděl. Totéž srovnání dělá tlačítko Odebrat.
                int idx = profiles.indexOf(profile);
                if (idx >= 0) {
                    profiles.remove(idx);
                    if (selectedIdx[0] == idx) selectedIdx[0] = -1;
                    else if (selectedIdx[0] > idx) selectedIdx[0]--;
                }
                refresh.run();
                reportDeadSource(owner, label, profile.getUrl(), error);
            });
        }, "tsa-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** Proč se zdroj do seznamu nedostal, řečeno rovnou a s adresou i chybou. */
    private static void reportDeadSource(Stage owner, String name, String url, String error) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(owner);
        alert.setTitle("Zdroj časových razítek neodpověděl");
        alert.setHeaderText("Zdroj nebyl přidán — nevydal žádné časové razítko");
        Label text = new Label(
                "Aplikace si od každého nového zdroje vyžádá jedno časové razítko: potřebuje "
                + "z něj vyčíst, dokdy platí certifikát autority a které otiskové funkce bere. "
                + "Tento zdroj neodpověděl ani na jednu ze tří, takže by z něj razítko nešlo "
                + "získat, ani kdyby o ně někdo požádal.\n\n"
                + name + "\n" + url + "\n" + error
                + "\n\nJe-li to jen dočasný výpadek nebo překlep v adrese, zkuste to znovu.");
        // Samotné setWrapText() na šířku nestačí: popisek si i tak řekne o tolik místa, kolik
        // by zabral nejdelší odstavec na jednom řádku, a okno se roztáhne přes celou obrazovku.
        // Teprve pevná šířka dá zalomení co dělat.
        text.setWrapText(true);
        text.setPrefWidth(420);
        text.setMaxWidth(420);
        alert.getDialogPane().setContent(text);
        alert.getDialogPane().setPrefWidth(460);
        alert.showAndWait();
    }

    /**
     * Zdroj funguje, ale jeho razítka nejsou kvalifikovaná — a to se musí říct hned.
     *
     * <p>Zdroj se <b>neodebírá</b>: testovací autorita je legitimní volba, právě kvůli ní se
     * PostSignum DEMO do předvoleb přidalo, a zkoušet si na ní úkony aplikace je rozumné.
     * Nekvalifikované razítko ale mění, co aplikace o dokumentu po zásahu tvrdí, takže to nesmí
     * být údaj, kterého si uživatel všimne až v drobném písmu seznamu.
     */
    private static void reportUnqualifiedSource(Stage owner, String name) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("Zdroj vydává nekvalifikovaná časová razítka");
        alert.setHeaderText("Zdroj byl přidán, jeho razítka ale nejsou kvalifikovaná");
        Label text = new Label(
                "Aplikace si od zdroje „" + name + "“ vyžádala časové razítko a posoudila je "
                + "stejně jako razítka, která nachází v dokumentech: podle evropských "
                + "důvěryhodných seznamů. Za tímto razítkem nestojí kvalifikovaná služba.\n\n"
                + "Zdroj v seznamu zůstává — pro zkoušení, jak se aplikace chová, je to "
                + "plnohodnotná volba. Dokument, do kterého se takové razítko přidá, ale "
                + "nezíská nic, o co při uchování jde: nekvalifikované razítko není důkazem "
                + "o existenci v čase, na který by se šlo spolehnout, a ostatní nástroje "
                + "s ním zpravidla nepočítají.\n\n"
                + "Je-li tohle zdroj, kterým se mají uchovávat dokumenty naostro, vyberte "
                + "jiný. Záložka Digitální kontinuita na tenhle zdroj upozorní u každého "
                + "dokumentu, dokud zůstane vybraný.");
        text.setWrapText(true);
        text.setPrefWidth(430);
        text.setMaxWidth(430);
        alert.getDialogPane().setContent(text);
        alert.getDialogPane().setPrefWidth(470);
        alert.showAndWait();
    }

    private static Optional<TsaProfile> showEditDialog(Stage owner, TsaProfile initial) {
        return showEditDialog(owner, initial, initial == null);
    }

    /**
     * @param adding zda se zdroj teprve zakládá. Odděleno od {@code initial == null}, protože
     *               předvolba PostSignum DEMO s certifikátem přichází jako <b>předvyplněný nový</b>
     *               zdroj: hodnoty už má, ale nabídka otiskových funkcí se u něj ještě neměřila,
     *               takže musí zůstat úplná.
     */
    private static Optional<TsaProfile> showEditDialog(Stage owner, TsaProfile initial,
                                                       boolean adding) {
        Stage stage = new Stage();
        stage.setTitle(adding ? "Přidat TSA" : "Upravit TSA");
        stage.initModality(Modality.APPLICATION_MODAL);
        // Nález uživatele z 18. 9. 2026: okno se otevřelo nižší, než jak vysoký byl jeho obsah,
        // takže pole na heslo k úložišti bylo uříznuté a na tlačítko OK se nedalo dosáhnout.
        //
        // Příčinu se **nepodařilo reprodukovat**: TsaEditDialogSizeTest projde i s tou podobou
        // kódu, která vadu měla, takže se zalamovanými popisky to nebylo. Odpovědí jsou proto
        // dvě opatření, která platí bez ohledu na příčinu — okno jde zvětšit, a po vysázení
        // obsahu se podle něj srovná (o řádek níž). Kdyby se to vrátilo, musí se hledat jinde
        // a tenhle odstavec to má říct nahlas, aby se nehledalo v už proměřeném.
        stage.setResizable(true);
        stage.initOwner(owner);

        TsaProfile[] result = {null};
        stage.setScene(new Scene(buildEditForm(stage, initial, adding, result)));
        // Srovnat okno až podle vysázeného obsahu, ne podle toho, co se o něm odhadlo předem.
        // Velikost okna se při zobrazení počítá z <i>předpokládaných</i> rozměrů, kdežto tenhle
        // přepočet běží po prvním průchodu rozvržením, kdy jsou skutečné. Zalamované popisky se
        // mezi obojím liší.
        stage.setOnShown(e -> javafx.application.Platform.runLater(stage::sizeToScene));
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    /**
     * Obsah editačního okna — postavený zvlášť od toho, jak se okno ukáže.
     *
     * <p>Oddělené proto, že se to <b>měří</b>. {@code TsaEditDialogSizeTest} hlídá, že se obsah
     * do okna vejde, a dokud byl obsah svázaný s modálním {@code showAndWait()}, musela zkouška
     * čekat na vnořenou smyčku událostí a chytat okno z {@code Window.getWindows()}. Zasekávala
     * se, pokaždé jinde — a zkouška, která se zasekne, neodliší vadu od své vlastní nespolehlivosti.
     * Takhle si postaví obsah, položí ho do scény a změří, bez jediného modálního okna.
     *
     * @param stage  okno, do kterého obsah přijde. Potřebují ho tlačítka (zavřít, dialog na výběr
     *               souboru) a přepočet velikosti při změně způsobu přihlášení.
     * @param result jednoprvkové pole, do kterého OK uloží výsledek; {@code null} po Zrušit
     */
    static VBox buildEditForm(Stage stage, TsaProfile initial, boolean adding,
                              TsaProfile[] result) {
        boolean blank = initial == null;
        TsaProfile base = blank ? new TsaProfile() : initial;

        // Form fields
        TextField tfName = new TextField(base.getName());
        tfName.setPromptText("Název serveru (volitelné)");
        tfName.setMaxWidth(Double.MAX_VALUE);

        TextField tfUrl = new TextField(base.getUrl());
        tfUrl.setPromptText("https://tsa.example.com/...");
        tfUrl.setMaxWidth(Double.MAX_VALUE);

        // Nabízejí se jen funkce, na které zdroj odpověděl. U nově přidávaného se to ještě neví
        // — zjistí se hned po potvrzení a nabídka se srovná při dalším otevření; do té doby
        // je úplná, protože neznalost není důvod uživatele omezovat.
        ComboBox<String> cbHash = new ComboBox<>();
        cbHash.getItems().addAll(adding
                ? TsaProfile.allHashAlgorithms() : base.offeredHashAlgorithms());
        cbHash.setValue(blank ? TsaProfile.HASH_SHA512 : base.getHashAlgorithm());
        if (!adding && !base.getSupportedHashes().isEmpty()
                && base.getSupportedHashes().size() < 3) {
            cbHash.setTooltip(new javafx.scene.control.Tooltip(
                    "Tento zdroj odpověděl razítkem na " + String.join(", ",
                            base.getSupportedHashes())
                    + ". Ostatní otiskové funkce odmítl, takže se nenabízejí."));
        }

        // ── Přihlášení ───────────────────────────────────────────────────────────────────────
        // Tři možnosti v přepínači, ne dvě sady polí vedle sebe. Vyplněné jméno i certifikát
        // naráz je stav, na který by se {@code TsaProfile.auth()} musela ptát po pořadí; takhle
        // se nevytvoří, protože potvrzení pole nezvoleného způsobu vyprázdní.
        ToggleGroup authGroup = new ToggleGroup();
        RadioButton rbNoAuth = new RadioButton("Žádné — server přihlášení nevyžaduje");
        RadioButton rbBasic = new RadioButton("Uživatelské jméno a heslo");
        RadioButton rbCert = new RadioButton("Klientský certifikát (TLS)");
        rbNoAuth.setToggleGroup(authGroup);
        rbBasic.setToggleGroup(authGroup);
        rbCert.setToggleGroup(authGroup);

        TextField tfUser = new TextField(base.getUsername());
        tfUser.setPromptText("Uživatelské jméno");
        tfUser.setMaxWidth(Double.MAX_VALUE);

        PasswordField pfPass = new PasswordField();
        pfPass.setText(base.getPassword());
        pfPass.setMaxWidth(Double.MAX_VALUE);
        HBox passRow = revealableRow(pfPass);

        VBox basicBox = new VBox(6,
                new Label("Uživatelské jméno:"), tfUser,
                new Label("Heslo:"), passRow);
        basicBox.setPadding(new Insets(4, 0, 0, 22));

        TextField tfCertPath = new TextField(base.getClientCertPath());
        tfCertPath.setPromptText("C:\\cesta\\k\\certifikatu.p12");
        HBox.setHgrow(tfCertPath, Priority.ALWAYS);
        Button btnBrowse = new Button("Procházet…");
        btnBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Úložiště s klientským certifikátem");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Úložiště klíčů (*.p12, *.pfx, *.jks)",
                            "*.p12", "*.pfx", "*.jks", "*.keystore"),
                    new FileChooser.ExtensionFilter("Všechny soubory", "*.*"));
            java.io.File current = new java.io.File(tfCertPath.getText().trim());
            if (current.getParentFile() != null && current.getParentFile().isDirectory()) {
                fc.setInitialDirectory(current.getParentFile());
            }
            java.io.File chosen = fc.showOpenDialog(stage);
            if (chosen != null) tfCertPath.setText(chosen.getAbsolutePath());
        });
        HBox certPathRow = new HBox(8, tfCertPath, btnBrowse);
        certPathRow.setAlignment(Pos.CENTER_LEFT);

        PasswordField pfCertPass = new PasswordField();
        pfCertPass.setText(base.getClientCertPassword());
        pfCertPass.setMaxWidth(Double.MAX_VALUE);
        HBox certPassRow = revealableRow(pfCertPass);

        Label certHint = new Label(
                "Soubor PKCS#12 (.p12, .pfx) nebo JKS s privátním klíčem a certifikátem. "
                + "Aplikace si pamatuje cestu k němu, ne jeho obsah.");
        certHint.setStyle("-fx-font-size:11px;-fx-text-fill:#555555;");
        wrapping(certHint, 400);

        VBox certBox = new VBox(6,
                new Label("Soubor s certifikátem:"), certPathRow,
                new Label("Heslo k úložišti:"), certPassRow,
                certHint);
        certBox.setPadding(new Insets(4, 0, 0, 22));

        // Viditelnost obou sad se řídí přepínačem. Jednou vazbou, ne posloucháním na každém
        // tlačítku zvlášť: dvě nezávislé reakce na jeden výběr se dřív nebo později rozejdou.
        basicBox.visibleProperty().bind(rbBasic.selectedProperty());
        basicBox.managedProperty().bind(rbBasic.selectedProperty());
        certBox.visibleProperty().bind(rbCert.selectedProperty());
        certBox.managedProperty().bind(rbCert.selectedProperty());

        switch (base.auth()) {
            case CERTIFICATE -> rbCert.setSelected(true);
            case BASIC       -> rbBasic.setSelected(true);
            case NONE        -> rbNoAuth.setSelected(true);
        }

        // Sectigo pre-fill button
        Button btnSectigo = new Button("Předvyplnit Sectigo Qualified");
        btnSectigo.setStyle("-fx-font-size:11px;");
        btnSectigo.setOnAction(e -> {
            TsaProfile sec = TsaProfile.sectigoQualified();
            tfName.setText(sec.getName());
            tfUrl.setText(sec.getUrl());
            cbHash.setValue(sec.getHashAlgorithm());
            tfUser.clear();
            pfPass.clear();
            rbNoAuth.setSelected(true);
        });

        MenuButton btnDemo = new MenuButton("Předvyplnit PostSignum DEMO");
        btnDemo.setStyle("-fx-font-size:11px;");
        MenuItem miBasic = new MenuItem("…s jménem a heslem");
        miBasic.setOnAction(e -> {
            TsaProfile demo = TsaProfile.postSignumDemoBasic();
            tfName.setText(demo.getName());
            tfUrl.setText(demo.getUrl());
            cbHash.setValue(demo.getHashAlgorithm());
            tfUser.setText(demo.getUsername());
            pfPass.setText(demo.getPassword());
            rbBasic.setSelected(true);
        });
        MenuItem miCert = new MenuItem("…s klientským certifikátem");
        miCert.setOnAction(e -> {
            TsaProfile demo = TsaProfile.postSignumDemoCertificate();
            tfName.setText(demo.getName());
            tfUrl.setText(demo.getUrl());
            cbHash.setValue(demo.getHashAlgorithm());
            tfUser.clear();
            pfPass.clear();
            rbCert.setSelected(true);
        });
        btnDemo.getItems().addAll(miBasic, miCert);

        Label lblError = new Label();
        lblError.setStyle("-fx-text-fill:#cc0000;-fx-font-size:11px;");
        wrapping(lblError, 400);

        Button btnOk = new Button("OK");
        btnOk.setDefaultButton(true);
        btnOk.setOnAction(e -> {
            String url = tfUrl.getText().trim();
            if (url.isEmpty()) {
                lblError.setText("URL je povinné pole.");
                return;
            }
            boolean cert = rbCert.isSelected();
            String certPath = tfCertPath.getText().trim();
            if (cert && certPath.isEmpty()) {
                lblError.setText("Pro přihlášení certifikátem je potřeba zadat soubor s ním.");
                return;
            }
            if (cert && !new java.io.File(certPath).isFile()) {
                lblError.setText("Zadaný soubor s certifikátem neexistuje.");
                return;
            }
            TsaProfile p = new TsaProfile(
                    tfName.getText().trim(),
                    url,
                    rbBasic.isSelected() ? tfUser.getText().trim() : "",
                    rbBasic.isSelected() ? pfPass.getText() : "",
                    cbHash.getValue()
            );
            if (cert) {
                p.setClientCertPath(certPath);
                p.setClientCertPassword(pfCertPass.getText());
            }
            result[0] = p;
            stage.close();
        });

        Button btnCancel = new Button("Zrušit");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> stage.close());

        // Layout
        Label lblAuth = new Label("Přihlášení k serveru:");
        lblAuth.setStyle("-fx-text-fill:#555555;-fx-font-size:11px;");

        HBox presetRow = new HBox(8, btnSectigo, btnDemo);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(8);
        form.getChildren().addAll(
                presetRow,
                new Separator(),
                new Label("Název:"), tfName,
                new Label("URL:"), tfUrl,
                new Label("Hashovací funkce:"), cbHash,
                new Separator(),
                lblAuth,
                rbNoAuth, rbBasic, basicBox, rbCert, certBox,
                lblError,
                new HBox(8, btnOk, btnCancel)
        );

        VBox root = new VBox(form);
        root.setPadding(new Insets(20));
        // Šířka se zadává, ne odhaduje: zalamované popisky si výšku počítají z ní, a dokud ji
        // neměly odkud vzít, vycházela na jeden řádek a okno se nastavilo o dva nižší, než mělo.
        root.setPrefWidth(470);
        root.setMinWidth(430);

        // Přepnutí způsobu přihlášení mění výšku obsahu — certifikát potřebuje o tři řádky víc
        // než jméno s heslem. Bez tohohle přepočtu by okno zůstalo velké tak, jak se otevřelo,
        // a pole na heslo by zmizelo pod jeho spodní hranou.
        authGroup.selectedToggleProperty().addListener((obs, was, is) -> {
            if (stage.getScene() != null) stage.sizeToScene();
        });
        return root;
    }

    /**
     * Zalamovaný popisek, který se nenechá stlačit a přizná svou výšku.
     *
     * <p><b>Čím tahle metoda není:</b> opravou nálezu z 18. 9. 2026. To se změřilo a vyšlo
     * záporně — {@code TsaEditDialogSizeTest} projde i s původní podobou (pouhé
     * {@code setWrapText} + {@code setMaxWidth}), takže uříznuté okno působilo něco jiného; viz
     * {@link #showEditDialog}. Zůstává jako obrana proti dvěma pastem, které v JavaFX skutečně
     * jsou, a proti kterým holé {@code setWrapText} nechrání:
     *
     * <ul>
     *   <li><b>Výška z jednoho řádku.</b> Nejmenší výška popisku se počítá z jednoho řádku, takže
     *       rodič s pevnou velikostí se může nastavit na míru textu, který se zalomí do tří.
     *       Proti tomu je {@code USE_PREF_SIZE}.</li>
     *   <li><b>Šířka na jeden znak.</b> Nejmenší šířka zalamovaného popisku je jeden znak, takže
     *       úzký rodič ho na ni stlačí — a popisek si pak o výšku řekne podle toho. Naměřeno
     *       toutéž zkouškou na mezistupni téhle metody, kde byl strop volný a podlaha ne:
     *       popisek vyšel <b>11 px široký a 1568 px vysoký</b>. Proti tomu je {@code minWidth}.</li>
     * </ul>
     *
     * <p>Strop zůstává volný, aby se text v roztaženém okně roztáhl do šířky místo do výšky.
     *
     * @param width šířka, pod kterou se popisek stlačit nesmí, a zároveň ta, ze které si počítá
     *              výšku v okně otevřeném na výchozí velikost
     */
    private static void wrapping(Label l, double width) {
        l.setWrapText(true);
        l.setMinWidth(width);
        l.setPrefWidth(width);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(Region.USE_PREF_SIZE);
    }

    /**
     * Pole na heslo se zaškrtávátkem „Zobrazit“.
     *
     * <p>Vytaženo do metody, protože hesla jsou od 18. 9. 2026 v okně dvě — k serveru a k úložišti
     * klíčů — a druhá kopie téhož kusu by se od první dřív nebo později lišila.
     */
    private static HBox revealableRow(PasswordField hidden) {
        TextField shown = new TextField();
        shown.setMaxWidth(Double.MAX_VALUE);
        shown.textProperty().bindBidirectional(hidden.textProperty());
        shown.setVisible(false);
        shown.setManaged(false);

        StackPane pane = new StackPane(hidden, shown);
        HBox.setHgrow(pane, Priority.ALWAYS);

        CheckBox reveal = new CheckBox("Zobrazit");
        reveal.setStyle("-fx-font-size:11px;");
        reveal.selectedProperty().addListener((obs, old, show) -> {
            hidden.setVisible(!show);
            hidden.setManaged(!show);
            shown.setVisible(show);
            shown.setManaged(show);
        });

        HBox row = new HBox(8, pane, reveal);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
