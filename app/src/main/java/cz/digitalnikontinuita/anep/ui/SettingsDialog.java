package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SettingsDialog {

    /** True when the signature/seal-related settings match the strict standard combination. */
    private static boolean isSignatureStandardMode(AppSettings s) {
        return !s.isAcceptNewerOcsp()
                && !s.isAcceptExpiredRevocationSources()
                && !s.isIgnoreSha1Deprecated()
                && !s.isAcceptPdfNotEtsi()
                && !s.isUseCustomValidationTime();
    }

    /**
     * @param refreshLotl obnova důvěryhodných seznamů; vrací hlášku pro uživatele. Není to
     *                    {@code Runnable}, protože obnova může uspět jen zčásti — jeden národní
     *                    seznam vypadne a ostatní projdou — a takový výsledek není ani „hotovo",
     *                    ani výjimka. Dřív se v obou případech psalo „úspěšně obnoven".
     */
    public static void show(Window owner, AppSettings settings,
                            java.util.function.Supplier<String> refreshLotl,
                            cz.digitalnikontinuita.anep.core.TimestampingService timestamping) {
        show(owner, settings, refreshLotl, false, timestamping);
    }

    /**
     * @param startAdvanced otevřít rovnou pokročilou stránku. Doporučení v Analýze na ni odkazují
     *                      adresně — poslat čtenáře na základní stránku a nechat ho hledat
     *                      tlačítko by z jednoho kliknutí udělalo tři.
     */
    /**
     * @param timestamping služba k razítkům. Nastavení s ní samo nic nedělá a jen ji podává dál
     *                     do správce zdrojů: ten si od nově zadaného zdroje vyžádá razítko a
     *                     poměří je s důvěryhodnými seznamy, k čemuž potřebuje ověřovatele
     *                     certifikátů — a ten drží právě tahle služba.
     */
    public static void show(Window owner, AppSettings settings,
                            java.util.function.Supplier<String> refreshLotl,
                            boolean startAdvanced,
                            cz.digitalnikontinuita.anep.core.TimestampingService timestamping) {
        Stage stage = new Stage();
        stage.setTitle("Nastavení");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(false);

        VBox root = new VBox();

        // ================================================================
        // ADVANCED PANE — build controls first so basic pane can reference
        // ================================================================

        // --- Čas ověření ---
        Label headingTime = new Label("Čas ověření");
        headingTime.setStyle("-fx-font-weight:bold;-fx-font-size:14px;");

        CheckBox cbCustomTime = new CheckBox("Ověřit k zadanému datu a času");
        cbCustomTime.setSelected(settings.isUseCustomValidationTime());

        LocalDateTime initTime = settings.getCustomValidationTime() != null
                ? settings.getCustomValidationTime() : LocalDateTime.now();

        DatePicker datePicker = new DatePicker(initTime.toLocalDate());
        datePicker.setPrefWidth(140);

        Spinner<Integer> spinHour = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 23, initTime.getHour()));
        spinHour.setEditable(true);
        spinHour.setPrefWidth(65);

        Spinner<Integer> spinMinute = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 59, initTime.getMinute()));
        spinMinute.setEditable(true);
        spinMinute.setPrefWidth(65);

        Label colonLabel = new Label(":");
        colonLabel.setStyle("-fx-font-size:14px;-fx-padding:4 2 0 2;");

        HBox timeRow = new HBox(6, datePicker, spinHour, colonLabel, spinMinute);

        boolean initEnabled = cbCustomTime.isSelected();
        datePicker.setDisable(!initEnabled);
        spinHour.setDisable(!initEnabled);
        spinMinute.setDisable(!initEnabled);
        cbCustomTime.selectedProperty().addListener((obs, old, checked) -> {
            datePicker.setDisable(!checked);
            spinHour.setDisable(!checked);
            spinMinute.setDisable(!checked);
        });

        HBox customTimeRow = infoRow(cbCustomTime, makeInfoButton(stage, "Čas ověření",
                "DSS standardně ověřuje k aktuálnímu systémovému času. Zadáním vlastního data " +
                "a času lze ověřit, zda byl podpis platný v konkrétní historický okamžik — " +
                "užitečné například při ověřování starších dokumentů.\n\n" +
                "Volba platí na jedno ověření: po jeho dokončení se ověřuje zase k aktuálnímu " +
                "času, dokud si zadaný okamžik nenastavíte znovu."));

        // --- Uvolnění validační politiky ---
        Label headingPolicy = new Label("Uvolnění validační politiky DSS");
        headingPolicy.setStyle("-fx-font-weight:bold;-fx-font-size:14px;");

        Label notePolicy = new Label(
                "Změna se projeví až při příštím ověření dokumentu a platí jen pro ně — "
                + "po dokončeném ověření se nastavení vrátí do výchozího stavu.");
        notePolicy.setStyle("-fx-text-fill:#555555;-fx-font-size:11px;");
        notePolicy.setWrapText(true);
        notePolicy.setMaxWidth(420);

        CheckBox cbNewerOcsp = new CheckBox("Využij i revokační informace, vydané po expiraci certifikátu");
        cbNewerOcsp.setSelected(settings.isAcceptNewerOcsp());
        cbNewerOcsp.setWrapText(true);
        HBox newerOcspRow = infoRow(cbNewerOcsp, makeInfoButton(stage,
                "Využij i revokační informace, vydané po expiraci certifikátu",
                "DSS standardně odmítá revokační informace (OCSP i CRL), jejichž pole thisUpdate leží " +
                "po datu expirace certifikátu (notAfter) — taková data vznikají tehdy, kdy byl " +
                "čerstvý OCSP nebo aktuální CRL získán až po vypršení platnosti certifikátu. " +
                "Zaškrtnutím umožníte využití i takových revokačních informací jako dokladu " +
                "o stavu certifikátu."));

        CheckBox cbExpiredRevocSources = new CheckBox(
                cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName());
        cbExpiredRevocSources.setSelected(settings.isAcceptExpiredRevocationSources());
        cbExpiredRevocSources.setWrapText(true);
        HBox expiredRevocRow = infoRow(cbExpiredRevocSources, makeInfoButton(stage,
                cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName(),
                "DSS žádá, aby certifikát vydavatele revokační informace (respondéra OCSP nebo vydavatele " +
                "CRL) platil k rozhodnému okamžiku ověřování. Nesplnit se to dá na obě strany: buď už " +
                "certifikát vydavatele vypršel, nebo — ověřuje-li se k okamžiku v minulosti — tehdy ještě " +
                "vydaný nebyl, protože odpověď podepsal respondér zřízený až později. Zaškrtnutím " +
                "umožníte přijmout i takovou informaci, pokud byla v době svého vydání v pořádku."));

        CheckBox cbSha1 = new CheckBox("Ignorovat zastaralost algoritmu SHA1");
        cbSha1.setSelected(settings.isIgnoreSha1Deprecated());
        HBox sha1Row = infoRow(cbSha1, makeInfoButton(stage,
                "Ignorovat zastaralost algoritmu SHA1",
                "SHA1 je od roku 2022 v DSS považován za zastaralý. Tato volba posune datum " +
                "expirace na rok 2099 a potlačí tak chyby způsobené použitím SHA1 v podpisu " +
                "nebo certifikátech."));

        CheckBox cbPdfNotEtsi = new CheckBox("Ověřovat i podpisy ve formátu PDF-NOT-ETSI");
        cbPdfNotEtsi.setSelected(settings.isAcceptPdfNotEtsi());
        HBox pdfNotEtsiRow = infoRow(cbPdfNotEtsi, makeInfoButton(stage,
                "Ověřovat i podpisy ve formátu PDF-NOT-ETSI",
                "PDF dokumenty mohou obsahovat podpisy podle specifikace Adobe (sub-filtry jako " +
                "adbe.pkcs7.detached), které neodpovídají standardu ETSI/PAdES. DSS standardně " +
                "odmítá tyto podpisy jako formátově neplatné. Zaškrtnutím umožníte, aby aplikace " +
                "takovéto podpisy kryptograficky ověřila a zobrazila výsledek, i když neplní " +
                "požadavky na formát ETSI."));

        // --- Vlastní důvěryhodné certifikáty ---
        Label headingCustomTrust = new Label("Vlastní důvěryhodné certifikáty");
        headingCustomTrust.setStyle("-fx-font-weight:bold;-fx-font-size:14px;");

        Button btnManageTrust = new Button("Spravovat...");
        btnManageTrust.setOnAction(e -> TrustedCertificatesDialog.show(stage));

        HBox trustRow = new HBox(8, btnManageTrust, makeInfoButton(stage,
                "Vlastní důvěryhodné certifikáty",
                "Umožní přidat vlastní certifikáty (např. interní firemní CA), které se budou " +
                "při ověřování považovat za důvěryhodné navíc k důvěryhodným seznamům EU (EUTL). " +
                "Změny se projeví ihned, bez nutnosti restartu aplikace."));
        trustRow.setAlignment(Pos.CENTER_LEFT);

        // --- Vlastní CRL seznamy ---
        Label headingCustomCrl = new Label("Vlastní CRL seznamy");
        headingCustomCrl.setStyle("-fx-font-weight:bold;-fx-font-size:14px;");

        Button btnManageCrl = new Button("Spravovat...");
        btnManageCrl.setOnAction(e -> CustomCrlDialog.show(stage));

        HBox crlRow = new HBox(8, btnManageCrl, makeInfoButton(stage,
                "Vlastní CRL seznamy",
                "Umožní vložit vlastní CRL seznamy jako záložní zdroj revokačních informací. " +
                "Vložené CRL soubory jsou automaticky použity při ověřování jako záložní zdroj " +
                "tehdy, kdy online CRL není dostupné nebo bylo vydáno po expiraci certifikátu."));
        crlRow.setAlignment(Pos.CENTER_LEFT);

        // --- Zdroj časových razítek (TSA) ---
        Label headingTsa = new Label("Zdroj časových razítek (TSA)");
        headingTsa.setStyle("-fx-font-weight:bold;-fx-font-size:14px;");

        Button btnManageTsa = new Button("Spravovat...");
        btnManageTsa.setOnAction(e -> TsaManagerDialog.show(stage, settings, timestamping));

        HBox tsaRow = new HBox(8, btnManageTsa, makeInfoButton(stage,
                "Zdroj časových razítek (TSA)",
                "Nastavení TSA serverů pro přidávání časových razítek k podpisům. Lze nakonfigurovat " +
                "více serverů a vybrat jeden jako aktivní. Každý server má vlastní URL, " +
                "preferovaný hashovací algoritmus pro žádost o razítko a způsob přihlášení: " +
                "žádné, uživatelské jméno s heslem, nebo klientský certifikát (soubor PKCS#12 " +
                "nebo JKS s privátním klíčem).\n\n" +
                "Aplikace si od každého nově zadaného zdroje vyžádá jedno časové razítko. Z něj " +
                "zjistí, dokdy platí certifikát autority, které otiskové funkce zdroj bere — " +
                "a zda jsou jeho razítka kvalifikovaná: razítko se posoudí proti evropským " +
                "důvěryhodným seznamům stejně jako razítka nalezená v dokumentech. Zdroj, " +
                "u kterého kvalifikovaná služba nalezena není (typicky testovací, jako " +
                "PostSignum DEMO), se v seznamu označí a záložka Digitální kontinuita na to " +
                "upozorní u každého dokumentu, dokud zůstane vybraný.\n\n" +
                "Pro samotné ověřování podpisů toto nastavení není potřeba."));
        tsaRow.setAlignment(Pos.CENTER_LEFT);

        // --- Důvěryhodné seznamy EU ---
        Label headingLotl = new Label("Důvěryhodné seznamy EU (EUTL)");
        headingLotl.setStyle("-fx-font-weight:bold;-fx-font-size:14px;");

        Button btnRefreshLotl = new Button("Načíst seznam EUTL znovu");

        Label refreshStatus = new Label();
        refreshStatus.setStyle("-fx-font-size:11px;");

        btnRefreshLotl.setOnAction(e -> {
            btnRefreshLotl.setDisable(true);
            refreshStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#555555;");
            refreshStatus.setText("Načítám — může trvat desítky sekund...");
            Thread t = new Thread(() -> {
                try {
                    String msg = refreshLotl.get();
                    boolean allGood = msg != null && msg.startsWith("Hotovo");
                    Platform.runLater(() -> {
                        refreshStatus.setText(msg != null ? msg : "Hotovo.");
                        refreshStatus.setStyle("-fx-font-size:11px;-fx-text-fill:"
                                + (allGood ? "#1a7a1a" : "#8a6d00") + ";");
                        refreshStatus.setWrapText(true);
                        refreshStatus.setMaxWidth(420);
                        btnRefreshLotl.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        refreshStatus.setText("Chyba: " + ex.getMessage());
                        refreshStatus.setStyle("-fx-font-size:11px;-fx-text-fill:#cc0000;");
                        btnRefreshLotl.setDisable(false);
                    });
                }
            });
            t.setDaemon(true);
            t.start();
        });

        HBox lotlRow = new HBox(8, btnRefreshLotl, makeInfoButton(stage,
                "Důvěryhodné seznamy EU (EUTL)",
                "Aplikace uchovává stažený obsah seznamů EU v lokální mezipaměti a při spuštění " +
                "jej aktualizuje pouze tehdy, je-li mezipaměť starší než 24 hodin. " +
                "Tlačítkem lze vynutit okamžité stažení aktuálního obsahu."));
        lotlRow.setAlignment(Pos.CENTER_LEFT);

        // --- Advanced save/cancel ---
        Button btnSaveAdv = new Button("Uložit");
        btnSaveAdv.setStyle("-fx-font-size:12px;");
        Button btnCancelAdv = new Button("Zrušit");
        btnCancelAdv.setOnAction(e -> stage.close());

        // --- Advanced "back" button ---
        Button btnToBasic = new Button("← Základní nastavení");
        btnToBasic.setStyle("-fx-font-size:11px;");

        VBox advancedPane = new VBox(10);
        advancedPane.setPadding(new Insets(20));
        advancedPane.setMinWidth(440);
        advancedPane.getChildren().addAll(
                btnToBasic,
                new Separator(),
                headingTime,
                customTimeRow,
                timeRow,
                new Separator(),
                headingPolicy,
                notePolicy,
                new Separator(),
                newerOcspRow,
                new Separator(),
                expiredRevocRow,
                new Separator(),
                sha1Row,
                new Separator(),
                pdfNotEtsiRow,
                new Separator(),
                headingCustomTrust,
                trustRow,
                new Separator(),
                headingCustomCrl,
                crlRow,
                new Separator(),
                headingTsa,
                tsaRow,
                new Separator(),
                headingLotl,
                lotlRow,
                refreshStatus,
                new Separator(),
                new HBox(8, btnSaveAdv, btnCancelAdv)
        );

        // ================================================================
        // BASIC PANE
        // ================================================================

        CheckBox cbStandard = new CheckBox("Ověřování podpisů a pečetí dle standardů");
        cbStandard.setSelected(isSignatureStandardMode(settings));
        cbStandard.setStyle("-fx-font-size:13px;-fx-font-weight:bold;");

        String standardHint = (
                "Podpisy a pečeti jsou ověřovány přísně podle platných standardů — doporučené " +
                "nastavení pro běžné použití. Pro nastavení tolerantnějšího ověřování zrušte " +
                "zaškrtnutí tohoto check-boxu. Pro úpravu dílčích parametrů přejděte do " +
                "pokročilého nastavení. Úleva platí vždy jen na jedno ověření: po jeho dokončení " +
                "se sem přísný režim vrátí sám, aby se další dokument neposoudil mírněji, aniž " +
                "by o tom kdokoli věděl.");

        CheckBox cbTsStandards = new CheckBox("Ověřování časových razítek podle standardů");
        cbTsStandards.setSelected(settings.isTimestampStandardsMode());
        cbTsStandards.setStyle("-fx-font-size:13px;-fx-font-weight:bold;");

        // Znění od uživatele 13. 10. 2026.
        String tsStandardsHint = (
                "Zaškrtnuto (výchozí stav): DSS ověřuje časová razítka dle standardu ETSI — jako " +
                "platné může být ověřeno i takové časové razítko, které je založeno na již " +
                "expirovaném certifikátu. Rozhodující je to, zda vydavatel časového razítka je " +
                "stále uveden na důvěryhodném seznamu s kvalifikovaným statusem (tj. zda u něj " +
                "ještě nedošlo k tzv. sunsetu, neboli k odebrání kvalifikovaného statusu). " +
                "Zrušením zaškrtnutí je aktivován přísnější režim ověřování, jaký používají " +
                "některé validátory (např. CzechPOINTy či některé podatelny OVM): razítko může " +
                "být platné jen tehdy, pokud jeho certifikát (k rozhodnému okamžiku) ještě " +
                "neexpiroval. I tato volba platí jen na jedno ověření.");

        CheckBox cbExpert = new CheckBox("Expertní mód");
        cbExpert.setSelected(settings.isExpertMode());
        cbExpert.setStyle("-fx-font-size:13px;-fx-font-weight:bold;");

        String expertHint = (
                "Zpřístupní záložky Detailní rozbor a Protokoly DSS. Ty ukazují úplný technický " +
                "rozbor — jednotlivé otisky, certifikační cesty, revokační záznamy a protokoly " +
                "knihovny DSS v původní podobě. Ve výchozím stavu jsou skryté, protože k posouzení " +
                "platnosti podpisu nejsou potřeba a méně zkušeného čtenáře spíš zmatou. " +
                "Volba se nepamatuje: po každém spuštění je vypnutá. Změna se projeví až při " +
                "následujícím ověření.");

        Button btnToAdvanced = new Button("Pokročilé nastavení...");
        btnToAdvanced.setStyle("-fx-font-size:11px;");

        Button btnSaveBasic = new Button("Uložit");
        btnSaveBasic.setStyle("-fx-font-size:12px;");
        Button btnCancelBasic = new Button("Zrušit");
        btnCancelBasic.setOnAction(e -> stage.close());

        VBox basicPane = new VBox(12);
        basicPane.setPadding(new Insets(20));
        basicPane.setMinWidth(440);
        // Okno „O aplikaci" patří sem, a ne mezi nastavení: nic nenastavuje. Je to ale
        // jediné místo, kam se čtenář dostane odkudkoli a kde ho nic neruší od čtení —
        // a věta o tom, že aplikace slouží výuce a výsledky jsou bez záruky, musí být
        // po ruce vždycky, ne jen v patičce vytištěného protokolu.
        Button btnAbout = new Button("O aplikaci…");
        btnAbout.setOnAction(e -> AboutDialog.show(stage));

        // Popisy zaškrtávátek jsou od 13. 10. 2026 schované pod tlačítky „Podrobněji“
        // (pokyn uživatele): popis časových razítek se rozrostl na odstavec a tři takové pod
        // sebou dělaly ze základního nastavení stěnu textu, ve které se volby ztrácely.
        // Týž vzor jako v pokročilém nastavení — tlačítko vpravo otevře okénko s výkladem.
        basicPane.getChildren().addAll(
                infoRow(cbStandard, makeInfoButton(stage,
                        cbStandard.getText(), standardHint)),
                new Separator(),
                infoRow(cbTsStandards, makeInfoButton(stage,
                        cbTsStandards.getText(), tsStandardsHint)),
                new Separator(),
                infoRow(cbExpert, makeInfoButton(stage,
                        cbExpert.getText(), expertHint)),
                new Separator(),
                btnToAdvanced,
                new Separator(),
                btnAbout,
                new Separator(),
                new HBox(8, btnSaveBasic, btnCancelBasic)
        );

        // ================================================================
        // SWITCH LOGIC
        // ================================================================

        // Guard prevents the cbStandard listener from firing during programmatic updates
        boolean[] suppressStandardListener = {false};

        // Helper: is the current state of advanced controls = signature-standard?
        java.util.function.BooleanSupplier advancedIsStandard = () ->
                !cbNewerOcsp.isSelected()
                && !cbExpiredRevocSources.isSelected()
                && !cbSha1.isSelected()
                && !cbPdfNotEtsi.isSelected()
                && !cbCustomTime.isSelected();

        btnToAdvanced.setOnAction(e -> {
            root.getChildren().setAll(advancedPane);
            Platform.runLater(stage::sizeToScene);
        });

        btnToBasic.setOnAction(e -> {
            suppressStandardListener[0] = true;
            cbStandard.setSelected(advancedIsStandard.getAsBoolean());
            suppressStandardListener[0] = false;
            root.getChildren().setAll(basicPane);
            Platform.runLater(stage::sizeToScene);
        });

        // cbStandard controls only signature/seal-related advanced settings; cbTsStandards is independent
        cbStandard.selectedProperty().addListener((obs, old, checked) -> {
            if (suppressStandardListener[0]) return;
            if (checked) {
                cbNewerOcsp.setSelected(false);
                cbExpiredRevocSources.setSelected(false);
                cbSha1.setSelected(false);
                cbPdfNotEtsi.setSelected(false);
                cbCustomTime.setSelected(false);
            } else {
                cbNewerOcsp.setSelected(true);
                cbExpiredRevocSources.setSelected(true);
                cbSha1.setSelected(true);
                cbPdfNotEtsi.setSelected(true);
            }
        });

        // Uloží se stav ovládacích prvků, ne odvozenina z přehledového zaškrtávátka — a obě
        // tlačítka Uložit ukládají totéž. Dialog je jeden, nastavení je jedno; která z jeho dvou
        // stránek je zrovna vidět, nesmí rozhodovat o tom, co se zapíše.
        //
        // Dřív měla každá stránka vlastní zápis a každý zahazoval to, co bylo na té druhé.
        // Z pokročilé se nezapisoval režim razítek ani expertní mód. Ze základní se zapisovaly
        // tolerance podle jediného zaškrtávátka „dle standardů": odškrtnuté znamenalo všechny
        // čtyři zapnuté — i když si uživatel v pokročilém nastavení vybral třeba jen SHA-1 —
        // a zadaný čas ověření se přitom nepodmíněně mazal. Přitom to zaškrtávátko je jen
        // přehled: cesta zpět (btnToBasic) ho z pokročilých voleb dopočítává, takže jeho
        // odškrtnutí říká „něco je zapnuté", ne „je zapnuté všechno".
        //
        // Pro toho, kdo do pokročilého nastavení nevkročí, se nemění nic: posluchač u cbStandard
        // pokročilá zaškrtávátka rovnou nastaví, takže se odsud přečte přesně týž stav jako dřív.
        Runnable persist = () -> {
            settings.setAcceptNewerOcsp(cbNewerOcsp.isSelected());
            settings.setAcceptExpiredRevocationSources(cbExpiredRevocSources.isSelected());
            settings.setIgnoreSha1Deprecated(cbSha1.isSelected());
            settings.setAcceptPdfNotEtsi(cbPdfNotEtsi.isSelected());
            settings.setTimestampStandardsMode(cbTsStandards.isSelected());
            settings.setExpertMode(cbExpert.isSelected());
            settings.setUseCustomValidationTime(cbCustomTime.isSelected());
            if (cbCustomTime.isSelected()) {
                LocalDate date = datePicker.getValue() != null
                        ? datePicker.getValue() : LocalDate.now();
                settings.setCustomValidationTime(LocalDateTime.of(date,
                        java.time.LocalTime.of(spinHour.getValue(), spinMinute.getValue())));
            } else {
                settings.setCustomValidationTime(null);
            }
            try { settings.save(); } catch (Exception ex) { /* non-fatal */ }
            stage.close();
        };

        btnSaveBasic.setDefaultButton(true);
        btnSaveBasic.setOnAction(e -> persist.run());
        btnSaveAdv.setDefaultButton(true);
        btnSaveAdv.setOnAction(e -> persist.run());

        // ================================================================
        // SHOW
        // ================================================================

        root.getChildren().add(startAdvanced ? advancedPane : basicPane);
        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    /** Wraps a main control and an info button in an HBox with the main control expanding. */
    private static HBox infoRow(javafx.scene.Node main, Button info) {
        HBox.setHgrow(main, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        HBox row = new HBox(8, main, spacer, info);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Malé tlačítko „Podrobněji“, které otevře okénko s výkladem. Do 13. 10. 2026 se v pokročilém
     * nastavení jmenovalo „Podrobnosti“; sjednoceno se základním nastavením (pokyn uživatele).
     */
    private static Button makeInfoButton(Stage owner, String title, String text) {
        Button btn = new Button("Podrobněji");
        btn.setStyle("-fx-font-size:10px;");
        btn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(owner);
            alert.setTitle(title);
            alert.setHeaderText(null);
            Label content = new Label(text);
            content.setWrapText(true);
            content.setMaxWidth(430);
            alert.getDialogPane().setContent(content);
            alert.getDialogPane().setPrefWidth(480);
            alert.showAndWait();
        });
        return btn;
    }
}
