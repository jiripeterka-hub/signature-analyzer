package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.AppClassLoader;
import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.EvidenceRecordDetector;
import cz.digitalnikontinuita.anep.core.PdfOrphanSignatureDetector;
import cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor;
import cz.digitalnikontinuita.anep.core.PdfSource;
import cz.digitalnikontinuita.anep.core.PdfSigningToolExtractor;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.StartupTrace;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.pades.exception.InvalidPasswordException;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class MainView {

    private static final String ZONE_IDLE =
            "-fx-border-color: #aaaaaa; -fx-border-radius: 4; -fx-border-style: dashed; " +
            "-fx-background-radius: 4; -fx-background-color: #f8f8f8;";
    private static final String ZONE_HOVER =
            "-fx-border-color: #0078d7; -fx-border-radius: 4; -fx-border-style: solid; " +
            "-fx-background-radius: 4; -fx-background-color: #e6f2ff;";
    private static final String ZONE_FILLED =
            "-fx-border-color: #555555; -fx-border-radius: 4; -fx-border-style: solid; " +
            "-fx-background-radius: 4; -fx-background-color: #f0f0f0;";
    private static final String PLACEHOLDER = "Přetáhněte sem, nebo klikněte pro výběr";
    /**
     * Tlačítko pro externí obsah má dva stavy a plocha je nese: prázdné je tiché, s načteným
     * souborem se rozsvítí. Proto se tu, na rozdíl od příloh, plná plocha nedává natvrdo —
     * kdyby svítilo pořád, přestalo by to o čemkoli vypovídat. Mění se s ní i barva kresby.
     *
     * <p>Hnědá je táž jako u příloh, a je to tak správně: obojí je materiál, který k dokumentu
     * někdo přidal, ne výrok o něm. Modrá zůstává kontejneru, zelená ověření.
     */
    private static final String ORIG_BTN_STYLE = "-fx-padding: 0 14 0 14;";
    private static final String ORIG_BTN_STYLE_FILLED =
            "-fx-background-color: #7a4a00; -fx-background-radius: 4; -fx-padding: 0 14 0 14;";
    private static final String ORIG_BTN_STYLE_FILLED_HOVER =
            "-fx-background-color: #9c5f00; -fx-background-radius: 4; -fx-padding: 0 14 0 14;";
    private static final String ORIG_TOOLTIP_DEFAULT =
            "Volitelně: externí podpis nebo evidenční záznam\nPřetáhněte sem, nebo klikněte pro výběr";

    private final BorderPane root = new BorderPane();
    private final VBox statusBox = new VBox(1);
    private final ProgressIndicator progress = new ProgressIndicator();
    /**
     * Vzhled tlačítka, které otevírá kontejner. Emoji se v JavaFX vykresluje jednobarevně
     * náhradním fontem a v 16 px z něj zbyl tmavý flek — proto vektor, a proto plná plocha:
     * v úzkém stavovém řádku je barevná plocha vidět dřív než kresba na šedém podkladu.
     *
     * <p>Hover se musí obsloužit ručně jen u plné plochy. Jakmile se nastaví
     * {@code -fx-background-color}, výchozí vzhled tlačítka i s jeho reakcí na myš přestane
     * platit; tichá varianta si výchozí skin ponechá a nepotřebuje nic.
     */
    private static final String OPEN_BTN_PLAIN = "-fx-padding: 0 10 0 10;";

    /**
     * Tlačítko příloh. Hnědá je táž, jakou aplikace jinde píše poznámky k výsledku — příloha
     * je poznámka k dokumentu, ne výrok o něm, takže si nebere ani modrou kontejneru, ani
     * zelenou ověření.
     */
    private static final String ATTACH_BTN_FILLED =
            "-fx-background-color: #7a4a00; -fx-background-radius: 3; -fx-padding: 0 10 0 10;";
    private static final String ATTACH_BTN_FILLED_HOVER =
            "-fx-background-color: #9c5f00; -fx-background-radius: 3; -fx-padding: 0 10 0 10;";

    /**
     * Tlačítko Ověřit nese barvu, kterou aplikace jinde označuje platný výsledek. Na tlačítku
     * to není výrok: dokud není co ověřovat, je nedostupné, a JavaFX je celé ztlumí průhledností.
     */
    private static final String VALIDATE_BTN =
            "-fx-background-color: #1a7a1a; -fx-background-radius: 4; -fx-padding: 0 18 0 18;";
    private static final String VALIDATE_BTN_HOVER =
            "-fx-background-color: #229622; -fx-background-radius: 4; -fx-padding: 0 18 0 18;";
    private static final String OPEN_BTN_FILLED =
            "-fx-background-color: #003189; -fx-background-radius: 3; -fx-padding: 0 10 0 10;";
    private static final String OPEN_BTN_FILLED_HOVER =
            "-fx-background-color: #0a4bb5; -fx-background-radius: 3; -fx-padding: 0 10 0 10;";

    private Button statusOpenBtn;
    private javafx.scene.control.Tooltip statusOpenBtnTooltip;
    private Button statusAttachBtn;
    private Button statusPreviewBtn;
    /** Otevírá podepsaný dokument v přidružené aplikaci, když modré tlačítko patří odpojenému podpisu. */
    private Button statusDocOpenBtn;
    /** Byl náhled otevřený, když začalo nové ověření? Pak se po něm otevře znovu. */
    private boolean previewWasOpen;

    /**
     * Ikona nad mřížkou 24 × 24. Kreslí se tahy, ne výplní — v malé velikosti je obrys čitelnější
     * než plná silueta. Nová instance při každém použití, protože uzel scény smí mít jen jednoho
     * rodiče.
     *
     * <p><b>Měřítko je vlastnost tvaru, ne rozhodnutí o velikosti.</b> Každý tvar zabírá z mřížky
     * jinou plochu, takže shodné měřítko dá různě velké ikony; srovnané jsou až tyhle hodnoty,
     * doladěné vykreslováním vedle sebe a k 30. 8. 2026 uzavřené:
     *
     * <pre>
     *   0,85  krabice kontejneru, sponka příloh   — sedí v nižším stavovém řádku
     *   1,15  ozubené kolo nastavení              — vyplňuje mřížku skoro celou
     *   1,45  list externího obsahu               — vysoký, ale úzký (asi 14 × 18)
     *   1,70  fajfka ověření                      — z mřížky jen asi 14 × 10,5, a záměrně
     *                                               o stupeň větší: je to hlavní úkon aplikace
     * </pre>
     *
     * <p>Sjednotit je na jedno číslo „kvůli důslednosti“ znamená rozbít to, co je vidět. Kdyby
     * přibyla ikona, patří k ní vlastní číslo, zjištěné týmž porovnáním, ne převzaté od souseda.
     *
     * <p><b>Původ tvarů.</b> Ozubené kolo a list dokumentu jsou odvozeny ze sady <b>Feather</b>
     * (ikony {@code settings} a {@code file-text}, MIT, Copyright (c) 2013-2023 Cole Bemis) —
     * táž geometrie, jen převedená z relativních souřadnic na absolutní. Ověřeno porovnáním
     * koncových bodů 1. 9. 2026: shodný počáteční bod {@code M19.4 15}, shodné poloměry
     * 1,65 a 2 a shodné význačné body. Uvedení autorství je proto v NOTICE.txt povinné, ne
     * volitelné. Fajfka ověření a zaoblený pruh předlohu nemají.
     */
    private static javafx.scene.shape.SVGPath strokeIcon(String content,
                                                         javafx.scene.paint.Color stroke,
                                                         double scale) {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent(content);
        icon.setFill(null);
        icon.setStroke(stroke);
        icon.setStrokeWidth(2);
        icon.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        icon.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        return icon;
    }

    /**
     * Fajfka tlačítka Ověřit. Týž symbol, jakým aplikace jinde označuje platný výsledek, a je to
     * vědomé: uživatel jej tu čte jako „prověř to“, ne jako výrok, protože stojí na tlačítku
     * a dokud není co ověřovat, je tlačítko nedostupné.
     *
     * <p>Měřítko 1,7, a ne 1,15 jako u sousedů — není to nedůslednost. Fajfka zabírá z mřížky
     * 24 × 24 jen asi 14 × 10,5, kdežto ozubené kolo skoro celou; při stejném měřítku proto
     * vychází zřetelně menší. Porovnáno vykreslením čtyř měřítek vedle kola a listu: opticky
     * stejně velké jsou při 1,5, tady je zvolena o stupeň větší, aby fajfka převažovala —
     * je to hlavní úkon celé aplikace.
     */
    private static javafx.scene.shape.SVGPath verifyIcon() {
        return strokeIcon("M5 12.5 L10 17.5 L19 7", javafx.scene.paint.Color.WHITE, 1.7);
    }

    /**
     * Ozubené kolo nastavení. Jediná ikona v hlavičce, která si nebere žádnou barvu — zůstává
     * v barvě textu, protože nastavení není ani úkon nad dokumentem, ani druh materiálu; je to
     * cesta jinam. Barevná by si nárokovala pozornost, kterou nepotřebuje.
     */
    private static javafx.scene.shape.SVGPath settingsIcon() {
        return strokeIcon("M19.4 15 A1.65 1.65 0 0 0 19.73 16.82 L19.79 16.88 A2 2 0 1 1 16.96 19.71 L16.9 19.65 A1.65 1.65 0 0 0 15.08 19.32 A1.65 1.65 0 0 0 14 20.83 L14 21 A2 2 0 1 1 10 21 L10 20.91 A1.65 1.65 0 0 0 8.91 19.4 A1.65 1.65 0 0 0 7.09 19.73 L7.03 19.79 A2 2 0 1 1 4.2 16.96 L4.26 16.9 A1.65 1.65 0 0 0 4.59 15.08 A1.65 1.65 0 0 0 3.08 14 L3 14 A2 2 0 1 1 3 10 L3.09 10 A1.65 1.65 0 0 0 4.6 8.91 A1.65 1.65 0 0 0 4.27 7.09 L4.21 7.03 A2 2 0 1 1 7.04 4.2 L7.1 4.26 A1.65 1.65 0 0 0 8.92 4.59 A1.65 1.65 0 0 0 10 3.08 L10 3 A2 2 0 1 1 14 3 L14 3.09 A1.65 1.65 0 0 0 15.09 4.6 A1.65 1.65 0 0 0 16.91 4.27 L16.97 4.21 A2 2 0 1 1 19.8 7.04 L19.74 7.1 A1.65 1.65 0 0 0 19.41 8.92 A1.65 1.65 0 0 0 20.92 10 L21 10 A2 2 0 1 1 21 14 L20.91 14 A1.65 1.65 0 0 0 19.4 15 Z M15 12 A3 3 0 1 1 9 12 A3 3 0 1 1 15 12",
                javafx.scene.paint.Color.web("#333333"), 1.15);
    }

    /**
     * List s plusem — „přidej soubor, který k dokumentu patří“. Tlačítko bere externí podpis nebo
     * evidenční záznam, tedy jeden konkrétní soubor, ne adresář; složka z emoji 📂 to říkala
     * nepřesně a v tahové kresbě se jí navíc překrývaly panely do nečitelného hrbolu.
     *
     * <p>Netluče se ani se sponkou příloh, ani s krabicí kontejneru — každá věc v hlavičce má
     * svůj tvar.
     *
     * <p>Měřítko 1,45, a ne 1,15 jako ozubené kolo: list je z mřížky 24 × 24 vysoký, ale úzký
     * (asi 14 × 18), kdežto kolo ji vyplní skoro celou, takže při shodném měřítku vycházel menší.
     * Porovnáno vykreslením čtyř měřítek vedle kola a fajfky. Táž úvaha jako u fajfky — číslo
     * měřítka není totéž co optická velikost.
     *
     * @param onFill bílá na hnědé ploše, když je soubor načtený; jinak hnědá kresba na tichém
     *               tlačítku. Barva je součástí stavu, ne jen vzhledu.
     */
    private static javafx.scene.shape.SVGPath externalContentIcon(boolean onFill) {
        return strokeIcon("M14 3 H7 A2 2 0 0 0 5 5 V19 A2 2 0 0 0 7 21 H17 A2 2 0 0 0 19 19 V8 Z"
                + " M14 3 V8 H19 M12 12 V17 M9.5 14.5 H14.5",
                onFill ? javafx.scene.paint.Color.WHITE
                       : javafx.scene.paint.Color.web("#7a4a00"), 1.45);
    }

    /**
     * Kancelářská sponka — podoba zůstala, změnilo se jen provedení: z emoji na tahy a z náhradního
     * fontu na bílou v hnědé ploše. Sponka je pro přílohu ustálený obrázek, není co vymýšlet.
     */
    private static javafx.scene.shape.SVGPath attachmentIcon() {
        return strokeIcon("M15 7 L8.5 13.5 A2.5 2.5 0 0 0 12 17 L18.5 10.5"
                + " A5 5 0 0 0 11.5 3.5 L5 10 A7.5 7.5 0 0 0 15.5 20.5 L20 16",
                javafx.scene.paint.Color.WHITE, 0.85);
    }

    /**
     * List s lupou — „podívat se do dokumentu“.
     *
     * <p><b>Vybráno uživatelem nad běžící aplikací</b> (18. 9. 2026). Šest variant — samotné
     * obroučky, brýle s nožičkami, hranaté obroučky, čtecí půlměsíčky, oko a tenhle list s lupou —
     * jsem mu nejdřív ukázal vykreslené vedle sebe, pak dočasně nasadil všechny naráz do pruhu
     * stavu, ve skutečném stylu a velikosti. Vyhrála ta poslední.
     *
     * <p>Je to <b>jediná varianta, která říká, čeho se náhled týká</b>: ostatní slibují „dívat se“,
     * tahle „dívat se do dokumentu“. To je u tlačítka, které sedí vedle „otevřít jinde“
     * a „přílohy“, ten rozdíl, o který jde.
     *
     * <p>Namítal jsem, že v třiceti pixelech má ze všech nejvíc tahů a že se vedle drobné sponky
     * příloh slije. Nad běžící aplikací to neobstálo — a je to už podruhé, co odhad nad kresbou
     * dopadl jinak než pohled na hotové tlačítko. <b>O ikoně rozhoduje vykreslení, ne úvaha nad
     * cestou.</b>
     *
     * <p>Tvar se netluče s ničím jiným v hlavičce: list s plusem je <i>jiný</i> list a stojí
     * v druhém rohu okna, sponka je oblouk a krabice má víko.
     *
     * <p>Měřítko 1,25, tedy totéž, v jakém se varianty porovnávaly.
     */
    private static javafx.scene.shape.SVGPath previewIcon() {
        return strokeIcon("M13 2.8 H6.2 a1.6 1.6 0 0 0 -1.6 1.6 v15.2 a1.6 1.6 0 0 0 1.6 1.6 H10"
                + " M13 2.8 L17.6 7.4 v3.4"
                + " M18.8 15.4 a3.3 3.3 0 1 1 -6.6 0 a3.3 3.3 0 1 1 6.6 0"
                + " M17.9 17.8 L21.2 21.1",
                javafx.scene.paint.Color.web("#003189"), 1.25);
    }

    /** Archivní krabice: víko, tělo a úchyt. Bílá, protože leží na modré ploše. */
    private static javafx.scene.shape.SVGPath containerIcon() {
        return strokeIcon("M4.5 4 H19.5 A1.5 1.5 0 0 1 21 5.5 V6.5 A1.5 1.5 0 0 1 19.5 8 H4.5"
                + " A1.5 1.5 0 0 1 3 6.5 V5.5 A1.5 1.5 0 0 1 4.5 4 Z"
                + " M5 8 V18 A2 2 0 0 0 7 20 H17 A2 2 0 0 0 19 18 V8"
                + " M10 12 H14", javafx.scene.paint.Color.WHITE, 0.85);
    }

    /**
     * Šipka ven z rámečku — „otevře se to jinde“. Sloveso, ne podstatné jméno: tlačítko soubor
     * předá systému, nedělá náhled. Proto ne oko a ne list dokumentu, které se čtou jinak.
     *
     * <p>Měřítko má větší než ikony na plné ploše. Bez barevného podkladu působí týž tvar menší,
     * takže 0,85 vedle modré krabice mizelo. Porovnáno vykreslením ve čtyřech měřítkách:
     * při 1,05 jsou obě opticky stejně velké, 1,15 je o kus větší a vyplní tlačítko skoro celé —
     * a to je zvolená hodnota, protože o tuhle ikonu jde víc než o srovnané okraje.
     */
    private static javafx.scene.shape.SVGPath openExternallyIcon() {
        return strokeIcon("M15 4 H20 V9 M20 4 L13 11"
                + " M18 13 V18 A2 2 0 0 1 16 20 H6 A2 2 0 0 1 4 18 V8 A2 2 0 0 1 6 6 H11",
                javafx.scene.paint.Color.web("#003189"), 1.15);
    }
    private javafx.scene.control.Tooltip statusAttachBtnTooltip;
    private final ResultView resultView = new ResultView();
    private final ExtendedView extendedView = new ExtendedView();
    private final AdvisorView advisorView = new AdvisorView();
    private final ContinuityView continuityView = new ContinuityView();
    private Tab summaryTab;
    private final DetailedReportView detailedReportView = new DetailedReportView();
    private final ProtocolView protokolView = new ProtocolView();

    private final AppSettings appSettings = AppSettings.load();

    private final DocumentLoader documentLoader = new DocumentLoader();
    private final TrustConfig trustConfig = new TrustConfig(appSettings);
    private final ValidatorFactory validatorFactory = new ValidatorFactory(appSettings);

    /**
     * Which of the enabled tolerances each signature genuinely could not do without.
     *
     * <p>Měří to {@link cz.digitalnikontinuita.anep.core.RelaxationMeasurement}; tady zůstalo
     * jen doplnění vstupů, které má po ruce {@code MainView}. Přesunuto tam 19. 9. 2026 proto,
     * že mimo tuhle třídu měření nikdo neměl — {@code CardSnapshot} mapoval bez něj a kreslil
     * kartu, jakou uživatel nedostane.
     */
    private java.util.Map<String, ReportMapper.RequiredRelaxations> requiredRelaxations(
            ValidationOutcome lenientOutcome, DSSDocument sigDoc, DSSDocument detachedContent,
            CommonCertificateVerifier verifier, char[] pdfPassword,
            java.util.List<DSSDocument> evidenceRecords) {
        return cz.digitalnikontinuita.anep.core.RelaxationMeasurement.measure(
                appSettings, lenientOutcome, sigDoc, detachedContent, verifier, pdfPassword,
                evidenceRecords);
    }

    private final ReportMapper reportMapper = new ReportMapper();

    private final AtomicReference<CommonCertificateVerifier> verifierRef = new AtomicReference<>();
    /**
     * Důvěryhodné seznamy se nenačetly a ověřuje se v offline režimu. Neznámý certifikát je pak
     * vinou prostředí, ne podpisu — to potřebuje blok o vkladové listině.
     */
    private volatile boolean trustListsOffline;
    private final cz.digitalnikontinuita.anep.core.AugmentationService augmentationService =
            new cz.digitalnikontinuita.anep.core.AugmentationService(verifierRef);
    private final cz.digitalnikontinuita.anep.core.TimestampingService timestampingService =
            new cz.digitalnikontinuita.anep.core.TimestampingService(verifierRef);
    private final cz.digitalnikontinuita.anep.core.FixationService fixationService =
            new cz.digitalnikontinuita.anep.core.FixationService(verifierRef);
    // No verifier: re-stamping applies no usability rule, so it has nothing to ask one about.
    private final cz.digitalnikontinuita.anep.core.RestampingService restampingService =
            new cz.digitalnikontinuita.anep.core.RestampingService();
    private final cz.digitalnikontinuita.anep.core.SignatureExtensionService extensionService =
            new cz.digitalnikontinuita.anep.core.SignatureExtensionService(verifierRef);

    private File sigFile;
    private File origFile;
    private File lastSigFile;
    private File lastOrigFile;
    /**
     * Běželo od posledního zadání dokumentu ověření?
     *
     * <p>Rozlišuje dvě věci, které {@link #acceptSigFile} jinak nerozezná: <b>doplnění</b>
     * dvojice (dokument k už zadanému externímu podpisu) a <b>nový případ</b> (dokument
     * předložený poté, co předchozí ověření skončilo). V prvním případě se druhý slot nechává
     * stát, ve druhém se zapomene — viz {@code acceptSigFile}.
     *
     * <p>Příznak se nastavuje při <b>spuštění</b> ověřování, ne při jeho dokončení. Cest ven
     * z {@code analyze()} je několik (prvek ke čtení, náhled do OOXML, nepodporovaný formát,
     * chyba) a stačí, aby jedna z nich {@code resetFileZones()} minula, a slot by se držel dál.
     * Začátek je jeden.
     */
    private boolean verificationRan;
    private Label sigNameLabel;
    private VBox sigZone;
    private Button origButton;
    private javafx.scene.control.Tooltip origTooltip;
    private Button validateButton;
    private boolean lotlReady = false;

    private TabPane tabPane;
    /** Detailní rozbor and Protokoly DSS, shown only in expert mode. */
    private List<Tab> expertTabs = List.of();
    /** Whether those tabs are currently in the pane — kept so we only touch it when it changes. */
    private boolean expertTabsShown = false;

    public MainView() {
        StartupTrace.mark("MainView fields initialized (views + settings + trust config)");
        progress.setMaxSize(22, 22);
        progress.setManaged(true);

        Consumer<File> onSigFile = this::acceptSigFile;

        // Klíč autority z každého skutečného razítka do nastavení — podle něj dialog zdrojů
        // upozorní, že přestane být spolehlivý dřív, než skončí certifikát (5. 10. 2026).
        // Razítka chodí z vláken na pozadí, nastavení se zapisuje z vlákna JavaFX.
        cz.digitalnikontinuita.anep.core.TsaSigningKey.observer = (profile, key) ->
                javafx.application.Platform.runLater(
                        () -> appSettings.rememberTsaSigningKey(profile, key));

        Button restoreLastButton = new Button("↺");
        restoreLastButton.setStyle("-fx-font-size: 26px; -fx-padding: 0 6 0 6; -fx-min-width: 30;");
        javafx.scene.control.Tooltip.install(restoreLastButton,
                new javafx.scene.control.Tooltip("Obnovit naposledy zadaný obsah"
                        + " (po spuštění aplikace naposledy ověřený)"
                        + "\nPravým tlačítkem: naposledy ověřované soubory"));
        // Pravé tlačítko: posledních deset ověřených souborů (či dvojic). Nabídka se staví při
        // každém otevření, protože seznam se mění s každým ověřením a soubory mezitím mohou zmizet.
        restoreLastButton.setOnContextMenuRequested(e -> {
            RecentFilesWindow.show(restoreLastButton.getScene() != null
                            ? restoreLastButton.getScene().getWindow() : null,
                    e.getScreenX(), e.getScreenY(), entry -> {
                        acceptSigFile(entry.document());
                        if (entry.external() != null) acceptOrigFile(entry.external());
                    });
            e.consume();
        });
        restoreLastButton.setOnAction(e -> {
            // Vrací obsah, ne nastavení — a nesmí se na nastavení sáhnout ani nechtěně. Ověření
            // zóny uklidí a politiku vrátí do výchozího stavu; tohle tlačítko je způsob, jak týž
            // dokument poslat znovu s něčím jiným zaškrtnutým, takže co si uživatel mezitím
            // nastavil, musí zůstat stát až do toho dalšího ověření.
            //
            // Odložit stranou dřív, než se sáhne na zóny — a od 20. 9. 2026 to zase není jen
            // opatrnost: acceptSigFile() po dokončeném ověření druhý slot i jeho paměť maže,
            // takže bez téhle dvojice řádků by obnova vrátila dokument a externí podpis by
            // cestou zahodila.
            File sig = lastSigFile;
            File orig = lastOrigFile;
            // Po startu aplikace ještě nic zadáno nebylo: vezme se nejnovější položka seznamu
            // naposledy ověřovaných souborů, pokud její soubory pořád existují. V průběhu běhu
            // platí dál to, co bylo naposledy zadáno, i když se to ještě neověřilo.
            if (sig == null) {
                var recent = cz.digitalnikontinuita.anep.core.RecentFiles.load();
                if (!recent.isEmpty() && recent.get(0).exists()) {
                    sig = recent.get(0).document();
                    orig = recent.get(0).external();
                }
            }
            if (sig != null) onSigFile.accept(sig);
            if (orig != null) acceptOrigFile(orig);
        });
        restoreLastButton.setOnMouseClicked(javafx.event.Event::consume);

        sigZone = makeDropZone(
                "Dokument/soubor s podepisovaným obsahem",
                "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #222222;",
                List.of(
                        new FileChooser.ExtensionFilter("Podepsané dokumenty",
                                "*.pdf", "*.asice", "*.asics", "*.sce", "*.scs", "*.zip",
                                "*.p7s", "*.p7m", "*.cms", "*.xml",
                                "*.json", "*.jose", "*.jws",
                                // Datové zprávy z datových schránek (ISDS)
                                "*.zfo",
                                // Evidence records: .ers/.er for RFC 4998, XMLERS shares *.xml
                                "*.ers", "*.er"),
                        new FileChooser.ExtensionFilter("Všechny soubory", "*.*")),
                onSigFile, restoreLastButton);
        // With headerExtra: zone→HBox(0)→VBox(0)→Label(1)
        HBox sigInnerRow = (HBox) sigZone.getChildren().get(0);
        VBox sigTextContent = (VBox) sigInnerRow.getChildren().get(0);
        sigNameLabel = (Label) sigTextContent.getChildren().get(1);

        origTooltip = new javafx.scene.control.Tooltip(ORIG_TOOLTIP_DEFAULT);
        origButton = new Button();
        origButton.setGraphic(externalContentIcon(false));
        origButton.setStyle(ORIG_BTN_STYLE);
        // Ruční jen pro plnou plochu; tiché tlačítko si reakci na myš obstará výchozím skinem.
        origButton.setOnMouseEntered(e -> {
            if (ORIG_BTN_STYLE_FILLED.equals(origButton.getStyle())) {
                origButton.setStyle(ORIG_BTN_STYLE_FILLED_HOVER);
            }
        });
        origButton.setOnMouseExited(e -> {
            if (ORIG_BTN_STYLE_FILLED_HOVER.equals(origButton.getStyle())) {
                origButton.setStyle(ORIG_BTN_STYLE_FILLED);
            }
        });
        origButton.setMaxHeight(Double.MAX_VALUE);
        javafx.scene.control.Tooltip.install(origButton, origTooltip);
        origButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Vyberte soubor");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Všechny soubory", "*.*"));
            Stage stage = (Stage) origButton.getScene().getWindow();
            File f = chooser.showOpenDialog(stage);
            // Zrušený výběr nad obsazeným tlačítkem znamená „ber zpět“. Je to jediná cesta, jak
            // sem vloženou položku odstranit bez ověření — zadání dokumentu ji od 08a6135 nechává
            // stát. Nad prázdným tlačítkem zrušení nedělá nic, není co brát.
            if (f != null) acceptOrigFile(f);
            else if (origFile != null) clearOrigFile();
        });
        origButton.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        origButton.setOnDragDropped(e -> {
            var db = e.getDragboard();
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                acceptOrigFile(db.getFiles().get(0));
                e.setDropCompleted(true);
            } else {
                e.setDropCompleted(false);
            }
            e.consume();
        });

        validateButton = new Button();
        validateButton.setGraphic(verifyIcon());
        validateButton.setStyle(VALIDATE_BTN);
        validateButton.setOnMouseEntered(e -> {
            if (!validateButton.isDisabled()) validateButton.setStyle(VALIDATE_BTN_HOVER);
        });
        validateButton.setOnMouseExited(e -> validateButton.setStyle(VALIDATE_BTN));
        validateButton.setMaxHeight(Double.MAX_VALUE);
        javafx.scene.control.Tooltip.install(validateButton, new javafx.scene.control.Tooltip("Ověřit"));
        validateButton.setDisable(true);
        validateButton.setDefaultButton(true);
        validateButton.setOnAction(e -> analyzeIfReady());

        Button settingsButton = new Button();
        settingsButton.setGraphic(settingsIcon());
        settingsButton.setStyle("-fx-padding: 0 14 0 14;");
        settingsButton.setMaxHeight(Double.MAX_VALUE);
        javafx.scene.control.Tooltip.install(settingsButton, new javafx.scene.control.Tooltip("Nastavení"));
        settingsButton.setOnAction(e -> {
            SettingsDialog.show(settingsButton.getScene().getWindow(), appSettings,
                    this::refreshTrustListsFromSettings, timestampingService);
            refreshValidateButton();
        });

        // Náhled obsahu. Vlevo od „↗“ schválně: vedlejší tlačítko otevírá dokument v přidružené
        // aplikaci, tohle v AnEP — dvě odpovědi na touž potřebu vedle sebe. Rozhodnutí uživatele
        // z 18. 9. 2026; podrobnosti v docs/nahled-pdf-navrh.md, oddíl 6.
        statusPreviewBtn = new Button();
        statusPreviewBtn.setGraphic(previewIcon());
        statusPreviewBtn.setVisible(false);
        statusPreviewBtn.setManaged(false);
        statusPreviewBtn.setMinHeight(0);
        statusPreviewBtn.setPrefHeight(1);
        statusPreviewBtn.setMaxHeight(Double.MAX_VALUE);
        statusPreviewBtn.setStyle(OPEN_BTN_PLAIN);
        javafx.scene.control.Tooltip.install(statusPreviewBtn,
                new javafx.scene.control.Tooltip("Náhled obsahu dokumentu"));

        statusOpenBtn = new Button("↗");
        // Poznává se podle stylu, ne podle grafiky: ikonu má nově i tichý vzhled, a ten si
        // reakci na myš obstará sám výchozím skinem tlačítka.
        statusOpenBtn.setOnMouseEntered(e -> {
            if (OPEN_BTN_FILLED.equals(statusOpenBtn.getStyle())) {
                statusOpenBtn.setStyle(OPEN_BTN_FILLED_HOVER);
            }
        });
        statusOpenBtn.setOnMouseExited(e -> {
            if (OPEN_BTN_FILLED_HOVER.equals(statusOpenBtn.getStyle())) {
                statusOpenBtn.setStyle(OPEN_BTN_FILLED);
            }
        });
        statusOpenBtn.setVisible(false);
        statusOpenBtn.setManaged(false);
        statusOpenBtn.setMinHeight(0);
        statusOpenBtn.setPrefHeight(1);
        statusOpenBtn.setMaxHeight(Double.MAX_VALUE);
        statusOpenBtn.setStyle("-fx-font-size: 16px; -fx-padding: 0 10 0 10;");
        statusOpenBtnTooltip = new javafx.scene.control.Tooltip("Otevřít v přidružené aplikaci");
        javafx.scene.control.Tooltip.install(statusOpenBtn, statusOpenBtnTooltip);

        // U odpojeného podpisu (dokument + .p7s/.asics) zabere „↗“ kontejner s podpisem a samotný
        // dokument by se jinak do přidružené aplikace dostat nedal. Dostává proto vlastní tiché
        // tlačítko mezi náhledem a kontejnerem.
        statusDocOpenBtn = new Button();
        statusDocOpenBtn.setGraphic(openExternallyIcon());
        statusDocOpenBtn.setVisible(false);
        statusDocOpenBtn.setManaged(false);
        statusDocOpenBtn.setMinHeight(0);
        statusDocOpenBtn.setPrefHeight(1);
        statusDocOpenBtn.setMaxHeight(Double.MAX_VALUE);
        statusDocOpenBtn.setStyle(OPEN_BTN_PLAIN);
        javafx.scene.control.Tooltip.install(statusDocOpenBtn,
                new javafx.scene.control.Tooltip("Otevřít dokument v přidružené aplikaci"));

        // A PDF with attachments is a container too, but it still belongs in the platform viewer —
        // so it gets a button of its own rather than taking the one beside it away.
        statusAttachBtn = new Button();
        statusAttachBtn.setGraphic(attachmentIcon());
        statusAttachBtn.setOnMouseEntered(e -> statusAttachBtn.setStyle(ATTACH_BTN_FILLED_HOVER));
        statusAttachBtn.setOnMouseExited(e -> statusAttachBtn.setStyle(ATTACH_BTN_FILLED));
        statusAttachBtn.setVisible(false);
        statusAttachBtn.setManaged(false);
        statusAttachBtn.setMinHeight(0);
        statusAttachBtn.setPrefHeight(1);
        statusAttachBtn.setMaxHeight(Double.MAX_VALUE);
        statusAttachBtn.setStyle(ATTACH_BTN_FILLED);
        statusAttachBtnTooltip = new javafx.scene.control.Tooltip("Zobrazit přílohy dokumentu");
        javafx.scene.control.Tooltip.install(statusAttachBtn, statusAttachBtnTooltip);

        ContainerView.setOpenInAnepHandler(this::verifyExtracted);
        // Verifying one of a group record's other documents is the ordinary single-document
        // flow with the same record attached — so it goes through the same two zones.
        EvidenceRecordCoverageWindow.setVerifyInAnepHandler((doc, record) -> {
            acceptSigFile(doc);
            acceptOrigFile(record);
            analyzeIfReady();
        });

        HBox.setHgrow(statusBox, Priority.ALWAYS);
        setStatus("Načítám důvěryhodné seznamy EU…");

        HBox fileRow = new HBox(8, sigZone, validateButton, settingsButton, origButton);
        fileRow.setFillHeight(true);
        HBox.setHgrow(sigZone, Priority.ALWAYS);

        HBox statusRow = new HBox(8, progress, statusPreviewBtn, statusDocOpenBtn, statusOpenBtn,
                statusAttachBtn, statusBox);
        statusRow.setFillHeight(true);

        VBox toolbar = new VBox(8, fileRow, statusRow);
        toolbar.setPadding(new Insets(10));

        advisorView.setLenientVerifyCallback(this::performLenientVerify);
        advisorView.setLandRegistryVerifyCallback(this::performLandRegistryVerify);
        // Doporučení v Analýze na Nastavení jen neodkazují slovy, ale otevřou ho — a rovnou tu
        // stránku, o které mluví.
        advisorView.setSettingsOpener(advanced -> {
            SettingsDialog.show(root.getScene().getWindow(), appSettings,
                    this::refreshTrustListsFromSettings, advanced, timestampingService);
            refreshValidateButton();
        });
        wireServices(advisorView);

        // Protokol umí přiložit ověřovaný soubor a nechat si přidat razítko; obojí se uživatele
        // ptá až u tlačítka. Zdroj razítek se čte v okamžiku razítkování, ne teď — uživatel ho
        // může v nastavení přepnout kdykoli mezi ověřením a generováním protokolu.
        PrintUtil.ProtocolExtras protocolExtras = new PrintUtil.ProtocolExtras() {
            @Override
            public String tsaName() {
                cz.digitalnikontinuita.anep.core.TsaProfile tsa = appSettings.getSelectedTsa();
                return tsa != null ? tsa.getName() : null;
            }

            @Override
            public cz.digitalnikontinuita.anep.core.TsaProfile.Quality tsaQuality() {
                cz.digitalnikontinuita.anep.core.TsaProfile tsa = appSettings.getSelectedTsa();
                return tsa != null ? tsa.getQuality()
                        : cz.digitalnikontinuita.anep.core.TsaProfile.Quality.UNKNOWN;
            }

            @Override
            public void stamp(java.io.File pdf) throws Exception {
                cz.digitalnikontinuita.anep.core.TsaProfile tsa = appSettings.getSelectedTsa();
                if (tsa == null) throw new IllegalStateException("Není vybrán zdroj časových razítek");
                var result = timestampingService.addTimestamp(pdf, null, tsa,
                        cz.digitalnikontinuita.anep.core.TimestampingService.resolveAlgorithm(
                                tsa.getHashAlgorithm()));
                try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(pdf.toPath())) {
                    result.doc().writeTo(os);
                }
                // Autorita si certifikát obmění, kdy uzná za vhodné; razítko je jediná chvíle,
                // kdy se to dá poznat, a Analýza ten údaj čte.
                appSettings.rememberTsaCertNotAfter(tsa, result.tsaCertNotAfter());
            }
        };
        resultView.setProtocolExtras(protocolExtras);
        extendedView.setProtocolExtras(protocolExtras);
        detailedReportView.setProtocolExtras(protocolExtras);
        advisorView.setProtocolExtras(protocolExtras);
        // Výsledek uchování, prodloužení či přerazítkování se ověří touž cestou jako kterýkoli
        // jiný soubor, ale na pozadí: hlavní okno dál ukazuje vstupní soubor (pokyn uživatele
        // 4. 10. 2026). Do okna se výsledek dostane až na žádost, tlačítkem v záznamu o úkonu
        // nebo v pruhu nad záložkou Digitální kontinuita.
        advisorView.setResultVerifier(new AdvisorView.ResultVerifier() {
            @Override
            public void verify(File document, File external, char[] password,
                               java.util.function.Consumer<AdvisorView> onChecked,
                               java.util.function.Consumer<Throwable> onFailed) {
                verifyInBackground(document, external, password, onChecked, onFailed);
            }

            @Override
            public void open(File document, File external, char[] password) {
                acceptSigFile(document);
                if (external != null) acceptOrigFile(external);
                analyze(document, external, password);
            }
        });

        TabPane tabs = new TabPane();
        summaryTab = new Tab("Zjednodušený přehled",  resultView.getRoot());
        Tab expandedTab  = new Tab("Rozšířený přehled",     extendedView.getRoot());
        Tab advisorTab   = new Tab("Analýza",                 advisorView.getRoot());
        // Za Analýzou, a ne mezi expertními: udržet dokument ověřitelný potřebuje i ten, kdo
        // expertní režim nikdy nezapne. Obsah i tlačítka staví AdvisorView, protože visí na jeho
        // branách; tahle záložka je jen hostí.
        Tab continuityTab = new Tab("Digitální kontinuita", continuityView.getRoot());
        continuityView.setActionRow(advisorView.getActionRow());
        advisorView.setContinuityView(continuityView);
        Tab detailTab    = new Tab("Detailní rozbor",      detailedReportView.getRoot());
        Tab protokolTab  = new Tab("Protokoly DSS",         protokolView.getRoot());
        // The DSS protocols are rendered only once their tab is opened: building the two
        // WebViews boots the WebKit engine (~0.9 s) and the XSLT rendering is not cheap either.
        protokolTab.setOnSelectionChanged(e -> { if (protokolTab.isSelected()) protokolView.onShown(); });
        summaryTab.setClosable(false);
        expandedTab.setClosable(false);
        advisorTab.setClosable(false);
        continuityTab.setClosable(false);
        detailTab.setClosable(false);
        protokolTab.setClosable(false);
        tabPane = tabs;
        expertTabs = List.of(detailTab, protokolTab);
        // Odpojit jde každá záložka kromě Zjednodušeného přehledu: ten je výchozí a aplikace se
        // na něj vrací (třeba po tolerantním ověření), takže by neměl umět zmizet.
        installDetachMenu(expandedTab, null);
        installDetachMenu(advisorTab, null);
        // Tlačítka úkonů se stěhují s ní: bydlí uvnitř jejího kořene a dialogy pro výběr souboru
        // si okno berou z tlačítka, takže vyskočí nad odpojeným oknem, ne nad hlavním.
        installDetachMenu(continuityTab, null);
        installDetachMenu(detailTab, null);
        // Protokoly se staví líně až při výběru záložky; odpojená ta událost nikdy nedostane,
        // takže by okno zůstalo prázdné. Postaví se tedy rovnou při odpojení.
        installDetachMenu(protokolTab, protokolView::onShown);
        // The two expert tabs start out absent, not merely empty: expert mode is off at every
        // launch, and applyExpertTabs() puts them in when a verification runs with it on.
        tabs.getTabs().addAll(summaryTab, expandedTab, advisorTab, continuityTab);

        root.setTop(toolbar);
        root.setCenter(tabs);

        initLotlAsync();
    }

    public BorderPane getRoot() {
        return root;
    }

    /**
     * Wakes the DSS stack up on a background thread. Called once the window is on screen, so
     * the cost of loading the validation engine is paid while the user is still choosing a
     * file instead of during their first verification.
     */
    public void startWarmup() {
        cz.digitalnikontinuita.anep.core.WarmupService.start(appSettings, AppClassLoader.get());
    }

    /** Creates a drop zone VBox. Children[0] = title row, children[1] = name/placeholder label. */
    private VBox makeDropZone(String title, String titleStyle,
                               List<FileChooser.ExtensionFilter> filters,
                               Consumer<File> onFile) {
        return makeDropZone(title, titleStyle, filters, onFile, null);
    }

    /** Overload that places {@code headerExtra} (e.g. a "restore last" button) beside the full text content. */
    private VBox makeDropZone(String title, String titleStyle,
                               List<FileChooser.ExtensionFilter> filters,
                               Consumer<File> onFile, javafx.scene.Node headerExtra) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle(titleStyle);
        titleLabel.setWrapText(true);

        Label nameLabel = new Label(PLACEHOLDER);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        nameLabel.setWrapText(true);

        VBox zone;
        if (headerExtra != null) {
            // Button sits beside the entire text block (title + name), centered vertically.
            // Setting prefHeight=1 / minHeight=0 prevents the button from dictating the zone height.
            VBox textContent = new VBox(4, titleLabel, nameLabel);
            HBox.setHgrow(textContent, Priority.ALWAYS);
            if (headerExtra instanceof javafx.scene.layout.Region r) {
                r.setMinHeight(0);
                r.setPrefHeight(1);
                r.setMaxHeight(Double.MAX_VALUE);
            }
            HBox innerRow = new HBox(8, textContent, headerExtra);
            zone = new VBox(innerRow);
        } else {
            zone = new VBox(4, titleLabel, nameLabel);
        }
        zone.setPadding(new Insets(8, 12, 8, 12));
        zone.setStyle(ZONE_IDLE);
        zone.setCursor(Cursor.HAND);
        zone.setMinWidth(180);

        zone.setOnMouseClicked(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Vyberte soubor");
            chooser.getExtensionFilters().addAll(filters);
            Stage stage = (Stage) zone.getScene().getWindow();
            File f = chooser.showOpenDialog(stage);
            if (f != null) {
                onFile.accept(f);
            }
        });

        zone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                zone.setStyle(ZONE_HOVER);
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });

        zone.setOnDragExited(e -> {
            zone.setStyle(nameLabel.getText().equals(PLACEHOLDER) ? ZONE_IDLE : ZONE_FILLED);
        });

        zone.setOnDragDropped(e -> {
            var db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                onFile.accept(db.getFiles().get(0));
                success = true;
            }
            e.setDropCompleted(success);
            e.consume();
        });

        return zone;
    }

    private void refreshValidateButton() {
        validateButton.setDisable(sigFile == null || !lotlReady);
    }

    /**
     * Adds or removes the two expert tabs. Called only when a verification completes, so the tab
     * strip never changes while the reader is looking at a result that does not match it.
     */
    /** Záložky odpojené do vlastního okna: záložka → okno, ve kterém právě bydlí. */
    private final java.util.Map<Tab, Stage> detachedTabs = new java.util.LinkedHashMap<>();

    /**
     * Nabídne na záložce její odpojení do vlastního okna, aby šly dvě číst vedle sebe.
     *
     * <p>Obsahem záložky je kořen příslušného pohledu a uzel JavaFX žije právě v jednom stromu
     * scény — odpojení tedy znamená přestěhovat ho, ne zkopírovat, a v hlavním okně záložka na tu
     * dobu zmizí. Ověřuje se dál jednou: {@code show(result)} pracuje nad uzly uvnitř
     * přestěhovaného kořene, takže se odpojené okno obnovuje s každým dalším ověřením samo.
     * Pohledy si navíc okno hledají až při volání, přes {@code getScene().getWindow()}, takže
     * „Uložit jako PDF" i dialogy vyskočí nad tím oknem, ze kterého je uživatel vyvolal.
     *
     * @param onDetached co dodělat po přestěhování; {@code null}, když netřeba nic
     */
    private void installDetachMenu(Tab tab, Runnable onDetached) {
        javafx.scene.control.MenuItem open =
                new javafx.scene.control.MenuItem("Otevřít v novém okně");
        open.setOnAction(e -> detachTab(tab, onDetached));
        tab.setContextMenu(new javafx.scene.control.ContextMenu(open));
    }

    private void detachTab(Tab tab, Runnable onDetached) {
        if (tabPane == null || detachedTabs.containsKey(tab)) return;
        int index = tabPane.getTabs().indexOf(tab);
        javafx.scene.Node content = tab.getContent();
        if (index < 0 || content == null) return;

        tab.setContent(null);
        tabPane.getTabs().remove(tab);

        VBox holder = new VBox(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        javafx.scene.Scene owner = root.getScene();
        javafx.scene.Scene scene = new javafx.scene.Scene(holder, 900, 700);
        if (owner != null) scene.getStylesheets().addAll(owner.getStylesheets());

        Stage stage = new Stage();
        stage.setTitle("AnEP — " + tab.getText());
        // Nadbytečné od chvíle, kdy ikonu rozdává Main všem novým oknům; ponecháno proto, že
        // tohle okno vzniká i v sondách, které Main vůbec nespouštějí.
        if (owner != null && owner.getWindow() instanceof Stage main) {
            stage.getIcons().addAll(main.getIcons());
        }
        stage.setScene(scene);
        // Zavření okna vrátí záložku tam, odkud odešla. Uzel se musí nejdřív odebrat z okna,
        // teprve pak vrátit záložce — jinak by se stěhoval mezi dvěma rodiči naráz.
        stage.setOnHidden(e -> {
            detachedTabs.remove(tab);
            holder.getChildren().clear();
            tab.setContent(content);
            if (tabPane != null && !tabPane.getTabs().contains(tab)) {
                tabPane.getTabs().add(Math.min(index, tabPane.getTabs().size()), tab);
            }
        });
        detachedTabs.put(tab, stage);
        stage.show();
        if (onDetached != null) onDetached.run();
    }

    private void applyExpertTabs(boolean expert) {
        if (expert == expertTabsShown || tabPane == null) return;
        if (expert) {
            for (Tab t : expertTabs) {
                if (!detachedTabs.containsKey(t) && !tabPane.getTabs().contains(t)) {
                    tabPane.getTabs().add(t);
                }
            }
        } else {
            // Odpojené okno patří režimu, který se právě vypíná. Zavření ho vrátí do panelu,
            // odkud ho hned nato odebereme spolu s ostatními — bez toho by okno zůstalo viset
            // nad aplikací, která už tu záložku nezná.
            for (Tab t : new java.util.ArrayList<>(expertTabs)) {
                Stage open = detachedTabs.get(t);
                if (open != null) open.close();
            }
            tabPane.getTabs().removeAll(expertTabs);
        }
        expertTabsShown = expert;
    }

    /**
     * Fills the main drop zone with {@code f}, exactly as dropping or choosing it would.
     *
     * <h2>Kdy se druhý slot zapomene a kdy ne</h2>
     *
     * <p><b>Nezapomene</b>, dokud se nic neověřovalo. Kdo podal nejdřív externí podpis a teprve
     * pak dokument — tedy pořadím, které je přirozené, když má v ruce ten podpis — by jinak
     * přišel o první polovinu dvojice dřív, než ji stačil zkompletovat. (Do commitu 08a6135 se
     * slot mazal vždycky a výjimka pro evidenční záznam řešila tentýž problém, jen pro jeden
     * druh souboru.)
     *
     * <p><b>Zapomene</b>, jakmile jednou ověření proběhlo ({@link #verificationRan}). Dokument
     * vložený po ověření je nový případ a externí podpis od toho předchozího u něj neznamená
     * nic — nález uživatele z 20. 9. 2026: „vložím nepodepsaný soubor a k němu externí podpis,
     * ověřím, a pak vložím nový soubor; ten externí tam stále zůstává a musí se mazat.“
     * Zapomene se i <b>paměť tlačítka ↺</b>, jinak by se tatáž dvojice vrátila obnovou a tiše
     * i při opakovaném ověření s ústupky, které si soubory bere odtamtud
     * ({@code performLenientVerify}).
     *
     * <p>Tlačítko ↺ samo tím netrpí: hodnoty si <b>odloží stranou dřív</b>, než zavolá tuhle
     * metodu, takže obnova celé dvojice funguje dál.
     *
     * <p>Co zůstává beze změny: výměna dokumentu <b>bez ověření mezi tím</b> druhý slot nechává
     * stát. Je to táž situace jako skládání dvojice a pozná se podle rozsvíceného tlačítka;
     * ověření takovou dvojici ohlásí jako nesprávnou (důvod 8).
     */
    private void acceptSigFile(File f) {
        if (verificationRan) {
            clearOrigFile();
            lastOrigFile = null;
            verificationRan = false;
        }
        sigFile = f;
        lastSigFile = f;
        sigNameLabel.setText(f.getName());
        sigZone.setStyle(ZONE_FILLED);
        refreshValidateButton();
    }


    /**
     * Takes an element out of a container window and verifies it as a document in its own right.
     * Attachments are very often signed documents themselves, and the alternative — save to disk,
     * find the file, drag it back in — is a detour with no purpose.
     */
    private void verifyExtracted(byte[] data, String name) {
        try {
            Path dir = Files.createTempDirectory("anep-prvek-");
            dir.toFile().deleteOnExit();
            Path tmp = dir.resolve(name.isBlank() ? "prvek" : name);
            Files.write(tmp, data);
            tmp.toFile().deleteOnExit();
            acceptSigFile(tmp.toFile());
            if (sigZone.getScene() != null && sigZone.getScene().getWindow() instanceof Stage s) {
                s.toFront();
            }
            analyzeIfReady();
        } catch (Exception e) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR,
                    "Prvek se nepodařilo předat k ověření:\n" + e.getMessage());
            alert.setHeaderText(null);
            alert.showAndWait();
        }
    }

    private void acceptOrigFile(File f) {
        origFile = f;
        lastOrigFile = f;
        // Bez téhle věty by se o možnosti vyprázdnit slot nikdo nedozvěděl: tlačítko vypadá
        // stejně jako předtím a zrušení výběru není nikde vidět.
        origTooltip.setText(f.getName() + "\nKlepnutím a zrušením výběru se vyprázdní");
        origButton.setGraphic(externalContentIcon(true));
        origButton.setStyle(ORIG_BTN_STYLE_FILLED);
    }

    /**
     * Vyprázdní slot pro externí obsah — hodnotu, nápovědu, plochu i kresbu.
     *
     * <p>Kresbu, ne jen plochu: bílá ikona na světlém tlačítku by zmizela. Paměť pro tlačítko ↺
     * se nemaže, takže vzít zpět jde vzít zpět.
     */
    private void clearOrigFile() {
        origFile = null;
        origTooltip.setText(ORIG_TOOLTIP_DEFAULT);
        origButton.setGraphic(externalContentIcon(false));
        origButton.setStyle(ORIG_BTN_STYLE);
    }

    private void resetFileZones() {
        sigFile = null;
        sigNameLabel.setText(PLACEHOLDER);
        clearOrigFile();
        sigZone.setStyle(ZONE_IDLE);
        refreshValidateButton();
    }

    private void analyzeIfReady() {
        if (sigFile != null) {
            if (PdfSource.needsPasswordToOpen(sigFile)) {
                char[] pwd = showPasswordDialog(sigFile.getName());
                if (pwd == null) return; // user cancelled
                analyze(sigFile, origFile, pwd);
            } else {
                analyze(sigFile, origFile, null);
            }
        }
    }

    private void initLotlAsync() {
        // Kontrola a stažení seznamů na pozadí doběhnou až po startu; bez tohohle ukazoval
        // stavový řádek i hlavička karet dál datum z doby před nimi (16. 10. 2026).
        trustConfig.setTrustStateListener(() -> Platform.runLater(this::onTrustStateChanged));
        Thread thread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(AppClassLoader.get());
            try {
                CommonCertificateVerifier verifier = trustConfig.buildLotlCertificateVerifier();
                trustListsOffline = false;
                verifierRef.set(verifier);
                StartupTrace.mark("LOTL verifier ready (validation enabled)");
                Platform.runLater(() -> {
                    lotlReady = true;
                    progress.setVisible(false);
                    progress.setManaged(false);
                    showTrustListStatus();
                    refreshValidateButton();
                });
            } catch (Exception ex) {
                trustListsOffline = true;
                verifierRef.set(trustConfig.buildOfflineCertificateVerifier());
                Platform.runLater(() -> {
                    lotlReady = true;
                    progress.setVisible(false);
                    progress.setManaged(false);
                    setStatus("Nelze načíst důvěryhodné seznamy EU (" + ex.getMessage()
                            + ") — provoz v offline režimu (pouze kryptografická kontrola).");
                    refreshValidateButton();
                });
            }
        }, "lotl-init");
        thread.setDaemon(true);
        thread.start();
    }

    private void analyze(File sf, File of, char[] pdfPassword) {
        analyze(sf, of, pdfPassword, null);
    }

    /**
     * Přenese stav důvěryhodných seznamů do hlaviček obou karet. Musí běžet na vlákně JavaFX.
     *
     * <p>Všech pět údajů se čte naráz a předá oběma kartám tytéž: hlavička je tvrzení
     * o běhu jako celku a dvě karty se v něm nesmějí rozejít.
     */
    private void publishTrustListInfo() {
        long checked = trustConfig.getLastSourceCheckTime();
        boolean running = trustConfig.isNetworkRefreshInProgress();
        cz.digitalnikontinuita.anep.core.TrustConfig.TrustListVersion latest =
                trustConfig.getLatestIssuedTrustList();
        java.util.List<cz.digitalnikontinuita.anep.core.TrustConfig.StaleTrustList> stale =
                trustConfig.getStaleTrustLists();
        java.util.List<String> empty = trustConfig.getEmptyTerritories();
        extendedView.setTrustListInfo(checked, running, latest, stale, empty);
        advisorView.setTrustListInfo(checked, running, latest, stale, empty);
    }

    /**
     * Stavový řádek o načtených seznamech. Čas je porovnání se zdrojem, ne posledního úplného
     * stažení — viz {@code TrustConfig.getLastSourceCheckTime()} a hlavička karet
     * ({@link TrustListInfoBar}).
     */
    private void showTrustListStatus() {
        long lastCheck = trustConfig.getLastSourceCheckTime();
        boolean refreshRunning = trustConfig.isNetworkRefreshInProgress();
        String refreshInfo = lastCheck > 0
                ? " (naposledy ověřeny u zdroje: " + formatLastModified(lastCheck) + ")" : "";
        String suffix = refreshRunning ? " — probíhá síťová aktualizace na pozadí…" : "";
        // The stale-list warning belongs here as much as in the two tabs' headers: this
        // line is what the reader sees before any document is opened, and "seznamy
        // načteny" on its own would not hint that some of them are only cached copies.
        setStatus("Důvěryhodné seznamy EU načteny" + refreshInfo + suffix
                + ". Přetáhněte nebo otevřete soubor s podpisem.",
                trustWarnings());
        statusShowsTrustLists = true;
    }

    /**
     * Stavový řádek ukazuje zprávu o seznamech (a ne třeba výsledek ověření). Jen tehdy ho smí
     * změna stavu seznamů na pozadí přepsat.
     */
    private boolean statusShowsTrustLists = false;

    /**
     * Stav seznamů se změnil na pozadí: kontrola doběhla, úplné stažení začalo nebo skončilo.
     * Hlavičky karet se překreslí vždy, stavový řádek jen tehdy, když ještě mluví o seznamech.
     */
    private void onTrustStateChanged() {
        if (!lotlReady) return;
        publishTrustListInfo();
        if (statusShowsTrustLists) showTrustListStatus();
    }

    /**
     * Obnova seznamů z Nastavení — a hned za ní překreslení hlavičky.
     *
     * <p>Bez toho druhého kroku obnova stav opravila, ale varování „Chybějící důvěryhodné seznamy"
     * viselo v hlavičce dál až do nejbližšího ověření, protože se hlavička plnila jedině tam.
     * Uživateli to vypadalo, že pokyn k načtení nic neudělal, i když udělal.
     *
     * <p>Obnova běží na vlákně dialogu Nastavení, proto překreslení přes {@code runLater}.
     */
    private String refreshTrustListsFromSettings() {
        String msg = trustConfig.refreshLotlNow();
        Platform.runLater(this::onTrustStateChanged);
        return msg;
    }

    private void performLenientVerify() {
        File sf = lastSigFile;
        File of = lastOrigFile;
        if (sf == null) return;

        char[] pdfPassword = null;
        if (PdfSource.needsPasswordToOpen(sf)) {
            pdfPassword = showPasswordDialog(sf.getName());
            if (pdfPassword == null) return;
        }

        appSettings.setAcceptNewerOcsp(true);
        appSettings.setAcceptExpiredRevocationSources(true);
        appSettings.setIgnoreSha1Deprecated(true);
        appSettings.setAcceptPdfNotEtsi(true);

        // Ústupky se odsud nevracejí zpátky ručně. Dělá to samo dokončené ověření přes
        // AppSettings.resetValidationPolicy(), a to ještě před tímhle callbackem — kdyby si je
        // úkon obnovoval sám, běželo by to po resetu a přepsalo by ho to zpátky na hodnoty,
        // které tu byly před ním.
        final char[] finalPwd = pdfPassword;
        analyze(sf, of, finalPwd, () -> {
            // getTabPane() je u odpojené záložky null — přepínat se dá jen na tu, která v panelu je.
            if (summaryTab != null && summaryTab.getTabPane() != null) {
                summaryTab.getTabPane().getSelectionModel().select(summaryTab);
            }
        });
    }

    /**
     * Jednorázové ověření s ústupky, které připouští podatelna katastru — z bloku o vkladové
     * listině. Totéž co {@link #performLenientVerify}, jen <b>bez tolerance k SHA-1</b>: podatelna
     * podpisy s SHA-1 nepřijímá, a ústupek, se kterým by podpis obstál jen u nás, by čtenáře
     * uvedl v omyl (informace podatelny katastru, 8. 10. 2026). Po ověření zůstane na záložce
     * Analýza, kde blok stojí.
     */
    private void performLandRegistryVerify() {
        File sf = lastSigFile;
        File of = lastOrigFile;
        if (sf == null) return;

        char[] pdfPassword = null;
        if (PdfSource.needsPasswordToOpen(sf)) {
            pdfPassword = showPasswordDialog(sf.getName());
            if (pdfPassword == null) return;
        }
        for (cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation r
                : LandRegistryDeed.LAND_REGISTRY_CONCESSIONS) {
            r.setEnabled(appSettings, true);
        }
        // Návrat do výchozího stavu obstará dokončené ověření, viz performLenientVerify.
        final char[] finalPwd = pdfPassword;
        analyze(sf, of, finalPwd, null);
    }

    /**
     * Holý certifikát, seznam CRL nebo odpověď OCSP — prvek, který se nečte ověřováním, nýbrž
     * očima. Vrátí {@code true}, když se o soubor postaral a ověřování se spouštět nemá.
     *
     * <p>Do 15. 9. 2026 takový soubor spadl do ověřování, tam ho DSS zkusil rozebrat jako CMS
     * a uživatel dostal do stavového řádku hlášku knihovny — změřeno: „Bad sequence size: 3"
     * u certifikátu i CRL, „illegal object in getInstance: ASN1Enumerated" u odpovědi OCSP.
     * Přitom si AnEP všechny tři umí přečíst už dávno; jen k tomu prohlížeči nevedla cesta odjinud
     * než z prohlížeče kontejnerů a z odkazů v Analýze.
     *
     * <p>Poznává se podle <b>obsahu</b>, ne podle přípony: {@code .cer} bývá i PEM a naopak
     * {@code .ors} nebo bezpříponový prvek vytáhnutý z kontejneru žádnou užitečnou příponu
     * nemá. Ověřitelný dokument se tím nezabaví: {@code readable()} ho pustí jen tehdy, když
     * bajty opravdu dají OCSPResponse, Certificate nebo CertificateList — PDF, ZIP ani XML
     * do DER neprojdou a CMS začíná OIDem, ne ENUMERATED ani SEQUENCE certifikátu.
     *
     * <p>Strop na velikost je proti tomu, aby se kvůli téhle otázce načítal do paměti
     * několikagigový soubor. Žádný z těch tří prvků takový není ani zdaleka.
     */
    private boolean openAsStandaloneElement(File sf) {
        if (sf == null || !sf.isFile() || sf.length() > 32L * 1024 * 1024) return false;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(sf.toPath());
        } catch (Exception unreadable) {
            return false;
        }
        ContainerInspector.Readable readable = ContainerInspector.readable(bytes);
        if (readable == null) return false;
        clearResults();

        Stage owner = getRoot().getScene() != null
                ? (Stage) getRoot().getScene().getWindow() : null;
        setStatus("Soubor " + sf.getName() + " není podepsaný dokument, nýbrž "
                + readable.title().toLowerCase(Locale.ROOT)
                + " — otevřen je v prohlížeči.");
        if (readable.kind() == ContainerInspector.ReadableKind.CRL) {
            // Prohlížeč CRL umí víc než výpis ve slovech: nese tabulku odvolaných sériových
            // čísel a hledání v ní. Hledání do něj přibylo právě kvůli téhle cestě: z odkazu
            // v Analýze přichází {@code highlight} a okno se na hledaném záznamu otevře samo,
            // kdežto vložený soubor žádný nemá.
            CrlView.show(bytes, null, null, owner);
        } else {
            boolean cert = readable.kind() == ContainerInspector.ReadableKind.CERTIFICATE;
            ElementTextView.show(readable.title(), readable.text(), bytes, sf.getName(),
                    cert ? "Certifikát" : "Odpověď OCSP",
                    cert ? "*.cer" : "*.ors", owner);
        }
        resetFileZones();
        return true;
    }

    /**
     * Smaže výsledek předchozího ověření ze všech záložek. Volá se tam, kde nový soubor výsledek
     * nedal — kancelářský dokument, nepodporovaný formát, chyba, samostatný certifikát či CRL.
     *
     * <p>Do 24. 9. 2026 zůstával výsledek stát: po PDF a následném DOCX ukazovalo hlavní okno dál
     * verdikt o PDF, zatímco stavový řádek i okno nad ním mluvily o DOCX.
     */
    private void clearResults() {
        resultView.clear();
        extendedView.clear();
        advisorView.clear();
        detailedReportView.clear();
        protokolView.clear();
    }

    private void analyze(File sf, File of, char[] pdfPassword, Runnable onComplete) {
        // Od téhle chvíle je předložená dvojice uzavřená věc; co přijde do hlavního pole potom,
        // je nový případ. Viz {@link #verificationRan}.
        verificationRan = true;
        // Do nabídky naposledy ověřovaných souborů (pravé tlačítko na ↺). Zapisuje se tady, ne až
        // po skončení: k souboru, jehož ověření spadlo nebo se ukázalo jako samostatný prvek, se
        // chce uživatel vracet stejně.
        cz.digitalnikontinuita.anep.core.RecentFiles.remember(sf, of);
        // Prohlížeče příloh a kontejnerů patří k předchozímu souboru; viz ContainerView.closeAll().
        // Zavírají se ještě před odbočkou pro samostatné prvky — i ta je nový soubor.
        ContainerView.closeAll();
        StructureInspectorWindow.closeAll();
        OfficePeekDialog.close();
        // Dřív než se rozběhne ověřování: není to prvek, který se jen čte? Viz
        // {@link #openAsStandaloneElement}.
        if (of == null && openAsStandaloneElement(sf)) return;
        // Okamžik, kdy ověřování začalo. Protokol vypovídá o ověření, ne o svém vzniku, a ten
        // může být o hodiny později — uživatel mezitím jen listuje záložkami.
        final java.time.Instant verificationTime = java.time.Instant.now();
        progress.setVisible(true);
        progress.setManaged(true);
        // Belongs to the previous document; the new one may have no attachments at all.
        statusAttachBtn.setVisible(false);
        statusAttachBtn.setManaged(false);
        // Náhled ukazuje dokument, o kterém mluví verdikt vedle. Nechat ho otevřený nad souborem,
        // který se právě přestal ověřovat, by znamenalo mít na obrazovce dvě tvrzení o dvou
        // různých dokumentech, aniž by to na kterémkoli z nich bylo napsané.
        previewWasOpen = PdfPreviewWindow.isOpen();
        PdfPreviewWindow.close();
        statusPreviewBtn.setVisible(false);
        statusPreviewBtn.setManaged(false);
        String label = of != null ? sf.getName() + " + " + of.getName() : sf.getName();
        setStatus("Ověřuji " + label + " …");

        // Counting attachments means opening the PDF once more, so it rides along on the
        // validation thread rather than stalling the UI when the result arrives.
        final java.util.concurrent.atomic.AtomicInteger attachmentCount =
                new java.util.concurrent.atomic.AtomicInteger();
        // A reference to another document, the way eLegalizace at CzechPOINT leaves one. Read on
        // the same thread and from the same bytes as the attachment count, for the same reason.
        final java.util.concurrent.atomic.AtomicReference<
                cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link> legalisationLink =
                new java.util.concurrent.atomic.AtomicReference<>();

        Task<ValidationResult> task = new Task<>() {
            @Override
            protected ValidationResult call() {
                return validateFiles(sf, of, pdfPassword, attachmentCount, legalisationLink);
            }
        };

        task.setOnSucceeded(e -> {
            progress.setVisible(false);
            progress.setManaged(false);
            ValidationResult result = task.getValue();
            int count = result.getSignatures().size();

            // Ověřování skončilo, ale nic nenašlo — a přitom v souboru podpis je. Nastává to
            // u kancelářských formátů: ODT je ZIP, který knihovna přijme jako kontejner a projde
            // bez nálezu, protože podpis leží v META-INF/documentsignatures.xml, kam se nedívá.
            // Hlásit „nalezeno 0 podpisů" nad podepsaným dokumentem by byla nepravda.
            if (count == 0) {
                var peeked = cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.peek(sf);
                if (peeked != null) {
                    // Totéž co u DOCX ve větvi setOnFailed: výsledek na obrazovce patří
                    // předchozímu souboru.
                    clearResults();
                    setStatus("Podpis v souboru " + sf.getName() + " aplikace neověřuje;"
                            + " co se o něm dá zjistit, je v samostatném okně.");
                    OfficePeekDialog.show(getRoot().getScene() != null
                                    ? getRoot().getScene().getWindow() : null,
                            sf, peeked, verifierRef, fixationService, appSettings);
                    // Ověření skončilo i tady, jen bez toho hlavního výsledku — a ústupky
                    // se mají vrátit do výchozího stavu po každém dokončeném ověření, ne jen
                    // po tom, které DSS umí dopočítat. Bez tohohle řádku zaškrtnutí přežilo
                    // do dalšího souboru, přesně to, co resetValidationPolicy() má bránit.
                    appSettings.resetValidationPolicy();
                    resetFileZones();
                    return;
                }
            }
            java.util.List<String[]> lines = new java.util.ArrayList<>();
            lines.add(new String[]{ "K ověření byl předložen soubor: ",
                    sf.getName() + " (" + formatFileSize(sf.length()) + ", " + formatLastModified(sf.lastModified()) + ")" });
            if (of != null) lines.add(new String[]{ "a dále soubor: ",
                    of.getName() + " (" + formatFileSize(of.length()) + ", " + formatLastModified(of.lastModified()) + ")" });
            // Prázdná pole patří do záhlaví ke stejnému výčtu jako podpisy: čtenář se z něj
            // dozví, co v souboru je, a pole připravené k podpisu tam je taky. Zvlášť u souboru
            // bez jediného podpisu — tam je to jediné, co se o něm dá říct.
            int emptyFieldCount = result.getEmptySignatureFields().size();
            String found = "nalezen(o) " + count + " podpis(ů)/pečeť(í)";
            if (emptyFieldCount > 0) {
                found += ", " + CzechPlural.count(emptyFieldCount, "prázdné podpisové pole",
                        "prázdná podpisová pole", "prázdných podpisových polí");
            }
            lines.add(new String[]{ "Hotovo: ", found });
            setStatusLines(lines);
            // A PDF is a document a person can read, so it goes to the platform viewer. A container
            // is a folder in a single file — handing it to the shell would at best unzip it — so it
            // opens in the built-in browser instead. When the signature came in as a separate file
            // (detached), that file is the container worth looking at.
            final File fileToOpen = sf;
            final File containerFile =
                    ContainerView.isContainer(sf) ? sf
                    : (of != null && ContainerView.isContainer(of)) ? of
                    : null;
            var isdsArtefact = containerFile == null ? null
                    : cz.digitalnikontinuita.anep.core.ContainerInspector.isdsArtefact(containerFile);
            statusOpenBtnTooltip.setText(containerFile == null ? "Otevřít v přidružené aplikaci"
                    : isdsArtefact != null ? "Zobrazit obsah " + isdsArtefact.genitive()
                    : "Zobrazit obsah kontejneru");
            // Kontejner dostane plnou modrou plochu s bílou kresbou, obyčejné PDF zůstává
            // u nenápadné šipky. Důvod je v tom, co se čte: u kontejneru je tlačítko jediná
            // cesta dovnitř, kdežto u PDF jen zkratka k tomu, co uživatel umí i sám.
            boolean isContainer = containerFile != null;
            statusOpenBtn.setText(null);
            statusOpenBtn.setGraphic(isContainer ? containerIcon() : openExternallyIcon());
            statusOpenBtn.setStyle(isContainer ? OPEN_BTN_FILLED : OPEN_BTN_PLAIN);
            statusOpenBtn.setOnAction(ev -> {
                if (containerFile != null && containerFile.exists()) {
                    if (ContainerView.open(statusOpenBtn.getScene().getWindow(), containerFile)) return;
                }
                if (fileToOpen.exists() && Desktop.isDesktopSupported()) {
                    try { Desktop.getDesktop().open(fileToOpen); } catch (Exception ignored) {}
                }
            });
            statusOpenBtn.setVisible(true);
            statusOpenBtn.setManaged(true);
            // Kontejnerem je tu druhý soubor (odpojený podpis), takže předložený dokument
            // potřebuje vlastní cestu do přidružené aplikace.
            boolean detachedDocument = containerFile != null && containerFile != sf;
            statusDocOpenBtn.setVisible(detachedDocument);
            statusDocOpenBtn.setManaged(detachedDocument);
            statusDocOpenBtn.setOnAction(ev -> {
                if (fileToOpen.exists() && Desktop.isDesktopSupported()) {
                    try { Desktop.getDesktop().open(fileToOpen); } catch (Exception ignored) {}
                }
            });

            // Náhled jen u PDF: revize a stránky jsou věc PDF a nad kontejnerem ani datovou
            // zprávou není co vykreslovat. Ne prázdné okno, nýbrž žádné tlačítko.
            final char[] previewPassword = pdfPassword;
            boolean canPreview = cz.digitalnikontinuita.anep.core.PdfSource.of(fileToOpen).isPdf();
            statusPreviewBtn.setVisible(canPreview);
            statusPreviewBtn.setManaged(canPreview);
            // Podoby dokumentu se počítají tady, protože k tomu je potřeba DiagnosticData
            // i délka souboru. Seznam má vždycky aspoň dnešní podobu; přepínač se ukáže, až
            // když je z čeho vybírat.
            //
            // Vedle rozdílů podle DSS se předávají i prvky, které samy kreslí viditelnou
            // vizualizaci (nález uživatele 22. 9. 2026). Vizualizace je v modelu už dávno,
            // protože ji vypisuje karta; tady z ní stačí příznak viditelnosti.
            final java.util.Set<String> visibleAppearances = result.getSignatures().stream()
                    .filter(s -> s.getAppearanceVisibility() != null
                            && s.getAppearanceVisibility().visible())
                    .map(cz.digitalnikontinuita.anep.model.SignatureSummary::getSignatureId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            final java.util.List<cz.digitalnikontinuita.anep.core.DocumentVersions.Version>
                    previewVersions = cz.digitalnikontinuita.anep.core.DocumentVersions.of(
                            result.getDiagnosticData(), fileToOpen.length(), visibleAppearances);
            statusPreviewBtn.setOnAction(ev -> PdfPreviewWindow.open(
                    statusPreviewBtn.getScene().getWindow(), fileToOpen, previewPassword,
                    previewVersions));
            // Odkazy z bloku „Po podepsání se v dokumentu změnil obsah", který od 9. 10. 2026
            // stojí v Rozšířeném přehledu (do té doby v Analýze). Čísla stran míří na podobu
            // krytou podpisem — dnešní ukáže kterákoli prohlížečka —, zvláštní odkaz pod
            // odstavcem na dnešní podobu, ať jsou obě poloviny srovnání po ruce.
            extendedView.setPreviewOpeners(
                    page -> PdfPreviewWindow.open(root.getScene().getWindow(), fileToOpen,
                            previewPassword, previewVersions, page, true),
                    page -> PdfPreviewWindow.openCurrent(root.getScene().getWindow(), fileToOpen,
                            previewPassword, previewVersions, page));
            // Bylo-li okno otevřené, než začalo tohle ověření, otevře se znovu — už nad novým
            // souborem. Kdo si náhled jednou vyžádal, nemá o něj přijít proto, že sáhl po dalším
            // dokumentu.
            if (previewWasOpen && canPreview) {
                PdfPreviewWindow.open(statusPreviewBtn.getScene().getWindow(),
                        fileToOpen, previewPassword, previewVersions);
            }
            previewWasOpen = false;

            int attachments = attachmentCount.get();
            final char[] attachPassword = pdfPassword;
            statusAttachBtn.setVisible(attachments > 0);
            statusAttachBtn.setManaged(attachments > 0);
            statusAttachBtnTooltip.setText("Zobrazit přílohy dokumentu (" + attachments + ")");
            statusAttachBtn.setOnAction(ev -> {
                if (fileToOpen.exists()) {
                    ContainerView.openPdfAttachments(
                            statusAttachBtn.getScene().getWindow(), fileToOpen, attachPassword);
                }
            });
            resultView.setFileInfo(sf, of);
            resultView.setVerificationTime(verificationTime);
            resultView.setLegalisationLink(legalisationLink.get());
            extendedView.setFileInfo(sf, of);
            extendedView.setVerificationTime(verificationTime);
            // Odkaz z eLegalizace se čte na validačním vlákně výš; karta o něm říká, co
            // uživatel o odkazovaném dokumentu zjistil — a to i tehdy, když kontrolu provedl
            // ještě před tímhle ověřením.
            extendedView.setLegalisationLink(legalisationLink.get());
            // Expert mode is read once here, so switching it takes effect on the next
            // verification rather than rearranging the tabs under the reader's hands.
            boolean expert = appSettings.isExpertMode();
            applyExpertTabs(expert);
            if (expert) {
                detailedReportView.setFileInfo(sf, of);
                detailedReportView.setVerificationTime(verificationTime);
                detailedReportView.setPdfPassword(pdfPassword);
                detailedReportView.setTimestampStandardsMode(appSettings.isTimestampStandardsMode());
                detailedReportView.setAcceptExpiredRevocationSources(appSettings.isAcceptExpiredRevocationSources());
                detailedReportView.setAcceptNewerOcsp(appSettings.isAcceptNewerOcsp());
                detailedReportView.setPostExpiryAccepted(
                        cz.digitalnikontinuita.anep.core.ReportMapper.postExpiryRevocationAccepted(appSettings));
            }
            resultView.setTimestampStandardsMode(appSettings.isTimestampStandardsMode());
            extendedView.setTimestampStandardsMode(appSettings.isTimestampStandardsMode());
            protokolView.setTimestampStandardsMode(appSettings.isTimestampStandardsMode());
            advisorView.setTimestampStandardsMode(appSettings.isTimestampStandardsMode());
            extendedView.setPreferLatestOcsp(appSettings.isAcceptNewerOcsp()
                    || (appSettings.isUseCustomValidationTime() && appSettings.getValidationDate() != null));
            extendedView.setCustomValidationTime(
                    appSettings.isUseCustomValidationTime() ? appSettings.getCustomValidationTime() : null);
            advisorView.setCustomValidationTime(
                    appSettings.isUseCustomValidationTime() ? appSettings.getCustomValidationTime() : null);
            publishTrustListInfo();
            resultView.show(result);
            extendedView.show(result);
            advisorView.setSourceFile(sf, of, pdfPassword);
            advisorView.show(result);
            // Building the detailed card tree and rendering the DSS protocols is real work —
            // certificate paths, revocation records and evidence records for every signature.
            // With the tabs absent nobody can look at the result, so it is not produced.
            if (expert) {
                detailedReportView.show(result);
                protokolView.show(result);
            }
            // Upozornění o rozchodu s DSS nad REVOKED_NO_POE tu bylo do 27. 8. 2026. Padlo spolu
            // s přepisem verdiktu, který ten rozchod působil: AnEP teď nad revokovaným
            // certifikátem bez důkazu o čase podpisu uvádí neznámou platnost stejně jako
            // protokoly pod záložkou vedle, takže není co vysvětlovat.
            // Nastavení validační politiky platí na jedno ověření a tímhle řádkem to ověření
            // skončilo. Musí to stát až tady, za vším, co si politiku přečetlo výš — pohledy
            // dostávají hodnoty předané, ne odkaz, takže po resetu už nikdo nesahá na to, podle
            // čeho se právě soudilo. Dřív tu totéž dělal jen zadaný čas ověření.
            //
            // Neukládá se: save() politiku do souboru nepíše, takže by to nic nezměnilo.
            appSettings.resetValidationPolicy();
            resetFileZones();
            StartupTrace.mark("result rendered in UI");
            // The reference is to a document that is not in the file, so nothing in the result
            // can confirm it — the reader has to be asked for the other document. Last, and only
            // once the verdict is on screen behind it: the window is about a second document,
            // not about the one just verified.
            cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link link = legalisationLink.get();
            if (link != null) {
                LegalisationLinkWindow.show(root.getScene().getWindow(), sf, link);
            }
            if (onComplete != null) onComplete.run();
        });

        task.setOnFailed(e -> {
            progress.setVisible(false);
            progress.setManaged(false);
            Throwable ex = task.getException();
            if (isPasswordException(ex)) {
                // PDF is encrypted; the password provided (if any) was wrong — ask again.
                char[] pwd = showPasswordDialog(sf.getName());
                if (pwd != null) {
                    analyze(sf, of, pwd);
                    return;
                }
            }
            // Odsud dál už ověření vždy skončilo, ať už chybou, nálezem přes OfficeSignaturePeek,
            // nebo nepodporovaným formátem — ani jedna z těchto větví to dřív nevracela do
            // výchozího stavu, takže zaškrtnuté ústupky přežily do dalšího souboru. Stojí to
            // schválně před rozcestím větví níž, ne v každé zvlášť, aby příští nová větev nezačala
            // znovu stejnou mezerou.
            appSettings.resetValidationPolicy();
            // A format nobody can read is not the same kind of failure as a broken document, and it
            // used to end the same way: a line of red in the status bar and the zones cleared. The
            // file is still evidence, and fixing it in time is still worth doing, so it gets the
            // offer instead of a dead end.
            // Kancelářský dokument s podpisem uvnitř. Ověřit ho aplikace neumí (OOXML potřebuje
            // RelationshipTransform, který DSS nezná), ale „formát nepodporujeme" je o něm méně,
            // než co je v něm napsáno: certifikát podepsané osoby, deklarovaný čas, razítko
            // a použité algoritmy. Změřeno na 140 podepsaných souborech — u 70 % z nich rozhodne
            // už samotný nález SHA-1, tedy odpověď, kterou by plné ověření nezměnilo.
            // Nový soubor výsledek nedal, takže ten, který je na obrazovce, patří předchozímu
            // souboru. Musí zmizet dřív, než se otevře okno níž — jinak by vedle něj stál
            // verdikt o jiném dokumentu po celou dobu, co ho čtenář čte.
            clearResults();
            var peek = cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.peek(sf);
            if (peek != null) {
                setStatus("Podpis v souboru " + sf.getName() + " aplikace neověřuje;"
                        + " co se o něm dá zjistit, je v samostatném okně.");
                OfficePeekDialog.show(getRoot().getScene() != null
                                ? getRoot().getScene().getWindow() : null,
                        sf, peek, verifierRef, fixationService, appSettings);
                resetFileZones();
                return;
            }
            if (isUnsupportedFormat(ex)) {
                java.util.List<File> files = new java.util.ArrayList<>();
                files.add(sf);
                if (of != null) files.add(of);
                javafx.stage.Window owner = getRoot().getScene() != null
                        ? getRoot().getScene().getWindow() : null;
                // Obyčejný archiv ZIP: podpis může být v souborech uvnitř, takže přednost má
                // prohlížeč s „Ověřit v AnEP“ a balíček je v něm tlačítkem (15. 10. 2026).
                // Druhý soubor tu nemá co dělat — u archivu jde o jeho obsah —, do balíčku
                // ale patří stejně jako dřív.
                if (ContainerView.openRejectedArchive(owner, sf, () ->
                        ForensicPackageDialog.show(owner, files, sf.getName(),
                                cz.digitalnikontinuita.anep.core.FixationService.Reason.UNSUPPORTED_FORMAT,
                                fixationService, appSettings))) {
                    setStatus("Soubor " + sf.getName() + " je obyčejný archiv ZIP, ne kontejner"
                            + " podpisu; jeho obsah je v samostatném okně.");
                    resetFileZones();
                    return;
                }
                // Kancelářský dokument sem dojde jen bez podpisu — podepsaný otevřelo nahlédnutí
                // výše. Tvrdit u něj, že formát aplikace nepodporuje, by nebyla pravda.
                cz.digitalnikontinuita.anep.core.FixationService.Reason reason =
                        cz.digitalnikontinuita.anep.core.OfficeSignaturePeek.isCandidate(sf)
                                ? cz.digitalnikontinuita.anep.core.FixationService.Reason.OFFICE_UNSIGNED
                                : cz.digitalnikontinuita.anep.core.FixationService.Reason.UNSUPPORTED_FORMAT;
                setStatus(reason == cz.digitalnikontinuita.anep.core.FixationService.Reason.OFFICE_UNSIGNED
                        ? "Soubor " + sf.getName() + " není podepsaný."
                        : "Formát souboru " + sf.getName() + " aplikace nepodporuje.");
                ForensicPackageDialog.show(owner, files, sf.getName(), reason,
                        fixationService, appSettings);
                resetFileZones();
                return;
            }
            setStatus("Chyba při ověřování " + sf.getName() + ": "
                    + (ex != null ? ex.getMessage() : "neznámá chyba"));
            resetFileZones();
        });

        Thread thread = new Thread(task, "dss-validation");
        thread.setContextClassLoader(AppClassLoader.get());
        thread.setDaemon(true);
        thread.start();
    }

    /** Služby a nastavení, se kterými pohled Analýzy počítá — pro viditelný i pro skrytý. */
    private void wireServices(AdvisorView view) {
        view.setAugmentationService(augmentationService);
        view.setExtensionService(extensionService);
        view.setTimestampingService(timestampingService);
        view.setFixationService(fixationService);
        view.setRestampingService(restampingService);
        view.setAppSettings(appSettings);
    }

    /**
     * Ověří výsledný soubor úkonu na pozadí a vyhodnotí ho ve skrytém pohledu Analýzy; hlavní
     * okno, náhled ani seznam naposledy ověřovaných souborů se nemění.
     *
     * <p>Skrytý pohled dostane tytéž služby a tytéž hodnoty z nastavení jako viditelný
     * (viz větev {@code setOnSucceeded} v {@link #analyze}), aby prvky, horizonty i brány vyšly
     * stejně, jako kdyby si uživatel soubor otevřel sám. Pohled potřebuje i záložku Digitální
     * kontinuita — bez ní prvky nepočítá —, a tak dostane vlastní, nikde nezobrazenou.
     *
     * <p>Validační politika se po ověření vrací do výchozího stavu stejně jako po každém jiném:
     * platí na jedno ověření.
     */
    private void verifyInBackground(File sf, File of, char[] pdfPassword,
                                    java.util.function.Consumer<AdvisorView> onChecked,
                                    java.util.function.Consumer<Throwable> onFailed) {
        Task<ValidationResult> task = new Task<>() {
            @Override
            protected ValidationResult call() {
                return validateFiles(sf, of, pdfPassword,
                        new java.util.concurrent.atomic.AtomicInteger(),
                        new java.util.concurrent.atomic.AtomicReference<>());
            }
        };
        task.setOnSucceeded(e -> {
            AdvisorView hidden = new AdvisorView();
            wireServices(hidden);
            hidden.setContinuityView(new ContinuityView());
            hidden.setTimestampStandardsMode(appSettings.isTimestampStandardsMode());
            hidden.setCustomValidationTime(
                    appSettings.isUseCustomValidationTime() ? appSettings.getCustomValidationTime() : null);
            hidden.setSourceFile(sf, of, pdfPassword);
            hidden.show(task.getValue());
            appSettings.resetValidationPolicy();
            onChecked.accept(hidden);
        });
        task.setOnFailed(e -> {
            appSettings.resetValidationPolicy();
            onFailed.accept(task.getException());
        });
        Thread thread = new Thread(task, "dss-validation-result");
        thread.setContextClassLoader(AppClassLoader.get());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Výpočetní část ověření: načtení, DSS, průchody PDF a {@code ReportMapper} — bez sáhnutí
     * na okno. Běží mimo vlákno JavaFX.
     *
     * <p>Vyjmuto z {@link #analyze} 4. 10. 2026, aby se výsledný soubor úkonu dal ověřit na
     * pozadí ({@link #verifyInBackground}) a hlavní okno přitom dál ukazovalo vstupní soubor
     * (pokyn uživatele). Obě cesty volají tuhle metodu, takže se ověření nemůže rozejít s tím,
     * co aplikace o souboru ukáže, když si ho uživatel otevře sám.
     *
     * @param attachmentCount  sem se zapíše počet příloh PDF
     * @param legalisationLink sem se zapíše odkaz z eLegalizace, je-li
     */
    private ValidationResult validateFiles(File sf, File of, char[] pdfPassword,
            java.util.concurrent.atomic.AtomicInteger attachmentCount,
            java.util.concurrent.atomic.AtomicReference<
                    cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link> legalisationLink) {
        // When only sf is supplied, sf is the self-contained signed document.
        // When of is also supplied, of is the external signature and sf is the
        // detached content — field semantics flipped with the new zone labels.
        StartupTrace.mark("validation task started");
        DSSDocument sigDoc;
        DSSDocument detachedContent;
        java.util.List<DSSDocument> evidenceRecords = java.util.List.of();
        // Bytes the loader had to cut off the front of the validated document to make DSS
        // read it at all; zero for everything well-formed. Reported in the course of the
        // verification, because nothing signed them.
        int strayPrefixBytes;
        if (of != null) {
            DocumentLoader.Loaded loadedFirst = documentLoader.loadWithDetail(sf);
            DocumentLoader.Loaded loadedSecond = documentLoader.loadWithDetail(of);
            DSSDocument first = loadedFirst.document();
            DSSDocument second = loadedSecond.document();
            // An evidence record supplied alongside a document is not a second signature
            // to validate — it is material proving the document already existed. Which of
            // the two zones it landed in does not matter, so both orders are accepted.
            if (EvidenceRecordDetector.isEvidenceRecord(second)) {
                sigDoc = first;
                detachedContent = null;
                evidenceRecords = java.util.List.of(second);
                strayPrefixBytes = loadedFirst.strayPrefixBytes();
            } else if (EvidenceRecordDetector.isEvidenceRecord(first)) {
                sigDoc = second;
                detachedContent = null;
                evidenceRecords = java.util.List.of(first);
                strayPrefixBytes = loadedSecond.strayPrefixBytes();
            } else {
                // Unchanged: `of` is the detached signature, `sf` the content it signs.
                sigDoc = second;
                detachedContent = first;
                // The trim can only have happened to the content: a detached signature is
                // not a PDF, so it never enters the branch that cuts anything.
                strayPrefixBytes = loadedFirst.strayPrefixBytes();
            }
        } else {
            // A lone evidence record still validates — its hash tree and time-stamps are
            // checked, only the covered data objects stay unmatched for lack of content.
            DocumentLoader.Loaded loaded = documentLoader.loadWithDetail(sf);
            sigDoc = loaded.document();
            detachedContent = null;
            strayPrefixBytes = loaded.strayPrefixBytes();
        }
        CommonCertificateVerifier verifier = verifierRef.get();
        boolean offlineTrust = verifier == null || trustListsOffline;
        if (verifier == null) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
        }
        StartupTrace.mark("document loaded");
        ValidationOutcome outcome = validatorFactory.validate(
                sigDoc, detachedContent, verifier, pdfPassword, evidenceRecords);
        StartupTrace.mark("DSS validateDocument done");
        // The PDF passes below read the same document DSS just parsed. Reuse the bytes
        // already in memory (see PdfSource) instead of opening the file three more times.
        cz.digitalnikontinuita.anep.core.PdfSource pdfSource = cz.digitalnikontinuita.anep.core.PdfSource.of(
                sf, detachedContent != null ? detachedContent : sigDoc);
        java.util.Map<String, PdfSignatureAppearanceExtractor.Appearance> appearances =
                PdfSignatureAppearanceExtractor.extract(pdfSource, pdfPassword);
        // Osiřelý podpis je podpis v PDF, který DSS nezpracovalo. Při externím podpisu ale
        // DSS ověřuje jen ten externí soubor a PDF je mu pouhým podepsaným obsahem, takže
        // by „osiřelé“ vyšly všechny podpisy, které PDF nese samo — a karta je hlásila jako
        // další prvky externího souboru. Nález uživatele 3. 10. 2026 nad dvojicí
        // „T uznávaný tesne pred expiraci.pdf“ + „.p7s“: dva prvky místo jednoho, druhý
        // jako „nerozpoznaný druh“ s SubFilterem ETSI.CAdES.detached.
        java.util.List<PdfOrphanSignatureDetector.OrphanSignature> pdfOwn =
                PdfOrphanSignatureDetector.detect(pdfSource, pdfPassword);
        java.util.List<PdfOrphanSignatureDetector.OrphanSignature> orphans =
                detachedContent != null ? java.util.List.of() : pdfOwn;
        // Místo toho se o nich karta zmíní: PDF podepsané je, jen se to tímhle
        // ověřením neposuzuje (rozhodnutí uživatele 3. 10. 2026).
        final int contentPdfTimestamps = detachedContent == null ? 0
                : (int) pdfOwn.stream().filter(o -> "ETSI.RFC3161".equals(o.subFilter())).count();
        final int contentPdfSignatures = detachedContent == null ? 0
                : pdfOwn.size() - contentPdfTimestamps;
        // Podpisy adbe.x509.rsa_sha1 DSS nezpracuje; ověří je AnEP sám — podpisovou
        // hodnotu vlastní kontrolou, certifikát přes DSS s týmž verifikátorem.
        orphans = cz.digitalnikontinuita.anep.core.Pkcs1SignatureValidator.verifyAll(
                orphans, verifier, appSettings,
                outcome.reports().getDiagnosticData(),
                outcome.reports().getSimpleReport());
        java.util.List<cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector.EmptyField>
                emptyFields = cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector
                        .detect(pdfSource, pdfPassword);
        java.util.Map<String, String> signingTools =
                PdfSigningToolExtractor.extract(pdfSource, pdfPassword);
        // Verze PDF a podmnožiny, ke kterým se dokument hlásí. Čte se tady vedle
        // ostatních průchodů PDFBoxem, protože sdílejí tytéž bajty (viz PdfSource).
        cz.digitalnikontinuita.anep.core.PdfProfileExtractor.Profile pdfProfile =
                cz.digitalnikontinuita.anep.core.PdfProfileExtractor
                        .extract(pdfSource, pdfPassword);
        // A dostál jim? Měřeno na 455 dokumentech s prohlášením: polovina se ověří do
        // 64 ms, přes vteřinu jich je 13 a nejhorší 9,6 s. Vejde se to tedy do běhu na
        // pozadí vedle ostatních průchodů; dokumentů bez prohlášení se to netýká vůbec.
        cz.digitalnikontinuita.anep.core.PdfAConformanceChecker.Conformance pdfConformance =
                cz.digitalnikontinuita.anep.core.PdfAConformanceChecker
                        .check(pdfSource, pdfProfile);
        // Prostý sken, nebo textová vrstva? Pro blok o vkladové listině. U dokumentu
        // s textem končí na první textové stránce, takže stojí jen skeny.
        cz.digitalnikontinuita.anep.core.PdfScanDetector.Kind pdfTextLayer =
                cz.digitalnikontinuita.anep.core.PdfScanDetector
                        .detect(pdfSource, pdfPassword);
        int attachments = cz.digitalnikontinuita.anep.core.PdfAttachmentInspector
                .countAttachments(pdfSource, pdfPassword);
        attachmentCount.set(attachments);
        // Reading the reference means parsing the document once more, and almost no
        // document has an attachment at all — so the count decides whether it is worth it.
        if (attachments > 0) {
            legalisationLink.set(cz.digitalnikontinuita.anep.core.LegalisationLinkInspector
                    .read(pdfSource, pdfPassword));
        }
        StartupTrace.mark("PDFBox extraction done");
        // An evidence record in the second slot is not an external signature: the
        // authentication elements stay in the document itself, the record only supports
        // them. Reporting "in the external file" would point the reader at the wrong file.
        boolean externalSig = of != null && evidenceRecords.isEmpty();
        ValidationResult mapped = reportMapper.map(sf.getName(), sigDoc.getClass().getSimpleName(),
                outcome, appSettings, appearances, orphans, externalSig, signingTools,
                requiredRelaxations(outcome, sigDoc, detachedContent, verifier,
                        pdfPassword, evidenceRecords),
                strayPrefixBytes);
        // DSS reports a data message as a plain CMS object; only the bytes say otherwise,
        // and the reader needs to be told which of the two they are looking at.
        mapped.setIsdsArtefact(
                cz.digitalnikontinuita.anep.core.ContainerInspector.isdsArtefact(sf));
        // Prepared but unsigned fields. Carried on the result rather than passed through
        // ReportMapper.map(): it is a finding about the document, not about a signature,
        // and a document can have nothing but empty fields — then map() has nothing to
        // hang it on.
        mapped.setEmptySignatureFields(emptyFields);
        mapped.setContentPdfSignatures(contentPdfSignatures, contentPdfTimestamps);
        mapped.setPdfProfile(pdfProfile);
        mapped.setPdfConformance(pdfConformance);
        mapped.setPdfTextLayer(pdfTextLayer);
        mapped.setTrustListsOffline(offlineTrust);
        java.util.Set<cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation> active =
                java.util.EnumSet.noneOf(cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.class);
        for (cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation r
                : cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation.values()) {
            if (r.isEnabled(appSettings)) active.add(r);
        }
        mapped.setActiveRelaxations(active);
        // Co je v souboru za posledním prvkem, který ho kryje. Počítá se tady, protože
        // k tomu je potřeba délka souboru — tu ReportMapper nezná a znát nemusí.
        // U kontejnerů a odloučených podpisů vyjde null: revize jsou věc PDF.
        mapped.setRevisionTail(cz.digitalnikontinuita.anep.core.RevisionTail.of(
                outcome.reports().getDiagnosticData(), sf.length()));
        StartupTrace.mark("report mapped");
        return mapped;
    }

    /**
     * True when validation failed because no validator claims the format — a .docx, a photograph,
     * a plain text file. DSS signals it from {@code SignedDocumentValidator.fromDocument} as an
     * {@link UnsupportedOperationException} carrying "Document format not recognized/handled";
     * measured on .txt, .png and .docx, all three arrive that way.
     *
     * <p>The message is matched as well as the type, because UnsupportedOperationException is a
     * JDK class and something else throwing one would be a real error worth reporting as such.
     */
    private static boolean isUnsupportedFormat(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof UnsupportedOperationException && t.getMessage() != null
                    && t.getMessage().contains("Document format not recognized")) {
                return true;
            }
        }
        return false;
    }

    private static String formatLastModified(long ms) {
        return TimeUtil.format(ms);
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f kB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.2f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private static final String STATUS_FIELD_STYLE =
            "-fx-background-color: transparent; -fx-background-insets: 0; " +
            "-fx-border-color: transparent; -fx-padding: 0; -fx-text-fill: #333333;";
    private static final String STATUS_LABEL_STYLE =
            "-fx-font-weight: bold; -fx-text-fill: #333333; -fx-padding: 0;";

    private void setStatus(String text) {
        setStatus(text, null);
    }

    /**
     * Status line with a node appended inline right after the text — the Trusted-List warning icon,
     * which has to read as qualifying the sentence it follows.
     *
     * <p>With something to append, the text is laid out as a {@link TextFlow} instead of the usual
     * selectable field. A field grows to fill the toolbar, so an icon placed after it would sit at
     * the far right of the window with the sentence ending somewhere in the middle — detached from
     * what it refers to. Nothing in this particular sentence is worth copying, so the trade is
     * cheap; every other status line keeps the field.
     */
    /**
     * Both warnings about the trusted lists, side by side, or null when there is nothing to warn
     * about. The status line takes a single node, and these two are not alternatives: a state can
     * be short of one country's list and behind with another's at the same time.
     */
    private javafx.scene.Node trustWarnings() {
        javafx.scene.control.Label stale =
                TrustListInfoBar.staleWarningIcon(trustConfig.getStaleTrustLists());
        javafx.scene.control.Label missing =
                TrustListInfoBar.missingWarningIcon(trustConfig.getEmptyTerritories());
        if (stale == null) return missing;
        if (missing == null) return stale;
        return new javafx.scene.layout.HBox(0, stale, missing);
    }

    private void setStatus(String text, javafx.scene.Node trailing) {
        statusShowsTrustLists = false;
        statusBox.getChildren().clear();
        if (trailing == null) {
            statusBox.getChildren().add(makeStatusField(text));
        } else {
            javafx.scene.text.Text t = new javafx.scene.text.Text(text);
            t.setStyle("-fx-fill: #333333;");
            javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow(t, trailing);
            HBox.setHgrow(flow, Priority.ALWAYS);
            statusBox.getChildren().add(flow);
        }
        statusOpenBtn.setVisible(false);
        statusOpenBtn.setManaged(false);
        statusDocOpenBtn.setVisible(false);
        statusDocOpenBtn.setManaged(false);
    }

    /** Each element is either String[]{text} (plain) or String[]{boldLabel, rest} (bold prefix). */
    private void setStatusLines(java.util.List<String[]> lines) {
        statusShowsTrustLists = false;
        statusBox.getChildren().clear();
        for (String[] parts : lines) {
            if (parts.length < 2 || parts[0] == null || parts[0].isEmpty()) {
                statusBox.getChildren().add(makeStatusField(parts[0]));
            } else {
                Label lbl = new Label(parts[0]);
                lbl.setStyle(STATUS_LABEL_STYLE);
                TextField tf = makeStatusField(parts[1]);
                HBox row = new HBox(0, lbl, tf);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                HBox.setHgrow(tf, Priority.ALWAYS);
                statusBox.getChildren().add(row);
            }
        }
    }

    private TextField makeStatusField(String text) {
        TextField tf = new TextField(text);
        tf.setEditable(false);
        tf.setFocusTraversable(false);
        tf.setStyle(STATUS_FIELD_STYLE);
        tf.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tf, Priority.ALWAYS);
        return tf;
    }

    /**
     * Returns {@code true} when the exception (or any cause in its chain) is a
     * DSS {@link InvalidPasswordException} or a PDFBox bad-password exception.
     */
    private static boolean isPasswordException(Throwable t) {
        while (t != null) {
            if (t instanceof InvalidPasswordException) return true;
            String name = t.getClass().getName();
            if (name.contains("InvalidPassword") || name.contains("BadPassword")) return true;
            t = t.getCause();
        }
        return false;
    }

    /**
     * Shows a modal dialog asking the user for a PDF password.
     * Returns the entered password as a {@code char[]} (never empty unless user types nothing),
     * or {@code null} if the user cancels.
     */
    private char[] showPasswordDialog(String fileName) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle("Heslem chráněný dokument");
        dialog.setHeaderText("Dokument \"" + fileName + "\" je chráněn heslem.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        PasswordField pwdField = new PasswordField();
        pwdField.setPromptText("Zadejte heslo…");
        pwdField.setPrefWidth(300);

        Label lbl = new Label("Heslo:");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12, 12, 4, 12));
        grid.add(lbl, 0, 0);
        grid.add(pwdField, 1, 0);
        GridPane.setHgrow(pwdField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);

        // Focus the password field when the dialog opens
        Platform.runLater(pwdField::requestFocus);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                String pwd = pwdField.getText();
                return pwd != null ? pwd.toCharArray() : new char[0];
            }
            return null;
        });

        return dialog.showAndWait().orElse(null);
    }
}
