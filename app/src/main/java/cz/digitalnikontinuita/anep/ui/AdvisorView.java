package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.RestampingService;
import cz.digitalnikontinuita.anep.core.SignatureExtensionService;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.model.EvidenceRecordEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationCourse;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextArea;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * "Analýza" tab — three independent visual forms per authentication element:
 *   • TOTAL_PASSED   → green  "Platný"    — positive confirmation
 *   • TOTAL_FAILED   → red    "Neplatný"  — causes + problem/solution per reason
 *   • INDETERMINATE  → amber  "Neznámý"   — what prevents verification + remediation
 */
public class AdvisorView {

    private static final String CARD_STYLE =
            "-fx-border-color: #cccccc; -fx-border-width: 1; -fx-border-radius: 4;";

    /** Header of the block holding the explanation of a negative or inconclusive verdict. */
    private static final String REASONS_BLOCK_TITLE = "Zjištěné důvody a doporučení";

    /**
     * Nadpis bloku s výhradami a upřesněními k výsledku. Týž na kartě s kladným i s neznámým
     * výsledkem — je to totéž sdělení a čtenář je má hledat pod týmž jménem.
     */
    private static final String NOTES_BLOCK_TITLE = "Poznámky k výsledku";

    /** S1 — the signed content no longer matches what was signed. */
    private static final String REASON_S1 =
            "Došlo ke změně podepsaného obsahu, neboli k porušení jeho integrity. Rozsah změn,"
            + " ani jejich přesné umístění v rámci podepsaného obsahu, nejsou známy - může jít"
            + " o změnu v jediném znaku, slově, větě, odstavci, či o zcela jiný obsah (dokument)."
            + " Jisté je pouze to, že obsah, předložený k ověření, není stejný jako ten, který"
            + " byl původně podepsán.";

    // S2 — certificate already revoked at the decisive instant. The two variants below are
    // deliberate near-duplicates: Czech declines "podpis" and "pečeť" differently through every
    // paragraph, so templating them would be less readable than keeping each text whole. When
    // editing one, check whether the other needs the same change.

    /** S2, signature variant. */
    private static final String REASON_S2_SIGNATURE =
            "Bylo prokázáno, že podpisový certifikát byl revokovaný (též: předčasně zneplatněný)"
            + " už k tzv. rozhodnému okamžiku, ke kterému se platnost podpisu vyhodnocuje. Což je"
            + " čas nejstaršího platného časového razítka (nebo aktuální čas, pokud podpis není"
            + " opatřen žádným platným časovým razítkem).\n\n"

            + "Nabízí se výklad, že držitel certifikátu (případně zaměstnavatel, u zaměstnaneckého"
            + " certifikátu) nechal svůj certifikát revokovat - a podpis byl vytvořen teprve"
            + " následně. Prokázáno to ale není: rozhodný okamžik je horní mez okamžiku podpisu,"
            + " ne dolní. Dokládá, že podpis v ten čas už existoval, nikoli že nevznikl ještě před"
            + " revokací. Právě proto ověření nekončí závěrem \"neplatný\", ale neznámou"
            + " platností.\n\n"

            + "Revokace certifikátu k určitému konkrétnímu okamžiku v zásadě znamená, že jeho"
            + " držitel je zbaven právních účinků takových elektronických podpisů, které vznikly"
            + " po okamžiku revokace. Zjednodušeně: podpis, založený na revokovaném certifikátu,"
            + " již nemůže být považován za podpis držitele certifikátu. Je to obdoba zablokování"
            + " platební karty k určitému okamžiku: od něj dále již banka nepovolí žádnou platební"
            + " transakci s touto kartou, která by jinak šla na vrub držitele této karty.\n\n"

            + "V obou případech jde o řešení, které dokáže ochránit držitele: v případě platební"
            + " karty před důsledky jejího zneužití při ztrátě, v případě certifikátu před"
            + " důsledky ztráty čipové karty či USB tokenu se soukromým klíčem, ke kterému byl"
            + " certifikát vydán (případně před jiným druhem kompromitace soukromého klíče, např."
            + " jeho okopírováním).\n\n"

            + "Revokace certifikátu ale může mít i jiné důvody. Například to, že držitel přestal"
            + " daný certifikát používat, protože si pořídil nový a začal používat ten - a tak"
            + " původní certifikát nechal revokovat. Nebo může jít o zaměstnanecký certifikát,"
            + " jehož držitel přestal být zaměstnancem - a tak zaměstnavatel nechal jeho"
            + " certifikát revokovat.";

    /** S2, seal variant. */
    private static final String REASON_S2_SEAL =
            "Bylo prokázáno, že certifikát elektronické pečeti byl revokovaný (též: předčasně"
            + " zneplatněný) už k tzv. rozhodnému okamžiku, ke kterému se platnost pečeti"
            + " vyhodnocuje. Což je čas nejstaršího platného časového razítka (nebo aktuální čas,"
            + " pokud pečeť není opatřena žádným platným časovým razítkem).\n\n"

            + "Nabízí se výklad, že držitel certifikátu (případně zaměstnavatel, u zaměstnaneckého"
            + " certifikátu) nechal svůj certifikát revokovat - a pečeť byla vytvořena teprve"
            + " následně. Prokázáno to ale není: rozhodný okamžik je horní mez okamžiku pečetění,"
            + " ne dolní. Dokládá, že pečeť v ten čas už existovala, nikoli že nevznikla ještě"
            + " před revokací. Právě proto ověření nekončí závěrem \"neplatná\", ale neznámou"
            + " platností.\n\n"

            + "Revokace certifikátu k určitému konkrétnímu okamžiku v zásadě znamená, že jeho"
            + " držitel je zbaven právních účinků takových elektronických pečetí, které vznikly"
            + " po okamžiku revokace. Zjednodušeně: pečeť, založená na revokovaném certifikátu,"
            + " již nemůže být považována za pečeť držitele certifikátu. Je to obdoba zablokování"
            + " platební karty k určitému okamžiku: od něj dále již banka nepovolí žádnou platební"
            + " transakci s touto kartou, která by jinak šla na vrub držitele této karty.\n\n"

            + "V obou případech jde o řešení, které dokáže ochránit držitele: v případě platební"
            + " karty před důsledky jejího zneužití při ztrátě, v případě certifikátu před"
            + " důsledky ztráty čipové karty či USB tokenu se soukromým klíčem, ke kterému byl"
            + " certifikát vydán (případně před jiným druhem kompromitace soukromého klíče, např."
            + " jeho okopírováním).\n\n"

            + "Revokace certifikátu ale může mít i jiné důvody. Například to, že držitel přestal"
            + " daný certifikát používat, protože si pořídil nový a začal používat ten - a tak"
            + " původní certifikát nechal revokovat.";

    private final VBox cardsBox = new VBox();
    private final ScrollPane scroll = new ScrollPane(cardsBox);
    private final VBox root;
    private boolean timestampStandardsMode = true;
    private final TrustListInfoBar trustListInfoBar = new TrustListInfoBar();
    private Runnable lenientVerifyCallback;
    private Runnable landRegistryVerifyCallback;
    /** Otevře Nastavení; {@code true} = rovnou pokročilou stránku. Viz {@link #solutionParagraph}. */
    private java.util.function.Consumer<Boolean> settingsOpener;
    /** Snapshot of the custom validation time, taken before AppSettings clears its one-shot flag. */
    private java.time.LocalDateTime customValidationTime = null;

    private File sourceFile;
    /** The second submitted file: a detached signature, or an evidence record. */
    private File secondaryFile;
    private char[] pdfPassword;
    private AugmentationService augmentationService;
    private SignatureExtensionService extensionService;
    private TimestampingService timestampingService;
    private RestampingService restampingService;
    private FixationService fixationService;
    private AppSettings settings;
    private List<SignatureSummary> lastResult = List.of();
    private ValidationResult lastValidationResult;
    /** How the signed data is packaged — needed by the "Druh formátu:" row, per validated file. */
    private boolean externalSignature;
    private boolean asicContainer;
    private String asicContainerKind;
    private boolean signedDataInside = true;
    /** No time-stamp anchors any signature in the file — level B. Set by {@link #show}. */
    private boolean notAnchored;

    /**
     * Jedno tlačítko pro všechny tři cesty, kterými se ověřitelnost prodlužuje — pojmenované
     * podle té, která je pro dokument otevřená.
     *
     * <p><b>Jedno, ne tři.</b> Cesty se vzájemně vylučují (viz {@link #show}), takže platí právě
     * jedna a uživatel mezi nimi nevybírá: je to otázka o stavu dokumentu, ne o jeho záměru. Tři
     * tlačítka vedle sebe by nabízela volbu, kterou nemá — a u dokumentu s několika podpisy by ji
     * ani nešlo udělat, protože každý podpis spadá jinam.
     *
     * <p><b>Přesto pojmenované.</b> Do 7. 9. 2026 stálo na tlačítku jednotné „Zachovat
     * ověřitelnost" s odůvodněním, že tři jména patří k jednomu úkonu. To druhé už neplatí:
     * výsledky se liší právě v tom, na čem uživateli záleží — uchovaný dokument ověří všechny
     * validátory, prodloužený jen ty, které časová razítka posuzují podle standardů, a
     * přerazítkovaný naopak jen ty s vlastní zásobou revokačních informací. Jméno úkonu je tedy
     * jméno výsledku, a to má být z tlačítka i ze jména souboru poznat.
     *
     * <p>Sloveso, ne holé „Provést": tlačítko má říct, co udělá, aby se to nemuselo dohledávat
     * v odstavci nad ním. A neříká nic o úrovni podle ETSI, takže u smíšeného dokumentu netvrdí
     * víc, než je pravda — čím se stane který podpis, stojí ve výčtu prvků.
     */
    private final Button preserveButton = new Button(ContinuityMode.PRESERVE.verb);
    private final ProgressIndicator preserveProgress = new ProgressIndicator();

    private final Button fixButton = new Button("Vytvořit forenzní balíček");
    private final ProgressIndicator fixProgress = new ProgressIndicator();

    /**
     * Kterou cestou se ověřitelnost zachová. Nastavuje {@link #show}, čte obsluha tlačítka.
     *
     * <p>Jména variant jsou tady, a jen tady: sloveso na tlačítko, podstatné jméno do titulků
     * dialogů a značka do jména výstupního souboru. Tři místa, která musí mluvit stejně —
     * uživatel, který si přečte o „prodloužení" v záložce, musí stejné slovo najít na tlačítku
     * i v souboru, který mu z toho vyjde.
     *
     * <p>Značka je bez diakritiky a bez mezer záměrně: jde do jména souboru, které putuje
     * e-mailem, do spisových služeb a na cizí systémy.
     */
    enum ContinuityMode {
        PRESERVE("Uchovat", "Uchování", "Uchovat dokument", "_Uchovano"),
        PROLONG("Prodloužit", "Prodloužení", "Prodloužit dokument", "_Prodlouzeno"),
        RESTAMP("Přerazítkovat", "Přerazítkování", "Přerazítkovat dokument", "_Prerazitkovano");

        /** Rozkaz na tlačítku, které úkon spustí. */
        final String verb;
        /** Jméno úkonu do titulků a do běžné řeči. */
        final String noun;
        /** Titulek potvrzovacího dialogu, kde samo sloveso nestačí. */
        final String prompt;
        /** Značka na konci jména výstupu, před datem. */
        final String mark;

        ContinuityMode(String verb, String noun, String prompt, String mark) {
            this.verb = verb;
            this.noun = noun;
            this.prompt = prompt;
            this.mark = mark;
        }

        /** Titulek dialogu pro uložení výsledku. */
        String saveTitle() {
            return "Uložit " + switch (this) {
                case PRESERVE -> "uchovaný";
                case PROLONG -> "prodloužený";
                case RESTAMP -> "přerazítkovaný";
            } + " dokument";
        }

        /** Titulek chybového dialogu. */
        String failTitle() { return noun + " selhalo"; }
    }
    private ContinuityMode continuityMode;
    /**
     * Podpisy, jejichž verdikt stojí na volnějším čtení (situace 45) a kterým úkon prospěje.
     *
     * <p>Jen jim se do dokumentu vloží materiál, který ověření vidělo (bez údajů, které o certifikátu
     * nic nevypovídají — {@code RestampingService.speaks}). Ostatním by to nic
     * nepřineslo a stálo by to megabajty — změřeno {@code UnionMaterialProbe} na
     * {@code pet podpisu LT.pdf}: doslovné vložení všeho přidalo 2,7 MB ve čtyřech CRL a nezlepšilo
     * ani jeden verdikt.
     */
    private java.util.Set<String> verbatimSigIds = java.util.Set.of();

    private final HBox augmentRow;
    /** Kam se posílá blok Digitální kontinuita. Bez zapojení se prostě nikam neposílá. */
    private ContinuityView continuityView;

    /**
     * Prvky tak, jak je naposledy spočítala záložka Digitální kontinuita — s verdiktem karty,
     * horizonty a předpovědí, zda na ně úkon dosáhne. Ze stejného výpočtu, jaký čtenář viděl,
     * vzniká stav „před úkonem“ v záznamu o úkonu, a po ověření výsledného souboru stav „po“.
     */
    private java.util.List<ContinuityElementBlocks.Element> lastElements = java.util.List.of();

    /**
     * Ověření výsledného souboru úkonu. Zapojuje {@code MainView}; bez zapojení (sondy) se po
     * úkonu nic neověřuje a záznam nevznikne.
     *
     * <p>Do 4. 10. 2026 se výsledek ověřoval v hlavním okně a to pak ukazovalo výsledný soubor
     * místo vstupního. Od té doby se ověřuje na pozadí (pokyn uživatele) a do okna se dostane
     * jen na žádost — {@link #open}.
     *
     * <p>Parametry obou metod: {@code document} je soubor s podpisem, nebo u odloučeného podpisu
     * podepsaný obsah; {@code external} odloučený podpis, nebo {@code null}.
     */
    interface ResultVerifier {
        /**
         * Ověří soubor na pozadí touž cestou jako hlavní okno. {@code onChecked} dostane skrytý
         * pohled, který výsledek vyhodnotil (pro {@link #fillAfter}); obě zpětná volání běží na
         * vlákně JavaFX.
         */
        void verify(File document, File external, char[] password,
                    java.util.function.Consumer<AdvisorView> onChecked,
                    java.util.function.Consumer<Throwable> onFailed);

        /** Otevře soubor v hlavním okně, jako by ho tam uživatel přetáhl sám. */
        void open(File document, File external, char[] password);
    }

    private ResultVerifier resultVerifier;
    private PrintUtil.ProtocolExtras protocolExtras;

    public void setResultVerifier(ResultVerifier v) { this.resultVerifier = v; }

    public void setProtocolExtras(PrintUtil.ProtocolExtras extras) { this.protocolExtras = extras; }

    public AdvisorView() {
        cardsBox.setPadding(new Insets(12));
        cardsBox.setSpacing(0);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        preserveProgress.setMaxSize(22, 22);
        preserveProgress.setVisible(false);
        preserveProgress.setManaged(false);

        preserveButton.setStyle(
                "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white;"
                + " -fx-background-color: linear-gradient(to bottom, #7b1fa2, #4a148c);"
                + " -fx-border-color: #4a148c; -fx-border-radius: 5; -fx-background-radius: 5;"
                + " -fx-padding: 6 18 6 18; -fx-cursor: hand;"
                + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 3, 0.0, 0, 2);");
        preserveButton.setVisible(false);
        preserveButton.setManaged(false);
        // Která ze tří cest se spustí, rozhodne brána — canPreserve, canRestamp a canProlong se
        // vzájemně vylučují už dnes, takže platí právě jedna. Uživatel tu volbu nemá a mít nemá:
        // je to otázka o stavu dokumentu, ne o jeho záměru.
        preserveButton.setOnAction(e -> {
            if (continuityMode == null) return;
            if (!confirmLockedCertification()) return;
            switch (continuityMode) {
                case RESTAMP -> runRestamping();
                case PROLONG -> runPreservation(PreserveMode.PROLONG);
                default -> runPreservation();
            }
        });

        // Bublinová nápověda u obou tlačítek zrušena: co úkon udělá, stojí v textu u prvku
        // nad nimi, a to podrobněji, než se do bubliny vejde. Široká bublina navíc překryje
        // právě ten text, kterým se čtenář rozhoduje.

        fixProgress.setMaxSize(22, 22);
        fixProgress.setVisible(false);
        fixProgress.setManaged(false);

        fixButton.setStyle(
                "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white;"
                + " -fx-background-color: linear-gradient(to bottom, #455a64, #263238);"
                + " -fx-border-color: #263238; -fx-border-radius: 5; -fx-background-radius: 5;"
                + " -fx-padding: 6 18 6 18; -fx-cursor: hand;"
                + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 3, 0.0, 0, 2);");
        fixButton.setVisible(false);
        fixButton.setManaged(false);
        fixButton.setOnAction(e -> runFixation());
        // Tlačítko pojmenovává výsledek, ne úkon. Dřív stálo "Zafixovat" s odůvodněním, že
        // tlačítka mají pojmenovávat úkony — jenže "zafixovat" samo neřekne co ani nač, a vedle
        // "Zachovat ověřitelnost" vypadalo jako jeho slabší varianta, přestože je to úkon jiného
        // druhu: ověřitelnost neprodlužuje vůbec. "Forenzní balíček" je navíc jméno, které kolem
        // toho už všechno používá — text v záložce, nápověda, dialog i předpona FB_ ve jméně
        // souboru — takže tlačítko bylo jediné místo, kde se ta věc jmenovala jinak.
        //
        // Nejvíc to váží u páté varianty: prvek obstál a přesto se nabízí jen tohle. Tam musí být
        // z tlačítka poznat, že výsledkem není zachovaná platnost, nýbrž balíček s podklady.
        augmentRow = new HBox(10, preserveProgress, preserveButton, fixProgress, fixButton);
        augmentRow.setAlignment(Pos.CENTER_LEFT);
        augmentRow.setPadding(new Insets(8, 12, 8, 12));

        root = new VBox(scroll);
    }

    public VBox getRoot() { return root; }

    /**
     * Řádek s tlačítky akcí. Nebydlí už pod kartami Analýzy, ale v záložce Digitální kontinuita —
     * akce platí pro celý soubor, kdežto karty mluví po podpisech, a pod poslední z nich ten řádek
     * vypadal, jako by patřil jí. Staví se ale dál tady, protože visí na branách v {@link #show}.
     */
    public HBox getActionRow() { return augmentRow; }

    public void setContinuityView(ContinuityView v) { this.continuityView = v; }

    public void setFixationService(FixationService s) { this.fixationService = s; }

    public void setRestampingService(RestampingService s) { this.restampingService = s; }

    public void setTimestampStandardsMode(boolean v) { this.timestampStandardsMode = v; }

    public void setLenientVerifyCallback(Runnable r) { this.lenientVerifyCallback = r; }
    /** Ověření s ústupky podatelny katastru, z bloku o vkladové listině. */
    public void setLandRegistryVerifyCallback(Runnable r) { this.landRegistryVerifyCallback = r; }
    public void setSettingsOpener(java.util.function.Consumer<Boolean> opener) {
        this.settingsOpener = opener;
    }

    public void setCustomValidationTime(java.time.LocalDateTime v) { this.customValidationTime = v; }

    public void setTrustListInfo(long sourceCheckMs, boolean refreshInProgress,
                                 cz.digitalnikontinuita.anep.core.TrustConfig.TrustListVersion latestList,
                                 List<cz.digitalnikontinuita.anep.core.TrustConfig.StaleTrustList> staleTrustLists,
                                 List<String> emptyTerritories) {
        trustListInfoBar.set(sourceCheckMs, refreshInProgress, latestList,
                staleTrustLists, emptyTerritories);
    }

    /**
     * @param file      the file the user submitted for validation. When a detached signature was
     *                  submitted too, this is the <em>signed document</em>, not the signature.
     * @param secondary the second file, when there was one — a detached signature or an evidence
     *                  record. Preservation of a detached signature needs both: the signature is
     *                  what gets extended, and the archive time-stamp covers the signed data.
     */
    public void setSourceFile(File file, File secondary, char[] password) {
        this.sourceFile = file;
        this.secondaryFile = secondary;
        this.pdfPassword = password;
    }

    /**
     * The file that carries the signature. The same as the submitted file, except when a detached
     * signature was submitted alongside the document it signs — then the signature is the second
     * file and {@link #sourceFile} is the signed content.
     */
    private File signatureFile() {
        return externalSignature && secondaryFile != null ? secondaryFile : sourceFile;
    }

    /** The signed content, when it lives outside the signature file; null when it is inside. */
    private File detachedContentFile() {
        return externalSignature && secondaryFile != null ? sourceFile : null;
    }

    public void setAugmentationService(AugmentationService svc) {
        this.augmentationService = svc;
    }

    public void setExtensionService(SignatureExtensionService svc) {
        this.extensionService = svc;
    }

    public void setTimestampingService(TimestampingService svc) {
        this.timestampingService = svc;
    }

    public void setAppSettings(AppSettings s) {
        this.settings = s;
    }

    /**
     * Vrátí záložku do podoby před prvním ověřením — i s tlačítky akcí a obsahem záložky
     * Digitální kontinuita, které odsud visí. Viz {@code MainView.clearResults()}.
     *
     * <p>Průběh běžícího úkonu se neschovává: úkon se dokončí nad svým souborem a jeho konec
     * ukazatel stejně uklidí.
     */
    public void clear() {
        cardsBox.getChildren().clear();
        lastResult = List.of();
        lastValidationResult = null;
        lastElements = java.util.List.of();
        preserveButton.setVisible(false);
        preserveButton.setManaged(false);
        fixButton.setVisible(false);
        fixButton.setManaged(false);
        if (continuityView != null) continuityView.setBlock(null);
    }

    public void show(ValidationResult result) {
        cardsBox.getChildren().clear();
        lastResult = result.getSignatures();
        lastValidationResult = result;
        lastElements = java.util.List.of();
        rememberPackaging(result);

        // Same header as "Rozšířený přehled": Trusted-List status, then the headline sentence
        // counting the authentication elements. Both statements describe the run as a whole,
        // so the two tabs render them from the same code.
        cardsBox.getChildren().add(trustListInfoBar.node());

        // Nálezy o dokumentu jako celku. Každý se staví jen tam, kde je co říct, a všechny musí
        // umět stát i nad souborem bez jediného podpisu:
        //  * shoda s PDF/A — k té se hlásí i dokumenty, které nikdo nepodepsal;
        //  * co v souboru přibylo po posledním kryjícím prvku — u dvou třetin souborů sahá
        //    poslední prvek na konec a blok tam mlčí.
        //
        // Blok o prázdných podpisových polích tu <b>není</b>: stál v Analýze i v Rozšířeném
        // přehledu ve zcela totožné podobě, takže čtenář, který si projde obě záložky, čte totéž
        // dvakrát. Pokyn uživatele 22. 9. 2026 — zůstává v Rozšířeném přehledu
        // ({@link ExtendedView}), odkud se staví z téhož {@link EmptySignatureFieldBlock}.
        //
        // Od 8. 10. 2026 jsou bloky o dokumentu věcí expertního módu (rozhodnutí uživatele):
        // běžnému uživateli by Analýzu zahlcovaly. Změna obsahu stránek po podpisu tu od
        // 9. 10. 2026 není vůbec: je to varování, které patří vedle verdiktu, takže stojí
        // v Rozšířeném přehledu pro všechny (viz RevisionTailBlock). Tady zůstává jen klidná
        // podoba — za podpisem přibyla jen validační data.
        boolean expertDoc = settings != null && settings.isExpertMode();
        java.util.List<VBox> documentBlocks = new java.util.ArrayList<>();
        if (expertDoc) addIfPresent(documentBlocks, PdfAConformanceBlock.build(result));
        if (expertDoc && !RevisionTailBlock.contentChanged(result)) {
            addIfPresent(documentBlocks, RevisionTailBlock.build(result));
        }
        //  * způsobilost jako vkladová listina pro katastr — u každého PDF s vnitřními podpisy;
        //    viz LandRegistryDeed.
        if (expertDoc) {
            addIfPresent(documentBlocks,
                    LandRegistryDeedBlock.build(result, landRegistryVerifyCallback));
        }

        // Jedna věta, nebo dvě? Dokud se nic o dokumentu nevysází, zůstává záhlaví jedno a nese
        // i podobu souboru — „Na dokumentu PDF (podtypu PDF/A-2b, verze 1.7) je 1 autentizační
        // prvek". Jakmile pod ním stojí dokumentové bloky, rozdělí se na dvě: podoba souboru se
        // stane nadpisem nad nimi a věta o prvcích nadpisem nad kartami. Bez toho rozdělení
        // nebylo z ničeho poznat, které bloky mluví o dokumentu a které o prvcích (rozhodnutí
        // uživatele z 18. 9. 2026).
        if (documentBlocks.isEmpty()) {
            cardsBox.getChildren().add(ResultView.authCountHeader(result));
        } else {
            cardsBox.getChildren().add(ResultView.documentHeader(result));
            for (VBox block : documentBlocks) {
                cardsBox.getChildren().add(new Separator());
                cardsBox.getChildren().add(block);
            }
            Separator between = new Separator();
            between.setPadding(new Insets(12, 0, 4, 0));
            cardsBox.getChildren().add(between);
            cardsBox.getChildren().add(ResultView.authCountHeader(result, false));
        }

        if (!result.getSignatures().isEmpty()) {
            cardsBox.getChildren().add(new Separator());
        }

        List<SignatureSummary> allSigs = result.getSignatures();
        // A broken document cannot be repaired by augmentation, a fresh timestamp or preservation
        // — every one of them would only notarise content that is provably not what was signed.
        boolean brokenIntegrity = allSigs.stream()
                .anyMatch(sig -> sig.getDetectedSituations().contains(1));

        // Once an evidence record covers a signature, ETSI TS 119 132-3 / TS 119 122-3 allow only
        // adding another record or renewing an existing one; for an ASiC container augmenting a
        // preserved signature is forbidden outright. Augmenting anyway would rewrite the very
        // bytes the record's hash tree attests to and invalidate the preservation — the one thing
        // the record exists to prevent. So the offer is withdrawn rather than left to misfire.
        boolean preservedByRecord = allSigs.stream()
                .anyMatch(sig -> !sig.getEvidenceRecords().isEmpty());

        boolean tsaConfigured = settings != null && settings.getSelectedTsa() != null;

        // One rule, both paths, three outcomes.
        //
        //  • No time-stamp yet (level B): only a time-stamp is offered. The signature is anchored
        //    to the present, so revocation data speaking about that anchor would have to be issued
        //    after now — it does not exist. Nothing can be embedded that would mean anything, and
        //    the time-stamp is what moves the anchor into the past so that later data can.
        //  • Anchored (T or LT) and the missing revocation data can be had, issued after the
        //    anchor: preservation, straight to LTA.
        //  • Anchored but that data cannot be had: fixation, which freezes what there is and
        //    claims nothing about continuity.
        //
        // The middle test is situation 33 plus freshness — see SignatureSummary's
        // preservationWorthwhile. S33 itself keeps describing the document; this says whether
        // acting on it would achieve anything.
        //
        // Doplnění revokačních dat se samostatně nenabízí: úroveň LT vloží revokační data, ale
        // nedokládá, kdy tam byla vložena — bez archivního razítka nemá ten materiál důkaz
        // existence. Nabízí se proto rovnou uchování (LTA) = doplnění dat + archivní razítko
        // v jednom kroku.
        boolean worthPreserving = allSigs.stream()
                .anyMatch(AdvisorView::preservationAddsSomething);

        // Level B: nothing is anchored yet. Taken from the best-signature-time rather than from
        // the format string, because that is the thing the rule is about — a signature DSS grades
        // as B but which some time-stamp does anchor is not this case.
        List<SignatureSummary> realSigs = allSigs.stream()
                .filter(sig -> !"TimeStampToken".equals(sig.getFormat()))
                .collect(Collectors.toList());
        notAnchored = !realSigs.isEmpty()
                && realSigs.stream().allMatch(sig -> sig.getBestSignatureTime() == null);

        // …and a time-stamp may only be added while the signing certificate is still alive.
        // EN 319 122-1 V1.3.1 clause 6.3, requirement m): the token "shall be created before the
        // signing certificate has been revoked or has expired"; EN 319 102-1 V1.4.1 clause 4.3.3.4
        // NOTE 2 repeats it and warns that validation of the result can otherwise fail. A
        // time-stamp obtained after that point does not anchor the signature to a moment when the
        // certificate was good — it only records that the signature existed once it was too late.
        // Ptá se to jen na prvky, kterým je úkon určen (reachable, viz níže) — prvek, na který se
        // stejně nedosáhne, tu nemá co vetovat. Dokud se brány ptaly přes všechny, stačil jediný
        // podpis s prošlým certifikátem a nešlo orazítkovat ani ty ostatní.
        Date now = new Date();

        // Containers carrying only time-stamps are covered by the same test: ReportMapper computes
        // situation 33 for them from the time-stamps' own certificate chains. That is what keeps
        // renewal from being offered once the last TSA certificate has expired — no usable
        // revocation for it can be fetched any more, so a fresh time-stamp would only attest to a
        // chain that already has a hole in it, and fixation is the honest offer instead.
        // Preservation is only offered where it produces something a reader will accept. Outside
        // expert mode that means the verdict is already "valid" and all four situations hold —
        // unbroken time-stamp chain (31), the last time-stamp still covered by its own certificate
        // (32), every missing revocation obtainable (33), no relaxations in force (34). Where 32
        // fails the gap in the time-stamp chain is already in the past and nothing added today can
        // bridge it: a conformant validator still accepts the result, but CzechPOINT, the OVM
        // filing rooms, Signer and SecuSign judge time-stamps by their certificate's validity and
        // will not. Offering a fatter file that those readers still reject is not a service, so
        // fixation stands in — canFix already picks it up when preservation is withheld.
        //
        // Situation 32 is now required of preservation always, expert mode included. It used to be
        // waived there, with a dialog on the way out warning that the result would be sound by the
        // standard and refused by readers who judge time-stamps by certificate validity. That case
        // has an action of its own now — Prodloužení, below — so waiving it here would put two
        // buttons on the same document and leave the warning doing the work a name should do.
        boolean expertMode = settings != null && settings.isExpertMode();
        List<SignatureSummary> gateSigs = realSigs.isEmpty() ? allSigs : realSigs;

        // A file whose only authentication elements are time-stamps, container or not. Computed
        // here rather than lower down because the anchor set below needs it, and that set is now
        // read by every gate instead of by prolongation alone.
        boolean timestampOnlyFile =
                SignatureExtensionService.isTimestampOnlyFile(lastValidationResult);
        java.util.Set<String> lastTimestampTrusted =
                lastTimestampTrusted(result.getDiagnosticData(), timestampOnlyFile);

        // Které prvky úkon vůbec získá.
        //
        // Tohle je ta změna. Brány se dosud ptaly `allMatch` přes všechny prvky, takže jediný
        // nedosažitelný podpis sebral nabídku i těm ostatním — a dokument, kde jsou dva podpisy
        // bezvadné a tři ne, nešlo uchovat vůbec (změřeno na pet podpisu LT.pdf a priklady podpisu
        // pro kurz media 4.pdf). Nečinnost je přitom pro ty bezvadné horší než úkon: razítko kryje
        // celý soubor, takže jim prospěje, a tomu nedosažitelnému neuškodí — co je na něm dnes
        // sporné, zůstane sporné i potom. ETSI TS 119 511 tenhle postoj mezi možnými strategiemi
        // uchovávací služby výslovně uvádí: „preserve all the information it can collect".
        //
        // Podmínky jednotlivých akcí se nemění, jen se ptají jinde: na prvcích, kterým je úkon
        // určen, ne na všech. Prvek, na který se nedosáhne, se z rozhodování vypouští — nemá právo
        // veta nad prvky, se kterými se dá něco dělat.
        //
        // Jediná výjimka je porušená integrita: ta zůstává vetem přes celý dokument (brokenIntegrity
        // výše), protože razítko by notářsky stvrdilo obsah, o kterém je prokázané, že podepsaný
        // nebyl. Tam nejde o to, komu úkon prospěje.
        List<SignatureSummary> reachable = gateSigs.stream()
                .filter(sig -> continuityObstacle(sig, lastTimestampTrusted) == null)
                .collect(Collectors.toList());

        verbatimSigIds = reachable.stream()
                .filter(sig -> sig.getDetectedSituations().contains(45))
                .map(SignatureSummary::getSignatureId)
                .collect(java.util.stream.Collectors.toSet());

        boolean signingCertsStillValid = reachable.stream().allMatch(sig ->
                !sig.isCertRevoked()
                && sig.getCertNotAfter() != null
                && sig.getCertNotAfter().isAfter(now.toInstant()));

        // Uchování je jediný ze tří úkonů, který umí platnost teprve vytvořit. Přesně to tvrdí
        // situace 35 — vložená revokační data v dokumentu jsou a chybí jen důkaz, že tam byla,
        // dokud šla ověřit; archivní razítko ho vytvoří. Je to změřené: na
        // souboru „kvalifikovany Reader 22 11.pdf" povýší uchování PKCS7-T na PKCS7-LTA
        // a INDETERMINATE/OUT_OF_BOUNDS_NO_POE na TOTAL_PASSED, a všechny záznamy CRS i PSV_CRS
        // v detailním protokolu vyberou právě tu vloženou odpověď.
        //
        // Brána continuityObstacle() takový prvek odmítá, a pro zbylé dva úkony právem: razítko
        // stvrzuje stav, takže co je sporné, zůstane sporné i potom. Uchování tedy dostává vlastní
        // seznam dosažitelných, o ten jediný případ širší. Bez toho si situace 35 a záložka
        // protiřečily — 33 podpisům ve sbírce slibovala nápravu, kterou nabídka neuměla provést.
        List<SignatureSummary> preservationReachable = gateSigs.stream()
                .filter(sig -> continuityObstacle(sig, lastTimestampTrusted) == null
                        || sig.getDetectedSituations().contains(35))
                .collect(Collectors.toList());

        // <b>Stačí jeden prvek.</b> Do 8. 9. 2026 tu stálo allMatch, tedy „všechny dosažitelné
        // prvky musí vyjít". Razítko ale kryje celý soubor a prvku, na který úkon nedosáhne,
        // neuškodí — co je na něm dnes sporné, zůstane sporné i potom (ETSI TS 119 511 uvádí
        // „preserve all the information it can collect" mezi možnými postoji uchovávací služby).
        // Odepřít úkon čtyřem bezvadným podpisům kvůli pátému je horší volba než ho nabídnout
        // a říct, na koho nedosáhne — a to říct se musí; obstarává to addCombinationBlock().
        //
        // Pravidla souběhu jsou od uživatele (8. 9. 2026) a znějí: nejsilnější varianta, na kterou
        // dosáhne aspoň jeden prvek, v pořadí uchování > přerazítkování > prodloužení. Pořadí drží
        // podmínky !canPreserve a !canRestamp o kus níž, ne tenhle řádek.
        boolean offerWorthy = preservationReachable.stream().anyMatch(this::preservationReaches);

        boolean preservable = !brokenIntegrity
                && !preservedByRecord
                && tsaConfigured
                && sourceFile != null
                && offerWorthy
                && worthPreserving;

        // Two paths, and the format decides which. PAdES preservation is not a signature extension
        // at all — the material goes into a PDF-level DSS dictionary and the archive time-stamp is
        // a new PDF revision — so it keeps its hand-built path. Everything else DSS extends itself.
        // The container test is not redundant: an ASiC may hold a signed PDF, and DSS then reports
        // a PAdES signature for a file PDFBox cannot open at all.
        //
        // Starting from a signature that nothing anchors takes two time-stamps here as well, since
        // this path stamps once per round: one to reach T, the archive one after the validation
        // data. runPreservation() runs both, so the offer stands — subject, like any first
        // time-stamp, to the signing certificate still being good (EN 319 122-1 requirement m).
        // The PDF variant of a time-stamp-only file is a document handed to one time-stamping
        // authority after another, and it reaches this path rather than DSS's: there is no signature
        // to extend, only a DSS dictionary to fill and a revision to add, which is the hand-built
        // path's whole job. (timestampOnlyFile itself is computed above, with the anchor set.)
        //
        // Renewal comes too late once the newest time-stamp's own certificate has expired — see
        // lastTimestampChainExpired(). Scoped to files carrying nothing but time-stamps: where
        // there is a signature, situation 33 already governs the offer.
        boolean tooLateToRenew =
                timestampOnlyFile && lastTimestampChainExpired(lastValidationResult);

        boolean canPreservePdf = preservable
                && (!notAnchored || signingCertsStillValid)
                && augmentationService != null
                && timestampingService != null
                && !asicContainer
                && !tooLateToRenew
                // Either a PAdES signature to carry to LTA, or nothing but time-stamps to renew.
                // The second is not a signature in any format, so the format test cannot see it:
                // its summaries say "TimeStampToken", and asking them about a PDF signature form
                // left a chain of document time-stamps with fixation as its only offer.
                && (timestampOnlyFile || allSigs.stream().anyMatch(sig ->
                        isPdfSignature(sig.getFormat()) && !isPdfNotEtsi(sig.getFormat())));

        // A detached signature can only be extended together with the document it signed: the
        // archive time-stamp covers the signed data, so the signature file alone is not enough.
        // Situation 13 is exactly that — the signed data was not found — and it is the right test
        // rather than an absent signature scope: a detached XAdES opened on its own still reports
        // a scope for the reference it could not resolve, and would be offered an action that
        // fails on "The Reference for URI dokument.pdf has no XMLSignatureInput".
        boolean signedDataMissing = allSigs.stream()
                .anyMatch(sig -> sig.getDetectedSituations().contains(13));
        // DSS's extendDocument() asks for the archive level and fills in everything on the way,
        // fetching both time-stamps in one call — measured on bajecny svet6 8 05 test B-B,
        // where a B-level CAdES, XAdES and JAdES each came out B-LTA with a SIGNATURE_TIMESTAMP and
        // an ARCHIVE_TIMESTAMP a second apart. So this path may start from B, provided the signing
        // certificate is still good: the time-stamp it adds is subject to EN 319 122-1 requirement
        // m) just as a stand-alone one would be.
        boolean canPreserveExtension = preservable
                && extensionService != null
                && !signedDataMissing
                && !tooLateToRenew
                && (!notAnchored || signingCertsStillValid)
                && SignatureExtensionService.handles(lastValidationResult, signatureFile());

        boolean canPreserve = canPreservePdf || canPreserveExtension;
        // Viditelnost se nastaví až dole, až budou známé všechny tři cesty.

        // Průhled do brány, zapíná se -Danep.gatetrace. Podmínek je přes deset a z nabídky je vidět
        // jen jejich součin, takže na otázku „proč se tomuhle dokumentu nenabízí zachování"
        // nešlo odpovědět jinak než čtením kódu a hádáním. Vzniklo u PAdES B-B kvalifikovaný
        // PS 4096.pdf, kde jsem hádal dvakrát a pokaždé vedle.
        if (Boolean.getBoolean("anep.gatetrace")) {
            System.out.println("[brána] prvků=" + gateSigs.size()
                    + " dosažitelných=" + reachable.size()
                    + " brokenIntegrity=" + brokenIntegrity
                    + " preservedByRecord=" + preservedByRecord
                    + " tsaConfigured=" + tsaConfigured
                    + " offerWorthy=" + offerWorthy
                    + " worthPreserving=" + worthPreserving
                    + " preservable=" + preservable
                    + " notAnchored=" + notAnchored
                    + " signingCertsStillValid=" + signingCertsStillValid
                    + " timestampOnlyFile=" + timestampOnlyFile
                    + " tooLateToRenew=" + tooLateToRenew
                    + " asicContainer=" + asicContainer
                    + " signedDataMissing=" + signedDataMissing
                    + " canPreservePdf=" + canPreservePdf
                    + " canPreserveExtension=" + canPreserveExtension);
            for (SignatureSummary sig : gateSigs) {
                System.out.println("[brána]   " + sig.getSignatureId() + " " + sig.getFormat()
                        + " " + displayedIndication(sig) + " situace=" + sig.getDetectedSituations()
                        + " razítek=" + sig.getAllTimestamps().size()
                        + " kvalifikovaný=" + sig.isQualified()
                        + " vlastníKotva=" + sig.isTrustedViaCustomStore()
                        + " právníTyp=" + sig.getLegalType()
                        + " kotvaZaPoslednímRazítkem="
                        + lastTimestampTrusted.contains(sig.getSignatureId())
                        + " ústupky=" + sig.getDecisiveSettings()
                        + " expiRespondér=" + sig.getExpiredResponderDetail()
                        + " razítka=" + sig.getAllTimestamps()
                        + " překážka=" + continuityObstacle(sig, lastTimestampTrusted));
            }
        }

        // Přerazítkování — the offer for a document heading to a validator that judges certificates
        // by their validity period rather than by ETSI's rules about what revocation data proves.
        //
        // It stands in the preservation button's place and never beside it. Where uchování is
        // possible it is the better artefact by every measure — a conformant validator accepts it,
        // and so does a tolerant one — so offering both would ask the user to choose between an
        // action and a weaker version of the same action. Fixation is different and is offered
        // alongside: it produces a container for a person to read, not a file for a program.
        //
        // Four conditions, and each of them is the target validator's own test rather than a
        // caution of ours:
        //
        //  • the verdict is "valid" — this does not repair a signature, it carries one forward;
        //  • situation 31, the time-stamp chain is unbroken;
        //  • situation 32, the last time-stamp's own certificate is still valid, so a stamp added
        //    today extends a chain that is still whole. Where 32 fails the gap is already in the
        //    past and nothing added now bridges it — those readers would reject the result anyway,
        //    which is why preservation insists on 32 outside expert mode for the same reason;
        //  • coverage is complete, strictly (33) or on the looser reading (45).
        //
        // Verification with concessions is deliberately neither required nor forbidden. The four
        // tolerances in Relaxation are precisely what these validators grant, so a signature that
        // needed one is the typical case — but the core case needs none: a clean verdict where the
        // only revocation to be had was published after the certificate expired. Both belong here.
        //
        // One thing is excluded: a verdict reached at a validation time of the user's choosing.
        // That is not a tolerance the reader grants but a fact about this run — the verdict does
        // not say the signature is valid today, and re-stamping it would produce an artefact whose
        // premise is false.
        //
        // Trust drawn from the application's own certificate store is not excluded, and was
        // briefly. The argument for excluding it — the target validator does not have the pinned
        // root — proves less than it appears to. That store ships pre-filled with the roots of
        // Czech commercial certification authorities, which are not obscure to a filing room; they
        // are outside the EU Trusted Lists because they are not qualified, not because nobody
        // recognises them. Whether a given reader has a given root is a question about that reader,
        // and answering it here by withholding the offer would decide it for them. Uchování has
        // never made that judgement either — situation 34 does not test the store, whatever its
        // javadoc says — and the same document should not be treated as sound enough to preserve
        // but too local to re-stamp. Where trust did come from the store the reader is told:
        // the signature carries the anchor's name and Rozšířený přehled names it in the verdict.
        // Situace 32 se žádá schválně, a stálo to dvě otočky.
        //
        // Napřed to vypadalo, že podpis s prošlým certifikátem posledního razítka o přerazítkování
        // přichází neprávem (PAdES T-level kvalifikovany.pdf, 2019, vložená OCSP odpověď od
        // expirovaného respondéra). Jenže nové razítko by vzniklo až po expiraci toho dosavadního
        // (26. 3. 2024), takže validátor posuzující razítka podle platnosti certifikátů — přesně
        // ten, pro kterého přerazítkování je — uvidí v řadě mezeru, kterou už nic nezacelí.
        // A přísný validátor tu OCSP odpověď neuzná dál; čerstvá nevznikne, vydavatel skončil.
        // Ani jeden tábor tedy úkonem nezíská nic a nabízet ho by byl planý slib.
        // Verdikt, nebo nález, že uspěje tolerantní validátor — táž dvojice jako v bráně, a musí
        // to tak být: prvek, který branou prošel, by jinak skončil v reachable, žádnou ze tří
        // cest by nezapnul a dokument by zůstal bez nabídky, ačkoli text u prvku tvrdí, že úkon
        // dosáhne. Ostatní tři podmínky se nemění; ty se ptají na dokument, ne na nastavení.
        //
        // Uchování a prodloužení se nerozšiřují schválně. Obě se dál ptají na kladný verdikt,
        // takže dokument, kde je vedle bezvadného podpisu i takovýhle, propadne na uchování
        // a spadne sem — dostane slabší výsledek, a to je správně: silnější by o jednom z jeho
        // podpisů tvrdil víc, než co o něm platí.
        boolean restampWorthy = !(settings != null && settings.isUseCustomValidationTime())
                && reachable.stream().anyMatch(this::restampReaches);

        // PDF only, PDF-NOT-ETSI included — the exclusion in canPreservePdf is there because
        // uchování cannot supply the signed attributes such a signature never carried, and this
        // action makes no claim about attributes. The other formats are out for a harder reason:
        // rebuilding their unsigned attributes needs DSS, and DSS applies exactly the filtering
        // this action exists to avoid.
        boolean canRestamp = !brokenIntegrity
                && !preservedByRecord
                && tsaConfigured
                && sourceFile != null
                && !canPreserve
                && restampWorthy
                && restampingService != null
                && timestampingService != null
                && !asicContainer
                && allSigs.stream().anyMatch(sig -> isPdfSignature(sig.getFormat()));


        // Prodloužení — for a signature that meets every condition preservation asks for except
        // situation 32: the last time-stamp's own certificate has expired.
        //
        // By the yardstick CzechPOINT and the filing rooms apply, such a chain has a hole and
        // nothing closes it; that is why preservation stands aside. By the standard it may still
        // be perfectly valid, and often is — the time-stamping unit's certificate is itself listed
        // in the Trusted List, so it is a trust anchor, and a trust anchor is not judged by its own
        // expiry but by the sunset of the service behind it. While no sunset has come, past
        // validation accepts the time-stamp and the signature passes.
        //
        // What a fresh time-stamp buys there is the difference between "valid today because the
        // sunset has not come" and "valid afterwards too": it creates a proof that the whole state
        // existed while the service still had its positive status, and that proof outlives the
        // status. It is the remedy situation 43 recommends, taken before 43 has anything to report.
        //
        // Which documents: those whose chain is broken by that yardstick — situation 31 or 32
        // missing — while the standards still pass.
        //
        // Keying on the absence of 32 alone was wrong, and one round of use showed it. Prolonging a
        // document produces exactly the state that keying misses: the new time-stamp restores 32
        // (its certificate runs to 2034) and destroys 31, because it was made after the previous
        // certificate had expired. The result — measured on
        // AnEP\test prodlouzeni\2020 11 11 ASiCe vlastni CRL — went from [31, 33, 34] to
        // [32, 33, 34], TOTAL_PASSED both times, and then qualified for nothing at all: preservation
        // and re-stamping want 31, prolongation wanted 32 missing. A document could be prolonged
        // once and never again, which is the opposite of continuity.
        //
        // So the test is on the pair. Both present is preservation's case and this stands aside;
        // either missing is this one. Nothing else changes: the verdict, coverage (33 or 45), no
        // relaxations (34), and situation 43 absent — with 43 the sunset has already happened, past
        // validation can no longer reach a proof of existence predating it, and a time-stamp added
        // now would produce a document neither reading accepts. There the honest offer is fixation,
        // and withholding this one is what leaves room for it.
        //
        // The work is preservation's too — runPreservation() does the right thing already; only the
        // name of the result and what the user is told about it differ.
        //
        // The trust anchor is asked for only where the last certificate has expired, which is where
        // the standard's leniency is doing the work: an anchor is not judged by its own expiry, the
        // Trusted List vouches for the service and only the sunset ends that. Without an anchor an
        // expired time-stamp certificate is simply expired — past validation would want revocation
        // data for it, everything obtainable today is post-expiry and gets refused, and the renewed
        // document would validate no better than the original (measured on
        // B 2019_10_23_ICA_do_2025_09_26.asice, where a renewal added chain certificates and not one
        // CRL or OCSP). Where 32 holds, the certificate is still valid on its own terms and needs no
        // such indulgence, so demanding an anchor there would refuse a second prolongation for a
        // reason that does not apply to it.
        //
        // Read from the diagnostic data rather than from the summary: TimestampEntry carries the
        // certificate's expiry but not whether it is an anchor, and adding that would push a field
        // through the model for one gate to read.
        boolean prolongWorthy = !(settings != null && settings.isUseCustomValidationTime())
                && reachable.stream().anyMatch(sig -> prolongReaches(sig, lastTimestampTrusted));

        boolean canProlong = !brokenIntegrity
                && !preservedByRecord
                && tsaConfigured
                && sourceFile != null
                && !canPreserve
                && !canRestamp
                && prolongWorthy
                // The same two paths preservation has, on the same terms. The check that the
                // signing certificate is still good is not repeated: situation 31 requires a
                // time-stamp, so nothing here is unanchored and that condition cannot bite.
                //
                // A file carrying nothing but time-stamps qualifies for the PDF path exactly as it
                // does for preservation, and for the same reason: its summaries say
                // "TimeStampToken", so the format test cannot see them. Prolongation is the action
                // such a document needs most — the certificate of its only time-stamp has expired,
                // which is what withheld preservation and left this gate to answer.
                //
                // Nereferenční formát (PDF-NOT-ETSI) se tu na rozdíl od uchování nevylučuje. To
                // vyloučení má u uchování svůj důvod — nedokáže dodat podepsané atributy, které
                // takový podpis nikdy nenesl — jenže prodloužení nic o atributech netvrdí, stejně
                // jako přerazítkování, které pro nereferenční formáty přímo vzniklo. Zděděné
                // omezení tu tedy nechávalo propadnout dokument, který spadl mezi všechny tři
                // brány: nereferenční podpis s prošlým certifikátem posledního razítka, kterému
                // uchování brání formát, přerazítkování chybějící situace 32 a prodloužení právě
                // tenhle test. Nalezeno skenem složky „jiz uchovane" (387 dokumentů) jako jediný
                // skutečný nález — B_A_kvalifikovany_ICA_neref_podpis_2019_03_20_do_2025_11_26.pdf,
                // situace [31, 33, 34], dvě razítka. Materiál se vkládá do slovníku /DSS, což je
                // konstrukce na úrovni PDF a o SubFilter nic neví.
                && ((augmentationService != null && timestampingService != null && !asicContainer
                        && (timestampOnlyFile || allSigs.stream().anyMatch(
                                sig -> isPdfSignature(sig.getFormat()))))
                    || (extensionService != null && !signedDataMissing
                        && SignatureExtensionService.handles(
                                lastValidationResult, signatureFile())));
        boolean canContinue = canPreserve || canRestamp || canProlong;
        continuityMode = canPreserve ? ContinuityMode.PRESERVE
                : canRestamp ? ContinuityMode.RESTAMP
                : canProlong ? ContinuityMode.PROLONG : null;
        preserveButton.setVisible(canContinue);
        preserveButton.setManaged(canContinue);
        // Jméno úkonu na tlačítko. Text se mění s dokumentem, protože varianta je vlastnost
        // dokumentu; při zavřené bráně se nemění na nic — tlačítko je stejně skryté a poslední
        // jméno na něm nikomu nelže, protože ho nikdo nevidí.
        if (continuityMode != null) preserveButton.setText(continuityMode.verb);

        // The bare time-stamp is no longer offered at all, and preservation covers what it was for.
        //
        // It only ever stood where nothing was anchored yet — the step from B to T — because
        // anywhere else a lone time-stamp leaves the previous one's certificate without revocation
        // data and produces an archive form that will not hold. At B it was the honest offer only
        // while preservation could not start there. It can: runPreservation() stamps, embeds the
        // validation data that the stamp has just made meaningful, and stamps again, reaching LTA
        // in one action. Leaving both meant offering the user a choice between a finished job and
        // the first third of it, described in words that do not make the difference visible.
        //
        // What is lost by removing it is a step the user can no longer take deliberately: stopping
        // at T. Nobody asked for that, and a document left at T is a document that will need this
        // application again before its certificate expires.

        // Pokrylo uchování celý dokument? Ne „nabízí se", nýbrž „dosáhne na každý prvek" —
        // s bránami na „aspoň jeden" je to od 8. 9. 2026 rozdíl, a je to přesně ten rozdíl,
        // kvůli kterému má vedle uchování stát i forenzní balíček.
        boolean preservationCoversAll = canPreserve && gateSigs.stream()
                .allMatch(sig -> preservationReachable.contains(sig) && preservationReaches(sig));

        // Fixation asks for almost nothing, on purpose. It does not touch the document, so broken
        // integrity is no obstacle — a document whose content was altered is exactly the kind an
        // expert may need frozen with its evidence — and an evidence record inside is safe too,
        // which is why this is offered where preservation is refused. Only a TSA is required,
        // since the whole point is the time-stamp over the container.
        boolean canFix = fixationService != null
                && settings != null
                && settings.getSelectedTsa() != null
                && sourceFile != null
                // Outside expert mode it stands in for preservation rather than beside it. Where
                // the document can be preserved, that is the better artefact by a wide margin: a
                // program verifies it, whereas a container has to be opened and read by a person.
                // Two similarly named actions side by side would cost more confusion than the
                // container adds. In expert mode both are offered, because there the container's
                // own contribution is worth having even then — the provenance of every item, and
                // the material preservation deliberately discards as unusable.
                // Re-stamping is not in this test, so fixation accompanies it. There the two do
                // not compete: the re-stamped document is for the filing room's validator, the
                // container for whoever later has to examine what the verdict rested on, and the
                // material only one reading of the rules accepts is exactly what makes that
                // examination worth having.
                //
                // Od 8. 9. 2026 nestačí, že se uchování nabízí — musí <b>pokrýt všechny prvky</b>.
                // Brány od té doby pouštějí dokument, na jehož část úkon nedosáhne, a právě tam má
                // kontejner samostatnou cenu: zachytí dnešní stav prvku, kterému uchování nepomůže
                // a kterého se po zásahu nikdo nedobere. Pravidlo je od uživatele.
                && (settings.isExpertMode() || !preservationCoversAll);
        fixButton.setVisible(canFix);
        fixButton.setManaged(canFix);

        // Mirror ResultView: in non-standards TS mode flag sigs whose last TSA cert expired
        Set<String> tsExpiredSigIds = new HashSet<>();
        if (!timestampStandardsMode) {
            DiagnosticData dd = result.getDiagnosticData();
            if (dd != null) {
                for (eu.europa.esig.dss.diagnostic.SignatureWrapper sw : dd.getSignatures()) {
                    List<TimestampWrapper> allTs = sw.getTimestampList().stream()
                            .filter(ts -> ts.getProductionTime() != null)
                            .sorted(Comparator.comparing(TimestampWrapper::getProductionTime))
                            .collect(Collectors.toList());
                    if (!allTs.isEmpty()) {
                        TimestampWrapper last = allTs.get(allTs.size() - 1);
                        if (hasExpiredCertInTsChain(last, dd, now))
                            tsExpiredSigIds.add(sw.getId());
                    }
                }
            }
        }

        List<SignatureSummary> sigs = result.getSignatures();
        boolean showHeaders = sigs.size() > 1;
        int itemCounter = 0;
        for (int i = 0; i < sigs.size(); i++) {
            SignatureSummary sig = sigs.get(i);
            String header = showHeaders ? "Autentizační prvek č.: " + (++itemCounter) : null;
            cardsBox.getChildren().add(buildCard(sig, header, tsExpiredSigIds));
            if (i < sigs.size() - 1) {
                Separator sep = new Separator();
                sep.setPadding(new Insets(10, 0, 10, 0));
                cardsBox.getChildren().add(sep);
            }
        }

        addContinuityBlock(sigs, lastTimestampTrusted, tsExpiredSigIds, canPreserve, canProlong,
                canRestamp, canFix);
    }

    /**
     * „Digitální kontinuita" — co se s dokumentem dá udělat, aby zůstal ověřitelný, a do kdy.
     *
     * <p>Blok pro celý dokument, ne pro jednotlivý podpis: tlačítka platí pro soubor jako celek,
     * takže opakovat tentýž text u každé karty by čtenáři podsouvalo, že jde pokaždé o něco
     * jiného. Právě proto se odsud vykresluje do {@link ContinuityView} a ne mezi karty — Analýza
     * mluví po podpisech, tahle věc po dokumentu.
     *
     * <p>Datum, do kdy se ověřitelnost protáhne, je konec platnosti certifikátu vybrané autority
     * časových razítek. Aplikace si ho pamatuje od chvíle, kdy se zdroj nastavoval, právě proto,
     * aby se dalo napsat sem — zjistit ho jinak než pořízením razítka nelze, a čtenář se má
     * rozhodovat předem, ne až podle výsledku.
     */
    private void addContinuityBlock(List<SignatureSummary> sigs,
                                    java.util.Set<String> lastTimestampTrusted,
                                    java.util.Set<String> tsExpiredSigIds,
                                    boolean canPreserve, boolean canProlong, boolean canRestamp,
                                    boolean canFix) {
        boolean anyAction = canPreserve || canProlong || canRestamp || canFix;
        if (continuityView == null) return;
        // Soubor bez podpisů, kterému se nic nenabízí, nemá o čem mluvit: úvodní odstavec o tom,
        // že podpis nevydrží navěky, by se vztahoval k podpisům, které tam nejsou. Jinak se blok
        // staví vždycky — i když se nenabízí nic. Právě tehdy má čtenář nejvíc důvodů se ptát proč,
        // a prázdná záložka mu na to neodpoví.
        if (!anyAction && sigs.isEmpty()) {
            continuityView.setBlock(null);
            return;
        }

        TsaProfile tsa = settings != null ? settings.getSelectedTsa() : null;
        java.util.Date until = tsa != null ? tsa.getCertNotAfter() : null;

        VBox block = new VBox(8);
        block.setPadding(new Insets(8, 12, 10, 12));

        // Úvodní odstavec „Elektronický podpis nevydrží navěky“ tu stál, dokud záložka neměla
        // části. Teď totéž — a lépe — říká výklad pojmu v první části, který si drží sama
        // ContinuityView; opakovat to sem znamená říct čtenáři dvakrát jinými slovy totéž.
        ContinuityElementBlocks.Gates gates =
                new ContinuityElementBlocks.Gates(canPreserve, canProlong, canRestamp, canFix);
        // Jeden záznam místo jména a data zvlášť. Kromě kvalifikovanosti, kvůli které vznikl,
        // tím padla i past: „je zdroj vybrán?" se v blocích poznávalo podle toho, zda má jméno —
        // jenže jméno je nepovinné pole a zdroj bez názvu vycházel jako nevybraný.
        // S politikou ověřování, aby blok o dosahu úkonu poznal slabý klíč autority
        // (5. 10. 2026); bez ní se o klíči mlčí jako dřív.
        eu.europa.esig.dss.model.policy.ValidationPolicy keyPolicy = null;
        try {
            if (settings != null) {
                keyPolicy = cz.digitalnikontinuita.anep.core.ValidationPolicyFactory.build(settings);
            }
        } catch (Exception e) {
            keyPolicy = null;
        }
        ContinuityElementBlocks.Source source = ContinuityElementBlocks.Source.of(tsa, keyPolicy);
        java.util.List<ContinuityElementBlocks.Element> elements = addElementBlocks(block,
                sigs, lastTimestampTrusted, tsExpiredSigIds, gates, source);
        lastElements = elements;

        addCombinationBlock(block, sigs, elements, gates, source);
        java.time.Instant docUntil = documentVerifiableUntil(sigs);

        // Popis akce tu stál z doby, kdy druhá část neexistovala a blok musel čtenáři sám
        // říct, co se stane. Dnes to říká blok „AnEP nabízí možnost…“
        // u každého prvku,
        // konkrétněji a s daty. Zbývá jediný případ, kdy by to nikdo neřekl: soubor, ve kterém
        // aplikace nenašla žádný autentizační prvek, a přesto se z něj dá vyrobit balíček.
        if (canFix && sigs.isEmpty()) {
            block.getChildren().add(continuityAction("Vytvořit forenzní balíček", true,
                    "Vznikne samostatný balíček: dokument, podklady k jeho ověření a soupis, "
                    + "co je uvnitř a odkud to pochází, to celé pod jedním časovým razítkem. "
                    + "Ověřitelnost samotného dokumentu to neprodlouží — zachová se tím, co se "
                    + "o něm ví dnes, aby to šlo doložit i později.",
                    until, docUntil));
        }

        // Totéž podruhé: obojí říká blok „Jak dlouho je možné zachovat ověřitelnost?“ u každého
        // prvku. Zůstává to jen pro soubor bez autentizačních prvků, kde ten blok není.
        if (sigs.isEmpty()) {
            if (tsa == null) {
                block.getChildren().add(continuityNote(
                        "Žádný zdroj časových razítek zatím není vybrán. Bez něj nelze razítko "
                        + "pořídit; vybrat se dá v nastavení."));
            } else if (until == null) {
                block.getChildren().add(continuityNote(
                        "U zdroje „" + tsa.getName() + "“ zatím není známo, do kdy platí "
                        + "certifikát jeho autority, takže se nedá říct, kam až by se ověřitelnost "
                        + "posunula. Zjistí se to při nejbližším razítku."));
            }
            // Soubor bez autentizačních prvků nemá bloky, ve kterých tahle výhrada jinak stojí,
            // a balíček se z něj přesto nabízí — pod razítkem, o kterém se ví, že kvalifikované
            // není. Bez téhle věty by to byl jediný úkon aplikace, u kterého se to nedozví.
            if (source.unqualified()) {
                block.getChildren().add(continuityNote(
                        "Vybraný zdroj časových razítek („" + tsa.getName() + "“) vydává "
                        + "razítka, která nejsou kvalifikovaná — aplikace k němu v evropských "
                        + "důvěryhodných seznamech kvalifikovanou službu nenašla. Balíček se "
                        + "s ním vytvořit dá, ale razítko na něm nebude tím, co se za "
                        + "kvalifikované časové razítko uznává."));
            }
        }
        // Věta „s tímhle dokumentem se nedá udělat nic" tu stávala a je pryč: od 9. 9. 2026
        // ji u dokumentu s několika prvky říká blok „Co AnEP nabízí" ve společné části, a to
        // rovnou i s důvodem u každého prvku. Tady by stála mimo bloky a podruhé.

        continuityView.setBlock(block);
    }

    /**
     * Druhá část záložky: prvek po prvku, každý s vlastní sadou rozbalovacích bloků.
     *
     * <p>Proč vůbec: razítko se pořizuje <b>jednou pro celý soubor</b> — {@code ByteRange} razítka
     * dokumentu kryje podle ETSI EN 319 142-1 celý dokument — kdežto úrovně jsou vlastností
     * <b>jednotlivých podpisů</b> (EN 319 142-1 mluví o „a PAdES-B-LTA signature“ a slovník
     * {@code /DSS} smí nést materiál „for some or all signatures“). Jeden úkon, různé užitky.
     *
     * <p>Část se staví i pro dokument s jediným prvkem. Chybějící výčet u jednoho prvku byl
     * naopak důvod, proč čtenář u tří nálezů ze skenu složky „k uchovani“ viděl platný verdikt,
     * žádnou nabídku a žádné vysvětlení.
     *
     * <p><b>Jméno prvku je nadpis, ne další rozbalovací obal.</b> Bloky pod ním nesou barevná
     * záhlaví, která sdělují výsledek; vnořit je do dalšího šedého záhlaví by znamenalo schovat
     * to sdělení za druhé kliknutí a postavit dvě hierarchie záhlaví na sebe.
     *
     * <p>Co který blok říká a proč je jich právě tolik, drží {@link ContinuityElementBlocks};
     * odsud se předává jen to, co se počítá pro celý dokument — brány, překážka prvku a zdroj
     * časových razítek.
     */
    private java.util.List<ContinuityElementBlocks.Element> addElementBlocks(
            VBox block, List<SignatureSummary> sigs,
            java.util.Set<String> lastTimestampTrusted,
            java.util.Set<String> tsExpiredSigIds,
            ContinuityElementBlocks.Gates gates, ContinuityElementBlocks.Source tsa) {
        boolean many = sigs.size() > 1;
        ContinuityHorizon.Run run = continuityRun();
        java.util.List<ContinuityElementBlocks.Element> elements = new java.util.ArrayList<>();
        for (SignatureSummary sig : sigs) {
            elements.add(ContinuityElementBlocks.element(sig,
                    cardVerdict(sig, tsExpiredSigIds), run,
                    tsExpiredSigIds.contains(sig.getSignatureId()),
                    offeredActionReaches(sig, gates, lastTimestampTrusted),
                    continuityObstacle(sig, lastTimestampTrusted)));
        }
        // Razítko, které kryje pozdější razítko téhož dokumentu, nese jeho horizont — viz
        // ContinuityElementBlocks.coveredBy. Počítá se tu, protože jen tady je vidět celý výčet.
        elements = coverTimestamps(sigs, elements);

        VBox parts = new VBox(10);
        for (int i = 0; i < sigs.size(); i++) {
            SignatureSummary sig = sigs.get(i);
            String noun = gendered(sig, "podpis", "pečeť", "časové razítko");
            String title = many
                    ? "Autentizační prvek č. " + (i + 1) + " — " + noun
                    : Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
            Label heading = new Label(title);
            heading.setWrapText(true);
            heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;"
                    + " -fx-text-fill: #111111; -fx-padding: 6 0 2 0;");

            VBox part = new VBox(2, heading);
            // Fakta v rozbalovacím bloku, zavřeném. Nejdřív stála pod jménem prvku holá, aby
            // čísla, ze kterých všechno ostatní plyne, měl čtenář před sebou — v aplikaci se ale
            // ukázalo, že čtyři odstavce nad blokem s odpověďmi působí nepřehledně a že je
            // nepotřebuje každý. Rozhodnutí uživatele.
            VBox facts = new VBox(4);
            facts.setPadding(new Insets(0, 12, 8, 12));
            for (String line : ContinuityFacts.paragraphs(sig)) {
                facts.getChildren().add(continuityText(line));
            }
            String lostQualification = lostQualificationNote(sig);
            if (lostQualification != null) facts.getChildren().add(continuityText(lostQualification));
            part.getChildren().add(collapsibleBlock(
                    "Rozbor " + gendered(sig, "podpisu", "pečeti", "časového razítka"),
                    facts, false, BlockTint.PLAIN));
            ContinuityElementBlocks.Element el = elements.get(i);
            // Poslední dva bloky patří k prvku jen u dokumentu s jediným prvkem; při souběhu
            // stojí jednou ve společné části pod výčtem — úkon je dokumentový a tlačítko
            // jedno, takže u každého prvku by to bylo totéž tolikrát, kolik je prvků.
            part.getChildren().add(ContinuityElementBlocks.build(el, gates, tsa,
                    // Odkazy do výkladu umí otevřít jen samotná záložka. Není-li zapojená
                    // (sondy, snímky jednotlivých karet), vysází se odstavce bez odkazů.
                    continuityView == null ? null : continuityView::open, !many,
                    conformantPdfaProfile(), sourceFile));
            parts.getChildren().add(part);
        }

        block.getChildren().add(parts);
        return elements;
    }

    /**
     * Vazby „razítko kryje razítko“ z diagnostických dat DSS a přenos horizontů po nich — viz
     * {@link ContinuityElementBlocks#coveredBy}. Pozdější dokumentové razítko uvádí v
     * {@code getTimestampedTimestamps()} všechna razítka, která jeho {@code ByteRange} kryje.
     */
    private java.util.List<ContinuityElementBlocks.Element> coverTimestamps(
            List<SignatureSummary> sigs, java.util.List<ContinuityElementBlocks.Element> elements) {
        return coverTimestamps(sigs, elements, lastValidationResult != null
                ? lastValidationResult.getDiagnosticData() : null);
    }

    static java.util.List<ContinuityElementBlocks.Element> coverTimestamps(
            List<SignatureSummary> sigs, java.util.List<ContinuityElementBlocks.Element> elements,
            DiagnosticData dd) {
        if (dd == null || sigs.size() < 2) return elements;
        java.util.Map<String, Integer> index = new java.util.HashMap<>();
        java.util.List<java.time.Instant> made = new java.util.ArrayList<>();
        java.util.List<eu.europa.esig.dss.diagnostic.TimestampWrapper> tokens =
                new java.util.ArrayList<>();
        for (int i = 0; i < sigs.size(); i++) {
            SignatureSummary sig = sigs.get(i);
            var tw = ContinuityHorizon.isTimestampElement(sig) && sig.getSignatureId() != null
                    ? dd.getTimestampById(sig.getSignatureId()) : null;
            tokens.add(tw);
            made.add(tw != null && tw.getProductionTime() != null
                    ? tw.getProductionTime().toInstant() : null);
            if (tw != null) index.put(tw.getId(), i);
        }
        java.util.Map<Integer, java.util.List<Integer>> covers = new java.util.HashMap<>();
        for (int j = 0; j < tokens.size(); j++) {
            var tw = tokens.get(j);
            if (tw == null) continue;
            for (var covered : tw.getTimestampedTimestamps()) {
                Integer i = index.get(covered.getId());
                if (i != null && i != j) covers.computeIfAbsent(i, k -> new java.util.ArrayList<>()).add(j);
            }
        }
        if (covers.isEmpty()) return elements;
        return ContinuityElementBlocks.coveredBy(elements, covers, made);
    }

    /** Stavy služby, které se počítají za kvalifikované — táž množina jako u situace 43. */
    private static final java.util.Set<String> QUALIFIED_STATUSES = java.util.Set.of(
            "granted", "recognisedatnationallevel", "undersupervision", "accredited",
            "supervisionincessation");

    /**
     * Razítko vzniklo jako kvalifikované, a přesto se za kvalifikované nepovažuje.
     *
     * <p>DSS posuzuje kvalifikovanost razítka k okamžiku, k němuž je jeho existence doložena
     * (nejnižší důkaz existence), ne k času, který razítko samo uvádí. Doloží-li ji až pozdější
     * razítko, když už služba kvalifikovaný status neměla, vyjde razítko nekvalifikované
     * ({@code QUAL_HAS_GRANTED_AT_ANS}). Nad dokumentem se čtyřmi razítky PostSignum TSU 2
     * (nález uživatele 25. 9. 2026) tak vyšla dvě razítka z února 2014 na témž certifikátu
     * jednou kvalifikovaná a jednou ne; holé „není kvalifikované“ to nevysvětlovalo.
     */
    private String lostQualificationNote(SignatureSummary sig) {
        if (!ContinuityHorizon.isTimestampElement(sig) || sig.isQualified()) return null;
        DiagnosticData dd = lastValidationResult != null
                ? lastValidationResult.getDiagnosticData() : null;
        if (dd == null || sig.getSignatureId() == null) return null;
        var tw = dd.getTimestampById(sig.getSignatureId());
        if (tw == null || tw.getProductionTime() == null || tw.getSigningCertificate() == null) {
            return null;
        }
        java.util.Date made = tw.getProductionTime();
        boolean qualifiedThen = false;
        for (var tsp : tw.getSigningCertificate().getTrustServiceProviders()) {
            for (var svc : tsp.getTrustServices()) {
                String type = uriTail(svc.getServiceType());
                if (!"QTST".equalsIgnoreCase(type) && !"TSA/QTST".equalsIgnoreCase(type)) continue;
                boolean positive = QUALIFIED_STATUSES.contains(
                        uriTail(svc.getStatus()).toLowerCase(Locale.ROOT));
                boolean covers = (svc.getStartDate() == null || !made.before(svc.getStartDate()))
                        && (svc.getEndDate() == null || made.before(svc.getEndDate()));
                if (positive && covers) qualifiedThen = true;
            }
        }
        if (!qualifiedThen) return null;
        return "Razítko vzniklo jako kvalifikované: služba, která je vydala, byla tehdy vedena"
                + " v důvěryhodných seznamech s kvalifikovaným statusem. Kvalifikovanost se ale"
                + " podle standardů posuzuje k okamžiku, k němuž je existence razítka doložena,"
                + " a ten tu dokládá až pozdější razítko — v době, kdy už služba kvalifikovaný"
                + " status neměla. Proto se razítko hodnotí jako nekvalifikované.";
    }

    private static String uriTail(String uri) {
        if (uri == null) return "";
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }

    /**
     * Nastavení, za kterého ověření proběhlo, ve tvaru, ve kterém se dá popsat čtenáři.
     *
     * <p>Čte se při stavbě bloků, tedy dřív, než {@code MainView} politiku resetuje — platí vždy
     * jen na jedno ověření. Kdyby se sáhlo později, popisoval by blok výchozí nastavení místo
     * toho, podle kterého verdikt vznikl.
     */
    /** Přidá blok, není-li {@code null}. Bloky o dokumentu se staví jen tam, kde je co říct. */
    private static void addIfPresent(java.util.List<VBox> into, VBox block) {
        if (block != null) into.add(block);
    }

    private ContinuityHorizon.Run continuityRun() {
        java.util.List<String> lenient = settings != null
                ? ReportMapper.Relaxation.enabledIn(settings).stream()
                        .map(ReportMapper.Relaxation::settingName).toList()
                : java.util.List.of();
        java.time.Instant custom = customValidationTime != null
                ? customValidationTime.atZone(java.time.ZoneId.systemDefault()).toInstant()
                : null;
        return new ContinuityHorizon.Run(timestampStandardsMode, lenient, custom);
    }

    /**
     * Společná část záložky: dokument jako celek. Jen tam, kde je prvků víc.
     *
     * <p>U jediného prvku není co shrnovat — všechno, co by tu stálo, je v jeho vlastní části,
     * a věta o dokumentu by tam říkala totéž jinými slovy, nebo, hůř, opak: pod „platnost už
     * ověřit nelze“ stálo „lhůta uplynula, tím spíš je na místě ji posunout“ (potkáno na
     * LT signed podpisuj.pdf).
     *
     * <p><b>Co tu od 9. 9. 2026 stojí.</b> Dva bloky, které dosud visely u každého prvku zvlášť
     * — „Jak dlouho je možné zachovat ověřitelnost?“ a „AnEP nabízí možnost…“ — a popis souběhu,
     * který dosud tvořil blok vlastní. Staví je {@link ContinuityElementBlocks#documentBlocks};
     * odsud se předává jen popis souběhu, protože stojí na zdejším výpočtu horizontu dokumentu.
     * Pokyn uživatele: úkon je dokumentový, takže nabídka patří k dokumentu, ne k prvku.
     *
     * <p>Tři věci, které z jednotlivých částí nevyplynou:
     *
     * <p><b>Že prvky tvoří řadu.</b> Vznikaly postupně a každý další podepsal i to, co v souboru
     * už bylo. Čtenář, který přečte pět částí pod sebou, vidí pět nezávislých tvrzení — a podle
     * toho by taky čekal, že se dá zasáhnout do každého zvlášť.
     *
     * <p><b>Že úkon je jeden.</b> Razítko kryje celý soubor ({@code ByteRange} razítka dokumentu
     * podle ETSI EN 319 142-1), takže se pořizuje jednou pro všechny prvky. Že přitom jedněm
     * prospěje a druhým ne, není závada, kterou by bylo potřeba omlouvat — ETSI TS 119 511 mezi
     * možnými postoji uchovávací služby výslovně uvádí „preserve all the information it can
     * collect“ — ale musí to být řečeno, ne zamlčeno.
     *
     * <p><b>Dokdy vydrží celek.</b> Podle posledního článku řady, viz
     * {@link #documentVerifiableUntil}.
     */
    private void addCombinationBlock(VBox block, List<SignatureSummary> sigs,
                                     java.util.List<ContinuityElementBlocks.Element> elements,
                                     ContinuityElementBlocks.Gates gates,
                                     ContinuityElementBlocks.Source tsa) {
        if (sigs.size() < 2) return;
        java.time.Instant docUntil = documentVerifiableUntil(sigs);
        boolean anyOffer = gates.preserve() || gates.prolong() || gates.restamp();

        // Dosah <b>zvolené varianty</b>, ne pouhá dosažitelnost. Do 8. 9. 2026 stačilo, že prvek
        // nemá překážku — což platilo, dokud brány žádaly, aby úkon vyšel všem dosažitelným.
        // S pravidlem „stačí jeden prvek" se ty dvě otázky rozešly: dokument projde branou
        // uchování kvůli jedinému podpisu a ostatní dosažitelné mohou vycházet na něco jiného
        // nebo na nic. Kdyby se tady dál počítala dosažitelnost, souhrn by tvrdil „prospěje všem"
        // nad dokumentem, kde úkon dosáhne na jeden z pěti prvků.
        java.util.List<Integer> reached = new java.util.ArrayList<>();
        java.util.List<Integer> missed = new java.util.ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).actionReaches()) reached.add(i + 1);
            else missed.add(i + 1);
        }

        java.util.List<javafx.scene.Node> combination = new java.util.ArrayList<>();
        combination.add(continuityText("Dokument nese " + sigs.size() + " "
                + (sigs.size() < 5 ? "autentizační prvky" : "autentizačních prvků")
                + ", které vznikaly postupně, jeden po druhém: každý další podepsal i to, co "
                + "v dokumentu už bylo, tedy včetně prvků před ním. Nejsou to nezávislá tvrzení "
                + "vedle sebe, ale řada, v níž pozdější prvek zastřešuje ty dřívější."));

        // Věta o jediném úkonu má smysl jen tam, kde se nějaký nabízí. Dokud stál souběh ve
        // vlastním bloku, psala se vždycky a nad dokumentem bez nabídky vysvětlovala, komu
        // nepomůže úkon, který neexistuje.
        if (anyOffer) {
            String once = "Úkon se přesto provádí jednou pro celý soubor — nové časové razítko "
                    + "kryje celý dokument, takže je nelze přidat jen k některému prvku. ";
            if (missed.isEmpty()) {
                combination.add(continuityText(once + "Tady prospěje všem."));
            } else if (reached.isEmpty()) {
                combination.add(continuityNote(once + "Žádnému z prvků ale nepomůže — proč, "
                        + "stojí u každého z nich níže."));
            } else {
                combination.add(continuityText(once + "Prospěje "
                        + dativeElements(reached) + ". " + capitalize(dativeElements(missed))
                        + " nepomůže, ale ani neuškodí: co je na nich dnes sporné, zůstane "
                        + "sporné i potom."));
            }
        }

        if (docUntil != null) {
            String when = shortDate(java.util.Date.from(docUntil));
            combination.add(docUntil.isAfter(java.time.Instant.now())
                    ? continuityText("Dokument jako celek je v současné podobě ověřitelný do "
                            + when + horizonExplanation(sigs))
                    // Minulé datum se nesmí říkat přítomným časem. Že dokument dnes přesto
                    // obstojí, na věci nic nemění — lhůta uplynula a další razítko je tím
                    // naléhavější, ne méně na místě.
                    : continuityNote("Doba, po kterou byl dokument jako celek ověřitelný bez "
                            + "dalšího zásahu, uplynula " + when + ". Tím spíš je na místě lhůtu "
                            + "posunout."));
        }

        // Předěl mezi výčtem prvků a společnou částí. Od chvíle, kdy prvky nesou vlastní nadpis
        // a pod ním několik bloků, by společná část bez něj visela pod posledním prvkem a četla
        // by se jako jeho další blok — přesně to zdánlivé připoutání k poslednímu podpisu, kvůli
        // kterému tahle záložka vznikla. Nadpis nad ní je z téhož důvodu: prvky se jmenují
        // „Autentizační prvek č. N“, a co platí o souboru, musí být pojmenováno stejně nahlas.
        Separator partSep = new Separator();
        partSep.setPadding(new Insets(12, 0, 6, 0));
        block.getChildren().add(partSep);
        Label heading = new Label("Dokument jako celek");
        heading.setWrapText(true);
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;"
                + " -fx-text-fill: #111111; -fx-padding: 6 0 2 0;");
        VBox part = new VBox(2, heading);
        part.getChildren().add(ContinuityElementBlocks.documentBlocks(elements, gates,
                combination, tsa, conformantPdfaProfile(),
                continuityView == null ? null : continuityView::open, sourceFile));
        block.getChildren().add(part);
    }

    /**
     * Jméno profilu PDF/A, ke kterému se dokument hlásí <b>a kterému vyhovuje</b> („PDF/A-2b"),
     * jinak {@code null}.
     *
     * <p>Obojí musí platit. U dokumentu, který se k PDF/A jen hlásí a nedostál tomu, nemá úkon
     * co zachovat a věta o archivní podobě by mu přisuzovala vlastnost, kterou nemá.
     */
    private String conformantPdfaProfile() {
        if (lastValidationResult == null) return null;
        var c = lastValidationResult.getPdfConformance();
        return c != null && !c.inconclusive() && c.compliant()
                ? PdfAConformanceBlock.name(c.flavour()) : null;
    }

    /** „prvku č. 2“, „prvkům č. 1, 4 a 5“ — tvar, který se vejde doprostřed věty. */
    private static String dativeElements(java.util.List<Integer> nums) {
        StringBuilder sb = new StringBuilder(nums.size() == 1 ? "prvku č. " : "prvkům č. ");
        for (int i = 0; i < nums.size(); i++) {
            if (i > 0) sb.append(i == nums.size() - 1 ? " a " : ", ");
            sb.append(nums.get(i));
        }
        return sb.toString();
    }

    /** První písmeno velké — věty, které začínají jménem certifikátu nebo prvku. */
    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Opak {@link #capitalize} — pro větu, která se z prvního místa posunula za středník. */
    static String uncapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Prvky, jejichž poslední razítko vydala kotva důvěry — klíčované id podpisu.
     *
     * <p>Otázka se klade jen tam, kde certifikát posledního razítka skončil (chybí situace 32).
     * Tam totiž pracuje shovívavost normy: kotva se neposuzuje podle vlastní expirace, ručí za ni
     * důvěryhodný seznam a končí až sunsetem služby. Bez kotvy je expirovaný certifikát razítka
     * prostě expirovaný — cokoli se dnes stáhne, je po expiraci a odmítne se, a obnovený dokument
     * by dopadl stejně jako původní (změřeno na B 2019_10_23_ICA_do_2025_09_26.asice, kde obnova
     * přidala certifikáty řetězu a ani jednu CRL či OCSP).
     *
     * <p>Čte se z diagnostiky, ne ze souhrnu: {@code TimestampEntry} nese konec platnosti
     * certifikátu, ale ne to, jestli je kotvou, a protlačovat kvůli jedné bráně další pole skrz
     * model se nevyplatí.
     *
     * <p>Soubor bez podpisu se ptá na totéž a má jednu odpověď pro celek: „poslední razítko" je to
     * nejnovější v souboru a každá karta v něm se ptá na ně. Množina výše je klíčovaná podpisem
     * a zůstala by prázdná, což prodloužení znemožnilo přesně těm dokumentům, pro které vzniklo.
     */
    private static java.util.Set<String> lastTimestampTrusted(
            eu.europa.esig.dss.diagnostic.DiagnosticData dd, boolean timestampOnlyFile) {
        java.util.Set<String> trusted = new HashSet<>();
        if (dd == null) return trusted;
        for (eu.europa.esig.dss.diagnostic.SignatureWrapper sw : dd.getSignatures()) {
            TimestampWrapper newest = newestTimestamp(sw.getTimestampList());
            if (newest == null) continue;
            eu.europa.esig.dss.diagnostic.CertificateWrapper tsu = newest.getSigningCertificate();
            if (tsu != null && tsu.isTrusted()) trusted.add(sw.getId());
        }
        if (timestampOnlyFile) {
            TimestampWrapper newest = newestTimestamp(dd.getTimestampList());
            eu.europa.esig.dss.diagnostic.CertificateWrapper tsu =
                    newest != null ? newest.getSigningCertificate() : null;
            if (tsu != null && tsu.isTrusted()) {
                for (TimestampWrapper ts : dd.getTimestampList()) trusted.add(ts.getId());
            }
        }
        return trusted;
    }

    private static TimestampWrapper newestTimestamp(List<TimestampWrapper> list) {
        TimestampWrapper newest = null;
        for (TimestampWrapper ts : list) {
            if (ts.getProductionTime() == null) continue;
            if (newest == null || ts.getProductionTime().after(newest.getProductionTime())) {
                newest = ts;
            }
        }
        return newest;
    }

    /**
     * Souběh dvou faktorů, kvůli kterému podpis ověří jen nástroj, který obojí kombinuje.
     *
     * <p><b>Faktor jedna:</b> certifikát autority posledního razítka skončil (chybí situace 32),
     * a razítko přesto platí — za službu ručí důvěryhodný seznam až do sunsetu. Tak to čte ten,
     * kdo se drží norem.
     *
     * <p><b>Faktor dva:</b> verdikt stojí na ústupku vůči zdrojům revokačních dat (chybí 34
     * a mezi rozhodujícími ústupky jsou právě jen ty revokační). Tak to čte ten, kdo si vypomáhá
     * daty, která normy nepřipouštějí.
     *
     * <p>Každý faktor sám o sobě je běžný; jenže tábor, který uzná první, druhý odmítá, a naopak.
     * Dohromady je nemá nikdo — kromě aplikace, která obojí kombinuje. Měřeno na
     * PAdES T-level kvalifikovany.pdf a na všech pěti podpisech souboru pet podpisu LT.pdf.
     *
     * <p>Ostatní ústupky sem nepatří. Kdyby verdikt nesla třeba tolerance k SHA-1, text o dvou
     * faktorech by ten dokument nepopisoval — a nabídka postavená na textu, který neplatí, je
     * horší než žádná. Proto se ptáme na <i>všechny</i> rozhodující ústupky, ne jen na jeden.
     */
    private static boolean toleratedRevocationWithExpiredTsu(SignatureSummary sig) {
        List<Integer> found = sig.getDetectedSituations();
        if (found.contains(34) || found.contains(32)) return false;
        // Vlastní kotva důvěry je třetí faktor, o kterém text o dvou faktorech nemluví — a mluvit
        // by musel, protože mění závěr: takový podpis neuzná jiný nástroj ani tehdy, když obě
        // čtení zkombinuje, dokud témuž certifikátu sám nezačne věřit. Do getDecisiveSettings()
        // se přitom nepromítá (jsou tam jen tolerance), takže test níž ji sám nezachytí.
        //
        // Potkáno na podpisech č. 4 a 5 souboru pet podpisu LT.pdf: „pouze zaručené“ podpisy
        // s vlastní kotvou, kterým se text o dvou faktorech vysadil úplně stejně jako podpisům
        // 1–3, u nichž platí doopravdy.
        if (sig.isTrustedViaCustomStore()) return false;
        List<String> decisive = sig.getDecisiveSettings();
        if (decisive.isEmpty()) return false;
        return decisive.stream().allMatch(name ->
                ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName().equals(name)
                || ReportMapper.Relaxation.NEWER_OCSP.settingName().equals(name));
    }

    /**
     * Umí to dnes ověřit ještě někdo — a posune mu to nové razítko?
     *
     * <p>Druhá cesta prvku s nekladným verdiktem do nabídky, vedle {@link
     * #tolerantReaderMaySucceed}. Ta první se ptá na <b>revokační mezeru</b>: chybí údaj, který
     * má cizí ověřovatel ve své zásobě. Tahle se ptá na něco jiného — na <b>měřítko</b>. Zdejší
     * ověření posuzuje časová razítka podle sunsetu, okruh CzechPOINTu podle řádné doby platnosti
     * jejich certifikátů, a tam, kde první čtení skončilo neznámou platností a druhé má lhůtu
     * ještě před sebou, je nové razítko to jediné, co tu lhůtu posune.
     *
     * <p><b>Bere se hotový horizont, ne vlastní podmínka.</b> {@code ContinuityHorizon.tolerant()}
     * je táž funkce, ze které se sází blok „Jak dlouho dokáží ověřit platnost CzechPOINT,
     * podatelny a další validátory?“, takže tlačítko nemůže tvrdit něco jiného než datum nad ním.
     * Vlastní kopie podmínky by se s ním rozešla — přesně to se už jednou stalo a stálo to
     * {@link #shouldOfferOtherValidators} jednu opravu.
     *
     * <p><b>Uplynulá lhůta neprojde.</b> Horizont {@code UNTIL} s datem v minulosti znamená, že
     * ani tenhle okruh už platnost neověří; nové razítko by pak neposouvalo nic.
     *
     * <h2>Proč nestačí sám horizont</h2>
     *
     * <p>{@code tolerant()} neumí crypto, formát ani důvěru: vrací {@code UNTIL} i tam, kde zdejší
     * ověření skončilo na algoritmu, na nereferenčním formátu nebo na nedůvěryhodném řetězu.
     * O těch AnEP <b>neví</b>, jak je posoudí cizí nástroj, a otevřít jim nabídku by znamenalo
     * slíbit něco, co predikát neunese. Změřeno na vzorku 489 dokumentů ze sbírky
     * {@code bajecny svet}: bez druhé podmínky přibylo pět nabídek, a ani jedna z nich nebyla
     * z důvodu, o který tu jde ({@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE} u SHA-3-224,
     * {@code SIG_CONSTRAINTS_FAILURE} u nereferenčního formátu, třikrát
     * {@code CERTIFICATE_CHAIN_GENERAL_FAILURE}). U tří z nich navíc obcházela pojistku
     * z {@link #tolerantReaderMaySucceed} („úkon musí mít co uchovat“), která tam přibyla
     * po měření.
     *
     * <p>Druhá podmínka proto pojmenovává přesně tu příčinu, o kterou jde: <b>některé razítko je
     * neporušené, a přesto neobstálo</b>. To je celý rozdíl mezi oběma čteními — zdejší se ptá na
     * status služby v důvěryhodných seznamech, ten druhý na řádnou dobu platnosti certifikátu —
     * a nové razítko je jediné, co s ním pro ten druhý okruh něco udělá.
     */
    static boolean tolerantReadingStillWorks(SignatureSummary sig) {
        if (!ContinuityHorizon.timestampJudgedDifferently(sig)) return false;
        ContinuityHorizon.Horizon h = ContinuityHorizon.tolerant(sig, displayedIndication(sig));
        return h.kind() == ContinuityHorizon.Kind.UNTIL
                && h.when() != null
                && h.when().isAfter(java.time.Instant.now());
    }

    /**
     * Smí se prvek s nekladným verdiktem dostat do nabídky? Tedy: uspěl by tam, kde zdejší
     * ověřování ne — a je vůbec co uchovávat?
     *
     * <p>První půlka je {@link #shouldOfferOtherValidators}, čistá funkce nálezu, kterou už čte
     * blok „Kde je šance na úspěšné ověření". Sdílet ji je záměr: tlačítko a text si nesmějí
     * o témž dokumentu tvrdit každý něco jiného.
     *
     * <p>Druhá půlka je <b>„úkon musí mít co uchovat"</b> a přibyla po měření. Predikát sám
     * pouští i prvek, o jehož certifikátu aplikace nedrží <b>vůbec žádný</b> revokační údaj —
     * pro blok správně, protože ověřovatel s vlastní zásobou k závěru dojít může, kdežto pro
     * nabídku ne: uchování by nevložilo nic a nové razítko by zapečetilo stav „nevíme“. Naměřeno
     * na {@code po revokaci\PAdES T po revokaci 2020 10 15.pdf}, dokumentu z úmyslné sbírky
     * (podepsáno po odvolání certifikátu), kde DSS hlásí {@code BBB_XCV_IRDPFC_ANS: No revocation
     * data found for the certificate!} — situace 20 ani 51 tam proto nemají z čeho vzniknout
     * a bez téhle podmínky se nabídka rozsvítila. Ze sbírky D:\tmp je to jediný takový případ ze
     * všech osmi, které pravidlo do nabídky přidalo.
     *
     * <p>Ptá se to na obě pole {@link ValidationCourse} — na využitý údaj i na ten, který se
     * využít nedal. Jen na první se ptát nelze a stálo to jeden běh přes sbírku: prvky, kterým je
     * tohle pravidlo určeno, mají svůj údaj právě mezi nepoužitelnými, takže podmínka vzala
     * nabídku všem osmi místo jednomu.
     */
    static boolean tolerantReaderMaySucceed(SignatureSummary sig) {
        if (!shouldOfferOtherValidators(sig)) return false;
        ValidationCourse vc = sig.getValidationCourse();
        if (vc == null) return false;
        // Obě pole, a to je oprava z měření. Ptát se jen na využitý údaj byla chyba: prvky, kterým
        // je tohle pravidlo určeno, jsou právě ty, jejichž údaj přísné čtení odmítlo — ten proto
        // neleží ve „využitém", nýbrž v „nepoužitelném". Podmínka tak vzala nabídku všem osmi
        // dokumentům místo tomu jedinému, o který šlo. Otázka zní „drží aplikace o tom certifikátu
        // vůbec něco?", ne „opřel se o to závěr?".
        return vc.signingCertRevocation() != null
                || vc.signingCertRevocationUnusableFact() != null;
    }

    /**
     * Proč na tenhle prvek nové razítko nedosáhne, nebo {@code null}, když dosáhne.
     *
     * <p>Jedna otázka místo tří bran. Uchování, prodloužení a přerazítkování se liší jménem
     * a materiálem, který do dokumentu vloží, ale ptají se na totéž: je co zachovávat a dá se to
     * zapečetit? Popisovat tady zvlášť, na kterou ze tří akcí prvek dosáhne, by čtenáři podsouvalo
     * rozhodnutí, které nemá — razítko je jedno a kryje všechno.
     *
     * <p>Pořadí důvodů je pořadí závažnosti: porušený obsah, pak neplatný verdikt, pak
     * evidenční záznam nad prvkem, pak vyřazená služba, pak chybějící pokrytí a nakonec stav
     * řetězu razítek. První, který platí, je ten, který se čtenáři řekne — vypsat všechny by
     * z popisu udělalo protokol.
     *
     * <p>Evidenční záznam stojí takhle vysoko proto, že je to <b>zákaz, ne nedostatek</b>:
     * důvody pod ním popisují, co prvku chybí, a tedy co by mu razítko dodalo — jenže nad
     * prvkem krytým záznamem se razítko přidat nesmí, takže by to byly rady, které nelze
     * uposlechnout.
     *
     * <p><b>Je to zároveň brána.</b> Vrátit {@code null} znamená, že prvek splní podmínky aspoň
     * jedné ze tří akcí, a {@link #show} podle toho vybírá prvky, kterých se rozhodování týká.
     * Text a nabídka tak nemohou tvrdit každý něco jiného; dvojí odpověď na tutéž otázku by byla
     * horší než obojí zvlášť.
     */
    // Balíčkově viditelná kvůli ContinuityObstacleTest: brána a text jsou tu jedno a totéž,
    // takže se to musí dát měřit přímo, ne oklikou přes vykreslené okno.
    static String continuityObstacle(SignatureSummary sig,
                                             java.util.Set<String> lastTimestampTrusted) {
        List<Integer> found = sig.getDetectedSituations();
        if (found.contains(1)) {
            return "obsah dokumentu neodpovídá tomu, co bylo podepsáno, a razítko by stvrdilo "
                    + "právě tenhle stav";
        }
        if (!"TOTAL_PASSED".equals(displayedIndication(sig)) && !tolerantReaderMaySucceed(sig)
                && !tolerantReadingStillWorks(sig)) {
            // Dva zcela různé případy pod jednou větou. Prvek se situací 45 má pokrytí úplné až na
            // volnější čtení, tedy mu k průchodu chybí jediné přepnutí v nastavení — kdežto prvek
            // bez ní neobstojí za žádných okolností. Slévat je do „ověření neskončilo platným
            // výsledkem" znamenalo zamlčet čtenáři, že u dvou ze tří podpisů stačí sáhnout do
            // nastavení (potkáno na tri podpisy.pdf: certifikáty vypršely a jediné dostupné OCSP
            // je po expiraci, takže neprůkazné — což je přesně to, co tolerance promíjí).
            // Otisk, který standardy nepřipouštějí (situace 56, SHA3-224), přebíjí větu o ústupku
            // níž: ta by slibovala, že volnější nastavení pomůže, a ono nepomůže. Nález uživatele
            // 4. 10. 2026 — u PAdES T se SHA3-224 nastávají 45 i 56 naráz.
            if (found.contains(56)) {
                return "stojí na hashovací funkci " + sig.getDigestAlgorithm() + ", kterou"
                        + " standardy pro elektronické podpisy nepřipouštějí, a žádné nastavení"
                        + " to nezmění";
            }
            return found.contains(45)
                    ? "podle norem neobstál, protože doklady o revokaci pocházejí až z doby po "
                      + "konci platnosti certifikátu; při volnějším způsobu ověřování, který se "
                      + "dá zapnout v nastavení, by na něj razítko dosáhlo"
                    : "ověření neskončilo platným výsledkem, takže není co zapečetit";
        }
        // …a tady je ta výjimka z předchozího odstavce, kvůli které se celá brána měnila.
        //
        // <p>Verdikt je funkcí <b>zapnutých ústupků</b>, ne dokumentu. Týž soubor tedy nabídku
        // dostal, nebo nedostal, podle zaškrtávátek v nastavení — a co s ním nové razítko udělá,
        // je přitom v obou případech totéž. Věta o kus výš to sama přiznávala: říkala čtenáři,
        // ať si v nastavení zapne volnější ověřování, a teprve pak že úkon dosáhne. Ústupky
        // přitom platí jen na jedno ověření (AppSettings.resetValidationPolicy), takže ta rada
        // znamenala: otevřít nastavení, povolit ústupek, ověřit znovu, teprve pak zmáčknout
        // tlačítko. To zvládne jen ten, kdo odpověď zná předem.
        //
        // <p><b>Podmínkou je nález, ne druhý verdikt.</b> {@link #shouldOfferOtherValidators} je
        // čistá funkce nálezu a ptá se přesně na to, oč tu jde: není indikováno nic beznadějného,
        // není pozdě (certifikát razítka ještě platí) a překážkou je právě revokační údaj. Táž otázka
        // stojí za blokem „Kde je šance na úspěšné ověření", takže si tlačítko a text nemohou
        // protiřečit. Predikát má obě strany ověřené zvenčí, na Úschovně Czech POINT: ze 16
        // vypovídajících dokumentů sedl u 14 — u 12 ze 13, kterým předpovídal neúspěch, a u 2
        // ze 3, kterým předpovídal úspěch.
        //
        // <p><b>Prvek tudy neprojde jen tak.</b> Kaskáda pod tímhle řádkem platí pro něj beze
        // změny: evidenční záznam, vyřazená služba (43), pokrytí revokacemi, stav řetězu razítek
        // i požadavek m) ho odmítnou stejně jako prvek s kladným verdiktem. A hlavně situace 20
        // a 51 nad tímhle řádkem — právě ty drží venku podvrhy, kterým {@code
        // shouldOfferOtherValidators} kladnou odpověď dává (změřeno na
        // „LT po revokaci later LTA timestamped.pdf").
        //
        // <p><b>Kam prvek dojde.</b> Úkon pro něj není uchování — to slibuje samonosný podpis
        // ověřitelný i validátory nad DSS, a to by u prvku, který přísné čtení odmítá, byla
        // nepravda. Vezme ho vnitřní cesta přerazítkování, jejíž text slibuje právě tolik, kolik
        // je: „prodlouží právě tu možnost ověření, která existuje dnes — ne širší". Tlačítko
        // zůstává jedno; která ze tří cest se spustí, rozhoduje brána, ne uživatel.
        // Evidenční záznam nad prvkem. Stojí hned za verdiktem a před vším ostatním, protože je
        // to zákaz, ne nedostatek: normy nad uchovaným podpisem dovolují jen přidat další záznam
        // nebo obnovit stávající (ETSI TS 119 132-3, TS 119 122-3), a u kontejneru ASiC je
        // augmentace uchovaného podpisu zakázaná rovnou. Kterákoli z podmínek pod tímhle řádkem
        // by proto vedla k radě, kterou nelze uposlechnout — chybějící revokační údaje se u prvku
        // krytého záznamem doplnit nesmějí, i kdyby byly po ruce.
        //
        // Nabídku tří úkonů stahuje preservedByRecord() v show() už dnes, ale mlčky. Brána tak
        // rozhodovala jedno a text neříkal nic; javadoc téhle funkce přitom žádá, aby odpovídaly
        // na tutéž otázku stejně.
        //
        // Dvě úrovně sdělení, protože jsou to dvě různě vážné věci. Každý záznam znamená, že
        // razítko navíc nepřidáme. Situace 41 k tomu přidává, že tenhle konkrétní podpis nenese
        // vlastní revokační údaje — záznam prokazuje existenci, nikdy stav revokace — takže až
        // skončí platnost jeho certifikátu, zůstane doloženo, kdy podpis existoval, a nic
        // nedoloží, že tehdy nebyl odvolán. To je zjištění, které se čtenář musí dozvědět teď:
        // později už s ním nepůjde udělat nic, protože augmentace na LT by přepsala právě ty
        // bajty, které kryje hašový strom záznamu.
        if (!sig.getEvidenceRecords().isEmpty()) {
            String base = "kryje jej evidenční záznam a další razítko by přepsalo právě ty bajty, "
                    + "které záznam stvrzuje; nad uchovaným podpisem normy dovolují jen přidat "
                    + "další záznam nebo obnovit stávající, což aplikace neumí";
            return found.contains(41)
                    ? base + ". Navíc sám nenese vložené revokační informace a záznam je nenahradí — "
                      + "prokazuje existenci, nikoli stav odvolání. Po skončení platnosti jeho "
                      + "certifikátu tak zůstane doloženo, kdy podpis vznikl, a nic nedoloží, "
                      + "že tehdy nebyl odvolán"
                    : base;
        }
        // Vyřazená služba je překážka tam, kde už úkon nemá co prodloužit — nové razítko opravdu
        // zpětně nevyrobí důkaz, že stav existoval, dokud služba kvalifikovaný status měla.
        // Jenže to je odpověď pro toho, kdo razítka posuzuje podle sunsetu. Okruh nástrojů se
        // „striktním" hodnocením se na status služby neptá vůbec a platnost ověřit dnes ještě
        // umí; nové razítko tu možnost posune, a přesně to je přerazítkování.
        //
        // Naměřeno na „LTA_kvalifikovany_2012_01_20_razitko_2016_do_2021_preserved_2020-11-14":
        // zdejší ověření skončilo neznámou platností (razítko z roku 2016 neprošlo kvůli
        // sunsetu), kdežto CzechPOINT a spol. platnost ověří do 23. 10. 2026. Dokument, kde má
        // přerazítkování největší cenu, dostával jako jediný úkon forenzní balíček.
        //
        // Uchování ani prodloužení tím neprojdou: obě žádají kladný verdikt, který situace 43
        // snižuje, a prodloužení navíc 43 vylučuje vlastní podmínkou.
        if (found.contains(43) && !tolerantReadingStillWorks(sig)) {
            return "služba, která vydala jeho poslední razítko, je vyřazená ze seznamu "
                    + "důvěryhodných, a doklad o dřívějším stavu už nevznikne";
        }
        // Situace 20 — kladný verdikt, a přitom aplikace <b>drží doklad</b>, že certifikát byl
        // v době podpisu už odvolaný: stažená OCSP odpověď nebo CRL, kterou přísné čtení
        // nepřipouští, protože vznikla až po expiraci certifikátu. Verdikt nese vložený údaj
        // z doby před odvoláním.
        //
        // Stojí před situací 51 schválně, ačkoli obě mluví o témž nebezpečí: 51 říká „o okamžiku
        // podpisu nic nevíme", kdežto 20 říká „víme, a je to špatné". Čtenáři, který se rozhoduje,
        // jestli dokument zapečetit, patří to silnější sdělení.
        //
        // Nabídku to bere i tam, kde k odvolání došlo z nevinného důvodu a podpis vznikl dřív —
        // jenže právě to je ta otázka, kterou zdejší ověřování nedokáže rozhodnout, a razítko
        // by ji zmrazilo v podobě, která tvrdí „platný".
        //
        // Potkáno na „po revokaci\LT AdES QC  po revokaci.pdf" (situace [20, 31, 32, 33, 34]),
        // dokumentu z úmyslné sbírky ukazující, že Adobe Acrobat Reader podvrh neodhalí.
        // Uchování se mu do 2. 9. 2026 nabízelo.
        if (found.contains(20)) {
            return "aplikace získala revokační údaj, podle kterého byl jeho certifikát v době "
                    + "podpisu už odvolaný; kladný výsledek nese jen starší údaj vydaný před "
                    + "odvoláním, a razítko by tenhle rozpor zapečetilo";
        }

        // Situace 51 tu stávala jako překážka a brala nabídku každému prvku, jehož nejnovější
        // revokační údaj je starší než on sám. Odstraněna 4. 9. 2026, protože ta cena byla
        // nesouměřitelná s užitkem:
        //
        //  • Dopadala na **každý** čerstvě podepsaný dokument. Ten má nejnovější údaj starší než
        //    sebe vždycky, takže se hned po podpisu nedalo zachovat nic; teprve až respondér
        //    vydal novější odpověď, nabídka se objevila. Nikdo to nemohl uhodnout.
        //  • Dopadala na poctivé dokumenty, kde údaj okamžik vzniku **pokrývá** — CA vydávají
        //    CRL po dávkách a odstup hodin je běžný provoz. Hlášeno nad pečetí
        //    z roku 2019 (CRL vydaná 1 h 40 min před zapečetěním, platná
        //    ještě celý další den). EN 319 102-1 V1.4.1 klauzule 5.2.5.4 krok 1 takový údaj
        //    přijímá; zdejší brzda byla přísnější než norma.
        //  • Změřeno na D:	mp: z 28 prvků se situací 51 je 25 INDETERMINATE (ty zastaví verdikt
        //    sám) a 2 kladné — obě kopie téhož úmyslného podvrhu. Jediný zásah, a proti němu
        //    všechny poctivé dokumenty s dávkovanou CRL.
        //
        // Co zůstává: situace 20, o dva bloky výš. Ta je **nález** — aplikace má revokační údaj,
        // podle kterého byl certifikát odvolán ještě před vznikem prvku — kdežto 51 byla jen
        // domněnka z odstupu. Podvrh, u kterého se odvolání dohledat dá, tedy neprojde dál.
        // Podvrh, u kterého se dohledat nedá („T 2020 10 15 po revokaci Signer s revinfo.pdf",
        // kde CA už nic nezveřejňuje), nabídku dostane; od poctivého dokumentu ho zdejší
        // ověřování nerozezná a odepírat úkon všem kvůli jednomu je horší volba. Výhrada
        // zůstává jako poznámka k výsledku, jen nezavírá dveře.
        boolean covered = found.contains(33) || found.contains(45)
                || preservationAddsSomething(sig);
        if (!covered) {
            return "chybí revokační informace, které už nelze získat";
        }
        // Stav řetězu razítek. Souvislý a živý řetěz (31 i 32) stačí sám o sobě — to je případ,
        // který dnes obslouží přerazítkování, a to se na toleranci neptá, protože právě pro
        // dokumenty ověřené s ústupkem vzniklo. Přerušený řetěz je případ prodloužení a ten klade
        // dvě podmínky: verdikt bez ústupků (34) a kotvu důvěry za posledním razítkem, jinak by
        // nové razítko stavělo na certifikátu, o kterém už se nic průkazného nedozvíme.
        // Podpis, který nekryje žádné razítko (úroveň B), se na stav řetězu neptá — žádný nemá.
        // Situace 31 i 32 popisují řetěz důkazů existence: 31 že nemá mezeru, 32 že jeho poslední
        // článek jde ještě posoudit. Kde řetěz není, nemůže padnout ani jedna, a testy pod tímhle
        // řádkem by z jejich nepřítomnosti vyvodily, že „certifikát posledního razítka skončil",
        // a žádaly kotvu důvěry, ve které takový podpis nemůže být nikdy.
        //
        // Přesně tahle regrese už jednou byla — viz preservationSituationsMet a její noChainYet —
        // a vrátila se sem s jednotnou bránou. Potkána na PAdES B-B kvalifikovaný PS 4096.pdf,
        // kterému se nabízelo jen zafixování. Zachovat ověřitelnost od úrovně B je přitom to,
        // kvůli čemu runPreservation() dělá dvě kola: razítko, pak validační data, pak archivní
        // razítko. Že podpisový certifikát musí být v té chvíli ještě platný (EN 319 122-1
        // požadavek m), hlídá signingCertsStillValid v show(), ne tenhle test.
        // Podpis, který nic neukotvuje, se dá zapečetit jen dokud žije jeho podpisový certifikát.
        // EN 319 122-1 V1.3.1 čl. 6.3 požadavek m): razítko „shall be created before the signing
        // certificate has been revoked or has expired"; EN 319 102-1 V1.4.1 čl. 4.3.3.4 pozn. 2 to
        // opakuje. Razítko pořízené později podpis neukotví k době, kdy byl certifikát v pořádku —
        // zaznamená jen, že podpis existoval, když už bylo pozdě.
        //
        // Test byl dosud jen v show() jako signingCertsStillValid, tedy mimo tuhle funkci — a tím
        // mimo text. Sken složky „k uchovani" (372 dokumentů) našel tři takové: verdikt platný,
        // překážka null, a přesto se nabídlo jen zafixování. Záložka k tomu neřekla nic a věta
        // „důvod stojí u jednotlivých podpisů v Analýze" tam nebyla pravdivá, protože v kartách
        // ten důvod není. Brána a text musí odpovídat na tutéž otázku stejně; jinak je výčet prvků
        // k ničemu právě tam, kde ho čtenář potřebuje.
        // Jen pro podpisy. Požadavek m) mluví o podpisu, který se má ukotvit razítkem, kdežto
        // prvek typu TimeStampToken je sám razítko: getCertNotAfter() je u něj konec platnosti
        // certifikátu razítkovací autority a „podpisový certifikát" tam žádný není. Bez téhle
        // podmínky se test vztáhl i na soubory nesoucí jen razítka, vyprázdnil reachable a sebral
        // nabídku 16 dokumentům z korpusu (rodina priklad 1_2 a podpis2011_uchovano2014, tedy
        // dokumenty zabalené v PDF s razítky). O obnově řetězu razítek rozhoduje tooLateToRenew.
        boolean anchored = sig.getBestSignatureTime() != null;
        boolean isTimestampElement = "TimeStampToken".equals(sig.getFormat());
        if (!isTimestampElement && !anchored
                && (sig.isCertRevoked() || sig.getCertNotAfter() == null
                || !sig.getCertNotAfter().isAfter(java.time.Instant.now()))) {
            return sig.isCertRevoked()
                    ? elementCert(sig, Case.NOMINATIV) + " byl revokován a "
                      + gendered(sig, "podpis nekryje", "pečeť nekryje", "razítko nekryje")
                      + " žádné razítko, takže nové razítko už "
                      + gendered(sig, "ho", "ji", "ho")
                      + " nemůže ukotvit k době, kdy byl certifikát v pořádku"
                    : elementCert(sig, Case.DATIV) + " skončila platnost a "
                      + gendered(sig, "podpis nekryje", "pečeť nekryje", "razítko nekryje")
                      + " žádné razítko, takže nové razítko už "
                      + gendered(sig, "ho", "ji", "ho")
                      + " nemůže ukotvit k době, kdy byl certifikát v pořádku";
        }

        // Nemá řetěz = nic ho nekotví. Ptá se to na totéž co požadavek m) o dva odstavce výš,
        // a to schválně: dokud se tenhle test ptal na „nemá vůbec žádné razítko"
        // (getAllTimestamps().isEmpty()), rozcházel se s ním u podpisu, který razítko má, ale
        // takové, které ho nekotví — razítko VRI nebo obsahové. Takový podpis propadl na testy
        // stavu řetězu a rozhodly by o něm situace 31 a 32 spočítané z razítka, které v otázce
        // kontinuity neznamená nic; test na kotvu důvěry by se ptal na jeho autoritu.
        //
        // Potkáno na priklad16.pdf_verify (3).pdf. Tam to nevadilo, protože ho stejně vyřadil
        // požadavek m), ale s dosud platným certifikátem by o něm rozhodl VRI. Správná cesta pro
        // něj je tatáž jako pro úroveň B: dvě razítka, jak je runPreservation() umí.
        if (!anchored) return null;

        boolean chainSoundByCertValidity = found.contains(31) && found.contains(32);
        if (chainSoundByCertValidity) return null;
        // Verdikt na ústupku sám o sobě odmítnutí není. Kde ústupek stojí na revokačních datech
        // a certifikát posledního razítka skončil, je to popsaný souběh dvou faktorů — a tam má
        // úkon co dělat: nové razítko od služby, která status má dnes, vytvoří důkaz, že celý ten
        // stav existoval, dokud ho měla i ta stará. Ten důkaz přežije sunset, kterým jinak zmizí
        // jediná cesta, kudy se dnes k platnému verdiktu dojde.
        //
        // Ověřitelnost to nikam nevrátí — mezera v řadě razítek podle platnosti certifikátů je
        // trvalá a validátoru z okruhu CzechPOINTu dokument nevrátí. Udrží ale to, co dnes platí.
        if (!found.contains(34) && !toleratedRevocationWithExpiredTsu(sig)) {
            return "jeho verdikt stojí na volnějším výkladu, než jaký normy předepisují, a řetěz "
                    + "razítek je zároveň přerušený";
        }
        boolean anchorNeeded = !found.contains(32);
        if (anchorNeeded && !lastTimestampTrusted.contains(sig.getSignatureId())) {
            return "certifikát autority jeho posledního razítka skončil a služba za ním není "
                    + "kotvou důvěry, takže nové razítko nemá na čem stavět";
        }
        return null;
    }

    /**
     * Čím to datum je, když prvků je víc — dovětek k větě o dokumentu.
     *
     * <p>Musí říkat totéž co pravidlo, které to datum spočítalo. Napsat u řetězu razítek „podle
     * prvku, který skončí nejdřív" bylo nepravdivé přesně naopak: tam rozhoduje ten poslední,
     * a čtenář, který si to ověří pohledem na výčet nad tím, uvidí, že to nesedí.
     */
    private static String horizonExplanation(List<SignatureSummary> sigs) {
        if (sigs.size() < 2) return ".";
        boolean anyRealSignature = sigs.stream()
                .anyMatch(s -> !"TimeStampToken".equals(s.getFormat()));
        return anyRealSignature
                ? " — podle prvku, který řadu uzavírá a zastřešuje tím ty před sebou."
                : " — podle posledního razítka v řadě, které zastřešuje ta předchozí.";
    }

    /**
     * Dokdy je ověřitelný dokument jako celek — podle posledního článku řady.
     *
     * <p><b>Prvky v dokumentu vznikají za sebou, ne vedle sebe.</b> Druhý podpis podepisuje i to,
     * co v souboru už bylo, tedy včetně prvního; razítko přidané potom kryje celý soubor a ukotví
     * tím všechno před sebou. Řada se proto měří svým posledním článkem, ne prvním — ten už za
     * sebou má někoho, kdo za něj ručí.
     *
     * <p><b>Dřív se brala nejdřívější hodnota</b> — nejslabší článek, s odkazem na EN 319 162-1
     * („the level of an ASiC baseline container shall be the lowest level of the incorporated
     * baseline signature(s)"). To pravidlo mluví o <i>úrovni</i> kontejneru s několika nezávislými
     * podpisy, kdežto tady jde o <i>dobu</i> a o prvky, které na sebe navazují. Minimum tam měřilo
     * stáří toho nejstaršího a vydávalo ho za konec platnosti celku.
     *
     * <p><b>Počítají se jen prvky, které obstály.</b> Konec ověřitelnosti se počítá bez ohledu na
     * verdikt, takže neplatnému podpisu vyjde stejně jako platnému — a maximum by si pro horizont
     * celku vzalo právě jeho, kdyby náhodou sahal nejdál. Slibovat dobu platnosti, která
     * neexistuje, je horší než neslíbit nic; když neobstál žádný prvek, vrací se {@code null}
     * a věta o dokumentu se prostě nenapíše.
     *
     * <p>Razítka se do výpočtu neberou tam, kde v souboru podpisy jsou: konec ověřitelnosti
     * podpisu už je z razítek, která ho kryjí, spočítaný (viz
     * {@code ReportMapper.computeVerifiableUntil}), takže brát je podruhé zvlášť znamená pustit
     * do výsledku vnitřek řetězu.
     */
    private static java.time.Instant documentVerifiableUntil(List<SignatureSummary> sigs) {
        List<SignatureSummary> real = sigs.stream()
                .filter(s -> !"TimeStampToken".equals(s.getFormat()))
                .collect(Collectors.toList());
        return latestVerifiableUntil(real.isEmpty() ? sigs : real);
    }

    private static java.time.Instant latestVerifiableUntil(List<SignatureSummary> sigs) {
        java.time.Instant latest = null;
        for (SignatureSummary s : sigs) {
            if (!"TOTAL_PASSED".equals(displayedIndication(s))) continue;
            java.time.Instant u = s.getVerifiableUntil();
            if (u == null) continue;
            if (latest == null || u.isAfter(latest)) latest = u;
        }
        return latest;
    }

    static Label continuityText(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
        return l;
    }

    static Label continuityNote(String text) {
        Label l = continuityText(text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: #7a4a00;");
        return l;
    }

    /**
     * Jedna nabízená akce: co udělá a do kdy posune ověřitelnost.
     *
     * <p>Datum je u zafixování jiné povahy než u ostatních tří — neposouvá ověřitelnost
     * dokumentu, jen říká, dokdy vydrží samotný balíček — takže se u něj i jinak uvozuje.
     *
     * <p><b>Posunout se nemusí.</b> Nové razítko dosáhne jen tak daleko, kam sahá platnost
     * certifikátu vybrané autority, a ta může skončit dřív než dosavadní ověřitelnost
     * dokumentu; potkáno na dokumentu platném do roku 2034 se zdrojem, jehož certifikát končí
     * 2029. Slíbit tam posun by bylo nepravdivé a čtenář by podle toho udělal krok, který mu
     * uškodí — proto se místo příslibu řekne, jak to je, a proč se vyplatí vybrat jiný zdroj.
     */
    private static VBox continuityAction(String name, boolean fixation, String what,
                                         java.util.Date tsaUntil, java.time.Instant docUntil) {
        Label title = new Label(name);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #111111;");

        VBox box = new VBox(2, title, continuityText(what));
        if (tsaUntil != null) {
            // Totéž pravidlo jako v záložce Digitální kontinuita, a schválně společné: dvě místa,
            // která odpovídají na tutéž otázku, se nesmějí rozejít. Ostře nerovné porovnání tu
            // pouštělo jako „posun“ i 48 minut.
            boolean noGain = !fixation && docUntil != null
                    && !ContinuityHorizon.worthTheAction(docUntil, tsaUntil.toInstant());
            Label u = new Label(fixation
                    ? "Balíček by byl ověřitelný do " + shortDate(tsaUntil) + "."
                    : noGain
                        ? "Ověřitelnost by se prakticky neposunula: certifikát vybraného zdroje "
                          + "časových razítek platí jen do " + shortDate(tsaUntil)
                          + ", tedy sotva déle než dosavadní "
                          + shortDate(java.util.Date.from(docUntil))
                          + ". Vyplatí se vybrat zdroj, jehož certifikát platí déle."
                        : "Ověřitelnost by se posunula do " + shortDate(tsaUntil) + ".");
            u.setWrapText(true);
            u.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
                    + (noGain ? "#7a4a00" : "#1a5c1a") + ";");
            box.getChildren().add(u);
        }
        return box;
    }

    /**
     * Datum a čas číslicemi, v místním čase. Sestavuje se ručně ze stejného důvodu jako {@link
     * #shortDate}: zabalené běhové prostředí nemá {@code jdk.localedata}, takže vzorový formát by se
     * vysadil podle nenápadně jiné výchozí lokality.
     *
     * <p>Minuta se doplňuje nulou, hodina ne: „14:05“ a „9:05“ je to, jak se čas píše česky.
     */
    /**
     * Okamžik — jeden zápis pro celou aplikaci, viz {@link cz.digitalnikontinuita.anep.core.TimeFormat}.
     *
     * <p>Jméno {@code shortDateTime} zůstává z doby, kdy tahle metoda psála zkrácený tvar bez
     * sekund a pásma; přejmenovat dvacet volaných míst by změnu formátu utopilo v přesunech.
     */
    static String shortDateTime(java.time.Instant t) {
        return TimeUtil.format(t);
    }

    private static String shortDateTime(java.util.Date d) {
        return TimeUtil.format(d);
    }

    /**
     * Datum číslicemi, bez času. Ve výkladu se tak píšou hranice platnosti certifikátů a datum
     * odvolání — hodina a minuta u nich nic nemění a jen tříští větu; přesný údaj nese Podrobný
     * výčet. Čas se v textu drží jen u okamžiku, který dokládá razítko.
     */
    /** Totéž; dřív samotné datum, dnes plný zápis jako všude jinde. */
    static String shortDate(java.time.Instant t) {
        return TimeUtil.format(t);
    }

    /**
     * Okamžik odvolání — vždy s časem a v obou pásmech.
     *
     * <p>Samotné datum tu nestačí. U zamaskované revokace dělí vydání vloženého údaje od odvolání
     * dvacet minut a „28. 4. 2026" na obou stranách by ten rozdíl smazalo; u odvolání krátce před
     * razítkem rozhoduje o závěru pořadí v rámci jediného dne. A pásmo, protože revokační údaje
     * nesou čas v UTC, kdežto čtenář žije v SEČ nebo SELČ — bez obojího si tu dvojici nespáruje.
     *
     * <p>Formát je týž, jaký má Rozšířený přehled i řádky výčtu ({@code TimeUtil.format}), aby se
     * tentýž okamžik četl všude stejně.
     */
    private static String revokedAt(java.time.Instant t) {
        return TimeUtil.format(t);
    }

    private static String shortDate(java.util.Date d) {
        return TimeUtil.format(d);
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Verdikt tak, jak ho ukáže karta — se všemi třemi korekcemi, které aplikace nad hlášením DSS
     * dělá. Sdílené, protože „podpis je platný“ musí znamenat totéž na kartě i v záložce
     * Digitální kontinuita; dokud si to obojí počítalo samo, hlásila karta neznámou platnost a
     * text pod ní psal, že podpis je platný.
     */
    private static String cardVerdict(SignatureSummary sig, Set<String> tsExpiredSigIds) {
        String indication = tsExpiredSigIds.contains(sig.getSignatureId())
                ? "INDETERMINATE" : sig.getIndication();
        // S1: the signed content was altered. DSS 6.4 masks this as NO_SIGNING_CERTIFICATE_FOUND
        // (the failed message-digest check invalidates the signing-certificate candidate, and ISC
        // runs before CV), so its own indication understates a provably broken document. S1 reads
        // the digest matchers directly. Matches the DSS online service and EN 319 102-1, which
        // both give TOTAL_FAILED/HASH_FAILURE. Applied first so the rules below cannot undo it.
        if ("INDETERMINATE".equals(indication) && sig.getDetectedSituations().contains(1)) {
            indication = "TOTAL_FAILED";
        }
        return displayedIndication(sig, indication);
    }

    private VBox buildCard(SignatureSummary sig, String header, Set<String> tsExpiredSigIds) {
        String indication = cardVerdict(sig, tsExpiredSigIds);
        VBox card = switch (indication) {
            case "TOTAL_PASSED" -> buildValidCard(sig, header);
            case "TOTAL_FAILED" -> buildInvalidCard(sig, header);
            default             -> buildIndeterminateCard(sig, header);
        };
        // Ladicí informace odsud odešly do Detailního rozboru (2. 9. 2026). Analýza odpovídá na
        // otázku „co s tím", kdežto výčet situací a příznaků je podklad pro ladění; na kartě, kde
        // vedle sebe stály rady a diagnostika, se navzájem přebíjely. Detailní rozbor je tab, kam
        // čtenář chodí právě pro tohle, a situace se tam vypisují vždy — i u platného podpisu,
        // kde na kartě Analýzy chyběly, protože „příčiny" by tam neměly co pojmenovávat.
        return card;
    }

    // -------------------------------------------------------------------------
    /**
     * The verdict as the cards show it, which is the raw DSS indication with the two corrections
     * the application makes: a certificate revoked before signing is a failure rather than an
     * open question, and an embedded revocation from an already-expired respondent leaves the
     * result unestablished (situation 29).
     *
     * <p>Shared with the preservation offer, because "the signature is valid" has to mean the same
     * thing in both places. Situation 43 used to downgrade here too; it no longer decides verdicts
     * anywhere, since trust anchors carry a sunset date and DSS applies that reading itself.
     */
    private static String displayedIndication(SignatureSummary sig, String indication) {
        // „REVOKED_NO_POE + situace 2 → TOTAL_FAILED" tu stálo do 27. 8. 2026. Zrušeno, protože
        // z razítka za revokací neplyne, že podpis vznikl až po ní — viz ResultView.buildCard().
        if ("TOTAL_PASSED".equals(indication) && sig.getDetectedSituations().contains(29)) {
            return "INDETERMINATE";
        }
        return indication;
    }

    /**
     * The verdict as the cards show it, for a summary that carries its own raw indication.
     *
     * <p>Přístupné celému balíčku, protože se na ně ptá i {@code MainView}: upozornění v Protokolech
     * DSS mluví o tom, že se závěr AnEP rozchází s tím, co hlásí DSS, a tuhle otázku smí zodpovědět
     * jen jedno místo. Dokud si ji {@code MainView} opisoval, vynechal v opise podmínku o situaci 21
     * a hlásil rozchod i tam, kde AnEP zobrazuje totéž co DSS.
     *
     * <p>Veřejné kvůli {@code SituationScan}, který se ptá na zobrazený verdikt při měření
     * predikátů {@link #shouldOfferOtherValidators} a {@link #shouldWarnSunsetOnly} na sbírce.
     */
    public static String displayedIndication(SignatureSummary sig) {
        return displayedIndication(sig, sig.getIndication());
    }

    /**
     * Situations that have to hold before preservation is worth offering: an unbroken chain of
     * time-stamps (31), every missing revocation obtainable (33), and a verdict reached without
     * relaxations (34). Situation 32 — the last time-stamp's own certificate still valid — is
     * asked for separately, since only the non-expert offer insists on it.
     *
     * <p>A signature carrying no time-stamp at all is asked neither 31 nor 32, and that is not an
     * exemption but the only reading that means anything. Both situations describe a chain of
     * proofs of existence: 31 that it has no gap, 32 that its newest link is still judgeable. A
     * level-B signature has no such chain — it has nothing — so neither can fire, and requiring
     * them made preservation unofferable for exactly the documents that need it most. That was a
     * regression: preservation from B is what {@code runPreservation()}'s two rounds exist for
     * (stamp, then validation data, then the archive stamp), and the machinery stayed in place
     * while the gate quietly stopped anyone reaching it.
     *
     * <p>What replaces them is not nothing. The signing certificate must still be good — EN 319
     * 122-1 clause 6.3 requirement m) forbids a time-stamp created after it was revoked or expired
     * — and that is checked by the callers, which have the whole document in view; one document
     * time-stamp covers every signature, so it is not a per-signature question. Coverage still has
     * to be obtainable, and at level B {@code preservationWorthwhile} already asks that in the form
     * the case calls for: the anchor does not exist yet, so the question is whether the authority
     * is publishing a usable status now, not whether some list predates a moment that has not
     * happened.
     */
    /**
     * Dosáhne na tenhle prvek úkon, který se pro dokument nabízí?
     *
     * <p>Jedna otázka, tři místa: brána u každé varianty, blok u prvku („AnEP nabízí možnost…“
     * proti „Úkon na tento podpis nedosáhne“) a souhrn souběhu. Dokud brány žádaly, aby úkon
     * vyšel <b>všem</b> dosažitelným prvkům, splývala tahle otázka s pouhou dosažitelností
     * a stačilo se ptát na {@code continuityObstacle()}. S pravidlem „stačí jeden prvek“
     * (8. 9. 2026) se rozešly: dokument projde branou uchování kvůli jedinému podpisu a ostatní
     * mohou vycházet na něco jiného nebo na nic.
     *
     * <p>Naměřeno na „dva podpisy T.pdf“: souhrn správně hlásil „prospěje prvku č. 1, prvku č. 2
     * nepomůže“, a prvek č. 2 měl přesto nad sebou blok „AnEP nabízí možnost uchování. Do kdy je
     * třeba ji využít?“ — dvě věty na jedné kartě, každá o něčem jiném.
     */
    private boolean offeredActionReaches(SignatureSummary sig,
                                         ContinuityElementBlocks.Gates gates,
                                         java.util.Set<String> lastTimestampTrusted) {
        boolean listed = continuityObstacle(sig, lastTimestampTrusted) == null
                || (gates.preserve() && sig.getDetectedSituations().contains(35));
        if (!listed) return false;
        if (gates.preserve()) return preservationReaches(sig);
        if (gates.restamp()) return restampReaches(sig);
        if (gates.prolong()) return prolongReaches(sig, lastTimestampTrusted);
        return false;
    }

    /**
     * Dosáhne <b>uchování</b> na tenhle prvek?
     *
     * <p>Vytaženo z brány ven, protože na tutéž otázku odpovídají dvě místa: brána rozhoduje, zda
     * se úkon vůbec nabídne, a souhrn souběhu ({@code addCombinationBlock}) čtenáři říká, kterým
     * prvkům prospěje. Od chvíle, kdy branou projde dokument, na jehož část úkon nedosáhne, se ta
     * dvě místa nesmějí rozejít — jinak tlačítko slibuje pro pět podpisů, co platí o jednom.
     *
     * <p>Členství v {@code preservationReachable} tahle metoda neřeší; to je otázka pro
     * {@code continuityObstacle()} a filtruje se před ní.
     */
    private boolean preservationReaches(SignatureSummary sig) {
        return ("TOTAL_PASSED".equals(displayedIndication(sig))
                        || sig.getDetectedSituations().contains(35))
                && preservationSituationsMet(sig, true);
    }

    /**
     * Dosáhne <b>přerazítkování</b> na tenhle prvek? Sesterská otázka k {@link
     * #preservationReaches} a ze stejného důvodu vytažená z brány ven.
     *
     * <p>Verdikt, nebo jedna ze dvou náhradních cest. Ta trojice musí sedět s {@code
     * continuityObstacle()}: prvek, který branou prošel a tady by neprošel, by skončil
     * v {@code reachable}, nezapnul by žádnou ze tří cest a dokument by zůstal bez nabídky,
     * ačkoli text u prvku tvrdí, že úkon dosáhne.
     *
     * <p>Souvislost řady se měří <b>měřítkem toho, komu je úkon určen</b>, ne zdejším. Situace 31
     * se počítá jen z razítek, která obstála v AnEP, takže u razítka odmítnutého kvůli sunsetu
     * chybí — ačkoli pro okruh nástrojů, který se na status služby neptá, je řada v pořádku.
     * {@code ContinuityHorizon} to už umí a ptá se na neporušenost tokenu; opřít se o tutéž funkci
     * znamená, že se datum v bloku o CzechPOINTu a tlačítko dole nemohou rozejít. Naměřeno na
     * „LTA_kvalifikovany_2012_01_20_razitko_2016_do_2021_preserved_2020-11-14“, kde 32 je a 31
     * chybí právě a jen kvůli sunsetu.
     */
    private boolean restampReaches(SignatureSummary sig) {
        return ("TOTAL_PASSED".equals(displayedIndication(sig))
                        || tolerantReaderMaySucceed(sig)
                        || tolerantReadingStillWorks(sig))
                && !ContinuityHorizon.brokenChainStopsTolerant(sig)
                && sig.getDetectedSituations().contains(32)
                && (sig.getDetectedSituations().contains(33)
                        || sig.getDetectedSituations().contains(45));
    }

    /**
     * Dosáhne <b>prodloužení</b> na tenhle prvek?
     *
     * <p>Řada, kterou okruh posuzující razítka podle platnosti certifikátů vidí jako přerušenou:
     * chybí situace 31 nebo 32, kdežto podle standardů prvek obstál. Situace 43 vylučuje sama —
     * po sunsetu už minulé ověření k důkazu existence před ním nedosáhne.
     *
     * <p>Ústupek na revokačních datech u prvku s prošlým certifikátem posledního razítka je
     * popsaný souběh, ne důvod k odmítnutí; ostatní ústupky ano. Viz {@code
     * toleratedRevocationWithExpiredTsu}.
     *
     * <p>Kotva důvěry se žádá jen tam, kde certifikát posledního razítka skončil — tam dělá práci
     * shovívavost standardů. Kde 32 platí, je certifikát v pořádku sám o sobě a žádat kotvu by
     * odmítlo druhé prodloužení z důvodu, který se ho netýká.
     *
     * <p><b>Bez {@code preservationAddsSomething()} schválně.</b> Ten test se ptá, jestli něco
     * chybí a dá se sehnat, což je otázka uchování. Prací prodloužení je samo nové razítko —
     * důkaz, že celý stav existoval, dokud si původní služba držela kvalifikovaný status — a ten
     * má cenu bez ohledu na to, co dokument už nese. S tím testem by o úkon přišlo právě to, co ho
     * potřebuje nejvíc: samostatné razítko z roku 2014, jehož certifikát TSU skončil v roce 2018
     * a které drží jen důvěryhodný seznam.
     */
    private boolean prolongReaches(SignatureSummary sig,
                                   java.util.Set<String> lastTimestampTrusted) {
        List<Integer> found = sig.getDetectedSituations();
        boolean chainSoundByCertValidity = found.contains(31) && found.contains(32);
        boolean anchorNeeded = !found.contains(32);
        return "TOTAL_PASSED".equals(displayedIndication(sig))
                && !chainSoundByCertValidity
                && !found.contains(43)
                && (found.contains(34) || toleratedRevocationWithExpiredTsu(sig))
                && (found.contains(33) || found.contains(45))
                && (!anchorNeeded || lastTimestampTrusted.contains(sig.getSignatureId()));
    }

    static boolean preservationSituationsMet(SignatureSummary sig, boolean requireS32) {
        List<Integer> found = sig.getDetectedSituations();
        boolean noChainYet = sig.getAllTimestamps().isEmpty();
        return (noChainYet || found.contains(31))
                && preservationAddsSomething(sig) && found.contains(34)
                && (noChainYet || !requireS32 || found.contains(32));
    }

    /**
     * Whether preservation is worth offering for this signature.
     *
     * <p>Being preserved already is not a reason to withhold it. Preservation does not last for
     * good: an archive time-stamp protects the document only while its own TSA certificate can
     * still be judged, so the document has to be preserved again — at the latest before that
     * certificate expires — and each round re-anchors the whole thing on a fresh time-stamp. That
     * repetition is what digital continuity consists of, so a document at LTA level is a candidate
     * for preservation exactly like one that has never been preserved. Withholding the offer there
     * would quietly cap how long a document can be kept verifiable.
     *
     * <p>What it does take is a signature that is anchored — at level B nothing is, and the offer
     * there is a time-stamp, decided elsewhere — and nothing unobtainable. S33's missing list has
     * to be empty, because preservation promises to end with the material in the document and
     * cannot promise what cannot be fetched; where it is not empty, fixation is offered instead.
     *
     * <p>{@link SignatureSummary#isPreservationWorthwhile()} stays as a first route because it also
     * covers signatures with no best-signature-time of their own.
     *
     * <p><b>Samostatné časové razítko se na best-signature-time ptát nesmí.</b> Rozhodný okamžik
     * je vlastnost podpisu; razítko je svým vlastním důkazem existence a žádný nemá — tak to
     * ostatně říká i „Rozbor časového razítka" v téže záložce. Dokud se tu ptalo, vycházelo
     * každé samostatné razítko jako neukotvené, {@code continuityObstacle()} spadla do své
     * poslední větve a oznámila „chybí revokační informace, které už nelze získat“. U razítka,
     * jehož certifikát je sám kotvou důvěry, je to tvrzení naopak: revokace se u kotvy
     * nezjišťuje, takže chybět nemůže. Potkáno na „podvod s revokaci\priklad6_13.pdf“, kde
     * je tím razítkem PostSignum TSA - TSU 2 a záložka si o dva bloky výš protiřečila.
     *
     * <p>Podmínka {@code getS33MissingDetail() == null} platí dál pro obojí: kde opravdu něco
     * chybí, se ta věta ozve právem.
     */
    private static boolean preservationAddsSomething(SignatureSummary sig) {
        if (sig.isPreservationWorthwhile()) return true;
        boolean anchored = "TimeStampToken".equals(sig.getFormat())
                || sig.getBestSignatureTime() != null;
        return anchored && sig.getS33MissingDetail() == null;
    }

    // Form 1: Platný (TOTAL_PASSED) — positive confirmation
    // -------------------------------------------------------------------------

    private VBox buildValidCard(SignatureSummary sig, String header) {
        VBox card = new VBox(0);
        card.setStyle(CARD_STYLE);
        card.getChildren().add(typeBlock(sig, header, "#1a7a1a", true));
        String headerMsg = "✓  Proces ověřování se podařilo úspěšně dokončit. Výsledkem je zjištění, že "
                + gendered(sig, "podpis je platný", "pečeť je platná", "razítko je platné") + ".";
        card.getChildren().add(coloredHeader(headerMsg, "#1a7a1a", "#ffffff"));
        addCounterSignatureBlock(card, sig);
        addLegalEffectsBlock(card, sig);
        addCertificationBlock(card, sig);
        addPdfLockBlock(card, sig);
        // Blok „Podepsaná osoba a čas" / „Pečetící osoba a čas" tu od 4. 9. 2026 není: kdo se
        // ptá na jméno, čas a druh certifikátu, najde je v Rozšířeném přehledu, a na kartě
        // s kladným výsledkem stály mezi odpovědí („platný") a tím, co s tím dál. Razítku blok
        // zůstává — jmenuje se „Časové razítko" a nese jeho čas, který jinde na kartě není.
        if ("TimeStampToken".equals(sig.getFormat())) {
            addSignerBlock(card, sig);
        }
        addFormatBlock(card, sig);
        addValidationCourseBlock(card, sig, "TOTAL_PASSED");
        addSunsetOnlyBlock(card, sig);

        VBox body = new VBox(8);
        body.setPadding(new Insets(10));

        // Blok je vždy sbalený — pokyn uživatele 2. 10. 2026. Do té doby se otevíral, kdykoli
        // nesl výhradu ke kladnému verdiktu (ústupky, doklad o odvolání, razítko bez kvalifikace).
        if (!sig.getDecisiveSettings().isEmpty()) {
            Label note = new Label("Platnost ověřena v tolerantním režimu ("
                    + String.join(", ", sig.getDecisiveSettings()) + ").");
            note.setStyle("-fx-text-fill: #7a4a00; -fx-font-style: italic; -fx-font-size: 12px;");
            note.setWrapText(true);
            body.getChildren().add(note);
        }

        // S20: rozepsané místo holého tlačítka, stejně jako situace 24 o kus níž — obojí byly
        // jediná dvě místa v poznámkách, kde tlačítko stálo samo, bez věty vedle sebe, takže
        // čtenář viděl barevný obdélník a nic víc.
        //
        // Sdělení nese <b>nadpis</b>, ne až rozbalený obsah, a to je rozdíl proti situaci 24.
        // Ta končí uklidněním („na platnost to nemá vliv"), kdežto tahle říká, že aplikace drží
        // doklad o odvolání ještě před podpisem — tedy že kladný verdikt stojí jen na starším
        // údaji. Kdyby to bylo schované za slovem „Upozornění", zamlčel by sbalený blok právě
        // to nejpodstatnější; takhle se čtenář dozví věc samu a rozbalí si jen odůvodnění.
        if (sig.getDetectedSituations().contains(20)) {
            Label detail = new Label(ResultView.SITUATION_LABELS.get(20));
            detail.setStyle("-fx-text-fill: #333333; -fx-font-size: 12px;");
            detail.setWrapText(true);
            detail.setMaxWidth(Double.MAX_VALUE);
            VBox inner = new VBox(detail);
            inner.setPadding(new Insets(4, 12, 8, 12));
            body.getChildren().add(collapsibleBlock(
                    "Aplikace má doklad, že certifikát byl odvolán ještě před "
                            + gendered(sig, "podpisem", "zapečetěním", "vydáním razítka"),
                    inner, false, BlockTint.RED));
        }
        // S51: upřesnění k doloženému závěru, ne pochybnost o něm.
        addStaleRevocationRow(body, sig);
        // S24: certifikát byl odvolán až po podpisu. Rozepsané, ne za tlačítkem — a je to jediné
        // místo v poznámkách, kde tlačítko stálo samo, bez věty vedle sebe. Čtenář tak viděl
        // modrý obdélník s nápisem „Informace" a nic víc: ani čeho se týká, ani jestli ho má
        // zajímat. Zjistil to teprve kliknutím, kterým se navíc otevře modální dialog.
        //
        // Vlastní zabalený blok, ne odstavec: sdělení je dlouhé na tři řádky a končí tím, že na
        // platnost podpisu to nemá vliv — tedy uklidněním. Nadpis říká, oč jde, a kdo chce
        // podrobnosti, rozbalí si je. Poznámky nad ním jsou nově otevřené, takže nadpis je vidět
        // hned; kdyby se rozbalil i on, zabral by výhradě, která platnost opravdu zpochybňuje,
        // víc místa než jí patří.
        if (sig.getDetectedSituations().contains(24) && sig.getLaterRevokedDetail() != null) {
            Label detail = new Label(sig.getLaterRevokedDetail());
            detail.setStyle("-fx-text-fill: #333333; -fx-font-size: 12px;");
            detail.setWrapText(true);
            detail.setMaxWidth(Double.MAX_VALUE);
            VBox inner = new VBox(detail);
            inner.setPadding(new Insets(4, 12, 8, 12));
            // „byl revokován, ale až po…" místo „byl odvolán až po…": nadpis se čte i zběžně
            // a v té podobě zaujme spojka, ne sloveso — čtenář si odnese, že k revokaci došlo,
            // a teprve pak že na platnost nemá vliv. Beze změny významu, jen v pořadí, v jakém
            // ta dvě sdělení dopadnou.
            body.getChildren().add(collapsibleBlock(
                    "Certifikát byl revokován, ale až po "
                            + gendered(sig, "podpisu", "zapečetění", "vydání razítka"),
                    inner, false));
        }
        // S49: verdikt platí, ale rozhodný okamžik nese razítko, které k okamžiku svého vlastního
        // důkazu existence kvalifikované nebylo. Čtenář odchází s tím, že podpis je platný —
        // a je lepší, když se o té výhradě dozví tady než od validátoru, který ji uplatní.
        //
        // Do 29. 8. 2026 tu stála táž větev pro situaci 43, jenže ta se na kladný verdikt
        // nedetekuje, takže se blok nikdy nepostavil.
        if (sig.getDetectedSituations().contains(49) && sig.getWithdrawnTsaDetail() != null) {
            Label note = new Label(sig.getWithdrawnTsaDetail());
            note.setStyle("-fx-text-fill: #7a4a00; -fx-font-size: 12px;");
            note.setWrapText(true);
            HBox row = new HBox(8, ResultView.makeInfoButton(ResultView.SITUATION_LABELS.get(49)), note);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(note, Priority.ALWAYS);
            body.getChildren().add(row);
        }
        // S50: o článek dál. Vlastní řádek, ne přípojná věta k předchozímu: mluví o jiném prvku
        // a nastat může i samostatně, když je rozhodné razítko samo v pořádku.
        if (sig.getDetectedSituations().contains(50) && sig.getProofCarrierDetail() != null) {
            Label note = new Label(sig.getProofCarrierDetail());
            note.setStyle("-fx-text-fill: #7a4a00; -fx-font-size: 12px;");
            note.setWrapText(true);
            HBox row = new HBox(8, ResultView.makeInfoButton(ResultView.SITUATION_LABELS.get(50)), note);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(note, Priority.ALWAYS);
            body.getChildren().add(row);
        }

        // Situace 54: kladný závěr stojí na důkazu existence odvozeném z prvku, který tenhle
        // kryje. Bez téhle poznámky by čtenář dostal „platný" u podpisu bez vlastního časového
        // razítka a neměl by se kde dozvědět, oč se to opírá.
        //
        // Není to výhrada, proto bez barvy. Ověření
        // proběhlo podle standardů — EN 319 102-1 zná „time indication for signature existence"
        // jako vstup od ověřující aplikace —, jen se opřelo o údaj, který si aplikace odvodila
        // z jiného prvku, a to má být vidět.
        if (sig.getDetectedSituations().contains(54) && sig.getCoveringProofDetail() != null) {
            Label note = new Label(sig.getCoveringProofDetail());
            note.setStyle("-fx-text-fill: #555555; -fx-font-size: 12px;");
            note.setWrapText(true);
            HBox row = new HBox(8, ResultView.makeInfoButton(ResultView.SITUATION_LABELS.get(54)),
                    note);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(note, Priority.ALWAYS);
            body.getChildren().add(row);
        }

        addSigningTimeRow(body, sig);
        addIgnoredTimestampRow(body, sig);

        // Žlutá vrstva protokolů DSS, poslední položkou poznámek. Stojí až za nimi schválně:
        // poznámky nad ní jsou výhrady, které si AnEP stává, kdežto tohle je cítovaný nález
        // knihovny — podklad, ne stanovisko. Blok se kvůli připomínkám
        // neotevírá, protože připomínku nese většina platných podpisů (33 ze 48 na sbírce D:\tmp)
        // a otevřený blok u každého druhého dokumentu by přestal něco znamenat.
        addDssRemarksBlock(body, sig);

        if (!body.getChildren().isEmpty()) {
            card.getChildren().add(collapsibleBlock(NOTES_BLOCK_TITLE, body, false));
        }
        return card;
    }

    /**
     * Podblok „Připomínky od knihovny DSS" — to, co je v protokolech DSS žluté.
     *
     * <p>Prvek obstál, a přesto k němu knihovna něco má. Do bloku o důvodech to nepatří (ten
     * odpovídá na otázku, proč výsledek není kladný, a tady kladný je) a do protokolů DSS se
     * dostane jen ten, kdo si zapne expertní mód. Bez zdejšího podbloku by se tedy čtenář
     * nedozvěděl vůbec nic o věcech, které jiný ověřovatel klidně vyhodnotí přísněji —
     * o SHA-1 uvnitř podpisu, o chybějícím QSCD, o překrývajících se prvcích na stránce.
     *
     * <p>Každá připomínka má tři řádky: českou větu, výklad, a nakonec původní znení knihovny
     * i s klíčem. Ten poslední řádek tu není pro parádu: je to jediné místo, kde si čtenář může
     * zdejší výklad ověřit proti protokolu DSS nebo proti jinému nástroji, který týž dokument
     * pustí.
     *
     * <p>Klíč, ke kterému výklad není, se nezamlčuje — viz {@link DssRemarkTexts}.
     */
    /**
     * Připomínky v pořadí ke čtení: nejdřív ověření podpisu, pak kvalifikovanost, a uvnitř té
     * druhé skupiny dvojice pohromadě.
     *
     * <p>Hlášení o kvalifikovanosti chodí v párech: táž otázka k rozhodnému okamžiku
     * ({@code _AT_ST_}) a k okamžiku vydání certifikátu ({@code _AT_CC_}). Knihovna je vydává
     * promíchané a druhá polovina páru pak začíná slovem „Ani“ několik řádků pod větou, na kterou
     * navazuje — čtenář ji čte jako samostatné tvrzení a marně hledá, k čemu to „ani“ patří.
     * Naměřeno na {@code D:/tmp/emailovy certifikat/dokument signed.pdf}, kde jich je šest.
     *
     * <p>U ověření podpisu zůstává pořadí knihovny: ta hlášení spolu nesouvisí a přerovnávat
     * je podle abecedy klíčů by čtenáři nic neřeklo.
     */
    private static List<SignatureSummary.DssRemark> orderedRemarks(
            List<SignatureSummary.DssRemark> remarks) {
        List<SignatureSummary.DssRemark> out = new java.util.ArrayList<>(remarks);
        out.sort(java.util.Comparator
                .comparing((SignatureSummary.DssRemark r) -> r.qualification() ? 1 : 0)
                .thenComparing(r -> r.qualification() ? pairKey(r.key()) : "")
                .thenComparing(r -> r.qualification() ? momentKey(r.key()) : ""));
        return out;
    }

    /** Jméno otázky bez okamžiku, aby obě poloviny páru vyšly stejně. */
    private static String pairKey(String key) {
        return key == null ? "" : key.replace("_AT_ST_", "_AT_").replace("_AT_CC_", "_AT_");
    }

    /** Rozhodný okamžik před okamžikem vydání: věta o něm je ta první, druhá na ni navazuje. */
    private static String momentKey(String key) {
        if (key == null) return "2";
        if (key.contains("_AT_ST_")) return "0";
        return key.contains("_AT_CC_") ? "1" : "2";
    }

    private void addDssRemarksBlock(VBox body, SignatureSummary sig) {
        List<SignatureSummary.DssRemark> remarks = sig.getDssRemarks();
        if (remarks == null || remarks.isEmpty()) return;

        VBox inner = new VBox(10);
        inner.setPadding(new Insets(6, 12, 8, 12));
        for (SignatureSummary.DssRemark r : orderedRemarks(remarks)) {
            VBox one = new VBox(2);

            String kratce = DssRemarkTexts.shortText(r.key());
            Label headline = new Label((r.qualification()
                    ? "Kvalifikovanost: " : "Ověření podpisu: ")
                    + (kratce != null ? kratce : "K tomuto hlášení zatím nemáme vlastní výklad."));
            headline.setStyle("-fx-text-fill: #7a4a00; -fx-font-weight: bold;"
                    + " -fx-font-size: 12px;");
            headline.setWrapText(true);
            headline.setMaxWidth(Double.MAX_VALUE);
            one.getChildren().add(headline);

            String vyklad = DssRemarkTexts.explanation(r.key());
            if (vyklad != null) {
                Label detail = new Label(vyklad);
                detail.setStyle("-fx-text-fill: #333333; -fx-font-size: 12px;");
                detail.setWrapText(true);
                detail.setMaxWidth(Double.MAX_VALUE);
                one.getChildren().add(detail);
            }

            Label origin = new Label(r.key()
                    + (r.message() != null && !r.message().isBlank()
                            ? " — " + r.message() : ""));
            origin.setStyle("-fx-text-fill: #777777; -fx-font-size: 11px;"
                    + " -fx-font-style: italic;");
            origin.setWrapText(true);
            origin.setMaxWidth(Double.MAX_VALUE);
            one.getChildren().add(origin);

            inner.getChildren().add(one);
        }

        String title = remarks.size() == 1
                ? "Připomínka od knihovny DSS"
                : "Připomínky od knihovny DSS (" + remarks.size() + ")";
        body.getChildren().add(collapsibleBlock(title, inner, false));
    }

    /**
     * Everything about who signed and when, including the visual appearance if the signature
     * carries one. Sits above the format block: the reader asks "who signed this?" before
     * "how is it packaged?".
     */
    private void addSignerBlock(VBox card, SignatureSummary sig) {
        boolean isTs = "TimeStampToken".equals(sig.getFormat());
        VBox block = new VBox(4);
        block.setPadding(new Insets(8, 12, 8, 12));

        if (!isTs && sig.getSignerDisplayName() != null && !sig.getSignerDisplayName().isBlank()) {
            block.getChildren().add(infoRow(sig.personLabel() + ":",
                    sig.getSignerDisplayName()));
        }

        if (isTs) {
            if (sig.getSigningTime() != null)
                block.getChildren().add(infoRow("Čas razítka:", TimeUtil.format(sig.getSigningTime())));
        } else if (sig.getBestSignatureTime() != null) {
            String note = sig.isBestSignatureTimeFromEvidenceRecord()
                    ? " (čas razítka evidenčního záznamu)"
                    : sig.hasValidTimestamp() ? " (čas razítka)"
                    : sig.isUsedCustomValidationTime() ? " (zadaný čas)"
                    : " (deklarovaný čas)";
            block.getChildren().add(infoRow(sig.isMark() ? "Čas značky:"
                    : sig.isSeal() ? "Čas pečeti:" : "Čas podpisu:",
                    TimeUtil.format(sig.getBestSignatureTime()) + note));
        } else if (sig.getSigningTime() != null) {
            block.getChildren().add(infoRow(sig.isMark() ? "Čas značky:"
                    : sig.isSeal() ? "Čas pečeti:" : "Čas podpisu:",
                    TimeUtil.format(sig.getSigningTime()) + " (deklarovaný čas)"));
        }

        if (!isTs && sig.getCertKind() != null && !sig.getCertKind().isBlank())
            block.getChildren().add(infoRow("Druh certifikátu:", certKindText(sig)));

        if (sig.getSignatureAppearancePng() != null)
            block.getChildren().add(buildAppearanceSection(sig, sig.getSignatureAppearancePng()));

        if (block.getChildren().isEmpty()) return;
        String title = "TimeStampToken".equals(sig.getFormat()) ? "Časové razítko"
                : sig.personLabel() + " a čas";
        card.getChildren().add(collapsibleBlock(title, block, false));
        card.getChildren().add(blockSeparator());
    }

    // -------------------------------------------------------------------------
    /**
     * Varování situace 51 — závěr o neodvolání nese revokační údaj starší než podpis sám.
     *
     * <p>Sdílené kartou s kladným i s neznámým výsledkem, protože situace 51 se počítá bez ohledu
     * na verdikt a na obou kartách znamená něco, co si čtenář musí odnést. Jen na kladné byla do
     * 2. 9. 2026 a bylo to špatně: ze 27 výskytů na sbírce D:\tmp jich 25 padlo na kartu s neznámou
     * platností, kde se situace spočítala a nečetla ji žádná větev — týž osud, jaký potkal situace
     * 37, 38, 40 a 41.
     *
     * <p><b>Proč ne důvod.</b> Důvody odpovídají na otázku „proč verdikt není kladný" a u těch 25
     * to situace 51 není; příčinou je nepoužitelný revokační údaj (důvod 6 nebo 2). Vlastní důvod
     * by tvrdil, že podpis padl na tohle, což není pravda — a tabulka převodu situací na důvody by
     * tím začala lhát. Situace 20 to řeší jinak a smí: má dvojče S30 pro nekladný verdikt, které
     * důvod 22 nese.
     *
     * <p><b>Na kartě s neznámou platností váží jinak, ale ne míň.</b> Tam se čtenáři zároveň
     * nabízí blok „Kde je šance na úspěšné ověření", tedy rada zkusit ověřovatele s vlastní
     * zásobou revokačních dat. K téhle radě ta výhrada patří: údaj, který v dokumentu leží,
     * o okamžiku podpisu nemluví, takže kladná odpověď odjinud nemusí znamenat, co se zdá. Mezi
     * těmi 25 jsou obě rodiny úmyslných podvrhů „po revokaci" a „ICA revokace 2018". Nabídne-li
     * se ten blok, platí to doslova; nenabídne-li se (jako u důvodu 4, kde revokační zásoba
     * nepomůže), míří táž výhrada na radu ověřit k zadanému datu — kladná odpověď i z té cesty
     * bude stát na tomtéž údaji.
     *
     * <p><b>Kde na té kartě stojí:</b> od 22. 9. 2026 v bloku „Poznámky k výsledku"
     * ({@link #addResultNotesBlock}), ne na konci výčtu důvodů, kde se četla jako další důvod.
     *
     * <p>Na kartu s prokázanou neplatností se nepřidává: tam je verdikt rozhodnutý a výhrada
     * k dokladu, který nic nezachrání, je jen šum. Na sbírce takový případ ostatně není ani jeden
     * — všech 25 nekladných je INDETERMINATE.
     */
    /**
     * Vyplatí se s uchováním chvíli počkat, až vydavatel zveřejní čerstvější revokační údaj?
     *
     * <p>Od chvíle, kdy situace 51 přestala brát nabídku, se uchování nabídne i vteřinu po
     * podpisu — a právě tehdy je nejhorší ho provést. Uchování vloží do dokumentu revokační
     * údaj, který je k dispozici <b>teď</b>, tedy ten starší než podpis, a archivní razítko ho
     * zafixuje. Výhrada se tím do dokumentu zapíše natrvalo: doloženo na
     * {@code T 2020 10 15 po revokaci Signer s revinfo_preserved_….pdf}, který situaci 51 nese
     * dál i po uchování. Po expiraci certifikátu už novější údaj nevznikne, takže ji z něj
     * nikdo nikdy nedostane ven.
     *
     * <p>Není to tedy překážka, ale doporučení k načasování — a proto stojí v bloku, který úkon
     * nabízí, ne v tom, který vysvětluje odmítnutí.
     *
     * <p>Tři podmínky, všechny nutné. <b>Situace 51</b> je ten stav. <b>Platný</b> prvek proto,
     * že u nekladného verdiktu se řeší něco jiného. A <b>podpis mladší než 24 hodin</b> proto,
     * že jen tam čekání k něčemu je: u staršího dokumentu s expirovaným certifikátem už žádný
     * novější údaj nevznikne (přesně případ hlášené pečeti z roku 2019)
     * a rada „počkejte" by byla bez konce. Bez razítka rozhoduje deklarovaný čas.
     */
    static boolean fresherRevocationLikelySoon(SignatureSummary sig, boolean valid) {
        if (!valid || !sig.getDetectedSituations().contains(51)) return false;
        java.time.Instant signed = sig.getBestSignatureTime() != null
                ? sig.getBestSignatureTime() : sig.getSigningTime();
        return signed != null
                && !signed.isBefore(java.time.Instant.now().minus(java.time.Duration.ofHours(24)));
    }

    /**
     * Blok „Poznámky k výsledku“ na kartě s neznámou platností — dnes v něm stojí jediná
     * poznámka, ta od situace 51.
     *
     * <p><b>Proč blok, a ne řádek na konci výčtu důvodů.</b> Tam poznámka stála do 22. 9. 2026
     * a četla se jako další důvod: blok se jmenuje „Zjištěné důvody a doporučení“, začíná větou
     * „Aplikace detekovala následující důvod(y) neúspěšného ověření“ a poznámka k němu byla
     * přilepená zezadu i s oranžovým tlačítkem „Upozornění“. Důvod to přitom není — na kartě
     * s neznámým výsledkem jím být nemůže, protože příčinou toho výsledku je něco jiného.
     * Nález uživatele nad pečetí, jejíž jedinou překážkou je expirovaný certifikát bez
     * razítka (důvod 4) a s revokačním údajem nesouvisí vůbec.
     *
     * <p><b>Nadpis je týž jako na kladné kartě</b> a poznámka se tím dostala tam, kde ji čtenář
     * u druhého výsledku už zná. Zavřený, jako je zavřený i tam, kde je situace 51 jedinou
     * poznámkou: je to výhrada k závěru, ke kterému by došel někdo jiný, ne odpověď na otázku,
     * se kterou čtenář ke kartě přišel.
     *
     * <p><b>Adresáta má i tady.</b> Karta radí ověřit k zadanému datu a blok „Kde je šance na
     * úspěšné ověření“ posílá k ověřovateli s vlastní zásobou revokačních dat; kladná odpověď
     * z obou cest by stála právě na tom údaji, který vznikl dřív než podpis.
     *
     * <p><b>Stojí až za „Průběhem ověřování“</b> (pokyn uživatele 22. 9. 2026). Nahoře je to,
     * proč ověření nedopadlo a jak probíhalo; poznámka je výhrada k závěru, ke kterému by došel
     * někdo jiný, a ta patří za vyprávění, ne mezi ně.
     */
    static void addResultNotesBlock(VBox card, SignatureSummary sig) {
        VBox body = new VBox(8);
        body.setPadding(new Insets(10));
        boolean stale = addStaleRevocationRow(body, sig);
        boolean signingTime = addSigningTimeRow(body, sig);
        boolean ignoredTs = addIgnoredTimestampRow(body, sig);
        if (!stale && !signingTime && !ignoredTs) return;
        card.getChildren().add(collapsibleBlock(NOTES_BLOCK_TITLE, body, false));
        card.getChildren().add(blockSeparator());
    }

    /**
     * Razítko, které neobstálo, ale o výsledku nerozhoduje: prvek má jiné platné razítko, které
     * může nést důkaz existence a vzniklo ještě za platnosti podpisového certifikátu. Důvody 21
     * a 23 se pak nevydávají ({@code ReportMapper.detectReasons}), a tohle je místo, kde se
     * o neobstálém razítku čtenář přesto dozví — je to fakt o dokumentu, jen ne příčina výsledku
     * (rozhodnutí uživatele 13. 10. 2026).
     *
     * <p>Podmínka se čte z razítek prvku, ne ze situace 27: u platného podpisu se zjišťování
     * situací zastaví dřív, než na ni dojde ({@code ReportMapper.detectFailureSituations}), a
     * poznámka by se na kladné kartě nikdy neukázala — přitom právě tam ji uživatel 13. 10. 2026
     * po doplnění CRL potřeboval. Důvody 21 ani 23 indikovány být nesmějí: kde jsou, mluví
     * o neobstálém razítku samy. Platné razítko, o které se poznámka opírá, je nejstarší platné
     * razítko prvku, které není razítkem obsahu ani holým razítkem VRI a vzniklo před koncem
     * platnosti certifikátu — totéž, na co se ptá {@code ReportMapper.ownPoeWithinValidity}.
     */
    static boolean addIgnoredTimestampRow(VBox body, SignatureSummary sig) {
        if (sig.getDetectedReasons().contains(21) || sig.getDetectedReasons().contains(23)) {
            return false;
        }
        java.time.Instant certUntil = sig.getCertNotAfter();
        SignatureSummary.TimestampEntry poe = sig.getAllTimestamps().stream()
                .filter(t -> t.passed() && t.time() != null)
                .filter(t -> !"content".equals(t.tsKind()) && !"vri_bare".equals(t.tsKind()))
                .filter(t -> certUntil == null || t.time().isBefore(certUntil))
                .min(java.util.Comparator.comparing(SignatureSummary.TimestampEntry::time))
                .orElse(null);
        List<SignatureSummary.TimestampEntry> failed = sig.getAllTimestamps().stream()
                .filter(t -> !t.passed()).toList();
        if (poe == null || failed.isEmpty()) return false;

        StringBuilder text = new StringBuilder();
        for (SignatureSummary.TimestampEntry t : failed) {
            String what = capitalize(ReportMapper.tsKindLabel(t))
                    + (t.time() != null ? " z " + TimeUtil.format(t.time()) : "");
            text.append(what).append(t.intact()
                    ? " se nepodařilo ověřit jako platné. "
                    : " je poškozené: jeho otisk neodpovídá datům, která má krýt, případně"
                      + " nesouhlasí jeho podpis. ");
        }
        text.append("O výsledku to ale nerozhoduje: důkaz existence může poskytnout ")
                .append(ReportMapper.tsKindLabel(poe)).append(" z ")
                .append(TimeUtil.format(poe.time()))
                .append(", které je platné a vzniklo ještě za platnosti podpisového certifikátu.");

        Label note = new Label(text.toString());
        note.setStyle("-fx-text-fill: #555555; -fx-font-size: 12px;");
        note.setWrapText(true);
        int situation = failed.stream().anyMatch(t -> !t.intact()) ? 36 : 27;
        HBox row = new HBox(8,
                ResultView.makeInfoButton(ResultView.situationLabel(situation, sig)), note);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(note, Priority.ALWAYS);
        body.getChildren().add(row);
        return true;
    }

    /**
     * Situace 22 — podpis nenese čitelný atribut signing-time. AnEP ho od 3. 10. 2026 nevyžaduje
     * (o době vzniku rozhoduje razítko, EN 319 102-1 deklarovaný čas nežádá), ale výchozí politika
     * knihovny DSS ano, takže validátory na ní postavené takový podpis odmítnou. Čtenář, který
     * dokument posílá dál, to vědět má. Celé vysvětlení je pod tlačítkem, řádek říká jen to
     * podstatné.
     */
    static boolean addSigningTimeRow(VBox body, SignatureSummary sig) {
        boolean outside = addClaimedTimeOutsideRow(body, sig);
        if (!sig.getDetectedSituations().contains(22)) return outside;
        Label note = new Label(sig.isSigningTimeUnreadable()
                ? "Podpis nese atribut signing-time (deklarovaný čas podpisu) zapsaný nestandardně,"
                  + " takže jej knihovna DSS nepřečte. AnEP jej nevyžaduje, validátory postavené na"
                  + " výchozím nastavení knihovny DSS však mohou podpis kvůli tomu odmítnout."
                : "Podpis nenese atribut signing-time (deklarovaný čas podpisu). AnEP jej"
                  + " nevyžaduje, validátory postavené na výchozím nastavení knihovny DSS však"
                  + " mohou podpis kvůli tomu odmítnout.");
        note.setStyle("-fx-text-fill: #7a4a00; -fx-font-size: 12px;");
        note.setWrapText(true);
        HBox row = new HBox(8,
                ResultView.makeInfoButton(ResultView.situationLabel(22, sig)), note);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(note, Priority.ALWAYS);
        body.getChildren().add(row);
        return true;
    }

    /**
     * Situace 55 — deklarovaný čas leží mimo dobu platnosti podpisového certifikátu. AnEP ho od
     * 4. 10. 2026 za překážku nepovažuje (pochází z hodin podepisujícího a nic nedokládá; NCA/I.CA
     * QVerify ho nezpracovává vůbec), výchozí politika DSS ale ano. Do té doby to byl důvod 34
     * a předtím mylně situace 22 („chybí signing-time“) — nález nad „rozdily v case“.
     */
    static boolean addClaimedTimeOutsideRow(VBox body, SignatureSummary sig) {
        if (!sig.getDetectedSituations().contains(55)) return false;
        StringBuilder text = new StringBuilder("Deklarovaný čas podpisu");
        if (sig.getSigningTime() != null) {
            text.append(", ").append(TimeUtil.format(sig.getSigningTime())).append(",");
        }
        text.append(" leží mimo dobu platnosti podpisového certifikátu");
        if (sig.getCertNotBefore() != null && sig.getCertNotAfter() != null) {
            text.append(" (od ").append(TimeUtil.format(sig.getCertNotBefore())).append(" do ")
                .append(TimeUtil.format(sig.getCertNotAfter())).append(")");
        }
        text.append(". AnEP to za překážku nepovažuje — deklarovaný čas pochází od podepisujícího"
                + " a sám nic nedokládá —, validátory postavené na výchozím nastavení knihovny DSS"
                + " však mohou podpis kvůli tomu odmítnout.");
        Label note = new Label(text.toString());
        note.setStyle("-fx-text-fill: #7a4a00; -fx-font-size: 12px;");
        note.setWrapText(true);
        HBox row = new HBox(8, ResultView.makeInfoButton(ResultView.situationLabel(55, sig)), note);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(note, Priority.ALWAYS);
        body.getChildren().add(row);
        return true;
    }

    static boolean addStaleRevocationRow(VBox body, SignatureSummary sig) {
        if (!sig.getDetectedSituations().contains(51) || sig.getStaleRevocationDetail() == null) {
            return false;
        }
        Label note = new Label(sig.getStaleRevocationDetail());
        note.setStyle("-fx-text-fill: #7a4a00; -fx-font-size: 12px;");
        note.setWrapText(true);
        HBox row = new HBox(8,
                ResultView.makeWarningButton(ResultView.SITUATION_LABELS.get(51)), note);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(note, Priority.ALWAYS);
        body.getChildren().add(row);
        return true;
    }

    // Form 2: Neplatný (TOTAL_FAILED) — verdict in header, problem/solution per reason
    // -------------------------------------------------------------------------

    private VBox buildInvalidCard(SignatureSummary sig, String header) {
        VBox card = new VBox(0);
        card.setStyle(CARD_STYLE);

        String headerMsg = "Proces ověřování se podařilo úspěšně dokončit. Výsledkem je zjištění, že "
                + gendered(sig, "podpis je neplatný", "pečeť je neplatná", "razítko je neplatné") + ".";
        card.getChildren().add(typeBlock(sig, header, "#cc0000", false));
        card.getChildren().add(coloredHeader(headerMsg, "#cc0000", "#ffffff"));
        addCounterSignatureBlock(card, sig);
        addCertificationBlock(card, sig);
        addPdfLockBlock(card, sig);
        addValidationCourseBlock(card, sig, "TOTAL_FAILED");

        VBox body = new VBox(8);
        body.setPadding(new Insets(10));

        List<Integer> situations = sig.getDetectedSituations();
        List<Integer> reasons    = sig.getDetectedReasons();

        // Situations that fully explain the verdict on their own: the card states the reason in
        // plain language and ends there, without the generic problem/solution blocks below.
        // Zbyla jen situace 1. Situace 2 sem chodila, dokud přepis verdiktu dělal z revokace
        // „neplatný"; od jeho zrušení končí takový podpis na kartě s neznámou platností, kde ji
        // nese důvod 9 — a výklad REASON_S2_* si bere přes getReasonDetail().
        String invalidityReason = situations.contains(1) ? REASON_S1 : null;
        if (invalidityReason != null) {
            body.getChildren().add(new Separator());
            body.getChildren().add(infoRow("Důvod neplatnosti:", invalidityReason));
            card.getChildren().add(collapsibleBlock(REASONS_BLOCK_TITLE, body, false));
            return card;
        }

        // Ptá se na vysázené důvody, ne na zjištěné situace: situací bez důvodu je dvacet ze
        // čtyřiceti osmi, a prvek, který má jen takovou, tudy propadl do výčtu, kde se pak
        // nevysázelo nic — zbyl blok s jediným oddělovačem.
        List<Integer> ordered = reasonsInDisplayOrder(sig);
        if (ordered.stream().noneMatch(ResultView.REASONS::containsKey)) {
            // „Nebyla rozpoznána" tu bývalo vždy, i když rozpoznaná je. Samostatné časové razítko
            // dostává důvody prázdné záměrně (viz ReportMapper: „failure reasons not applicable
            // to standalone timestamps"), takže neprojde ani důvod 23 o poškozeném razítku —
            // a přitom právě ten tuhle dílčí indikaci vykládá. Potkáno na
            // „jen casove razitko\priklad pouze s razitkem - kopie.pdf".
            Label msg = new Label("HASH_FAILURE".equals(sig.getSubIndication())
                    ? "Otisk, který razítko nese, neodpovídá datům, jež má krýt. Typicky jde"
                      + " o dokument, který byl změněn až poté, co k němu bylo razítko"
                      + " připojeno. Máte-li původní, nezměněnou podobu, ověřte ji."
                    : "Konkrétní příčina nebyla rozpoznána.");
            msg.setStyle("-fx-text-fill: #cc0000;");
            msg.setWrapText(true);
            body.getChildren().add(msg);
            card.getChildren().add(collapsibleBlock(REASONS_BLOCK_TITLE, body, false));
            return card;
        }

        body.getChildren().add(new Separator());

        // One block per reason: "V čem je problém:" then "Co může pomoci:", separated by lines
        boolean firstReason = true;
        java.util.Set<Integer> tipUnder = expertTipRows(sig, ordered);
        for (int rn : ordered) {
            ResultView.ReasonEntry re = ResultView.REASONS.get(rn);
            if (re == null) continue;

            if (!firstReason) body.getChildren().add(new Separator());
            firstReason = false;

            body.getChildren().add(reasonBlock(sig, rn, re, tipUnder.contains(rn)));
        }
        card.getChildren().add(collapsibleBlock(REASONS_BLOCK_TITLE, body, false));
        return card;
    }

    // -------------------------------------------------------------------------
    // Form 3: Neznámý (INDETERMINATE) — verification failed, problem + solution per reason
    // -------------------------------------------------------------------------

    private VBox buildIndeterminateCard(SignatureSummary sig, String header) {
        VBox card = new VBox(0);
        card.setStyle(CARD_STYLE);

        // Main message goes into the header strip; "? Neznámý" not shown
        String headerMsg = "Proces ověřování se nepodařilo dokončit, stav platnosti "
                + genitiveType(sig) + " tak zůstává neznámý.";
        card.getChildren().add(typeBlock(sig, header, "#9a6700", false));
        card.getChildren().add(coloredHeader(headerMsg, "#9a6700", "#ffffff"));
        addCounterSignatureBlock(card, sig);
        addCertificationBlock(card, sig);
        addPdfLockBlock(card, sig);

        VBox body = new VBox(8);
        body.setPadding(new Insets(10));

        List<Integer> situations = sig.getDetectedSituations();
        List<Integer> reasons    = sig.getDetectedReasons();

        // Rozhoduje to, co se opravdu vysází, ne to, co se zjistilo. Podmínka se ptala na
        // situace, kdežto bloky pod ní staví důvody — a situací bez důvodu je dvacet ze
        // čtyřiceti osmi. Prvek, který nějakou takovou má a žádný důvod k ní, dostal hlavičku
        // slibující výčet a pod ní prázdno. Potkáno 14. 9. 2026 na
        // „priklady 2018\T uznavany ICA s razitkem BE.pdf": situace [34], důvody žádné.
        List<Integer> ordered = reasonsInDisplayOrder(sig);
        boolean anyReasonShown = ordered.stream().anyMatch(ResultView.REASONS::containsKey);
        if (anyReasonShown) {
            Label leadIn = new Label("Aplikace detekovala následující důvod(y) neúspěšného ověření:");
            leadIn.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
            leadIn.setWrapText(true);
            body.getChildren().add(leadIn);
            body.getChildren().add(new Separator());
        } else {
            // Mlčet by bylo horší: blok se jmenuje „Zjištěné důvody a doporučení" a prázdný
            // vypadá jako závada zobrazení. Věta říká, co se stalo, a posílá tam, kde se
            // čtenář dozví víc — týž postoj jako „Konkrétní příčina nebyla rozpoznána."
            // na kartě s prokázanou neplatností.
            Label none = new Label("Konkrétní příčinu se nepodařilo pojmenovat. Jak ověřování"
                    + " probíhalo a kde skončilo, stojí v bloku „Průběh ověřování“ níže.");
            none.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
            none.setWrapText(true);
            body.getChildren().add(none);
        }

        // One block per reason: "V čem je problém:" then "Co může pomoci:", separated by lines
        boolean firstReason = true;
        java.util.Set<Integer> tipUnder = expertTipRows(sig, ordered);
        for (int rn : ordered) {
            ResultView.ReasonEntry re = ResultView.REASONS.get(rn);
            if (re == null) continue;

            if (!firstReason) body.getChildren().add(new Separator());
            firstReason = false;

            body.getChildren().add(reasonBlock(sig, rn, re, tipUnder.contains(rn)));
        }
        // Pořadí bloků je tu jiné než na kartách s rozhodnutým výsledkem, a to schválně. Ke kartě
        // s neznámou platností přichází čtenář s jedinou otázkou — proč to nedopadlo a co s tím —
        // takže odpověď na ni stojí první a rozbalená. Teprve za ní je průběh ověřování (jak se
        // k tomu došlo), formát (vlastnost prvku, ne nález) a ladicí informace, které přidává
        // volající. Kdo se ptá na formát, najde ho i o dva bloky níž; kdo se ptá na důvod,
        // neměl by ho hledat vůbec.
        card.getChildren().add(collapsibleBlock(REASONS_BLOCK_TITLE, body, true));
        card.getChildren().add(blockSeparator());
        addValidationCourseBlock(card, sig, "INDETERMINATE");
        addResultNotesBlock(card, sig);
        addOtherValidatorsBlock(card, sig);
        addFormatBlock(card, sig);
        return card;
    }

    /**
     * Důvody, u nichž je překážkou chybějící použitelný revokační údaj — tedy přesně to, co může
     * mít jiný ověřovatel ve vlastní zásobě a čím může dojít dál. Táž sedmice jako
     * {@link ResultView#EXPERT_CRL_TIP_REASONS}, jen rozšířená o důvody 24 a 30: rada „sežeňte
     * CRL z vhodné doby“ a nabídka „zkuste ověřovatele, který nějaké má“ míří na totéž.
     *
     * <p>Tenhle výčet zůstává v kódu, kdežto ten druhý se od 15. 9. 2026 řídí souborem
     * ({@code zkusenejsi} u důvodu). Kdo tam radu dopíše k dalšímu důvodu, měl by zvážit,
     * jestli nepatří i sem — samo se to nepropíše.
     *
     * <p>Rozhoduje se podle <b>důvodů</b>, ne podle situací, a to je měřené rozhodnutí. Situace
     * 5 až 9 (a novější 45, 46, 47) říkají jen tolik, že s revokačním údajem něco není v pořádku;
     * neříkají, že je to <em>ta</em> překážka. Na sbírce D:\Downloads by se blok podle situací
     * ukázal u 81 % podpisů s neznámou platností, mezi nimi u stovek dokumentů ve formátu PKCS #7
     * nebo PDF-NOT-ETSI, které drží úplně jiná vada — a čtenáře by poslal na CzechPOINT s tím, že
     * tam to vyjde. Potlačovací tabulka tohle rozhodnutí dělá už dnes a je vyladěná měřením:
     * je-li indikován důvod 18 (chybí signing-time), důvod 2 se potlačí, a s ním má zmizet i tahle
     * nabídka.
     */
    static final java.util.Set<Integer> REVOCATION_GAP_REASONS =
            java.util.Set.of(2, 6, 10, 11, 12, 24, 25, 29, 30);

    /**
     * Důvody, při kterých se blok neukáže, i kdyby některý z výše uvedených svítil zároveň.
     *
     * <p>Důvod 9 a 22 říkají, že revokační údaj <b>je</b> a prokazuje odvolání: ověřovatel
     * s vlastní zásobou by nedošel k platnosti, ale k neplatnosti, a slib „mohli by platnost
     * prokázat“ by byl nepravdivý. Důvody 8 (nesprávná dvojice soubor + podpis) a 13 (podpis
     * vznikl před začátkem platnosti certifikátu) jsou vady, které žádný revokační údaj nespraví;
     * potlačovací tabulka je nepotlačuje, takže je nutné je vyjmenovat tady.
     *
     * <p>Důvod 20 — podpis vznikl až po expiraci certifikátu, a je to doloženo časovým razítkem,
     * ne jen tvrzeno. Tolerantní ověřovatel čte rozhodný okamžik shovívavěji, ale ani on nemůže
     * uznat podpis, který prokazatelně vznikl mimo dobu platnosti; revokační zásoba s tím nehne.
     * V tabulce potlačení sedí ze stejného důvodu nad důvody 2 a 25. Sám o sobě by sem nemusel —
     * ty důvody umlčí — jenže situace 45 spouští blok i bez nich, a bez tohohle řádku by se
     * nabídl na „s pretocenim casu do platnosti.pdf“, dokumentu podepsaném po expiraci s ručně
     * přetočenými hodinami.
     *
     * <p>Důvod 3 — řetězec certifikátů nelze sestavit k důvěryhodné kotvě. Revokační zásoba je
     * tu k ničemu: stav certifikátu nelze ověřit, dokud není po ruce jeho vydavatel, a zásoba
     * revokačních dat vydavatele nenahradí. (Důvody 2 a 12 trojku potlačují, takže když přežije,
     * je to opravdu ona hlavní překážka.)
     *
     * <p>Důvod 16 — kriticky nedůvěryhodná hašovací funkce (MD5, RIPEMD-160). Tu neuzná ani
     * tolerantní ověřovatel; na rozdíl od SHA-1, kterou zdejší ověřování umí odpustit a cizí
     * zpravidla také, proto důvod 15 mezi beznadějnými není. Šestnáctka potlačuje revokační
     * důvody, takže by se sem sama nedostala — jenže situace 45 spouští blok i bez nich.
     *
     * <p>Důvod 35 — hashovací funkce, kterou standardy nepřipouštějí (SHA3-224). Pokyn uživatele
     * 4. 10. 2026: u ní ověření jinde nenabízet, protože CzechPOINT ani podatelny rodinu SHA3
     * zatím nepodporují (viz {@link ContinuityHorizon#unsupportedDigest}). Důvod 35 umlčuje
     * revokační rady, takže se sem dostane jen přes situaci 45 — stejně jako šestnáctka.
     */
    static final java.util.Set<Integer> HOPELESS_REASONS =
            java.util.Set.of(3, 8, 9, 13, 16, 20, 22, 35);

    /**
     * Dvě beznadějné situace, které žádný důvod nevydávají, a přes {@link #HOPELESS_REASONS} by
     * tedy neprošly.
     *
     * <p>Situace 13 — podepsaná data se nenašla; u odděleného podpisu bez souboru nemá co ověřovat
     * ani ověřovatel s sebevětší zásobou revokačních dat. Zachyceno na
     * {@code 1-signed-cades-baseline-lt.p7s}, kde je vedle ní indikován důvod 6 a blok by se nabídl.
     *
     * <p>Situace 44 — revokace stojí v cestě; certifikát někde v řetězci je odvolaný. Důvod 9
     * ani 22 přitom vzniknout nemusí (odvolaná bývá i certifikační autorita, což hlásí situace 11
     * a důvod 14), takže samotné důvody na tenhle případ nestačí.
     */
    static final java.util.Set<Integer> HOPELESS_SITUATIONS = java.util.Set.of(13, 44);

    /**
     * „Kde je šance na úspěšné ověření“ — blok pro podpis, jehož platnost zůstala neznámá jen
     * proto, že se nesehnal použitelný revokační údaj.
     *
     * <p>Aplikace ověřuje tím, co si k dokumentu dokáže obstarat teď a ze sítě. Ověřovatelé
     * s vlastní zásobou revokačních dat jsou na tom u starších podpisů lépe — mají seznamy z doby,
     * kdy je vydavatel ještě zveřejňoval. Čtenáři, kterému aplikace nedokázala odpovědět, tak
     * zbývá cesta, o které by se jinak nedozvěděl.
     *
     * <p>Zabalený schválně: je to odbočka od výsledku, ne jeho část.
     */
    private void addOtherValidatorsBlock(VBox card, SignatureSummary sig) {
        // Větev pro situaci 54 tu byla, dokud odvozený důkaz jen doprovázel neznámý výsledek.
        // Od chvíle, kdy ten důkaz verdikt nese, je prvek platný a blok o naději u něj nemá co
        // dělat; vysvětlení nese poznámka k výsledku.
        if (!shouldOfferOtherValidators(sig)) return;

        VBox block = new VBox(8);
        block.setPadding(new Insets(8, 12, 10, 12));
        if (revocationStoreMayHelp(sig)) {
            block.getChildren().add(unlabelledRow(
                    "Jedním z důvodů, kvůli kterým zůstal stav platnosti "
                    + genitiveType(sig) + " neznámý, je nedostupnost využitelných revokačních"
                    + " informací. Existují ale služby pro ověřování (validátory) s vlastní"
                    + " zásobou revokačních informací, které by z ní mohly čerpat a platnost "
                    + genitiveType(sig) + " úspěšně prokázat."));
        }
        if (tolerantTimestampMayHelp(sig)) {
            block.getChildren().add(unlabelledRow(tolerantTimestampText(sig)));
        }
        // Společný závěr, ne konec jedné z vět: obou cest se týká týž okruh nástrojů a dvakrát
        // vypsávaný by čtenář četl jako dva různé seznamy.
        block.getChildren().add(unlabelledRow(
                "Jde například o CzechPOINTy, službu SecuSign, program Signer, řadu podatelen"
                + " OVM a další."));

        card.getChildren().add(collapsibleBlock("Kde je šance na úspěšné ověření", block, false));
        card.getChildren().add(blockSeparator());
    }

    /**
     * Rozhodnutí, zda blok „Kde ověření nebude úspěšné“ dává smysl — zrcadlo
     * {@link #shouldOfferOtherValidators}: tam aplikace neví a jinde uspět mohou, tady aplikace
     * uspěla a jinde neuspějí.
     *
     * <p>Nastane, když se sejdou čtyři věci: výsledek je kladný, podpisový certifikát už
     * expiroval, řetěz razítek je nepřerušený (situace 31) a poslední platné razítko už není
     * kryté vlastním certifikátem (chybí situace 32). Poslední dvě dohromady znamenají, že
     * razítko obstálo <b>jen</b> díky sunsetu — tedy proto, že razítkovací služba má
     * v důvěryhodných seznamech dosud kvalifikovaný status, ačkoli certifikátu, na kterém razítko
     * stojí, řádná doba platnosti už skončila.
     *
     * <p>Bez situace 31 by kladný výsledek stál na něčem jiném a věta o razítku by nesouhlasila;
     * bez expirace podpisového certifikátu by ověření prošlo i bez pomoci razítka.
     *
     * <p>Kladný verdikt je součástí podmínky, ne jen důsledkem toho, kde se metoda volá. Při
     * vypnutém „Ověřování časových razítek podle standardů“ si totiž takový podpis sníží na
     * neznámý sama aplikace — a pak se s tuzemskými validátory nerozchází a není co hlásit.
     * Předává se zobrazený verdikt, ne {@code sig.getIndication()}: ten o zpřísnění neví.
     */
    public static boolean shouldWarnSunsetOnly(SignatureSummary sig, String displayedIndication) {
        if (!"TOTAL_PASSED".equals(displayedIndication)) return false;
        java.util.List<Integer> situations = sig.getDetectedSituations();
        if (!situations.contains(31) || situations.contains(32)) return false;
        java.time.Instant certUntil = sig.getCertNotAfter();
        return certUntil != null && !certUntil.isAfter(java.time.Instant.now());
    }

    /**
     * „Kde ověření nebude úspěšné“ — kladný výsledek, který stojí na sunsetu, a tedy na čtení
     * standardů, které řada tuzemských validátorů nesdílí.
     *
     * <p>Čtenář odchází s tím, že podpis je platný. Předloží-li týž dokument CzechPOINTu, dostane
     * neznámou platnost — ne proto, že by se jedna ze stran mýlila, ale proto, že se liší v tom,
     * podle čeho se posuzuje platnost časového razítka. Tuhle nepříjemnost je lepší se dozvědět
     * tady než u přepážky.
     *
     * <p>Zabalený schválně: výsledek to nemění, jen říká, kde se o něm bude smýšlet jinak.
     */
    private void addSunsetOnlyBlock(VBox card, SignatureSummary sig) {
        // Karta se staví jen pro kladný verdikt, takže ho sem lze předat natvrdo.
        if (!shouldWarnSunsetOnly(sig, "TOTAL_PASSED")) return;

        String genitiv = genitiveType(sig);
        VBox block = new VBox(8);
        block.setPadding(new Insets(8, 12, 10, 12));

        block.getChildren().add(unlabelledRow(
                "Aplikace AnEP dokázala ověřit platnost " + genitiv + " díky tomu, že platnost"
                + " časových razítek hodnotí v souladu s technickými standardy podle tzv. sunsetu."
                + " Tedy podle toho, zda služba, v rámci které bylo (kvalifikované elektronické)"
                + " časové razítko vydáno, je na důvěryhodných seznamech stále uvedena jako služba"
                + " s kvalifikovaným statusem. To znamená, že časové razítko může být hodnoceno"
                + " jako platné i přesto, že již expiroval certifikát, na kterém je časové razítko"
                + " založeno (že tomuto certifikátu již skončila řádná doba jeho platnosti)."));

        block.getChildren().add(unlabelledRow(
                "V případě právě ověřovaného " + genitiv + " mohlo být díky tomu poslední"
                + " (kvalifikované elektronické) časové razítko ověřeno jako platné a posloužilo"
                + " jako důkaz existence " + genitiv + " v dřívější době. To umožnilo ověřit "
                + gendered(sig, "samotný podpis jako platný", "samotnou pečeť jako platnou",
                          "samotné razítko jako platné") + "."));

        block.getChildren().add(unlabelledRow(
                "Řada nástrojů a služeb pro ověřování elektronických podpisů (tzv. validátorů),"
                + " jako např. CzechPOINTy, služba SecuSign, program Signer, podatelny OVM"
                + " a další, ale hodnotí platnost časových razítek striktně podle platnosti"
                + " certifikátů, na kterých jsou razítka založena — a jakmile tato platnost"
                + " skončí, časové razítko již nehodnotí jako platné."));

        block.getChildren().add(unlabelledRow(
                "V případě právě ověřovaného " + genitiv + " by takovéto validátory nevyhodnotily"
                + " jako platné žádné z časových razítek, a tudíž by je nemohly využít jako důkaz"
                + " pro existenci " + gendered(sig, "samotného podpisu", "samotné pečeti",
                          "samotného razítka")
                + " v dřívějším čase. Vzhledem k tomu by ani "
                + gendered(sig, "samotný podpis nevyhodnotily jako platný",
                          "samotnou pečeť nevyhodnotily jako platnou",
                          "samotné razítko nevyhodnotily jako platné")
                + " — ale " + gendered(sig, "jeho", "její", "jeho")
                + " platnost by zůstala neznámá."));

        card.getChildren().add(collapsibleBlock("Kde ověření nebude úspěšné", block, false));
        card.getChildren().add(blockSeparator());
    }

    /**
     * Rozhodnutí, zda blok „Kde je šance na úspěšné ověření“ dává smysl — čistá funkce nálezu,
     * oddělená od stavby karty, aby šla změřit na sbírce ({@code SituationScan -otherval}).
     *
     * <p>Dvě společné závory a za nimi <b>dvě cesty</b>. Závory: nesmí být indikován důvod ani
     * situace, se kterou nepomůže nikdo ({@link #hopelessAnywhere}), a nesmí být pozdě
     * ({@link #isTooLate}). Cesty:
     *
     * <ul>
     *   <li>{@link #revocationStoreMayHelp} — překážkou je revokační údaj, buď podle důvodů,
     *       nebo podle situace 45. Původní a dodnes častější.</li>
     *   <li>{@link #tolerantTimestampMayHelp} — překážkou je časové razítko, které zdejší
     *       přísné ověření nepustí, ale tolerantnější ověřovatel ano. Přibyla 15. 9. 2026
     *       z vnějšího potvrzení — viz tam.</li>
     * </ul>
     *
     * <p>Která cesta zabrala, rozhoduje i o tom, co v bloku stojí: věta o revokační zásobě nad
     * podpisem, kterému revokační údaj nechybí, by čtenáře poslala hledat vadu, která tam není.
     *
     * <p>Situace 45 spouští blok i sama, bez revokačního důvodu. Znamená totiž přesně tohle: údaje
     * k dispozici jsou, jen v podobě, kterou přísná pravidla nepřipouštějí — a její vlastní popis
     * jmenuje CzechPOINT a podatelny jako ty, kdo je uznávají. Bez toho ji umlčel důvod 21, kdykoli
     * žádné razítko neposloužilo jako důkaz existence: potlačovací tabulka je laděná na to, co zmůže
     * zdejší ověřování, kdežto tolerantní ověřovatel bere deklarovaný čas podpisu a uspět může.
     *
     * <h2>Vlastní kotva důvěry je čtvrtá podmínka, a je to veto</h2>
     *
     * <p>Predikát mluví za ověřovatele, který má <b>vlastní zásobu revokačních informací</b> — ne
     * vlastní zásobu kotev důvěry. Stojí-li zdejší závěr na certifikátu z {@code CustomTrustStore},
     * který evropské důvěryhodné seznamy neuvádějí, chybí tomu ověřovateli něco jiného než
     * revokační údaj a zásoba mu to nedá. Odpověď „šance je" je u takového prvku slib, který
     * nikdo nesplní.
     *
     * <p><b>Bylo to vidět na jedné kartě naráz.</b> Táž otázka se totiž rozhoduje ještě jednou,
     * v {@code ContinuityHorizon.tolerant()} (vlastní kotva → {@code LOST}) a v textu, který z toho
     * píše {@code lostForTolerant}. Nad „PAdES T zaruceny ICA.pdf" — pouze zaručený podpis
     * ověřený jen díky připnutému kořeni I.CA — tedy stál oranžový blok „CzechPOINT, podatelny
     * a další validátory platnost dnes již ověřit nedokáží", nad ním věta „Nástroj s vlastní
     * zásobou revokačních informací je na tom ale jinak; o tom je blok níže", pod ním nabídka
     * úkonu a v ní slib, že „platnost půjde ověřit jen na CzechPOINTu, na podatelnách…".
     * Tři místa proti jednomu, a to jedno mělo pravdu.
     *
     * <p>Precedens je o pár set řádků výš: {@link #toleratedRevocationWithExpiredTsu} tenhle test
     * už má, se zdůvodněním „takový podpis neuzná jiný nástroj ani tehdy, když obě čtení
     * zkombinuje" — a naměřený na téže rodině (podpisy 4 a 5 souboru {@code pet podpisu LT.pdf}).
     *
     * <p><b>Co tím padá a co ne.</b> Padá výjimka v {@link #continuityObstacle}, kterou prvek
     * s nekladným verdiktem procházel do nabídky: bez ní se mu úkon nenabídne a zbude forenzní
     * balíček, který o kontinuitě nic netvrdí. Nepadá nabídka prvku s <b>kladným</b> verdiktem
     * a vlastní kotvou — ta jde první branou (verdikt) a zůstává; o tom, proč se u přerazítkování
     * vlastní kotva nevetuje, je vlastní odstavec v {@code show()}. Mění se tam jen text, který
     * si nesmí vymýšlet, kdo výsledek ověří.
     */
    public static boolean shouldOfferOtherValidators(SignatureSummary sig) {
        if (hopelessAnywhere(sig)) return false;
        if (isTooLate(sig)) return false;
        return revocationStoreMayHelp(sig) || tolerantTimestampMayHelp(sig);
    }

    /** Vady, se kterými nepomůže žádný ověřovatel — veto pro obě cesty. */
    private static boolean hopelessAnywhere(SignatureSummary sig) {
        if (sig.getDetectedReasons().stream().anyMatch(HOPELESS_REASONS::contains)) return true;
        if (sig.getDetectedSituations().stream().anyMatch(HOPELESS_SITUATIONS::contains)) {
            return true;
        }
        // Otisk z rodiny SHA3 — i SHA3-256 a vyšší, které AnEP ověří. Ty nástroje, na které
        // nabídka míří především, je zatím nepodporují (pokyn uživatele 4. 10. 2026).
        if (ContinuityHorizon.unsupportedDigest(sig)) return true;
        return sig.isTrustedViaCustomStore();
    }

    /** První cesta: chybí použitelný revokační údaj a cizí zásoba ho může mít. */
    private static boolean revocationStoreMayHelp(SignatureSummary sig) {
        return sig.getDetectedSituations().contains(45)
                || sig.getDetectedReasons().stream().anyMatch(REVOCATION_GAP_REASONS::contains);
    }

    /**
     * Druhá cesta: překážkou není revokační údaj, nýbrž <b>časové razítko, které jinde projde</b>.
     *
     * <p>Naměřeno na {@code priklad6_2_Razitko_do_03_08_2034.pdf} 15. 9. 2026: podpis ze
     * 20. 1. 2012 se čtyřmi razítky, ze kterých zdejší ověření dvě nejstarší nepřijalo — stojí
     * na SHA-1 (situace 15) a vydala je služba, které byl v důvěryhodných seznamech odebrán
     * kvalifikovaný status (situace 43). <b>Úschovna Czech POINT týž dokument ověřila jako
     * platný</b> a razítko z roku 2012 přitom vypsala jako platné; opřela se tedy právě o to,
     * které zdejší ověření odmítlo. Ani jedna z obou překážek není revokační, takže dosavadní
     * podmínka blok neotevřela a čtenář se o té cestě nedozvěděl nic.
     *
     * <p><b>Doloženo téhož dne na celém výběru.</b> Pravidlo přidá na sbírce D:/Downloads
     * 44 nabídek (36 PAdES-LTA a 8 PDF-NOT-ETSI, ve 43 souborech) a uživatel je projel
     * Úschovnou: <b>všechny se ověří jako platné</b>. To je na tenhle predikát výjimečně
     * silné potvrzení: revokační cesta vedle něj stojí na běhu ze září 2026, kde predikce
     * sedla u 14 z 16 — ale její KLADNÁ odpověď jen u 2 ze 3, viz {@link #isTooLate}.
     * Platí to ovšem o jedné rodině dokumentů: uchovávané a přerazítkované
     * podpisy I.CA a PostSignum z let 2012–2015. Šest ze 44 nemá situaci 43 a stojí jen na
     * SHA-1, takže o samotné toleranci k otisku vypovídá výběr slabeji než o toleranci
     * k odebranému statusu.
     *
     * <h2>Ptá se na příčinu selhání razítka, ne na výčet situací</h2>
     *
     * <p>Rozhoduje razítko, které je <b>neporušené</b> ({@code intact}) a přesto neobstálo,
     * a to z jedné ze dvou příčin, které tolerantnější čtení odpouští:
     * {@link SignatureSummary.TimestampFailure#CRYPTO} (zastaralý otisk) a
     * {@link SignatureSummary.TimestampFailure#EXPIRED_NO_POE} (certifikátu autority skončila
     * platnost a nic nedokládá, že razítko vzniklo ještě za ní). {@code BROKEN}, {@code NO_CHAIN}
     * a {@code REVOKED} sem nepatří: porouchaný otisk neuzná nikdo, řetěz bez kotvy nikomu
     * nedoplní shovívavost a odvolaný certifikát už nezachrání vůbec nic.
     *
     * <p>Situace 23 je veto: tam razítkovací služba <b>v důvěryhodných seznamech nikdy nebyla</b>.
     * Podle uživatele je právě tohle dělící čára: tuzemské validátory směřují shovívavost ke
     * službě, která kvalifikovaný status (dříve akreditaci) kdysi měla a později ho pozbyla —
     * ne ke službě, kterou seznamy neznají vůbec.
     *
     * <h2>{@link #isTooLate} platí i tady, a je to záměr</h2>
     *
     * <p>Svádí to k úvaze, že by se ten test měl na téhle cestě vynechat: ptá se, jestli je
     * rozhodný okamžik ukotvený v minulosti, a ukotvuje ho podle razítek, která obstála
     * <b>tady</b> — kdežto tolerantnější ověřovatel by se opřel i o to odmítnuté. Změřeno:
     * bez něj přibudou na D:\tmp čtyři nabídky místo jedné a tři z nich jsou dokumenty, jejichž
     * řetěz razítek není ukotvený vůbec (situace 4 a 27 bez jediného platného razítka) — tedy
     * přesně ten tvar, který už jednou změřený běh přes Úschovnu vyřadil a k jehož vyřazení
     * je v {@link #isTooLate} vlastní odstavec.
     *
     * <p>Ani jedno ze 44 potvrzení ten tvar nemá — všechna leží <b>uvnitř</b> té závory, takže
     * o dokumentech, které vylučuje, neříkají nic. Cesta zůstává záměrně úzká.
     *
     * <p><b>A otázka je zodpovězená.</b> Bez té závory přibudou na D:/tmp přesně tři dokumenty
     * s nekladným verdiktem a uživatel je 15. 9. 2026 projel Úschovnou: <b>ani jeden z nich
     * se ověřit nedá</b>.
     *
     * <ul>
     *   <li>{@code kratsi razitko\LT s podpisem delsim nez je casove razitko.pdf}</li>
     *   <li>{@code Q ICA pred expiraci 07 2019\PAdES LT tesne vizualizovany.pdf}</li>
     *   <li>{@code Q ICA pred expiraci 07 2019\PAdES T tesne vizualizovany.pdf}</li>
     * </ul>
     *
     * <p>Dohromady s těmi 44 výše to dává řez, jaký zdejší predikáty jinak nemívají:
     * uvnitř závory 44 z 44 uznáno, vně ní 0 ze 3. Závora tedy není opatrnost zděděná
     * z jiného měření, nýbrž <b>změřená dělící čára</b>. Nepovolovat ji.
     */
    private static boolean tolerantTimestampMayHelp(SignatureSummary sig) {
        // Ověřování muselo na něčem uváznout. Bez téhle podmínky se cesta otevřela i prvku
        // s kladným výsledkem, který jen vedle platných razítek nese ještě jedno odmítnuté —
        // naměřeno na {@code kratsi razitko\LT s podpisem delsim nez je casove razitko
        // extended.pdf}, jediném rozdílu na celé sbírce D:/tmp. Blok se u něj sice nestaví
        // (je jen na kartě s neznámou platností), ale predikát čte ještě brána nabídky
        // a tvrdit o platném podpisu „jinde by mohli uspět“ nemá smysl.
        if (sig.getDetectedReasons().isEmpty()) return false;
        if (sig.getDetectedSituations().contains(23)) return false;
        return sig.getAllTimestamps().stream().anyMatch(t -> !t.passed() && t.intact()
                && (t.failureCause() == SignatureSummary.TimestampFailure.CRYPTO
                        || t.failureCause()
                                == SignatureSummary.TimestampFailure.EXPIRED_NO_POE));
    }

    /**
     * Co v bloku stojí o druhé cestě. Jmenuje jen ty překážky, které prvek opravdu má: věta
     * o SHA-1 nad podpisem bez SHA-1 by čtenáře poslala hledat vadu, která tam není.
     */
    private static String tolerantTimestampText(SignatureSummary sig) {
        boolean withdrawn = sig.getDetectedSituations().contains(43);
        boolean sha1 = sig.getDetectedReasons().contains(15);
        StringBuilder sb = new StringBuilder("Zdejší ověřování se drží standardů i tam, kde jsou"
                + " přísnější než praxe. Platnost " + genitiveType(sig) + " tu zůstala neznámá"
                + " mimo jiné kvůli časovému razítku, které je samo o sobě neporušené");
        if (withdrawn && sha1) {
            sb.append(", vydala je ale služba, které byl v důvěryhodných seznamech kvalifikovaný"
                    + " status později odebrán, a stojí navíc na zastaralém otisku SHA-1.");
        } else if (withdrawn) {
            sb.append(", vydala je ale služba, které byl v důvěryhodných seznamech kvalifikovaný"
                    + " status později odebrán.");
        } else if (sha1) {
            sb.append(", stojí ale na zastaralém otisku SHA-1.");
        } else {
            sb.append(" — neprošlo jen proto, že nic nedokládá, že vzniklo ještě za platnosti"
                    + " certifikátu své razítkovací autority.");
        }
        // Závěrečná věta zněla „Jistota to není, ale stojí za to to zkusit", dokud za cestou
        // stál jediný doložený dokument. Poté, co uživatel projel Úschovnou celý výběr
        // a všech 44 se ověřilo jako platných, je taková opatrnost už podceňování — čtenáře
        // by od cesty odrazovala víc, než je na místě. Slib to ale pořád není: doložená je
        // jedna rodina dokumentů, ne všechno, co tím predikátem projde.
        sb.append(" Tuzemské validátory bývají v tomhle shovívavější: razítko od služby, která"
                + " kvalifikovaný status (dříve akreditaci) kdysi měla, uznávají, a zastaralost"
                + " SHA-1 jim zpravidla nevadí. U dokumentů téhle podoby to vychází — ověřeno"
                + " na Úschovně Czech POINT —, i když záruka to není. Stojí za to to zkusit.");
        return sb.toString();
    }

    /**
     * Je na cizí zásobu revokačních dat pozdě?
     *
     * <p>Zásoba pomůže jen tomu, u koho revokační údaj je poslední chybějící dílek. Když prvek
     * neunese ani sám sebe, nespraví to žádný ověřovatel — a nabídka by čtenáře poslala na cestu,
     * ze které se vrátí s týmž výsledkem.
     *
     * <p>Rozhoduje poslední článek, kterým prvek visí v čase:
     * <ul>
     *   <li>Je-li tu platné časové razítko, je jím <b>certifikát jeho razítkovací autority</b>.
     *       Dokud platí, je rozhodný okamžik ukotven v minulosti a tolerantní ověřovatel má
     *       k čemu revokační údaj vztáhnout. Jakmile vyprší, padá ukotvení a s ním i šance.</li>
     *   <li>Není-li žádné platné razítko (úroveň B, nebo T se samými neplatnými razítky), je jím
     *       <b>podpisový certifikát</b>. Rozhodný okamžik pak leží v přítomnosti, a je-li k ní
     *       certifikát po expiraci, není revokační údaj to, co chybí.</li>
     * </ul>
     *
     * <p>Vidět na „dva podpisy chybi revokace.pdf“: situace 45 říká, že revokační údaje jsou
     * k dispozici v tolerantní podobě, jenže certifikáty razítkovacích autorit už vypršely
     * (situace 4) a žádné razítko neslouží jako důkaz existence (situace 27). Blok se tam
     * nabízel a neměl.
     *
     * <p>Je to táž úvaha, jakou vede záložka Digitální kontinuita přes situace 31 a 32; tady se
     * počítá z časových razítek přímo, protože 31 a 32 se vydávají jen za dalších podmínek.
     *
     * <p><b>Přísnost je ověřená v praxi, nepovolovat ji.</b> Na sbírce D:\tmp propadne nabídka
     * ze 101 podpisů na 6 ze 172 s neznámou platností, což svádí k úvaze, že je pravidlo příliš
     * tvrdé — vždyť tolerantní ověřovatel si vezme deklarovaný čas podpisu a na expiraci
     * certifikátu razítkovací autority se neptá. Změřeno však na <b>pěti dokumentech</b>, které
     * pravidlo vyřadilo, a ani jeden z nich cizí ověřovatel neuznal:
     *
     * <ul>
     *   <li>podpis z roku 2023 s razítkem po expiraci — odmítl CzechPOINT</li>
     *   <li>{@code 2026 08 09\LT uznavany.sce} — odmítl program Signer</li>
     *   <li>{@code kratsi razitko\LT s podpisem delsim nez je casove razitko.pdf}, a dál
     *       {@code Q ICA pred expiraci 07 2019\PAdES LT tesne vizualizovany.pdf} a
     *       {@code …\PAdES T tesne vizualizovany.pdf} — všechny tři odmítl CzechPOINT
     *       (15. 9. 2026). Jsou to právě ty, které by přibyly, kdyby se tenhle test vynechal
     *       u {@link #tolerantTimestampMayHelp} — viz tam.</li>
     * </ul>
     *
     * <p>Volnější varianta („stačí, aby certifikát TSA platil v době, kdy razítko vzniklo“) by
     * tedy slibovala něco, co ti ověřovatelé nesplní.
     *
     * <h2>Co je změřené a co ne</h2>
     *
     * <p>Dne 2. 9. 2026 prošlo Úschovnou Czech POINT 18 dokumentů z D:\tmp, vybraných tak, aby
     * polovině tenhle predikát úspěch předpovídal a polovině ne. Dva z nich měří něco jiného
     * (úmyslný podvrh a dokument, u kterého Úschovna hlásí „není podepsán“, ač podpisové pole má),
     * takže vypovídajících je 16. <b>Predikce sedla u 14</b> — u 12 ze 13, kterým věštila neúspěch,
     * a u 2 ze 3, kterým věštila úspěch. Na to, aby pravidlo sloužilo jako veto, to stačí; na
     * to, aby se z jeho kladné odpovědi dal odvozovat slib, ne.
     *
     * <p><b>Mechanismus tím ale doložený není.</b> Věta, kterou se tenhle predikát odjakživa
     * zdůvodňoval — že ty nástroje posuzují razítka výhradně podle řádné doby platnosti jejich
     * certifikátů —, se v hláškách Úschovny nepotvrdila: razítko z roku 2018 s certifikátem
     * propadlým v srpnu 2023 označila za <b>platné</b>, kdežto razítko z roku 2019 s certifikátem
     * propadlým v listopadu 2024 za neplatné, a v jednom případě uznala podpis, jehož razítko sama
     * prohlásila za neplatné. Na šest ze sedmi hlášek sedí spíš tohle: razítko s propadlým
     * certifikátem uznají, pokud <b>pozdější razítko dokládá, že existovalo, dokud certifikát
     * platil</b> — tedy táž úvaha jako situace 31 a 32. Jistota to není, sedm hlášek je málo.
     *
     * <p><b>Dvě známé meze.</b> Obě chyby z těch 16 leží v tom, <i>které</i> razítko se do řetězu
     * počítá, a míří proti sobě:
     * <ul>
     *   <li>{@code integrita\porusena integrita casoveho razitka.pdf} — Úschovna dokument uznala.
     *       Zdejší výběr bere nejnovější <b>platné</b> razítko, tedy podpisové s certifikátem
     *       propadlým 27. 10. 2025, a minul dokumentové s certifikátem do 23. 10. 2026, jehož
     *       otisk ale nesedí. Vypadá to, že si toho rozbitého otisku nevšimli.</li>
     *   <li>{@code casove razitko v dokumentu\PAdES uznavany 2019 s razitkem v dokumentu.pdf} —
     *       Úschovna dokument odmítla se slovy, že podpis „není označen časovým razítkem“, ačkoli
     *       razítko z roku 2025 vypsala jako <b>samostatné</b>. Vysvětlovalo se to tím, že
     *       dokumentové razítko za důkaz existence podpisu nepovažuje; <b>18. 9. 2026 to bylo
     *       vyvráceno</b>. Nad {@code bajecny svet\2023\05 02\PAdES B-B a dokumentove
     *       razitko.pdf} — podpis rovněž bez vlastního razítka, dokumentové z 2. 5. 2023,
     *       podpisový certifikát do 25. 5. 2023 — Úschovna dokument <b>uznala</b>. Ty dva se liší
     *       jediným: tady je článkem uvnitř platnosti certifikátu razítko <b>VRI</b> (8. 10. 2019),
     *       kdežto tam dokumentové. Razítko VRI leží v nepodepsané přírůstkové aktualizaci
     *       a jejich rozbor, který vypisuje podpisové slovníky, ho nevidí. Čtení pro tenhle okruh
     *       ({@code ContinuityHorizon.strictChain}) proto razítka VRI od téhož dne vylučuje —
     *       zdejší predikát se tím ale neřídí, viz níž.</li>
     * </ul>
     *
     * <p>Zúžit výběr na podpisové razítko by první chybu neopravilo a druhou vyměnilo za novou:
     * {@code kratsi razitko\LT s podpisem delsim nez je casove razitko extended.pdf} má podpisové
     * razítko, které Úschovna sama zavrhla, a dokument přesto uznala. Vyšlo by to zase 14 ze 16.
     * Proto zůstává, jak je.
     *
     * <p>A vyloučit razítka VRI i tady se <b>záměrně nedělá</b>, ačkoli by to druhou chybu
     * opravilo: zdejší predikát je závora, jejíž šířka je změřená (44 ze 44 uvnitř uznáno, 0 ze 3
     * vně) a zúžení může některý z těch 44 ztratit. Cena je jedna známá chyba proti riziku, které
     * nikdo neměřil. Blok o dosahu si razítka VRI odfiltruje sám a na tenhle predikát nesahá.
     *
     * <p><b>Past při čtení jejich hlášek.</b> Úschovna nezná výsledek „platnost je neznámá“
     * a hlásí ho jako „neplatný“, u razítek stejně. Z věty „podpis je neplatný“ se tedy nedá
     * usuzovat, že něco odhalili — může to znamenat i to, že na tom byli stejně jako AnEP.
     */
    private static boolean isTooLate(SignatureSummary sig) {
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant tsuEnd = validTimestampChainReach(sig);
        if (tsuEnd != null) return !tsuEnd.isAfter(now);
        java.time.Instant certUntil = sig.getCertNotAfter();
        return certUntil == null || !certUntil.isAfter(now);
    }

    /**
     * Důvody, které odstraní některý ze čtyř ústupků (revokační údaj po expiraci, expirovaný
     * vydavatel revokace, SHA-1, PDF-NOT-ETSI). Změřeno {@code ReasonOrderScan} 25. 9. 2026 na
     * 2 784 souborech ({@code D:\tmp}, {@code D:\Downloads}, reálné podpisy z praxe), každý ověřen
     * přísně a se všemi ústupky: tyhle nepřežily ústupky ani jednou (15: 0 z 206, 2: 0 ze 122,
     * 6: 0 ze 123, 1: 0 z 29, 11: 0 ze 4, 30: 0 ze 4). Naopak 3, 4, 10, 12, 19, 20, 27 a 33 přežily
     * pokaždé a 23 v šesti ze sedmi. Důvod 21 přežívá jen tehdy, když razítko neselhalo na
     * zastaralém algoritmu, a 22 se po ústupcích mění na 9 — revokace se tím prokáže, nezmizí.
     */
    private static final java.util.Set<Integer> RELAXABLE_REASONS =
            java.util.Set.of(1, 2, 6, 11, 15, 30);

    /** Důvody v pořadí, v jakém je Analýza vypisuje u daného prvku; viz níže. */
    public static List<Integer> reasonsInDisplayOrder(SignatureSummary sig) {
        java.util.List<SignatureSummary.TimestampFailure> causes = timestampFailures(sig);
        boolean timestampCryptoOnly = !causes.isEmpty()
                && causes.stream().allMatch(c -> c == SignatureSummary.TimestampFailure.CRYPTO);
        return reasonsInDisplayOrder(sig.getDetectedReasons(), sig.getCertRevokedAt(),
                sig.getCertNotAfter(), timestampCryptoOnly);
    }

    static List<Integer> reasonsInDisplayOrder(List<Integer> reasons) {
        return reasonsInDisplayOrder(reasons, null, null, false);
    }

    /**
     * Důvody v pořadí, v jakém je Analýza vypisuje — ve třech skupinách, uvnitř každé v pořadí,
     * v jakém je vydal {@code ReportMapper}:
     *
     * <ol>
     * <li><b>Důvody, které žádný ústupek nespraví</b> — na nich ověřování opravdu skončilo. Mezi
     *     nimi jde napřed důvod 22 (odvolání za platnosti certifikátu): ze všeho, co karta nese,
     *     je to nejtěžší zpráva.</li>
     * <li><b>Důvody, které spraví ústupek</b> ({@link #RELAXABLE_REASONS}, a důvod 21, selhala-li
     *     razítka jen na zastaralém algoritmu) — bez nich by výsledek stejně nebyl kladný, dokud
     *     trvá některý z první skupiny.</li>
     * <li><b>Důvod 22, přišlo-li odvolání až po konci platnosti</b> certifikátu. Podpis musel
     *     vzniknout nejpozději s koncem platnosti, takže pozdější odvolání se ho netýká.</li>
     * </ol>
     *
     * <p>Do 25. 9. 2026 šel důvod 22 vždy první a ostatní stály v pořadí čísel. Nález uživatele
     * nad {@code 2result.pdf}: karta začínala důvodem 22, pak 1, 4, 15 — ačkoli ověřování skončilo
     * na důvodu 4 (expirovaný certifikát bez důkazu existence) a certifikát odvolali až 30. 11. 2020,
     * rok po konci jeho platnosti (7. 12. 2019). Teď 4, 1, 15, 22. Na sbírce se pořadí změnilo
     * u 94 ze 410 karet s víc důvody, typicky [15, 21] → [21, 15] a [2, 33] → [33, 2].
     *
     * <p>K odvolání po expiraci: standardy s ním počítají (EN 319 102-1 V1.4.0, pozn. 9 v 5.5.4:
     * „not all CAs … are willing to revoke certificates after expiration“; CR#14 téže verze), ale
     * údaj vydaný po expiraci připouštějí jen od autority, která ohlásí, že stav expirovaných
     * certifikátů vede dál (pozn. 3 v 5.6.2.2.4, rozšíření expiredCertsOnCRL).
     *
     * @param revokedAt           kdy byl podpisový certifikát odvolán; {@code null}, není-li známo
     * @param certNotAfter        konec platnosti podpisového certifikátu; {@code null}, není-li znám
     * @param timestampCryptoOnly razítka neobstála jen kvůli zastaralému algoritmu
     */
    static List<Integer> reasonsInDisplayOrder(List<Integer> reasons,
                                               java.time.Instant revokedAt,
                                               java.time.Instant certNotAfter,
                                               boolean timestampCryptoOnly) {
        if (reasons.size() < 2) return reasons;
        boolean revokedAfterExpiry = revokedAt != null && certNotAfter != null
                && revokedAt.isAfter(certNotAfter);
        List<Integer> decisive = new java.util.ArrayList<>();
        List<Integer> relaxable = new java.util.ArrayList<>();
        List<Integer> last = new java.util.ArrayList<>();
        for (int rn : reasons) {
            if (rn == 22 && revokedAfterExpiry) last.add(rn);
            else if (rn == 22) decisive.add(0, rn);
            else if (RELAXABLE_REASONS.contains(rn) || (rn == 21 && timestampCryptoOnly)) {
                relaxable.add(rn);
            } else decisive.add(rn);
        }
        List<Integer> ordered = new java.util.ArrayList<>(reasons.size());
        ordered.addAll(decisive);
        ordered.addAll(relaxable);
        ordered.addAll(last);
        return ordered.equals(reasons) ? reasons : ordered;
    }

    /**
     * Pod kterými důvody stojí rada pro zkušenější uživatele.
     *
     * <p>Jednou za každé <b>znění</b>, ne jednou za kartu. Opakuje-li se táž věta u víc důvodů —
     * a to je případ rady „sežeňte CRL z vhodné doby", kterou má osm z nich — stojí pod spodním
     * z nich, protože je to rada k ověření, ne k jedné jeho položce. Liší-li se ale rady obsahem,
     * patří každá ke svému důvodu a sloučit je nelze.
     *
     * <p>Do 21. 9. 2026 se řádek stavěl jen jeden, pod posledním důvodem, který nějakou radu měl.
     * Dokud byly ty věty všechny stejné, vycházelo to nastejno; jakmile se sešly dvě různé,
     * jedna zmizela. Nález uživatele nad {@code Metodicky_pokyn_-_pristupnost…v1-3.pdf}: důvody
     * 3 a 4 tam mají každý svou radu — „vyjádřit důvěru certifikátu" a „ověřit k zadanému datu"
     * — a karta ukázala jen tu druhou.
     *
     * <p>Seznam musí přijít už seřazený {@link #reasonsInDisplayOrder}, aby „spodní z nich"
     * znamenalo totéž co na kartě. Ptá se rovnou po {@link #expertTip textu té rady}, ne po
     * členství ve výčtu {@link ResultView#EXPERT_CRL_TIP_REASONS}: ten zná jen katalogové oddíly,
     * kdežto radu smí nést i náhradní znění příčiny.
     */
    static java.util.Set<Integer> expertTipRows(SignatureSummary sig, List<Integer> reasons) {
        java.util.Map<String, Integer> lastByText = new java.util.LinkedHashMap<>();
        for (int rn : reasons) {
            String tip = expertTip(sig, rn);
            if (tip != null) lastByText.put(tip, rn);
        }
        return new java.util.LinkedHashSet<>(lastByText.values());
    }

    /**
     * Důvody, jejichž rada pro zkušenější míří do úložiště vlastních CRL — a jen k nim tedy patří
     * odkaz na okno „kde vzít CRL".
     *
     * <p>Dokud stál na kartě jediný řádek s radou, byla otázka bezpředmětná: ten řádek byl vždycky
     * ten o CRL. Od chvíle, kdy radu dostane každé znění, by odkaz visel i pod „vyjádřit důvěru
     * certifikátu" (důvod 3) nebo „ověřit k zadanému datu" (důvod 4) a ukazoval na okno, které
     * s nimi nesouvisí.
     *
     * <p>Výčet hlídá {@code ReasonTextsTest}: srovnává ho s tím, u kterých důvodů v souboru
     * opravdu stojí ta věta o CRL, takže se nemůže rozejít s texty.
     */
    static final java.util.Set<Integer> CRL_STORE_TIP_REASONS =
            java.util.Set.of(2, 6, 10, 11, 12, 21, 25, 29);

    /**
     * Rada pro zkušenější uživatele k jednomu důvodu — přednost má znění psané k příčině.
     *
     * <p>U důvodu 21 se „co s tím" liší podle toho, proč razítko neobstálo: sehnat seznam CRL
     * z vhodné doby má smysl radit u nezjištěné revokace, ostatním příčinám ta věta neříká nic.
     * Do 19. 9. 2026 se {@code zkusenejsi} četla výhradně z katalogového oddílu, takže napsat
     * radu k jedné příčině nešlo — text v oddílu {@code [21/NO_REVOCATION]} nikdo nečetl a nic
     * to neohlásilo.
     *
     * <p>Vrací {@code null}, není-li rada ani u příčiny, ani v katalogu; podle toho se rozhoduje,
     * jestli řádek na kartě vůbec stojí.
     */
    static String expertTip(SignatureSummary sig, int rn) {
        // Rada „ověřit k zadanému datu" nahrazuje chybějící důkaz existence. U prvku, který kryje
        // pozdější prvek s platným razítkem, ten důkaz je a ověření k němu už proběhlo — rada by
        // čtenáře posílala hledat, co už v dokumentu má (nález uživatele 2. 10. 2026). Tutéž
        // podmínku má znění [4/kryto], viz reasonProblem().
        if (rn == 4 && sig.getCoveringProofDetail() != null) return null;
        if (rn == 21) {
            java.util.List<SignatureSummary.TimestampFailure> causes = timestampFailures(sig);
            if (causes.size() == 1) {
                String byCause = ReasonTexts.variant(21, causes.get(0).name(), "zkusenejsi");
                if (byCause != null) return fillCauseText(sig, causes.get(0), byCause);
            }
        }
        return ResultView.EXPERT_CRL_TIP_REASONS.contains(rn)
                ? ReasonTexts.get(String.valueOf(rn), "zkusenejsi") : null;
    }

    /**
     * Shared reason block: "V čem je problém:" + "N. [problem]" (+Podrobněji),
     * then "Co může pomoci:" + solution (+Linkable for reason 12).
     * Font size 12 px on both labels and values; Pos.TOP_LEFT alignment.
     *
     * <p>{@code withExpertTip} přidá pod ně ještě třetí řádek s radou pro zkušenější uživatele —
     * viz {@link #expertTipRows}.
     */
    private VBox reasonBlock(SignatureSummary sig, int rn, ResultView.ReasonEntry re,
                             boolean withExpertTip) {
        VBox block = new VBox(4);

        // Problem row
        Label problemLbl = new Label("V čem je problém:");
        problemLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
        problemLbl.setMinWidth(170);
        problemLbl.setMaxWidth(170);
        problemLbl.setWrapText(true);

        String detail = getReasonDetail(sig, rn, re);
        // U neznámého certifikátu je ten certifikát to, co čtenář potřebuje v ruce: podívat se,
        // kdo ho vydal, a případně ho vložit do vlastního úložiště kotev důvěry.
        byte[] unknownCert = rn == 3 ? signingCertBytes(sig) : null;
        HBox problemRow;
        if (unknownCert != null) {
            TextFlow flow = certLinkParagraph(rn + ". " + reasonProblem(sig, rn, re),
                    unknownCert, detail);
            HBox.setHgrow(flow, Priority.ALWAYS);
            problemRow = new HBox(8, problemLbl, flow);
        } else if (detail != null) {
            // Odkaz na konci věty, ne tlačítko pod ní: podrobnosti jsou pokračováním téhož
            // sdělení, kdežto tlačítko vypadá jako úkon a odsazuje se od textu, ke kterému patří.
            TextFlow flow = detailLinkParagraph(rn + ". " + reasonProblem(sig, rn, re), detail);
            HBox.setHgrow(flow, Priority.ALWAYS);
            problemRow = new HBox(8, problemLbl, flow);
        } else {
            TextArea problemTxt = ResultView.selectable(rn + ". " + reasonProblem(sig, rn, re),
                    "-fx-text-fill: #111111; -fx-font-size: 12px;");
            HBox.setHgrow(problemTxt, Priority.ALWAYS);
            problemRow = new HBox(8, problemLbl, problemTxt);
        }
        problemRow.setAlignment(Pos.TOP_LEFT);
        block.getChildren().add(problemRow);

        // Solution row. Důvody 10, 11 a 12 ji nemají: jejich jediná rada byla ta o CRL, a ta
        // teď stojí vlastním řádkem níž. Prázdný řádek s popiskem „Co může pomoci:“ by tvrdil,
        // že se odpověď nenašla.
        // Tlačítko tolerantního ověření tu bývalo u každého důvodu, jehož řešení to zmiňovalo,
        // a u tří důvodů se tak opakovalo třikrát. Přesunulo se do části Doporučení pod výčtem
        // — jenže ta 25. 8. 2026 zmizela, takže se dnes nestaví vůbec; obsluha je živá, viz
        // makeLenientButton().
        String solution = reasonSolution(sig, rn, re);
        if (solution != null) {
            Label solutionLbl = new Label("Co může pomoci:");
            solutionLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
            solutionLbl.setMinWidth(170);
            solutionLbl.setMaxWidth(170);
            solutionLbl.setWrapText(true);

            javafx.scene.Node solutionTxt = solutionParagraph(solution);
            HBox.setHgrow(solutionTxt, Priority.ALWAYS);
            HBox solutionRow = new HBox(8, solutionLbl, solutionTxt);
            solutionRow.setAlignment(Pos.TOP_LEFT);
            block.getChildren().add(solutionRow);
        }

        if (withExpertTip) block.getChildren().add(expertTipRow(sig, rn));

        return block;
    }

    /**
     * Rada pro zkušenější uživatele: vlastní řádek se stejným popiskem i zarovnáním jako dva nad
     * ním, protože je to další položka téhož výčtu, ne poznámka pod čarou.
     *
     * <p>Končí odkazem <b>Podrobněji …</b> na okno „kde vzít CRL“ — ale jen tam, kde je co
     * ukázat. Podklad pro ně skládá ReportMapper z certifikátů obou důvodů, pod kterými rada
     * stojí; nemá-li o čem psát, zůstane rada bez odkazu.
     */
    private HBox expertTipRow(SignatureSummary sig, int rn) {
        Label lbl = new Label("Pro zkušenější uživatele:");
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
        lbl.setMinWidth(170);
        lbl.setMaxWidth(170);
        lbl.setWrapText(true);

        javafx.scene.Node tipTxt = solutionParagraph(expertTip(sig, rn),
                CRL_STORE_TIP_REASONS.contains(rn) ? sig.getCrlSourceHelp() : null);
        HBox.setHgrow(tipTxt, Priority.ALWAYS);
        HBox row = new HBox(8, lbl, tipTxt);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /**
     * Doporučení, v němž jsou zmínky o Nastavení proklikem.
     *
     * <p>Rada „v Nastavení zrušte zaškrtnutí…“ je návod k úkonu, a ten se má dát provést odtud,
     * ne hledat. Obě obvyklé formulace vedou každá jinam: „v Nastavení“ na základní stránku,
     * „v rámci pokročilého nastavení“ rovnou na tu pokročilou, protože přesně o ní ta věta mluví.
     *
     * <p>{@link TextFlow} místo {@code TextArea} ze stejného důvodu jako u
     * {@link #detailLinkParagraph}: odkaz má plynout uvnitř věty. Výběr textu myší tím padá,
     * proto je pod pravým tlačítkem „Kopírovat text“ s původním zněním.
     *
     * <p>Nenajde-li se ani jedna zmínka — nebo není-li kam otevírat — vrací se obyčejný
     * vybíratelný text, tedy přesně to, co tu bylo dřív.
     */
    private javafx.scene.Node solutionParagraph(String text) {
        return solutionParagraph(text, null);
    }

    /**
     * Totéž, jen se za radu ještě přidá odkaz <b>Podrobněji …</b> na okno „kde vzít CRL“.
     *
     * <p>{@code crlHelp} je podklad pro to okno, nebo {@code null}, nemá-li se ukazovat.
     * Odkaz patří dovnitř téhož odstavce: rada zní „sežeňte CRL z vhodné doby“ a okno říká
     * z jaké — jako tlačítko pod odstavcem by se od ní odtrhl na vlastní řádek.
     */
    private javafx.scene.Node solutionParagraph(String text, String crlHelp) {
        java.util.List<String> anchors = java.util.List.of(
                "v rámci pokročilého nastavení", "v Nastavení");
        boolean settingsLinks = settingsOpener != null && anchors.stream().anyMatch(text::contains);
        if (!settingsLinks && crlHelp == null) {
            return ResultView.selectable(text, "-fx-text-fill: #111111; -fx-font-size: 12px;");
        }
        TextFlow flow = new TextFlow();
        int pos = 0;
        while (settingsLinks && pos < text.length()) {
            String hit = null;
            int at = Integer.MAX_VALUE;
            for (String a : anchors) {
                int i = text.indexOf(a, pos);
                if (i >= 0 && i < at) { at = i; hit = a; }
            }
            if (hit == null) break;
            appendRun(flow, new StringBuilder(), text.substring(pos, at));
            boolean advanced = hit.startsWith("v rámci");
            Hyperlink link = new Hyperlink(hit);
            link.setStyle("-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                    + " -fx-border-width: 0;");
            Tooltip.install(link, new Tooltip(advanced
                    ? "Otevře Nastavení na stránce pokročilého nastavení"
                    : "Otevře Nastavení"));
            link.setOnAction(e -> settingsOpener.accept(advanced));
            flow.getChildren().add(link);
            pos = at + hit.length();
        }
        appendRun(flow, new StringBuilder(), text.substring(pos));
        if (crlHelp != null) {
            appendRun(flow, new StringBuilder(), "  ");
            Hyperlink more = new Hyperlink("Podrobněji …");
            more.setStyle("-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                    + " -fx-border-width: 0;");
            Tooltip.install(more, new Tooltip(
                    "Do kdy musí být CRL vydáno a kde hledat historické seznamy"));
            more.setOnAction(e -> ResultView.showLinkableDetailDialog(crlHelp));
            flow.getChildren().add(more);
        }
        withCopyMenu(flow, text);
        return flow;
    }

    /**
     * Text důvodu zakončený odkazem <b>Podrobněji …</b>, který otevře totéž okno jako dosavadní
     * tlačítko.
     *
     * <p>{@link TextFlow} místo {@code TextArea}: odkaz má stát na konci věty a plynout s ní,
     * což popisek se svým jediným řezem písma neumí. Výběr textu myší tím padá, proto je pod
     * pravým tlačítkem „Kopírovat text“ — táž náhrada, jakou používají ostatní odstavce
     * skládané z běhů.
     */
    /**
     * Text důvodu zakončený odkazy <b>Ukaž</b> a <b>Ulož</b> na certifikát, a je-li k důvodu
     * podrobnější výklad, ještě <b>Podrobněji …</b> za nimi — týž řád jako u
     * {@link #detailLinkParagraph}, jen s certifikátem vloženým před výklad.
     */
    private TextFlow certLinkParagraph(String text, byte[] der, String detail) {
        TextFlow flow = new TextFlow();
        // Odkazy stojí hned za větou, která certifikát jmenuje, ne až za důsledkem, který z toho
        // plyne (pokyn uživatele 24. 9. 2026). Druhá věta začíná ve všech třech zněních důvodu 3
        // jinak, proto se hledá kterýkoli z obou začátků; nenajde-li se, jdou odkazy na konec.
        int split = -1;
        for (String next : java.util.List.of(" Aplikace proto ", " Proto neví")) {
            int i = text.indexOf(next);
            if (i > 0) { split = i; break; }
        }
        String head = split > 0 ? text.substring(0, split) : text;
        String tail = split > 0 ? text.substring(split + 1) : null;
        Text body = new Text(head + "  ");
        body.setStyle("-fx-font-size: 12px; -fx-fill: #111111;");
        String linkStyle = "-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                + " -fx-border-width: 0;";
        Hyperlink show = new Hyperlink("Ukaž");
        show.setStyle(linkStyle);
        Tooltip.install(show, new Tooltip("Zobrazí certifikát"));
        show.setOnAction(e -> {
            show.setVisited(false);
            ElementTextView.show("Certifikát",
                    cz.digitalnikontinuita.anep.core.ContainerInspector.describeCertificate(der),
                    der, "certifikat.cer", "Certifikát (*.cer)", "*.cer",
                    root.getScene() != null && root.getScene().getWindow() instanceof Stage s
                            ? s : null);
        });
        Hyperlink save = new Hyperlink("Ulož");
        save.setStyle(linkStyle);
        Tooltip.install(save, new Tooltip("Uloží certifikát do souboru"));
        save.setOnAction(e -> {
            save.setVisited(false);
            saveBytes(der, "certifikat.cer", "Certifikát (*.cer)", "*.cer");
        });
        flow.getChildren().addAll(body, show, new Text(" "), save);
        if (tail != null) {
            Text rest = new Text("  " + tail);
            rest.setStyle(body.getStyle());
            flow.getChildren().add(rest);
        }
        if (detail != null) {
            Hyperlink more = new Hyperlink("Podrobněji …");
            more.setStyle(linkStyle);
            more.setOnAction(e -> ResultView.showDetailDialog(detail));
            flow.getChildren().addAll(new Text("  "), more);
        }
        withCopyMenu(flow, text);
        return flow;
    }

    /**
     * Podpisový certifikát prvku — u podpisu či pečeti i u samostatného časového razítka;
     * {@code null}, nezná-li ho DSS nebo nejsou-li po ruce jeho bajty.
     */
    private byte[] signingCertBytes(SignatureSummary sig) {
        if (lastValidationResult == null || sig.getSignatureId() == null) return null;
        var dd = lastValidationResult.getDiagnosticData();
        if (dd == null) return null;
        eu.europa.esig.dss.diagnostic.CertificateWrapper cert = null;
        var sw = dd.getSignatureById(sig.getSignatureId());
        if (sw != null) {
            cert = sw.getSigningCertificate();
        } else {
            var ts = dd.getTimestampById(sig.getSignatureId());
            if (ts != null) cert = ts.getSigningCertificate();
        }
        byte[] der = cert != null ? cert.getBinaries() : null;
        return der != null && der.length > 0 ? der : null;
    }

    private static TextFlow detailLinkParagraph(String text, String detail) {
        TextFlow flow = new TextFlow();
        Text body = new Text(text + "  ");
        body.setStyle("-fx-font-size: 12px; -fx-fill: #111111;");
        Hyperlink link = new Hyperlink("Podrobněji …");
        link.setStyle("-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                + " -fx-border-width: 0;");
        link.setOnAction(e -> ResultView.showDetailDialog(detail));
        flow.getChildren().addAll(body, link);
        withCopyMenu(flow, text);
        return flow;
    }

    /**
     * The instant the signature's validity is assessed at (BST), formatted with a note saying
     * where it came from. Mirrors the "Rozhodný okamžik pro ověření:" row in Rozšířený přehled,
     * so the two tabs cannot report different instants for the same signature.
     */
    private String decisiveInstant(SignatureSummary sig) {
        if (sig.isBestSignatureTimeFromEvidenceRecord()) {
            return TimeUtil.format(sig.getBestSignatureTime())
                    + "  (čas archivního razítka evidenčního záznamu)";
        }
        if (sig.hasValidTimestamp() && sig.getBestSignatureTime() != null) {
            return TimeUtil.format(sig.getBestSignatureTime()) + "  (čas časového razítka)";
        }
        if (customValidationTime != null) {
            return TimeUtil.format(
                    customValidationTime.atZone(java.time.ZoneId.systemDefault()).toInstant())
                    + "  (explicitně nastavený rozhodný okamžik)";
        }
        // No timestamp usable as BST: the signature is assessed at the current time. Reaching
        // this branch always means that, so the current time is what to show — never
        // getBestSignatureTime(), which is null here whenever the first branch did not fire.
        return TimeUtil.format(java.time.Instant.now()) + "  (aktuální čas)";
    }

    /**
     * Okamžik ověření zvolený v nastavení, pokud se k němu u tohoto prvku opravdu ověřovalo;
     * jinak {@code null} a ověřovalo se k aktuálnímu času.
     */
    private java.time.Instant chosenValidationTime(SignatureSummary sig) {
        if (customValidationTime == null || !sig.isUsedCustomValidationTime()) return null;
        return customValidationTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    /** Okamžik, ke kterému se ověřovalo: zadaný v nastavení, jinak teď. */
    private java.time.Instant checkTimeOf(SignatureSummary sig) {
        java.time.Instant chosen = chosenValidationTime(sig);
        return chosen != null ? chosen : java.time.Instant.now();
    }

    /**
     * Kolik ústupků vypisuje blok Průběh ověřování ve skupině „Porušilo se pravidlo?“ — tatáž
     * pravidla jako v {@link #appendVerificationMode}, aby Závěr mluvil o týchž ústupcích
     * v témž čísle.
     */
    private int shownConcessionCount(SignatureSummary sig, ValidationCourse vc) {
        List<String> decisive = sig.getDecisiveSettings() != null
                ? sig.getDecisiveSettings() : List.of();
        boolean relNewerOcsp = decisive.contains(ReportMapper.Relaxation.NEWER_OCSP.settingName());
        int n = relNewerOcsp ? 1 : 0;
        if (decisive.contains(ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName())) n++;
        if (decisive.contains(ReportMapper.Relaxation.SHA1.settingName())) n++;
        if (decisive.contains(ReportMapper.Relaxation.PDF_NOT_ETSI.settingName())) n++;
        if (sig.isUsedCustomValidationTime() && !relNewerOcsp
                && vc != null && vc.postExpiryRevocationPresent()) n++;
        return n;
    }

    // -------------------------------------------------------------------------
    // Format, level and reference-format rows
    // -------------------------------------------------------------------------

    /**
     * Records how the validated file packages the signed data. Kept per file rather than per
     * signature: whether the content sits inside the opened file or beside it is a property of
     * the submission, and DSS reports the ASiC container type for the file as a whole.
     */
    private void rememberPackaging(ValidationResult result) {
        externalSignature = result.isExternalSignature();
        DiagnosticData dd = result.getDiagnosticData();
        asicContainer = dd != null && dd.getContainerInfo() != null;
        asicContainerKind = asicContainer && dd.getContainerType() != null
                ? switch (dd.getContainerType()) { case ASiC_S -> "ASiC-S"; case ASiC_E -> "ASiC-E"; }
                : null;
        // Empty signature scopes mean DSS had no signed data to describe — a detached signature
        // opened without the file it signs. Same reasoning as ResultView.hasSignedDataInside().
        signedDataInside = dd == null || dd.getSignatures().isEmpty()
                || !dd.getSignatures().get(0).getSignatureScopes().isEmpty();
    }

    /**
     * The format block: the raw format name and what it means — where the signature lives, how
     * long it will stay verifiable and whether public bodies must accept it. Its own block,
     * because it describes the signature as an object, not the person behind it.
     */
    // ── Blok „Průběh ověřování“ ──────────────────────────────────────────────────────────────

    /**
     * Za jakých pravidel se ověřovalo, jak AnEP postupoval a čím to skončilo.
     *
     * <p>Vzniklo 19. 8. 2026 sloučením dvou bloků — „Průběh ověřování“ (řádky s časy a tři
     * seznamy revokačních údajů) a „Způsob ověření“ (za jakých pravidel běh proběhl). Dělily
     * jednu odpověď na dvě půlky, z nichž ani jedna sama nedávala smysl: seznamy říkaly co se
     * našlo, ale ne proč to stačilo či nestačilo, a pravidla říkala jak se soudilo, ale ne co
     * z toho vyšlo.
     *
     * <p>Blok má tři části: pravidla, souvislý výklad postupu, a složený <b>Podrobný výčet</b>
     * s původními řádky a seznamy. Výklad je pro čtenáře, výčet pro toho, kdo si chce ověřit
     * jednotlivosti — čísla a jména certifikátů do plynulého textu nepatří, u dokumentu na
     * úrovni LTA by ho rozbila docela.
     */
    private void addValidationCourseBlock(VBox card, SignatureSummary sig, String verdict) {
        ValidationCourse vc = sig.getValidationCourse();

        VBox block = new VBox(8);
        block.setPadding(new Insets(8, 12, 10, 12));

        appendVerificationMode(block, sig, verdict);
        if (vc != null) appendCourseNarrative(block, sig, vc, verdict);

        card.getChildren().add(collapsibleBlock("Průběh ověřování", block, false));
        card.getChildren().add(blockSeparator());
    }

    /**
     * Souvislý výklad postupu: ověření k aktuálnímu času, důkaz existence, revokační informace,
     * závěr.
     *
     * <p>Pořadí není libovolné, kopíruje ETSI EN 319 102-1: rozhodný okamžik začíná na aktuálním
     * čase a teprve když podpis k němu neobstojí, snižuje se na čas prokázaný razítkem. Čtenář,
     * který ví jen tolik, že „podpis má razítko“, jinak nechápe, proč mu razítko někdy pomůže
     * a jindy ne — a to je právě otázka, se kterou k tomuhle bloku přichází.
     */
    private void appendCourseNarrative(VBox block, SignatureSummary sig, ValidationCourse vc,
                                       String verdict) {
        // Verdikt přichází z karty, nepočítá se tu znovu. Karta ho totiž umí přebít: při vypnutém
        // „Ověřování časových razítek podle standardů" se prvku s expirovaným certifikátem
        // razítkovací autority sníží na INDETERMINATE, což displayedIndication() nevidí — a výklad
        // pak pod hlavičkou „stav platnosti zůstává neznámý" psal „Podpis je proto platný".
        boolean passed = "TOTAL_PASSED".equals(verdict);
        boolean failed = "TOTAL_FAILED".equals(verdict);

        java.time.Instant certUntil = sig.getCertNotAfter();
        // Okamžik, ke kterému se ověřovalo. Do 24. 9. 2026 tu stálo natvrdo Instant.now(), takže
        // při ověřování k zadanému času v minulosti první odstavec tvrdil „neobstál — certifikátu
        // skončila platnost“ o certifikátu, který k zadanému okamžiku platil, a za ním následoval
        // odstavec o důkazu existence, který tu neměl co dělat (nález uživatele).
        java.time.Instant setTime = chosenValidationTime(sig);
        java.time.Instant checkTime = checkTimeOf(sig);
        boolean certExpired = certUntil != null && !certUntil.isAfter(checkTime);
        // Certifikát, který je sám kotvou důvěry, se podle doby platnosti neposuzuje (EN 319 102-1
        // kotvu nekontroluje), takže jeho expirace ověření k aktuálnímu času nezastaví a důkaz
        // existence není potřeba. Bez toho tu u protokolu SecuSign z roku 2017 stálo „Neobstál —
        // certifikátu pečeti skončila řádná doba platnosti" a za tím „není o co se opřít" — nad
        // pečetí, kterou týž běh ověřil k dnešku jako platnou (nález uživatele 1. 10. 2026).
        boolean anchorExpired = certExpired && sig.isAnchorCert();
        if (anchorExpired) certExpired = false;
        // Odvolání se v prvním odstavci zmiňuje jen tehdy, když se o něm dalo rozhodnout. Kde
        // je údaj k dispozici, ale použít ho nešlo, zaznívá až ve třetím odstavci i s důvodem —
        // jinak by první odstavec tvrdil zjištění, které tenhle běh neudělal.
        boolean revocationEstablished = vc.signingCertRevocationUsed() != null;
        // K zadanému okamžiku je revokován jen certifikát odvolaný nejpozději tehdy; pozdější
        // odvolání o tom okamžiku nic neříká.
        boolean revoked = revocationEstablished && sig.isCertRevoked()
                && (setTime == null || sig.getCertRevokedAt() == null
                        || !sig.getCertRevokedAt().isAfter(setTime));

        // ── Ověření k aktuálnímu (či zadanému) času ──────────────────────────────────────
        block.getChildren().add(partSeparator());
        StringBuilder now = new StringBuilder(setTime != null
                ? "**Ověření k zadanému času:** začalo se posouzením, zda "
                  + gendered(sig, "podpis obstojí", "pečeť obstojí", "razítko obstojí")
                  + " k okamžiku " + shortDateTime(setTime) + ", zvolenému v nastavení."
                : "**Ověření k aktuálnímu času:** začalo se posouzením, zda "
                  + gendered(sig, "podpis obstojí", "pečeť obstojí", "razítko obstojí")
                  + " k okamžiku, kdy ověřování probíhá.");
        boolean currentTimeFailed = certExpired || revoked;
        if (certExpired && revoked) {
            now.append(" Neobstál — ").append(elementCert(sig, Case.DATIV))
               .append(" skončila řádná doba platnosti ")
               .append(shortDate(certUntil))
               .append(setTime != null ? " a k tomuto okamžiku byl navíc revokován."
                                       : " a k dnešnímu dni je navíc revokován.");
        } else if (certExpired) {
            now.append(" Neobstál — ").append(elementCert(sig, Case.DATIV))
               .append(" skončila řádná doba platnosti ")
               .append(shortDate(certUntil)).append(".");
        } else if (revoked) {
            now.append(" Neobstál — ").append(elementCert(sig, Case.NOMINATIV))
               .append(" byl revokován")
               .append(sig.getCertRevokedAt() != null
                       ? " " + revokedAt(sig.getCertRevokedAt()) : "").append(".");
        } else if (anchorExpired) {
            now.append(passed
                    ? " K němu " + gendered(sig, "podpis obstál", "pečeť obstála", "razítko obstálo")
                      + ": " + elementCert(sig, Case.DATIV) + " sice skončila řádná doba platnosti "
                    : " " + capitalize(elementCert(sig, Case.DATIV))
                      + " sice skončila řádná doba platnosti ")
               .append(shortDate(certUntil))
               .append(", je ale sám kotvou důvěry, a u kotvy se doba platnosti neposuzuje.");
        } else if (passed) {
            now.append(" K němu ").append(gendered(sig, "podpis obstál", "pečeť obstála",
                    "razítko obstálo")).append(".");
        }
        block.getChildren().add(boldMarkup(now.toString()));

        // ── Důkaz existence v čase ───────────────────────────────────────────────────────
        if (currentTimeFailed) {
            block.getChildren().add(boldMarkup(proofOfExistenceText(sig, vc)));
        }

        // ── Kotva důvěry ─────────────────────────────────────────────────────────────────
        // Jen fakt, důsledek si vezme Závěr. Do 20. 9. 2026 tu nestálo nic a průběh tak o jediné
        // skutečné překážce mlčel — nález uživatele: „skutečná příčina neznámé platnosti, a to
        // neznámý podpisový certifikát, není v průběhu ověřování ani v Závěru zmiňován."
        if (chainNotBuilt(sig)) {
            block.getChildren().add(partSeparator());
            String issuer = certIssuerName(sig);
            block.getChildren().add(boldMarkup("**Kotva důvěry.** Řetězec certifikátů se"
                    + " nepodařilo sestavit k žádné kotvě, kterou aplikace zná"
                    + (issuer != null
                            ? " — vydavatelem " + gendered(sig, "podpisového certifikátu",
                                    "certifikátu pečeti", "certifikátu razítka")
                              + " je „" + issuer + "“."
                            : ".")));
        }

        // ── Revokační informace ──────────────────────────────────────────────────────────
        RevocationItem item = revocationItem(sig, vc, passed, failed);
        switch (item) {
            case AS_IS -> block.getChildren().add(maskedRevocation(sig, passed)
                    ? markupWithColoredTail(revocationText(sig, vc, certUntil, passed),
                            maskedRevocationWarning(sig, vc), "#cc0000")
                    : boldMarkup(revocationText(sig, vc, certUntil, passed)));
            case UNUSABLE -> block.getChildren().add(boldMarkup(
                    "**Revokační informace.** Nepodařilo se získat využitelné informace "
                    + gendered(sig, "o stavu revokace podpisového certifikátu",
                            "o stavu revokace certifikátu, na kterém je pečeť založena",
                            "o stavu revokace certifikátu, na kterém je razítko založeno")
                    + embeddedUnusableNote(vc) + "."));
            case REVOKED -> block.getChildren().add(labeledMarkup("Revokační informace.",
                    " Informace o stavu revokace, které se podařilo získat, nelze podle standardů"
                    + " využít. Indikují ale, že "
                    + gendered(sig, "podpisový certifikát", "certifikát pečeti",
                            "certifikát razítka")
                    + " byl ke konci své platnosti již revokovaný."
                    // Datum je z téhož údaje, který se zrovna „nedá využít" — a právě proto tu
                    // stojí: čtenář, kterému se tvrdí odvolání, musí mít z čeho posoudit, jak
                    // daleko od jeho vlastní věci to bylo. V korpusu D:\tmp ho zná všech 36
                    // prvků této větve, přesto nesmí chybět pojistka: getCertRevokedAt() se plní
                    // jen z podpisového certifikátu, kdežto větev se hlásí i tehdy, když odvolání
                    // potkalo certifikát autority nad ním.
                    + (sig.getCertRevokedAt() != null
                            ? " K revokaci došlo " + TimeUtil.format(sig.getCertRevokedAt()) + "."
                            : ""), "#cc0000"));
            case NOT_REACHED -> block.getChildren().add(boldMarkup(notReachedRevocationText(sig, vc)));
            case OMITTED -> { }
        }

        // Podrobný výčet uzavírá popis toho, co se dělo — patří tedy ještě před předěl, za kterým
        // se z průběhu stává výsledek. Za závěrem by naopak vypadal jako dodatek k němu, ačkoli
        // je to podklad k odstavcům nad ním.
        // Kde výklad o revokaci nic netvrdí, nemají co dělat ani podklady k němu: výčet je z větší
        // části soupis revokačních údajů a vedle věty, že se využitelný údaj nenašel, by působil
        // jako její popření.
        VBox detail = item == RevocationItem.AS_IS ? courseDetail(sig, vc) : null;
        if (detail != null) {
            block.getChildren().add(collapsibleBlock("Podrobný výčet", detail, false));
        }

        // ── Závěr ────────────────────────────────────────────────────────────────────────
        block.getChildren().add(partSeparator());
        String conclusion = conclusionText(sig, vc, passed, failed, certUntil);
        block.getChildren().add(maskedRevocation(sig, passed)
                ? markupWithColoredTail(conclusion, maskedRevocationRestated(sig), "#cc0000")
                : boldMarkup(conclusion));
    }

    /** Druhý odstavec: co dokládá dřívější existenci a jak je na tom řetěz razítek. */
    private String proofOfExistenceText(SignatureSummary sig, ValidationCourse vc) {
        StringBuilder sb = new StringBuilder("**Důkaz existence v čase.** Pro takový případ"
                + " standardy dovolují opřít se o důkaz, že "
                + gendered(sig, "podpis existoval", "pečeť existovala", "razítko existovalo")
                + " už dříve, a posuzovat "
                + gendered(sig, "jej", "ji", "je") + " k onomu dřívějšímu okamžiku.");

        List<SignatureSummary.TimestampEntry> all = sig.getAllTimestamps();
        // Rozhodný okamžik doložil evidenční záznam. Do 27. 9. 2026 o něm odstavec nevěděl
        // a u podpisu bez razítka psal „není o co se opřít“, ačkoli závěr stál právě na záznamu.
        EvidenceRecordEntry record = provingRecord(sig, vc);
        if (record != null) {
            SignatureSummary.TimestampEntry own = earliestValidOwnTimestamp(sig);
            sb.append(" ");
            if (all.isEmpty()) {
                sb.append(gendered(sig, "Podpis sám není opatřen",
                        "Pečeť sama není opatřena", "Prvek sám není opatřen"))
                  .append(" žádným časovým razítkem, kryje ")
                  .append(gendered(sig, "jej", "ji", "jej")).append(" ale evidenční záznam.");
            } else {
                sb.append(gendered(sig, "Podpis nese ", "Pečeť nese ", "Prvek nese "))
                  .append(all.size() == 1 ? "jedno časové razítko"
                          : CzechPlural.count(all.size(), "časové razítko", "časová razítka",
                                  "časových razítek"))
                  .append(own != null ? ", starší je ale čas, který dokládá evidenční záznam."
                                      : ", ověřit jako platné se ale nepodařilo; kryje "
                                        + gendered(sig, "jej", "ji", "jej")
                                        + " však evidenční záznam.");
            }
            SignatureSummary.TimestampEntry ts = record.timestamps().stream()
                    .filter(SignatureSummary.TimestampEntry::passed)
                    .filter(t -> vc.provenExistence().equals(t.time()))
                    .findFirst().orElse(null);
            String from = ts != null && ts.issuer() != null && !ts.issuer().isBlank()
                    ? " od " + ts.issuer() : "";
            boolean lowered = vc.dssBestSignatureTime() != null
                    && !vc.dssBestSignatureTime().isAfter(vc.provenExistence().plusSeconds(1));
            sb.append(" Jeho archivní časové razítko").append(from).append(", s časem ")
              .append(shortDateTime(vc.provenExistence()))
              .append(", bylo ověřeno jako platné a dokládá, že ")
              .append(gendered(sig, "podpis v té chvíli již existoval",
                      "pečeť v té chvíli již existovala", "prvek v té chvíli již existoval"))
              .append(lowered ? " — **rozhodným okamžikem se stal tento čas**." : ".");
            return sb.toString();
        }
        if (all.isEmpty()) {
            sb.append(" ").append(gendered(sig, "Podpis ale není opatřen",
                    "Pečeť ale není opatřena", "Prvek ale není opatřen"))
              .append(" žádným časovým razítkem");
            // „Nedokládá nic" se smí říct jen tam, kde opravdu nic není. Nález uživatele
            // z 12. 9. 2026: u prvku se situací 54 tu stálo „že existoval dříve, nedokládá nic",
            // ačkoli závěr o platnosti právě na tom důkazu stál.
            if (sig.getDetectedSituations().contains(54) && sig.getCoveringProofDetail() != null) {
                // Bez druhého „ale": předchozí věta ho má taky a dvě za sebou se nečtou.
                return sb.append(". Dřívější existenci dokládá jiný prvek téhož dokumentu, a právě"
                        + " k okamžiku, který tím doložil, se platnost posuzovala — viz poznámku"
                        + " k výsledku.").toString();
            }
            // Týž doklad, který ale nestačil. Věta „nedokládá nic" tu byla stejně nepravdivá jako
            // u situace 54 — jen se na ni nepřišlo, protože bez kladného přepočtu se o krycím
            // prvku nikde nepsalo (nález uživatele 22. 9. 2026). Slovo „žádným" v předchozí větě
            // by tu bylo taky nepravdivé, proto se nahrazuje: razítko v dokumentu je, jen ne
            // u tohohle prvku.
            if (sig.getCoveringProofDetail() != null) {
                return sb.toString().replace(" žádným časovým razítkem",
                                " vlastním časovým razítkem")
                        + ". " + sig.getCoveringProofDetail();
            }
            return sb.append(", takže není o co se opřít —")
                    .append(" že existoval dříve, nedokládá nic.").toString();
        }
        String count = all.size() == 1 ? "jedno časové razítko"
                : CzechPlural.count(all.size(), "časové razítko", "časová razítka",
                        "časových razítek");
        SignatureSummary.TimestampEntry decisive = all.stream()
                .filter(SignatureSummary.TimestampEntry::passed)
                .filter(t -> t.time() != null && t.time().equals(vc.provenExistence()))
                .findFirst().orElse(null);
        if (decisive == null) {
            boolean one = all.size() == 1;
            SignatureSummary.TimestampEntry ownValid = earliestValidOwnTimestamp(sig);
            OwnTimestampStatus status = ownTimestampStatus(sig, vc.provenExistence());
            if (status == OwnTimestampStatus.NONE_VALID) {
                return sb.append(" ")
                        .append(gendered(sig, "Podpis nese ", "Pečeť nese ", "Prvek nese "))
                        .append(count)
                        .append(one ? ", ověřit jako platné se ho ale nepodařilo"
                                    : ", ověřit jako platné se ale nepodařilo ani jedno")
                        .append(", takže dřívější existenci ").append(genitiveType(sig))
                        .append(one ? " nedokládá." : " nedokládají.")
                        .toString();
            }
            String from = ownValid.issuer() != null && !ownValid.issuer().isBlank()
                    ? " od " + ownValid.issuer() : "";
            sb.append(" ").append(gendered(sig, "Podpis nese ", "Pečeť nese ", "Prvek nese "))
              .append(count);
            if (one) {
                sb.append(from).append(", s časem ").append(shortDateTime(ownValid.time()))
                  .append(". Razítko bylo ověřeno jako platné.");
            } else {
                sb.append("; nejstarší z těch, která obstála, je").append(from)
                  .append(", s časem ").append(shortDateTime(ownValid.time())).append(".");
            }
            // Odkud se tedy rozhodný okamžik vzal. Bez toho zůstane věta o platném razítku viset
            // bez souvislosti s okamžikem, ke kterému se nakonec soudilo.
            switch (status) {
                case PROVED_BY_OTHER_ELEMENT -> sb.append(
                        " **Rozhodným okamžikem se ale stal čas, který doložil jiný prvek téhož"
                        + " dokumentu** — viz poznámku k výsledku.");
                // Razítko obstálo, a přesto se žádný dřívější okamžik neprokázal. Původní závěr
                // („nedokládá") tu platil, mylné bylo jen jeho zdůvodnění. Proč nedokládá,
                // vysvětluje situace 52; zdejší věta ji nemá opakovat.
                case PROVES_NOTHING -> sb.append(" Dřívější existenci ").append(genitiveType(sig))
                        .append(one ? " ale nedokládá." : " ale nedokládá žádné z nich.");
                default -> sb.append(" Rozhodný okamžik se ale opírá o jiný údaj než o razítka,"
                        + " která nese sám prvek.");
            }
            return sb.toString();
        }

        sb.append(" ").append(gendered(sig, "Podpis nese ", "Pečeť nese ", "Prvek nese "))
          .append(count);
        if (all.size() > 1) sb.append("; rozhodlo z nich nejstarší platné");
        sb.append(" — ").append(vc.provenExistenceNote() != null
                        ? vc.provenExistenceNote() : "časové razítko")
          .append(", s časem ").append(shortDateTime(decisive.time())).append(".");

        java.time.Instant tsuEnd = validTimestampChainReach(sig);
        boolean tsuGone = tsuEnd != null && !tsuEnd.isAfter(java.time.Instant.now());
        boolean rejectedByStrictMode = !timestampStandardsMode && tsuGone;

        if (rejectedByStrictMode) {
            // Razítko sice knihovna ověřila jako platné, ale zdejší nastavení je neuznává. Tvrdit
            // tu „rozhodným okamžikem se stal tento čas" by odporovalo hlavičce karty, která
            // u takového prvku hlásí neznámý stav platnosti.
            sb.append(" Podle standardů by bylo platné, **v nastaveném přísném režimu se ale"
                    + " neuznává**: ")
              .append(all.size() > 1
                      ? "certifikátům platných časových razítek skončila platnost nejpozději"
                      : "certifikát časového razítka expiroval")
              .append(" ").append(shortDate(tsuEnd))
              .append(" a toto nastavení posuzuje razítka podle řádné doby platnosti certifikátu,"
                      + " nikoli podle toho, zda služba dosud drží kvalifikovaný status.");
            return sb.toString();
        }

        boolean lowered = vc.dssBestSignatureTime() != null && vc.provenExistence() != null
                && !vc.dssBestSignatureTime().isAfter(vc.provenExistence().plusSeconds(1));
        sb.append(" Razítko bylo ověřeno jako platné, takže ").append(lowered
                ? "**rozhodným okamžikem se stal tento čas**."
                : "existenci " + genitiveType(sig) + " k tomuto okamžiku skutečně dokládá.");

        List<Integer> found = sig.getDetectedSituations();
        java.time.Instant tsuUntil = validTimestampChainReach(sig);
        boolean tsuExpired = tsuUntil != null && !tsuUntil.isAfter(java.time.Instant.now());
        if (found.contains(31)) {
            // U víc razítek jmenuje každá půlka věty něco jiného: nepřerušenost řetězu drží
            // *nejstarší* platné razítko (to muselo vzniknout ještě za platnosti podpisového
            // certifikátu), kdežto datum na konci věty je konec krytí celé řady — tedy nejzazší
            // z konců platnosti jejích certifikátů, ne konec toho posledního razítka. Bez toho
            // rozlišení četl čtenář obě půlky o témže razítku a dvě různá data u jednoho razítka
            // mu nedávala smysl.
            boolean many = all.size() > 1;
            sb.append(" Řetězec razítek je nepřerušený, ")
              .append(many ? "nejstarší platné razítko vzniklo" : "razítko vzniklo")
              .append(" ještě za platnosti ").append(elementCert(sig, Case.GENITIV));
            sb.append(tsuExpired ? "."
                    : many
                      ? " a řada platných časových razítek kryje"
                        + (tsuUntil != null ? " až do " + shortDate(tsuUntil) : "") + "."
                      : " a certifikát časového razítka sám platí"
                        + (tsuUntil != null ? " až do " + shortDate(tsuUntil) : "") + ".");
        } else {
            sb.append(" Řetězec razítek je ale přerušený — některé razítko vzniklo až poté, co"
                    + " skončila platnost certifikátu, který měl krýt.");
        }
        // Proč razítko platí, přestože certifikát autority už skončil. Bez téhle věty výklad
        // zamlčel celý důvod, na kterém výsledek stojí — a čtenář, který ví jen tolik, že
        // certifikátu skončila platnost, musí zdejší verdikt považovat za omyl.
        if (tsuExpired) {
            // U více razítek je to konec krytí celé řady, kdežto věta nad tím jmenovala to
            // nejstarší razítko, které rozhodlo o čase. Bez upřesnění by čtenář datum přiřadil
            // jednomu razítku.
            sb.append(all.size() > 1
                    ? " Certifikátům platných časových razítek sice už skončila platnost,"
                      + " naposledy ("
                    : " Certifikát časového razítka sice už expiroval (")
              .append(shortDate(tsuUntil))
              .append("), ale **razítko přesto platí**: standardy je posuzují podle toho, zda je"
                      + " služba, která je vydala, dosud vedena na důvěryhodných seznamech jako"
                      + " kvalifikovaná — tedy až do tzv. sunsetu, kdy jí bude kvalifikovaný"
                      + " status odebrán. Nikoli podle řádné doby platnosti certifikátu, na"
                      + " kterém razítko stojí.");
        }
        return sb.toString();
    }

    /**
     * Čím zakončit větu o údaji, který použít nešlo.
     *
     * <p>„Žádný jiný údaj … není" se smí říct jen tehdy, když opravdu žádný jiný není. U certifikátu,
     * o jehož odvolání se aplikace dozvěděla odjinud, je to nepravda — a zrovna tam na tom druhém
     * údaji závěr stojí. Potkáno na „PAdES LT kvalifikovny viditelny 1.pdf": vložená odpověď
     * z 7. 6. 2025 použít nešla, ale stažená z téhož certifikátu hlásí odvolání k 28. 8. 2025
     * a podpis vychází kladně, protože k odvolání došlo až po rozhodném okamžiku.
     */
    private static String otherRevocationNote(SignatureSummary sig) {
        if (sig.isCertRevoked()) {
            return " Stav certifikátu ale určil jiný údaj: certifikát byl revokován"
                    + (sig.getCertRevokedAt() != null
                            ? " " + revokedAt(sig.getCertRevokedAt()) : "") + ".";
        }
        return " Žádný jiný údaj o stavu tohoto certifikátu k dispozici není.";
    }

    /**
     * Zamaskovaná revokace: {@code TOTAL_PASSED} stojí na vloženém údaji vydaném těsně před
     * odvoláním, o kterém se ví odjinud.
     *
     * <p>Situace 20 přesně tohle popisuje: DSS v přísném režimu použije vložený údaj („odvolán
     * není“) a stažený, který odvolání prokazuje, zahodí jako post-expirační. Vznikne tím
     * dokument, který se ověří jako platný, ačkoli certifikát byl v době vzniku podpisu už
     * odvolaný — a protože ten vložený údaj tam někdo uložil úmyslně, je to konstrukce, ne shoda
     * náhod. Zjednodušený i Rozšířený přehled na to upozorňují; Analýza to donedávna zamlčel a
     * v Závěru dokonce tvrdil, že „revokace přišla až později“.
     *
     * <p>Datum odvolání se bere z {@link SignatureSummary#getCertRevokedAt()} — plní se z
     * DiagnosticData bez ohledu na to, zda DSS údaj použilo, takže je k dispozici i tady.
     */
    private static boolean maskedRevocation(SignatureSummary sig, boolean passed) {
        return passed && sig.getDetectedSituations().contains(20)
                && sig.getCertRevokedAt() != null;
    }

    /**
     * Závorka za větou o chybějícím využitelném údaji: dokument nějaký nese, jen se použít nedal.
     *
     * <p>Rozdíl, na kterém čtenáři záleží. „Nepodařilo se získat" samo o sobě zní, jako by
     * v dokumentu nebylo nic a stačilo se lépe připojit k síti; tady je ale materiál na místě
     * a vadu má on, ne spojení. Prázdné u podpisu, kterému vložený údaj opravdu chybí — tam by
     * závorka tvrdila o dokumentu něco, co v něm není.
     *
     * <p>Ptá se jen na podpisový certifikát, protože o něm mluví věta, ke které se závorka
     * připojuje. Vložený údaj o vydavateli nebo o certifikátu razítka na jejím sdělení nic nemění.
     *
     * <p>Zajímá ji <b>obojí</b> — údaj odmítnutý i ten, který AnEP vede mezi přijatelnými. To
     * druhé není rozpor: tahle věta se píše jen tam, kde se stav revokace zjistit nepodařilo,
     * takže „přijatelný" tu znamená nanejvýš „vada se mu nevytkla", ne „závěr se o něj opřel".
     * Přesně tak vypadá situace 35: materiál v dokumentu je v pořádku, chybí jen důkaz, že tam
     * byl, dokud šel ověřit. Rozlišovat ty dva případy závorkou by čtenáři podsouvalo rozdíl,
     * který pro jeho otázku žádný není — potkáno na dvojici „PAdES B-LT viditelny PS.pdf"
     * a „PAdES B-LT neviditelny PS.pdf", které se liší jen způsobem vložení OCSP odpovědi.
     *
     * <p>Ptá se proto {@link ValidationCourse#signingCertHasEmbeddedRevocation()}, tedy příznaku
     * spočítaného přes všechny záznamy, ne rozkladu prvního z nich. Chvíli tu stálo to druhé
     * a věta se měnila mezi dvěma běhy téhož sestavení podle toho, který údaj dorazil dřív.
     */
    private static String embeddedUnusableNote(ValidationCourse vc) {
        return vc != null && vc.signingCertHasEmbeddedRevocation()
                ? " (vložené revokační informace nelze využít)" : "";
    }

    /**
     * Revokační údaj, ke kterému se ověřování nedostalo.
     *
     * <p>Říká obojí, a v tomhle pořadí: <b>co dokument nese</b> a <b>proč z toho nic neplyne</b>.
     * Samotné „nepodařilo se získat využitelné informace" bylo nepravdivé — údaj v dokumentu je
     * a přípustný je; „byla využita" by ale bylo nepravdivé taky, protože ověřování se k němu
     * nedostalo. Mezi tím je rozdíl, který si model vede od začátku (přípustnost × využití) a
     * který se do textu musel dostat, aby čtenář nehledal v Podrobném výčtu popření téhle věty.
     */
    private static String notReachedRevocationText(SignatureSummary sig, ValidationCourse vc) {
        ValidationCourse.RevocationFact f = vc.signingCertRevocation();
        StringBuilder sb = new StringBuilder("**Revokační informace.** Dokument nese ");
        sb.append(f.ocsp() ? "odpověď OCSP" : "seznam CRL");
        if (f.issued() != null) sb.append(" z ").append(shortDate(f.issued()));
        sb.append(", podle ").append(f.ocsp() ? "níž " : "něhož ")
          .append(gendered(sig, "podpisový certifikát", "certifikát pečeti", "certifikát razítka"))
          .append(f.revoked() ? " revokován byl" : " revokován není")
          .append(". Ověřování se k němu ale nedostalo: bez kotvy důvěry se stav revokace"
                + " nezjišťuje, a odpověď podepsanou tímtéž neznámým vydavatelem by nebylo"
                + " čím ověřit.");
        return sb.toString();
    }

    /** Datum vydání vloženého revokačního údaje, o který se závěr opřel; {@code null}, není-li. */
    private static java.time.Instant embeddedRevocationIssued(ValidationCourse vc) {
        ValidationCourse.RevocationFact f = vc != null ? vc.signingCertRevocation() : null;
        return f != null && "document".equals(f.origin()) ? f.issued() : null;
    }

    /**
     * Varování k položce „Revokační informace“ — s oběma daty i odstupem mezi nimi.
     *
     * <p>Odstup je tu to podstatné: u zdejších příkladů dělí vydání vloženého údaje od odvolání
     * dvacet minut. Bez toho čísla si čtenář přečte dvě data a nic mu neřeknou.
     */
    private static String maskedRevocationWarning(SignatureSummary sig, ValidationCourse vc) {
        java.time.Instant revoked = sig.getCertRevokedAt();
        java.time.Instant issued = embeddedRevocationIssued(vc);
        StringBuilder sb = new StringBuilder("**Pozor:** revokační informace, které se podařilo"
                + " získat, prokazují, že ")
                .append(gendered(sig, "podpisový certifikát", "certifikát pečeti",
                        "certifikát razítka"))
                .append(" byl revokován ").append(revokedAt(revoked))
                .append(" — ještě před rozhodným okamžikem, ke kterému se platnost posuzuje.");
        if (issued != null) {
            // Týž formát jako u odvolání o řádek výš: věta ty dva okamžiky přímo srovnává a odstup
            // mezi nimi je jejím sdělením. Jeden člen dvojice se sekundami a pásmem a druhý bez nich
            // by čtenáři spárování ztížil právě tam, kde na něm záleží.
            sb.append(" Ověření podle standardů se ale opřelo o revokační informaci vloženou"
                    + " v dokumentu, vydanou ").append(TimeUtil.format(issued))
              .append(", tedy o ").append(preciseGap(issued, revoked))
              .append(" dřív; ta o revokaci ještě neví a tvrdí, že certifikát revokován nebyl.");
        } else {
            sb.append(" Ověření podle standardů se ale opřelo o starší revokační informaci vloženou"
                    + " v dokumentu, podle níž certifikát revokován nebyl.");
        }
        return sb.append(" **Vložená informace tak revokaci zakrývá.**").toString();
    }

    /**
     * Je ten údaj „z té doby“ — tedy vznikl ještě za platnosti certifikátu, o kterém vypovídá?
     *
     * <p>Jen o takovém se dá říct, že by o rozhodném okamžiku vypovídat mohl, kdyby ho šlo použít.
     * Údaj stažený až po expiraci mluví o době, kdy certifikát dávno neplatil, a věta „údaj z té
     * doby použít nešlo“ by o něm tvrdila něco jiného, než co je.
     */
    private static boolean contemporaryWithValidity(ValidationCourse.RevocationFact f,
                                                    java.time.Instant certUntil) {
        return f != null && (certUntil == null || f.issued() == null
                || !f.issued().isAfter(certUntil));
    }

    /** Totéž pro Závěr — bez dat a odstupu, aby se čtenáři neopakovalo, co četl o kus výš. */
    private static String maskedRevocationRestated(SignatureSummary sig) {
        return "**Pozor:** revokační informace, které se podařilo získat, přitom prokazují, že "
                + gendered(sig, "podpisový certifikát", "certifikát pečeti", "certifikát razítka")
                + " byl revokován ještě před rozhodným okamžikem. Vložený údaj je starší a o revokaci"
                + " ještě neví — **výsledek se tedy opírá o tvrzení, které pozdější zjištění"
                + " popírá.**";
    }

    /** Jaká podoba položky „Revokační informace“ připadá v úvahu — viz {@link #revocationItem}. */
    enum RevocationItem {
        /** Původní výklad podle {@link #revocationText}, včetně Podrobného výčtu. */
        AS_IS,
        /** Jedna věta: využitelný údaj o stavu revokace se získat nepodařilo. */
        UNUSABLE,
        /** Jedna věta červeně: údaje se získat podařilo, využít je nelze, a indikují odvolání. */
        REVOKED,
        /** Položka se neuvádí vůbec. */
        OMITTED,
        /**
         * Údaj v dokumentu je a je přípustný, ověřování se k němu ale nedostalo: řetěz nevede
         * k žádné kotvě důvěry.
         *
         * <p>Otázka uživatele z 20. 9. 2026: „když AnEP/DSS neví, zda může podpisovému
         * certifikátu důvěřovat, protože nezná jeho vydavatele, může důvěřovat revokačním
         * informacím k tomu certifikátu — od vydavatele, kterého nezná — a využít je?" Nemůže,
         * a texty to musí říct: odpověď OCSP i seznam CRL podepisuje týž neznámý vydavatel
         * (nebo respondér, kterého pověřil), takže jejich odpověď platí přesně tolik co klíč,
         * kterým jsou podepsány. Odvozovat z nich důvěru v certifikát je kruhem.
         *
         * <p>Věcně je to táž hranice, jakou vede ETSI EN 319 102-1 v článku 5.2.6: revokace se
         * zjišťuje <b>uvnitř</b> ověření certifikačního řetězu, až po sestavení cesty ke kotvě.
         * Bez kotvy blok skončí na {@code NO_CERTIFICATE_CHAIN_FOUND} a na revokaci nedojde.
         */
        NOT_REACHED
    }

    /**
     * Co o revokaci říct — podle verdiktu a zjištěných situací.
     *
     * <p>U {@code TOTAL_PASSED} i {@code TOTAL_FAILED} zůstává výklad beze změny: tam se o stavu
     * revokace rozhodlo a odstavec má co popisovat. Neurčitý výsledek je jiný případ — výklad
     * psaný pro rozhodnutí, které nepadlo, čtenáři podsouvá zjištění, jež ověření neudělalo.
     *
     * <p>Pořadí větví je zároveň jejich předností a rozhoduje o něm to, co má pro čtenáře větší
     * váhu, ne velikost skupiny. <b>Červená věta je první:</b> tvrdí konkrétní zjištění — že
     * získané údaje indikují odvolání — a to nemá ustoupit ani sdělení, že využitelný údaj
     * chybí, ani úplnému vynechání položky. Ke střetům dochází pravidelně: situace 30 se hlásí
     * vždy spolu s 21 a běžně i vedle 6, a u podpisu úrovně B po expiraci certifikátu k tomu
     * ještě přibývá situace 3 — v kurátorovaném korpusu takových podpisů bylo 11 z 233
     * a osmi z nich sama knihovna DSS vrací {@code REVOKED_NO_POE}.
     *
     * <p>Teprve za ní stojí situace 3 a 39, které brání tomu, aby o revokaci bylo co říct
     * (expirovaný certifikát bez důvěryhodného razítka, resp. porušená integrita dat krytých
     * evidenčním záznamem), a úplně nakonec neutrální věta o chybějícím údaji.
     */
    static RevocationItem revocationItem(SignatureSummary sig, ValidationCourse vc,
                                                 boolean passed, boolean failed) {
        if (passed || failed) return RevocationItem.AS_IS;
        List<Integer> found = sig.getDetectedSituations();
        if (found.contains(20) || found.contains(21) || found.contains(30)) {
            return RevocationItem.REVOKED;
        }
        if (found.contains(3) || found.contains(39)) return RevocationItem.OMITTED;
        // Bez kotvy důvěry se na revokaci vůbec nedojde — viz {@link RevocationItem#NOT_REACHED}.
        // Musí to stát před výčtem níž: situace 9 tu bývá taky (on-line dotaz opravdu selhal)
        // a věta „nepodařilo se získat využitelné informace" by o vloženém údaji lhala podruhé,
        // jen naopak.
        if (chainNotBuilt(sig) && revocationEstablished(vc)) return RevocationItem.NOT_REACHED;
        for (int s : new int[] {5, 6, 7, 8, 29, 35, 45}) {
            if (found.contains(s)) return RevocationItem.UNUSABLE;
        }
        // Situace 9 mluví o síti, ne o tom, co dokument nese. Do 20. 9. 2026 byla v témže výčtu
        // jako ostatní a odstavec z ní dělal „nepodařilo se získat využitelné informace (vložené
        // revokační informace nelze využít)" — i nad podpisem, jehož stav revokace zjistila
        // vložená odpověď OCSP. Nález uživatele: týž běh o ní zároveň psal, že vznikla deset
        // minut před podpisem a okamžik podepsání pokrývá (situace 51), a Detailní rozbor ji
        // uváděl se stavem „V pořádku". Tři místa, dvě z nich pravdivá.
        //
        // Ptá se proto na průběh, ne na výčet situací: stav se zjistit podařilo, nebo nepodařilo
        // — a to ví ValidationCourse, ať už údaj přišel z dokumentu, z vlastního úložiště, nebo
        // ze sítě.
        if (found.contains(9) && !revocationEstablished(vc)) return RevocationItem.UNUSABLE;
        return RevocationItem.AS_IS;
    }

    /**
     * Podařilo se stav revokace podpisového certifikátu zjistit?
     *
     * <p>Ne „byl k dispozici nějaký údaj": rozhoduje, že z něj plyne stav. {@code statusUnknown}
     * pokrývá případ, kdy údaj dorazil, ale odpověděl „nevím".
     */
    private static boolean revocationEstablished(ValidationCourse vc) {
        ValidationCourse.RevocationFact f = vc != null ? vc.signingCertRevocation() : null;
        return f != null && !f.statusUnknown();
    }

    /**
     * Třetí odstavec: co se o stavu revokace podařilo zjistit.
     *
     * <p>Mluví adresně o <b>podpisovém certifikátu</b> — na něm výsledek stojí — a zbytek řetězců
     * shrne jednou větou. Jmenovat každý certifikát by z odstavce udělalo tentýž seznam, jaký je
     * o kus níž v Podrobném výčtu, jen hůř čitelný.
     */
    static String revocationText(SignatureSummary sig, ValidationCourse vc,
                                          java.time.Instant certUntil, boolean passed) {
        StringBuilder sb = new StringBuilder("**Revokační informace.** ");
        List<Integer> found = sig.getDetectedSituations();

        if (vc.signingCertRevocation() != null) {
            sb.append(revocationSentence(sig, vc.signingCertRevocation(), certUntil, true));
            // Použitelný údaj sám o sobě neznamená, že se stav k rozhodnému okamžiku podařilo
            // zjistit: odpověď stažená dnes vypovídá o dnešku. Když se přitom údaj z té doby
            // odmítl, je to důvod, proč ověření skončilo jako neprůkazné — a bez téhle věty
            // odstavec končil zjištěním, ze kterého závěr o kus níž nijak neplynul.
            //
            // Doslova to říká protokol on-line DSS nad „PAdES B-LT neviditelny PS.pdf":
            // „The signing certificate is not known to be not revoked! Current time is not in
            // the validity range of the revocation data issuer's certificate!"
            //
            // Věta ale mluví o údaji **z té doby** — tedy o takovém, který vznikl ještě za
            // platnosti certifikátu a o rozhodném okamžiku by tudíž vypovídat mohl. Odmítnutá
            // odpověď stažená dnes tou dobou není a věta o ní tvrdí nesmysl. Potkáno na
            // „LT uznavany pred expiraci_Do_03_08_2034.pdf": závěr stojí na vloženém seznamu CRL
            // z 27. 11. 2020, tedy na současníku podpisu, a věta k němu přilepila zmínku
            // o odpovědi OCSP z 25. 8. 2026 — o šest let mladší než expirace certifikátu.
            // ... a jen tam, kde ověření opravdu neobstálo. Kde obstálo, je stav k rozhodnému
            // okamžiku zjištěný — ť už tím, že údaj žádnou revokaci nehlásí, nebo tím, že ji hlásí
            // až po něm — a věta „údaj z té doby použít nešlo“ pak nevysvětluje nic; jen říká
            // o využitém údaji, že nestačil, a popíše místo něj jiný.
            //
            // Potkáno na „PAdES LT kvalifikovny viditelny 1.pdf“: podpis je platný, protože
            // stažená odpověď OCSP hlásí revokaci až tři měsíce po rozhodném okamžiku — a odstavec
            // přesto pokračoval popisem vložené odpovědi ze 7. 6. 2025, kterou použít nešlo.
            // Závěr o kus níž přitom říká pravý opak: revokace přišla až později, podpis je platný.
            if (!passed
                    && contemporaryWithValidity(vc.signingCertRevocationUnusableFact(), certUntil)) {
                sb.append(" Ta ale vypovídá o dnešku, ne o rozhodném okamžiku, a údaj z té doby"
                        + " použít nešlo: ")
                  .append(revocationSentenceTail(vc.signingCertRevocationUnusableFact(), certUntil));
            }
        } else if (vc.signingCertRevocationUsed() != null) {
            sb.append("U ").append(elementCert(sig, Case.GENITIV))
              .append(" se stav revokace zjistit podařilo: ");
            sb.append(sig.isCertRevoked()
                    ? "certifikát byl revokován" + (sig.getCertRevokedAt() != null
                            ? " " + revokedAt(sig.getCertRevokedAt()) : "") + "."
                    : "revokován nebyl.");
        } else if (vc.signingCertRevocationUnusableFact() != null) {
            sb.append(revocationSentence(sig, vc.signingCertRevocationUnusableFact(), certUntil,
                    false));
            sb.append(otherRevocationNote(sig));
        } else if (vc.signingCertRevocationUnusable() != null) {
            // Bez rozkladu — takový je jen údaj přeskočený ve prospěch vlastní CRL, který se
            // o standardy neopírá vůbec.
            sb.append("U ").append(elementCert(sig, Case.GENITIV))
              .append(" se údaj o stavu revokace našel, **použít jej ale nešlo**");
            sb.append(found.contains(7) ? ": respondér stav toho certifikátu nezná." : ".");
            sb.append(otherRevocationNote(sig));
        } else if (vc.signingCertRevocationMissing() != null) {
            sb.append("O stavu ").append(elementCert(sig, Case.GENITIV))
              .append(" se nepodařilo zjistit nic — žádný údaj se nepovedlo získat.");
        } else if (!vc.revocationNotApplicable().isEmpty()) {
            // Certifikát podepsaný sám sebou, který ve vlastním úložišti důvěryhodných certifikátů
            // není, kotvou důvěry <b>není</b> — a věta o důvěryhodném seznamu mu takové postavení
            // přisuzovala. Nález uživatele z 21. 9. 2026 na „selfsigned s krivkou 3.pdf": týž běh
            // o kus výš správně hlásí, že se řetěz nepodařilo sestavit k žádné kotvě, a Podrobný
            // výčet u téhož certifikátu píše „revokační záznam pro něj nikdo nevydává".
            //
            // Rozhoduje situace 12, tedy že v podpisovém řetězu není jediný důvěryhodný
            // certifikát — týž test, jakým to měří Podrobný výčet
            // (ReportMapper.describeTrustAnchorLeaf se ptá na !c.isTrusted()). Vlastnoručně
            // podepsaný certifikát vložený do úložiště kotvou důvěry je a věta o seznamu u něj
            // platí dál.
            sb.append(sig.isCertSelfSigned() && found.contains(12)
                    ? "U " + elementCert(sig, Case.GENITIV) + " se stav revokace nezjišťoval:"
                      + " je podepsaný sám sebou a pro takový certifikát žádné revokační"
                      + " informace nevznikají — nemá je kdo vydávat."
                    : "U " + elementCert(sig, Case.GENITIV) + " se stav revokace nezjišťoval:"
                      + " je sám kotvou důvěry a o jeho postavení rozhoduje důvěryhodný seznam,"
                      + " nikoli CRL nebo OCSP.");
        } else {
            sb.append("O ").append(elementCert(sig, Case.LOKAL))
              .append(" se stav revokace nezjišťoval.");
        }

        // Zbytek řetězců jednou větou; podrobnosti nese Podrobný výčet. Věta mluví o tom, že se
        // údaje *podařilo získat*, takže se počítají obě skupiny přípustných — i ta, na které
        // závěr nakonec nestojí. Po rozdělení seznamu na „využita" a „nevyužita" by jinak věta
        // zmizela u řetězců, kde se údaj získal, jen o něj nebylo třeba se opřít.
        int others = vc.revocationUsed().size() + vc.revocationNotDecisive().size()
                - (vc.signingCertRevocationUsed() != null ? 1 : 0);
        boolean othersMissing = vc.revocationMissing().size()
                - (vc.signingCertRevocationMissing() != null ? 1 : 0) > 0;
        if (othersMissing) {
            sb.append(" U některého z dalších certifikátů v řetězcích údaj chybí; které to jsou,"
                    + " stojí v podrobném výčtu.");
        } else if (vc.timestampCertRevocation() != null) {
            sb.append(" ").append(availableRevocationSentence(vc.timestampCertRevocation()));
        } else if (others > 0) {
            sb.append(" K ostatním certifikátům — v řetězci ").append(elementCert(sig, Case.GENITIV))
              .append(" i u časových razítek — se použitelné údaje získat podařilo.");
        }
        return sb.toString();
    }

    /**
     * Věta o jednom použitém revokačním údaji: odkud je, kdy vznikl, co říká — a když ho standardy
     * samy o sobě nepřipouštějí, i čemu vděčí za to, že se přesto použil.
     *
     * <p>Poslední část je ta podstatná. Bez ní čte uživatel v tolerantním režimu, že se stav
     * revokace „zjistit podařilo“, a nic mu nenapoví, že za to vděčí aktivovanému ústupku a že bez
     * něj by týž dokument dopadl jinak.
     *
     * @param certUntil konec platnosti dotčeného certifikátu, kvůli větě o post-expiračním údaji;
     *                  {@code null}, když se nemá uvádět
     */
    private static String revocationSentence(SignatureSummary sig,
                                             ValidationCourse.RevocationFact f,
                                             java.time.Instant certUntil, boolean used) {
        boolean fem = f.ocsp();                       // „odpověď OCSP" × „seznam CRL"
        String what = f.ocsp() ? "odpověď OCSP" : "seznam CRL";
        // Uvození pojmenovává úkon, ne jen cíl: čtenář se ptá, čím se stav revokace zjišťoval,
        // a „k podpisovému certifikátu" na to samo neodpovídá.
        StringBuilder sb = new StringBuilder("K ověření stavu revokace "
                + elementCert(sig, Case.GENITIV) + " ");
        // Úvod se řídí tím, jestli se údaj opravdu použil. Dřív tvrdil „byla využita" vždycky
        // a spoléhal na to, že si to konec věty vezme zpátky — jenže ta oprava se připojuje jen
        // u vady, na kterou míří nějaký ústupek, takže u ostatních zůstalo holé nepravdivé
        // tvrzení. Potkáno na „LT po revokaci later LTA timestamped.pdf".
        switch (f.origin()) {
            case "document" -> sb.append(used
                        ? (fem ? "byla využita vložená " : "byl využit vložený ")
                        : (fem ? "byla k dispozici vložená " : "byl k dispozici vložený "))
                    .append(what);
            case "store"    -> sb.append(used ? "posloužil" + (fem ? "a " : " ") + what
                            + " z vlastního úložiště"
                    : "byl" + (fem ? "a " : " ") + what + " z vlastního úložiště k dispozici");
            // Stažený údaj musí zůstat v 1. pádě, protože věta za ním pokračuje přívlastkem
            // „, vydaná …". Stálo tu „se podařilo získat odpověď OCSP on-line, vydaná …", což
            // je rozpor v pádech; větev byla dlouho téměř nedosažitelná, protože stažený údaj
            // vycházel jako využitý jen zřídka.
            default          -> sb.append(used
                        ? (fem ? "byla využita " : "byl využit ")
                        : (fem ? "byla " : "byl "))
                    .append(what)
                    .append(used ? (fem ? " získaná on-line" : " získaný on-line")
                                 : " k dispozici on-line");
        }
        // Bez závorky: plný zápis okamžiku svou závorku pro místní čas už má a druhá kolem něj
        // dělá „(vydán … (…))". Přístavek oddělený čárkami čte se stejně a nezávorčkuje se.
        if (f.issued() != null) {
            sb.append(", vydan").append(fem ? "á " : "ý ").append(shortDate(f.issued()));
        }
        sb.append(", podle ").append(fem ? "níž " : "něhož ")
          .append(f.statusUnknown() ? "stav certifikátu není znám"
                  : f.revoked()
                      ? "byl certifikát revokován" + (f.revokedAt() != null
                              ? " " + revokedAt(f.revokedAt()) : "")
                      : "certifikát revokován není")
          .append(".");

        // Údaj, o který se závěr opřel, ačkoli jeho vydavateli mezitím skončila platnost:
        // vsuvka se vynechá celá.
        //
        // Ta vsuvka existuje proto, aby vysvětlila, <b>proč údaj v tomhle běhu neobstál</b>.
        // Jenže on obstál a závěr na něm stojí, takže nemá co vysvětlovat. Sdílený závěr věty
        // („standardy jej nepřipouštějí — díky aktivovanému ústupku se však použil") by tu lhal
        // dvakrát: k rozhodnému okamžiku vydavatel platil, takže standardy ten údaj připouštěly,
        // a žádný ústupek zapnutý nebyl.
        //
        // Chvíli tu místo toho stála věta, že na ten údaj pozdější ověřovatel už nedosáhne.
        // Je pravdivá, ale je to <b>výhled, ne průběh</b> — a hned za větou „byla využita" se
        // čte jako její popření. Uživatele zmátla dvakrát po sobě (13. 9. 2026). Patří do
        // záložky Digitální kontinuita, která je celá o tom, do kdy ověřitelnost vydrží;
        // odstavec o proběhlém ověření má popisovat, co se stalo.
        if (used && "expiredIssuerNoProof".equals(f.relaxedRule())) {
            return sb.toString();
        }

        if (f.relaxedRule() != null) {
            // Proč údaj nevyhovuje přísnému čtení. Táž věta slouží oběma režimům; liší se jen
            // tím, jak končí — v tolerantním „přesto se použil", v přísném „použít nešlo".
            // Dřív to přísný režim neřekl vůbec a čtenář se u téhož podpisu nedozvěděl, odkud
            // údaj je, ačkoli v tolerantním to stálo hned v první větě.
            if ("respondér".equals(f.relaxedRule()) || f.statusUnknown()) {
                sb.append(" Respondér ale stav toho certifikátu nezná");
            } else {
                sb.append(" ").append(fem ? "Vznikla" : "Vznikl");
                if ("postExpiry".equals(f.relaxedRule())) {
                    sb.append(" až po skončení řádné doby platnosti certifikátu")
                      .append(certUntil != null ? ", která skončila " + shortDate(certUntil) : "");
                } else if ("expiredIssuer".equals(f.relaxedRule())) {
                    sb.append(" v pořádku, ale vydavateli mezitím vypršel vlastní certifikát");
                } else if ("expiredIssuerNoProof".equals(f.relaxedRule())) {
                    // Nesmí končit spojkou: sdílený závěr věty připojuje vlastní „takže".
                    sb.append(" v pořádku a vydavatel tehdy platil, jenže od té doby mu platnost"
                            + " skončila a chybí archivní časové razítko z doby, kdy ještě platil");
                } else {
                    sb.append(" v podobě, kterou přísná pravidla nepřipouštějí");
                }
            }
            sb.append(", takže ").append(fem ? "ji " : "jej ")
              .append("standardy nepřipouštějí — ")
              .append(used
                      ? "**díky aktivovanému ústupku se však použil" + (fem ? "a" : "") + "**."
                      : "**použít " + (fem ? "ji" : "jej") + " proto nešlo**.");
        }
        return sb.toString();
    }

    /**
     * Krátký tvar věty o odmítnutém údaji — čím byl, kdy vznikl a proč použít nešel.
     *
     * <p>Plná věta z {@link #revocationSentence} se sem nehodí: uvozuje se „K ověření stavu
     * revokace podpisového certifikátu…", a to už v odstavci jednou zaznělo o kus výš.
     */
    private static String revocationSentenceTail(ValidationCourse.RevocationFact f,
                                                 java.time.Instant certUntil) {
        boolean fem = f.ocsp();
        StringBuilder sb = new StringBuilder(switch (f.origin()) {
            case "document" -> fem ? "vložená odpověď OCSP" : "vložený seznam CRL";
            case "store"    -> fem ? "odpověď OCSP z vlastního úložiště"
                                   : "seznam CRL z vlastního úložiště";
            default          -> fem ? "odpověď OCSP získaná on-line" : "seznam CRL získaný on-line";
        });
        if (f.issued() != null) sb.append(" z ").append(shortDate(f.issued()));
        String rule = f.relaxedRule();
        if (f.statusUnknown() || "respondér".equals(rule)) {
            sb.append(" — respondér stav toho certifikátu nezná");
        } else if ("expiredIssuerNoProof".equals(rule)) {
            sb.append(" — vydavateli od té doby skončila platnost a chybí archivní časové razítko"
                    + " z doby, kdy ještě platil");
        } else if ("expiredIssuer".equals(rule)) {
            sb.append(" — vydavatel měl v době vydání už expirovaný vlastní certifikát");
        } else if ("postExpiry".equals(rule)) {
            sb.append(" — vzniknul až po skončení řádné doby platnosti certifikátu")
              .append(certUntil != null ? ", která skončila " + shortDate(certUntil) : "");
        } else {
            sb.append(" — v podobě, kterou přísná pravidla nepřipouštějí");
        }
        return sb.append(".").toString();
    }

    /**
     * Kratší tvar téže věty pro certifikát razítkovací autority, kterým odstavec končí.
     *
     * <p>Plná vazba („se podařilo získat odpověď OCSP on-line…“) je tu podruhé za sebou
     * a odstavec by se o ni
     * zadrhl. Kratší tvar navíc odpovídá tomu, čím ta věta je: doplněním, ne zjištěním, o které
     * by se výsledek opíral.
     */
    private static String availableRevocationSentence(ValidationCourse.RevocationFact f) {
        boolean fem = f.ocsp();
        StringBuilder sb = new StringBuilder(
                "K ověření stavu revokace certifikátu razítkovací autority byl");
        sb.append(fem ? "a k dispozici odpověď OCSP" : " k dispozici seznam CRL");
        if (f.issued() != null) sb.append(" z ").append(shortDate(f.issued()));
        sb.append(", podle ").append(fem ? "níž " : "něhož ")
          .append(f.revoked()
                  ? "byl revokován" + (f.revokedAt() != null ? " " + revokedAt(f.revokedAt()) : "")
                  : "revokován není")
          .append(".");
        return sb.toString();
    }

    /** Čtvrtý odstavec: k čemu to všechno vedlo. */
    private String conclusionText(SignatureSummary sig, ValidationCourse vc,
                                   boolean passed, boolean failed, java.time.Instant certUntil) {
        StringBuilder sb = new StringBuilder("**Závěr.** ");

        if (passed) {
            StringBuilder body = new StringBuilder();
            java.time.Instant bst = vc.provenExistence() != null
                    ? vc.provenExistence() : vc.dssBestSignatureTime();
            // Zamaskovaná revokace se nedá popsat větou „certifikát byl platný a revokován nebyl":
            // odvolán byl, jen o tom údaj, na kterém verdikt stojí, nevěděl. Věta musí pojmenovat
            // ten údaj, ne stav certifikátu — jinak Závěr tvrdí opak toho, co říká varování
            // o kus výš.
            if (maskedRevocation(sig, true)) {
                body.append(bst != null
                        ? "K rozhodnému okamžiku " + shortDateTime(bst) + " se podle standardů"
                        : "Podle standardů se");
                body.append(" využily revokační informace vložené v dokumentu, podle nichž ")
                  .append(gendered(sig, "podpisový certifikát", "certifikát pečeti",
                          "certifikát razítka"))
                  .append(" revokován nebyl. **")
                  .append(gendered(sig, "Podpis je proto hodnocen jako platný",
                          "Pečeť je proto hodnocena jako platná",
                          "Razítko je proto hodnoceno jako platné"))
                  .append(".**");
            } else if (sig.isAnchorCert()) {
                // Kotva důvěry: „byl platný a revokován nebyl" by tvrdilo dvě zjištění, která
                // tenhle běh neudělal — doba platnosti kotvy se neposuzuje a revokace se u ní
                // nezjišťuje. Rozhoduje to, že ji jako kotvu vede důvěryhodný seznam (nebo
                // vlastní úložiště).
                body.append(bst != null
                        ? "K rozhodnému okamžiku " + shortDateTime(bst) + " byl "
                        : capitalize(elementCert(sig, Case.NOMINATIV)) + " byl v rozhodném okamžiku ");
                if (bst != null) body.append(elementCert(sig, Case.NOMINATIV)).append(" ");
                body.append(sig.isTrustedViaCustomStore()
                        ? "kotvou důvěry z vlastního úložiště certifikátů aplikace"
                        : "kotvou důvěry: důvěryhodný seznam ho vede jako identitu služby")
                  .append(". U kotvy se neposuzuje doba platnosti ani stav revokace. **")
                  .append(gendered(sig, "Podpis je proto platný", "Pečeť je proto platná",
                          "Razítko je proto platné")).append(".**");
            } else {
                // Je-li rozhodným okamžikem čas zadaný v nastavení, má to Závěr říct: jinak čte
                // datum jako zjištění z dokumentu, ačkoli ho zvolil ten, kdo ověřuje.
                java.time.Instant chosen = chosenValidationTime(sig);
                boolean atChosen = chosen != null && bst != null
                        && Math.abs(java.time.Duration.between(chosen, bst).getSeconds()) <= 1;
                body.append(atChosen
                        ? "K zadanému okamžiku " + shortDateTime(bst)
                          + " (zvolenému v nastavení) byl "
                          + elementCert(sig, Case.NOMINATIV) + " platný"
                        : bst != null
                        ? "K rozhodnému okamžiku " + shortDateTime(bst) + " byl "
                          + elementCert(sig, Case.NOMINATIV) + " platný"
                        : capitalize(elementCert(sig, Case.NOMINATIV))
                          + " byl v rozhodném okamžiku platný");
                // Jen odvolání, ke kterému opravdu došlo později. Dřív tu podmínka na pořadí
                // chyběla a nad zamaskovanou revokací vycházelo „revokace přišla až 28. 4., tedy
                // o několik hodin později" — ačkoli k němu došlo den *před* razítkem.
                if (sig.isCertRevoked() && sig.getCertRevokedAt() != null && bst != null
                        && sig.getCertRevokedAt().isAfter(bst)) {
                    // Odkud to datum je. Hlásí-li odvolání sám údaj, o který se závěr opřel,
                    // zdroj už zazněl v odstavci o revokačních informacích a opakovat se nemusí.
                    // Přichází-li odjinud — typicky z odpovědi stažené dnes, kterou tentýž
                    // odstavec uvádí jako nevyužitou —, musí se to říct: bez toho věta tvrdí
                    // datum, které podle jmenovaného údaje („revokován není") nemá odkud vzít,
                    // a čtenář hledá rozpor, který tam není. Pokyn uživatele 13. 9. 2026.
                    boolean revokedBySameEntry = vc.signingCertRevocation() != null
                            && vc.signingCertRevocation().revoked();
                    body.append(revokedBySameEntry
                            ? " a revokován nebyl — revokace přišla až "
                            : " a revokován nebyl — podle jiného revokačního údaje přišla"
                              + " revokace až ")
                      .append(revokedAt(sig.getCertRevokedAt()))
                      .append(", tedy ").append(gapPhrase(bst, sig.getCertRevokedAt()))
                      .append(" později.");
                } else {
                    body.append(" a revokován nebyl.");
                }
                body.append(" **").append(gendered(sig, "Podpis je proto platný",
                        "Pečeť je proto platná", "Razítko je proto platné")).append(".**");
            }
            // A „stojí to celé na“ platí jen o změřeném výčtu. Odvozený výčet říká jen to, že
            // tolerance byla zapnutá a dokument má něco, čeho se týká — ne že na ní verdikt stojí.
            if (!sig.getDecisiveSettings().isEmpty() && sig.isDecisiveSettingsMeasured()) {
                // Jednotné číslo a „bez něj" platí jen o nezastupitelném ústupku. Zastupují-li se,
                // je pravdivé tvrzení slabší: bez nich všech by nebylo čím rozhodnout, ale žádný
                // z nich to nenese sám. Viz appendVerificationMode.
                //
                // Počet se bere z toho, co blok nad Závěrem opravdu vypsal. Při ověřování
                // k zadanému času tam přibývá ústupek, který si zadaný okamžik uděluje sám, a Závěr
                // pak mluvil o „uvedeném ústupku“ v jednotném čísle nad výčtem dvou.
                int shown = shownConcessionCount(sig, vc);
                body.append(sig.isDecisiveSettingsInterchangeable()
                        ? " Stojí to ale celé na uvedených ústupcích: bez nich by o stavu"
                          + " certifikátu nebylo čím rozhodnout a stav platnosti by zůstal"
                          + " neznámý. Stačí přitom kterýkoli z nich."
                        : shown > 1
                        ? " Stojí to ale celé na uvedených ústupcích: bez kteréhokoli z nich by"
                          + " o stavu certifikátu nebylo čím rozhodnout a stav platnosti by zůstal"
                          + " neznámý."
                        : " Stojí to ale celé na uvedeném ústupku: bez něj by o stavu certifikátu"
                          + " nebylo čím rozhodnout a stav platnosti by zůstal neznámý.");
            }
            if (chosenValidationTime(sig) != null) {
                body.append(" Výsledek platí k zadanému okamžiku, ne k dnešku.");
            }
            // Integrita napřed (pokyn uživatele 22. 9. 2026): Závěr konstatoval
            // platnost z doby platnosti certifikátu a stavu revokace, ale to
            // nejzákladnější — že podepsaný obsah je nedotčený — nechával nevyřčené,
            // ačkoli kladný verdikt na něm stojí jako na prvním kroku.
            return sb.append("Integrita není porušena; ")
                    .append(uncapitalize(body.toString())).toString();
        }

        if (failed) {
            sb.append("**").append(gendered(sig, "Podpis je neplatný", "Pečeť je neplatná",
                    "Razítko je neplatné")).append(".** Důvod je rozebrán v bloku"
                    + " Zjištěné důvody a doporučení.");
            return sb.toString();
        }

        // Neznámý výsledek. Závěr smí přidávat jen to, co nad ním ještě nezaznělo: že razítko
        // chybí nebo neobstálo, řekl druhý odstavec, konec platnosti certifikátu první. Původní
        // znění (z bloku, kde stálo samo) obojí opakovalo i s daty — čtenář to pak čte podruhé
        // a napotřetí přestane číst.
        java.time.Instant tsuEnd = validTimestampChainReach(sig);
        boolean strictRejects = !timestampStandardsMode
                && tsuEnd != null && !tsuEnd.isAfter(java.time.Instant.now());
        boolean poeMissing = strictRejects || (sig.getAllTimestamps().isEmpty()
                ? certUntil != null && !certUntil.isAfter(checkTimeOf(sig))
                : sig.getAllTimestamps().stream()
                        .noneMatch(SignatureSummary.TimestampEntry::passed));
        List<Integer> found = sig.getDetectedSituations();
        // Řekl odstavec o kus výš, že se revokační údaj využil? Závěr s ním musí souhlasit,
        // ať už tím, že to zopakuje, nebo tím, že netvrdí opak. Ptá se na {@link #revocationItem},
        // protože právě ta rozhoduje, která věta v odstavci stojí — {@code
        // signingCertRevocationUsed()} sám nestačí a obě se rozcházejí: u {@code
        // Q ICA pred expiraci 07 2019/PAdES LT.pdf} je vyplněný, ačkoli odstavec kvůli situaci 5
        // hlásí „nepodařilo se získat využitelné informace". Ptát se jen na něj vzalo tomu
        // dokumentu smysluplný závěr a nechalo mu holé „Ověření se nepodařilo dokončit." —
        // naměřeno {@code ConclusionScan}em jako jediný věcný rozdíl na celé sbírce D:/tmp.
        boolean revocationReportedAsUsed =
                revocationItem(sig, vc, passed, failed) == RevocationItem.AS_IS
                        && vc.signingCertRevocationUsed() != null;
        // Pořadí je totéž, jaké se osvědčilo dřív: chybějící důkaz existence napřed. Situace 25
        // se totiž u prvku bez platného razítka opírá jen o *tvrzený* čas podpisu, a tvrzení není
        // zjištění; formát zase o platnosti nevypovídá nic, kdežto chybějící rozhodný okamžik
        // ano.
        //
        // Zastaralý otisk je ale před tím vším, a to na přímý pokyn uživatele (15. 9. 2026) —
        // viz {@link #cryptoDecisive}.
        if (cryptoDecisive(sig)) {
            boolean hopeless = found.contains(16);   // MD5, RIPEMD-160
            // SHA3-224 a spol. (situace 56): slabé nejsou, standardy je jen neuvádějí. Má-li
            // podpis vedle toho i MD5, platí věta o zastaralosti — ta je závažnější. Se SHA-1
            // naopak vyhrává tahle: volnější nastavení by SHA-1 odpustilo, otisk mimo standardy
            // ne, takže věta „uznat ji může volnější nastavení" by slibovala nemožné.
            boolean notAccepted = found.contains(56) && !hopeless;
            if (notAccepted) {
                sb.append("Rozhodla hašovací funkce, na které ")
                  .append(gendered(sig, "podpis stojí", "pečeť stojí", "razítko stojí"))
                  .append(": standardy pro elektronické podpisy (ETSI TS 119 312) ji neuvádějí,"
                          + " a ověřování podle nich ji proto nepřipouští, ačkoli slabá není.");
            } else {
                sb.append("Rozhodla zastaralost hašovací funkce, na které ")
                  .append(gendered(sig, "podpis stojí", "pečeť stojí", "razítko stojí"))
                  .append(": otisk toho druhu se dnes za spolehlivý nepovažuje.");
            }
            // Přímo proti tomu, na co čtenář narazil: odstavec o revokaci hlásí „byla využita",
            // závěr pak „bez použitelného údaje". Kde se stav revokace zjistit podařilo, má to
            // závěr říct — a rovnou i to, že na výsledku to nic nemění.
            if (revocationReportedAsUsed) {
                sb.append(" Stav revokace ")
                  .append(gendered(sig, "podpisového certifikátu", "certifikátu pečeti",
                          "certifikátu razítka"))
                  .append(" se přitom zjistit podařilo; na výsledku to ale nic nemění.");
            }
            sb.append(hopeless
                    ? " **Řešení pro tenhle případ není: takovou funkci nelze uznat ani volnějším"
                            + " nastavením.**"
                    : notAccepted
                    ? " **Žádné nastavení v AnEP to nezmění; validátory, které se řídí jinými"
                            + " pravidly, mohou podpis posoudit jinak.**"
                    : " **Uznat ji může jedině volnější nastavení; ověřování podle standardů"
                            + " ji nepřipouští.**");
        } else if (chainNotBuilt(sig)) {
            // Před chybějícím důkazem existence, a je to oprava z 20. 9. 2026. Podpis, jehož
            // řetěz nevede k žádné známé kotvě, má obvykle i razítko, které se z téhož důvodu
            // nepodařilo ověřit — a závěr pak vyprávěl o tom razítku, ačkoli skutečnou překážkou
            // je certifikát, o kterém aplikace nic neví. Nález uživatele: „skutečná příčina
            // neznámé platnosti — a to neznámý podpisový certifikát — není v průběhu ověřování
            // ani v Závěru zmiňován." Dílčí indikace DSS přitom říká právě tohle.
            sb.append("Řetězec certifikátů ")
              .append(gendered(sig, "podpisu", "pečeti", "razítka"))
              .append(" se nepodařilo sestavit k žádné kotvě důvěry, kterou aplikace zná — ")
              .append(elementCert(sig, Case.NOMINATIV))
              .append(" ani jeho vydavatele nemá odkud znát. Bez toho"
                    + " nelze posoudit ani platnost certifikátu, ani stav jeho odvolání, takže"
                    + " se ověřování k vlastnímu posouzení ")
              .append(gendered(sig, "podpisu", "pečeti", "razítka"))
              .append(" nedostalo. **Není to nález o ")
              .append(gendered(sig, "podpisu", "pečeti", "razítku"))
              .append(", nýbrž o tom, že chybí, oč se opřít.**");
        } else if (poeMissing) {
            if (found.contains(43)) {
                sb.append("Razítko vydala služba, které byl v důvěryhodných seznamech odebrán"
                        + " kvalifikovaný status, a jako důkaz existence se proto nepřijímá. ");
            } else if (found.contains(36)) {
                sb.append("Razítko má porušenou integritu, takže neprokazuje nic. ");
            }
            // Krycí prvek důkaz dodal, jen nepomohl. Odstavec „Důkaz existence v čase" o něm
            // výš píše, takže „bez důkazu existence" a „vznik se prokázat nepodařilo" by mu
            // odporovaly (nález uživatele 2. 10. 2026 nad „ruzne vizualizace.pdf").
            boolean coveredButFailed = sig.getCoveringProofDetail() != null && !found.contains(54);
            if (coveredButFailed) {
                sb.append("Dřívější existenci sice dokládá jiný prvek téhož dokumentu, ověření"
                        + " k tomu okamžiku ale neuspělo ani tak: překážkou není doba vzniku,"
                        + " nýbrž něco jiného.");
            } else {
                sb.append("Bez důkazu existence v čase není k dispozici dřívější rozhodný okamžik,"
                        + " ke kterému by se platnost " + elementCert(sig, Case.GENITIV)
                        + " posuzovala.");
            }
            // A co z toho plyne. Bez téhle věty končí závěr u chybějícího rozhodného okamžiku, což je
            // popis postupu, ne odpověď na otázku, se kterou čtenář přišel: zda ten podpis vznikl,
            // dokud certifikát platil. Neprokázalo se to — a přesně to se má říct, místo aby se to
            // nechalo domyslet. Pokyn uživatele 15. 9. 2026.
            if (!coveredButFailed && certUntil != null && !certUntil.isAfter(checkTimeOf(sig))) {
                sb.append(" **Že ")
                  .append(gendered(sig, "podpis vznikl", "pečeť vznikla", "razítko vzniklo"))
                  .append(" ještě v době platnosti svého certifikátu, se tím prokázat nepodařilo —"
                          + " a bez toho platnost ").append(genitiveType(sig))
                  .append(" potvrdit nelze.**");
            }
        } else if (found.contains(25) || found.contains(26)) {
            // Situace 25 říká jen tolik, že rozhodný okamžik leží za koncem platnosti certifikátu.
            // Jestli to je zjištění o době vzniku, nebo jen chybějící důkaz o ní, rozhoduje až
            // {@link #unprovenTimestampsWithinValidity} — viz tam.
            List<SignatureSummary.TimestampEntry> unproven = found.contains(26)
                    ? List.of() : unprovenTimestampsWithinValidity(sig, certUntil);
            if (!unproven.isEmpty()) {
                boolean one = unproven.size() == 1;
                sb.append(gendered(sig, "Podpis sice nese", "Pečeť sice nese", "Prvek sice nese"))
                  .append(one
                          ? " i razítko s časem " + shortDateTime(unproven.get(0).time())
                                  + ", tedy ještě z doby platnosti certifikátu, ověřit jako platné"
                                  + " se ho ale nepodařilo."
                          : " i razítka ještě z doby platnosti certifikátu, nejstarší s časem "
                                  + shortDateTime(unproven.get(0).time())
                                  + ", ověřit jako platné se ale nepodařilo ani jedno z nich.")
                  .append(" **Že ")
                  .append(gendered(sig, "podpis vznikl", "pečeť vznikla", "razítko vzniklo"))
                  .append(" ještě v době platnosti svého certifikátu, se proto prokázat nepodařilo —"
                          + " a bez toho platnost ").append(genitiveType(sig))
                  .append(" potvrdit nelze.**");
            } else {
                sb.append(gendered(sig, "Podpis tedy vznikl", "Pečeť tedy vznikla",
                                "Razítko tedy vzniklo"))
                  .append(found.contains(26) ? " ještě před začátkem" : " až po skončení")
                  .append(" řádné doby platnosti svého certifikátu. **Certifikát v tu chvíli"
                          + " k podepisování neopravňoval, takže platnost ")
                  .append(genitiveType(sig))
                  .append(" nelze potvrdit — a nezmění na tom nic ani revokační informace, ani"
                          + " volnější nastavení.**");
            }
        } else if (revokedBeforeDecisive(sig, vc)) {
            // Nejčastější „proč tedy neplatný?" nad tímhle výsledkem. Časové razítko je horní mez
            // okamžiku podpisu, ne dolní: dokládá, že podpis v ten čas už existoval, ale ne že
            // neexistoval ještě před revokací. Standardy proto nedovolí ani jeden z obou závěrů
            // a proces skončí jako neprůkazný.
            //
            // Doslova to říká i protokol on-line DSS nad týmž dokumentem: pastSignatureValidation
            // → INDETERMINATE/REVOKED_NO_POE, „The revocation time is not after
            // best-signature-time!", a pastCertificateValidation přitom PASSED. Bez téhle věty
            // spadl závěr do holého „Ověření se nepodařilo dokončit." a čtenář se důvod nedozvěděl
            // nikde — přestože Podrobný výčet o kus výš uvádí revokační údaj jako použitelný.
            // Bez důkazu existence stojí rozhodný okamžik na přítomnosti, a mluvit tam o časovém
            // razítku znamená odporovat si o dva odstavce výš, kde téže kartě stálo, že žádné
            // razítko není. Vidět to na „PAdES B-B kvalifikovany ICA.pdf": certifikát platí do
            // 28. 8. 2026, takže větev poeMissing výš nezabere — chybějící důkaz tu není
            // překážkou, tou je revokace — a závěr pak vysvětloval revokaci razítkem, které
            // dokument nemá.
            java.time.Instant proven = vc.provenExistence();
            java.time.Instant decisive = proven != null ? proven : vc.dssBestSignatureTime();
            sb.append(capitalize(elementCert(sig, Case.NOMINATIV))).append(" byl revokován ")
              .append(revokedAt(sig.getCertRevokedAt()));
            if (proven != null) {
                // Totéž srovnání dvou okamžiků, totéž pravidlo o formátu.
                sb.append(", tedy dřív, než kam sahá důkaz existence — ten sahá k ")
                  .append(TimeUtil.format(decisive))
                  .append(". Časové razítko ale dokládá jen to, že ")
                  .append(gendered(sig, "podpis v ten čas už existoval",
                          "pečeť v ten čas už existovala", "razítko v ten čas už existovalo"));
            } else {
                sb.append(". Žádný důkaz existence k dispozici není, takže se ")
                  .append(gendered(sig, "podpis", "pečeť", "razítko"))
                  .append(" posuzuje k okamžiku ověření, ")
                  .append(TimeUtil.format(decisive))
                  .append(" — a k němu je certifikát odvolaný. To ale dokládá jen to, že ")
                  .append(gendered(sig, "podpis existuje teď", "pečeť existuje teď",
                          "razítko existuje teď"));
            }
            sb.append("; že ")
              .append(gendered(sig, "neexistoval", "neexistovala", "neexistovalo"))
              .append(" ještě před revokací, nedokládá. **Standardy proto odmítají prohlásit ")
              // Čtvrtý pád, ne druhý: „prohlásit podpis za neplatný", nikoli „podpisu".
              .append(gendered(sig, "podpis", "pečeť", "razítko")).append(" za neplatn")
              .append(gendered(sig, "ý", "ou", "é"))
              .append(" — a ze stejného důvodu ani za platn")
              .append(gendered(sig, "ý", "ou", "é")).append(".**");
        } else if (found.contains(17) || found.contains(18)) {
            sb.append(gendered(sig, "Podpis je", "Pečeť je", "Prvek je")).append(" ve formátu ")
              .append(found.contains(17) ? "PDF-NOT-ETSI" : "PKCS #7")
              .append(", který nesplňuje požadavky ETSI na formát, a ověřování podle standardů"
                      + " jej proto odmítá ověřit. **O platnosti ").append(genitiveType(sig))
              .append(" samotné" + gendered(sig, "ho", "", "ho")
                      + " to nevypovídá nic — jen o tom, že se neposuzovala.**");
        } else if (!revocationReportedAsUsed
                && (staleButAdmissible(vc, certUntil)
                        || vc.signingCertRevocationUnusable() != null
                        || vc.signingCertRevocationMissing() != null)) {
            // Tři cesty k témuž neprůkaznému výsledku, a jeden text pro všechny: závěr se ptá,
            // proč výsledek není kladný, a odpověď je pokaždé „k rozhodnému okamžiku chybí
            // použitelný údaj o stavu revokace". Čím to je — údaj se nenašel, našel a nešel
            // použít, nebo je v dokumentu a přípustný, jen se k němu proces nedostal —
            // rozebírá položka Revokační informace o kus výš i Podrobný výčet; opakovat to
            // podruhé, a u každé cesty jinak podrobně, dělalo ze dvou téměř shodných dokumentů
            // dvě různě dlouhá čtení. Změřeno na dvojici „PAdES B-LT viditelny PS.pdf" a
            // „PAdES B-LT neviditelny PS.pdf": liší se jen vizualizací a způsobem vložení OCSP,
            // a přesto první dostával o dvě věty delší závěr než druhý.
            //
            // Zůstává tu {@link #staleButAdmissible} jako podmínka, ne jako vlastní znění: u toho
            // podpisu jsou signingCertRevocationUnusable() i ...Missing() prázdné — údaj je
            // použitelný — takže bez ní by závěr spadl do holého „Ověření se nepodařilo dokončit."
            // Věcně je to případ, kdy ověřování začíná aktuálním časem, tam je certifikát po
            // expiraci, a vrátit se k dřívějšímu okamžiku umí jedině Past Signature Validation,
            // která patří až k úrovni LTA. Protokol on-line DSS to ukazuje doslova:
            // pastSignatureValidation, pastCertificateValidation i validationTimeSliding jsou
            // disabled a základní ověření vrátí OUT_OF_BOUNDS_NO_POE, který klauzule 5.5.4
            // krok 4)b) propouští beze změny.
            //
            // „K rozhodnému okamžiku" tu není ozdoba. Údaj o stavu certifikátu k dispozici být
            // může — odpověď stažená dnes — a přesto nestačí, protože mluví o dnešku. Bez toho
            // upřesnění si věta odporovala s Podrobným výčtem hned nad sebou, který tentýž údaj
            // uvádí mezi použitelnými.
            sb.append("Bez použitelného údaje o stavu revokace " + elementCert(sig, Case.GENITIV)
                    + " k rozhodnému okamžiku nelze rozhodnout, zda byl certifikát tehdy"
                    + " v pořádku — a to platí bez ohledu na to, ke kterému okamžiku se "
                    + gendered(sig, "podpis", "pečeť", "razítko") + " posuzuje.");
            if (vc.dssBestSignatureTime() != null && vc.provenExistence() != null
                    && vc.dssBestSignatureTime().isAfter(vc.provenExistence().plusSeconds(1))) {
                sb.append(" Ověřování se proto zastavilo dřív, než stačilo rozhodný okamžik na čas"
                        + " razítka vůbec posunout.");
            }
        } else {
            sb.append("Ověření se nepodařilo dokončit.");
        }
        sb.append(" **Stav platnosti ").append(genitiveType(sig))
          .append(" zůstává neznámý — nikoli neplatný.** Podrobnosti jsou v bloku"
                + " Zjištěné důvody a doporučení.");
        // Ukazatel na situaci 54 tu stával od 12. 9. 2026 a téhož dne zmizel: patří o dva
        // odstavce výš, k větě o důkazu existence, která o něm tvrdila opak. Závěr smí přidávat
        // jen to, co nad ním ještě nezaznělo — viz proofOfExistenceText().
        return sb.toString();
    }

    /**
     * Uvázlo ověřování na tom, že řetězec nevede k žádné známé kotvě důvěry?
     *
     * <p>Ptá se na <b>dílčí indikaci</b>, tedy na to, čím proces skončil, ne na výčet situací.
     * Situace 12 sama o sobě nestačí — její vlastní znění říká, že „může být přímou příčinou
     * nebo důsledkem jiného selhání“, takže se objeví i tam, kde rozhodlo něco jiného.
     */
    static boolean chainNotBuilt(SignatureSummary sig) {
        String sub = sig.getSubIndication();
        return sub != null && sub.startsWith("NO_CERTIFICATE_CHAIN_FOUND");
    }

    /**
     * Rozhodla o výsledku zastaralost hašovací funkce?
     *
     * <p>Ptá se na <b>dílčí indikaci</b>, ne na výčet situací, a to je tu podstatné. Situace 15
     * (SHA-1) bývá indikována i tam, kde o výsledku rozhodlo něco docela jiného — například
     * u {@code priklad6_2_Razitko_do_03_08_2034.pdf}, kde vedle ní stojí důvod 21 a rozhoduje
     * razítko. Kdežto {@code CRYPTO_CONSTRAINTS_FAILURE(_NO_POE)} je výrok samotné knihovny
     * o tom, na čem ověření skončilo.
     *
     * <p>Nález uživatele z 15. 9. 2026 nad {@code hashe\RSA MD5 2019 11 22 s A.pdf}: jediný
     * důvod je 16 (MD5), dílčí indikace {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE} — a závěr
     * přesto mluvil o chybějícím revokačním údaji, o MD5 ani slovo. V tolerantním režimu si
     * navíc odporoval s odstavcem o kus výš, který hlásil využitou vloženou odpověď OCSP.
     *
     * <p>Stojí jako <b>první</b> větev závěru, před chybějícím důkazem existence. Zastaralý otisk
     * je vada samotného podpisu a žádné razítko ani žádný revokační údaj s ní nic neudělá;
     * jmenovat místo ní cokoli jiného by opakovalo právě tu chybu, kvůli které větev vznikla.
     *
     * <p>Situace 16 (MD5, RIPEMD-160) proti situaci 15 (SHA-1) rozhoduje o konci věty:
     * šestnáctka je bez řešení, patáctou promine volnější nastavení. Shánět to z důvodů nelze:
     * v tolerantním režimu se důvod 15 vůbec nevydává.
     *
     * <p>Četnost: na D:/tmp žádný prvek, na D:/Downloads 17.
     *
     * <p>Od 4. 10. 2026 i situace 56 — otisk, který politika nepřipouští vůbec (SHA3-224). Závěr
     * pro ni má vlastní znění: o zastaralosti tu mluvit nelze a volnější nastavení nepomůže.
     */
    // Balíčkově viditelné kvůli testu: je to čistá funkce nálezu, stejně jako
    // shouldOfferOtherValidators, a jako taková se dá změřit bez stavění karty.
    static boolean cryptoDecisive(SignatureSummary sig) {
        // Situace 56 bez ohledu na dílčí indikaci: vzniká jen u neúspěšného ověření a podpis
        // zastaví vždy, i když DSS jako první ohlásí revokaci (viz ReportMapper, situace 56).
        if (sig.getDetectedSituations().contains(56)) return true;
        String si = sig.getSubIndication();
        return si != null && si.startsWith("CRYPTO_CONSTRAINTS_FAILURE")
                && (sig.getDetectedSituations().contains(15)
                        || sig.getDetectedSituations().contains(16));
    }

    /**
     * Ústupky byly v běhu zapnuté, ale co z nich verdikt nesl, se nezjistilo.
     *
     * <p>Prázdný výčet rozhodujících ústupků znamená dvojí a blok „Průběh ověřování“ ty dvě
     * věci nesmí ztotožnit: <b>změřeno</b> a žádný nebyl potřeba, nebo <b>neměřeno</b> a odvození
     * nic nenašlo. Jen o tom prvním se smí říct „bez nich by ověření dopadlo stejně“ či
     * „bez jakýchkoli ústupků“ — obojí je závěr z měření, ne z absence nálezu.
     *
     * <p>Měří to {@code RelaxationMeasurement}: ověření se zopakuje bez ústupků a porovná se,
     * co obstálo. Nemusí proběhnout vždy — může selhat, a prvek, jehož kladný závěr vydal až
     * druhý průchod k odvozenému okamžiku, v něm není nikdy. {@code ReportMapper} pak
     * rozhodující ústupky odvozuje, a odvození hledá čtyři předem vyjmenované tvary vady, takže
     * úplné být nemůže: 19. 9. 2026 nesedl na PAdES-BES, jehož jediným revokačním údajem byl
     * seznam CRL vydaný pět let před vydáním certifikátu ({@code BBB_XCV_IARDPFC_ANS}) — ani jeden
     * z těch čtyř to není, přesto bez ústupku ten podpis neprojde, a karta o ústupcích napsala,
     * že se neprojevily.
     */
    static boolean concessionsUnmeasured(SignatureSummary sig, AppSettings settings) {
        return !sig.isDecisiveSettingsMeasured() && settings != null
                && !ReportMapper.Relaxation.enabledIn(settings).isEmpty();
    }

    /**
     * Podpis, kterému k rozhodnutí nechybí materiál, nýbrž úroveň.
     *
     * <p>Údaj o stavu podpisového certifikátu z doby, kdy certifikát ještě platil, v dokumentu je
     * a je přípustný — jenže certifikát je dnes po expiraci a proces začíná aktuálním časem.
     * Podmínka „vydán ještě za platnosti certifikátu" je tu ta podstatná: odpověď stažená dnes by
     * ji nesplnila a takový podpis patří do větve o chybějícím údaji k rozhodnému okamžiku.
     */
    private static boolean staleButAdmissible(ValidationCourse vc, java.time.Instant certUntil) {
        ValidationCourse.RevocationFact f = vc.signingCertRevocation();
        if (f == null || certUntil == null || f.issued() == null) return false;
        if (certUntil.isAfter(java.time.Instant.now())) return false;
        return !f.issued().isAfter(certUntil);
    }

    /**
     * Zda byl certifikát revokován nejpozději k okamžiku, který prokazuje důkaz existence.
     *
     * <p>Tenhle tvar dává {@code INDETERMINATE/REVOKED_NO_POE}: odvolání předchází rozhodnému
     * okamžiku, takže se nedá vyloučit, že podpis vznikl až po něm — ani to, že vznikl před ním.
     */
    private static boolean revokedBeforeDecisive(SignatureSummary sig, ValidationCourse vc) {
        if (!sig.isCertRevoked() || sig.getCertRevokedAt() == null) return false;
        java.time.Instant decisive = vc.provenExistence() != null
                ? vc.provenExistence() : vc.dssBestSignatureTime();
        return decisive != null && !sig.getCertRevokedAt().isAfter(decisive);
    }

    /** „o tři a půl měsíce“, „o 12 dní“ — hrubá míra odstupu, jen aby si čtenář udělal představu. */
    private static String gapPhrase(java.time.Instant from, java.time.Instant to) {
        long days = java.time.Duration.between(from, to).toDays();
        if (days < 1)  return "o několik hodin";
        if (days < 60) {
            return "o " + word(days) + (days == 1 ? " den" : days <= 4 ? " dny" : " dní");
        }
        long months = days / 30;
        boolean half = (days % 30) >= 12 && (days % 30) <= 22;
        return "o " + word(months) + (half ? " a půl" : "")
                + (months <= 4 || half ? " měsíce" : " měsíců");
    }

    /**
     * Přesný odstup dvou okamžiků — „20 minut“, „tři hodiny“, „pět dní“.
     *
     * <p>Vedle {@link #gapPhrase}, které záměrně zaokrouhluje na měsíce a všechno pod den odbude
     * slovy „o několik hodin“. U zamaskované revokace je ale právě ta krátkost sdělením: vložený
     * údaj z 15:00 a odvolání v 15:20 téhož dne není náhodná souhra, a „o několik hodin“ by ten
     * rozdíl smazalo.
     */
    private static String preciseGap(java.time.Instant from, java.time.Instant to) {
        java.time.Duration d = java.time.Duration.between(from, to).abs();
        long days = d.toDays();
        if (days >= 1) {
            return CzechPlural.count((int) days, "den", "dny", "dní");
        }
        long hours = d.toHours();
        if (hours >= 1) {
            return CzechPlural.count((int) hours, "hodinu", "hodiny", "hodin");
        }
        long minutes = Math.max(1, d.toMinutes());
        return CzechPlural.count((int) minutes, "minutu", "minuty", "minut");
    }

    /** Malá čísla se ve větě píšou slovem; „o 3 a půl měsíce" vypadá jako výpis, ne jako text. */
    private static String word(long n) {
        return switch ((int) n) {
            case 1 -> "jeden"; case 2 -> "dva";   case 3 -> "tři";  case 4 -> "čtyři";
            case 5 -> "pět";   case 6 -> "šest";  case 7 -> "sedm"; case 8 -> "osm";
            case 9 -> "devět"; case 10 -> "deset";
            default -> Long.toString(n);
        };
    }

    /**
     * Co se dá říct o razítkách, která prvek nese, když se k žádnému z nich neváže prokázaný
     * okamžik. Ptá se na to {@link #proofOfExistenceText} a <b>jen v té situaci</b>.
     *
     * <p>Rozlišení vzniklo z nálezu uživatele z 13. 9. 2026: text tvrdil „ověřit jako platné se
     * ho nepodařilo" i o razítku, které obstálo — a téhož razítka se Rozšířený přehled ve vedlejší
     * záložce zastával jako platného. Dvě různé věci byly slité do jedné věty:
     *
     * <ul>
     *   <li>{@code NONE_VALID} — prvek nemá žádné razítko, které by obstálo. Jediný případ, kdy
     *       se smí říct, že se ověřit nepodařilo.</li>
     *   <li>{@code PROVED_BY_OTHER_ELEMENT} — razítko obstálo, ale rozhodný okamžik doložil jiný
     *       prvek téhož dokumentu (situace 54). Přípravky:
     *       {@code …\2024\02 04 full\externi\dokument-signed-xades-baseline-lt-counter-signed-…xml}
     *       a {@code …\2026\02 18 counter signature\ASiC XAdES\container-counter-signed-…sce},
     *       ke každému leží ve sbírce týž podpis bez protipodpisu.</li>
     *   <li>{@code PROVES_NOTHING} — razítko obstálo, a přesto se žádný dřívější okamžik
     *       neprokázal. Přípravek {@code …\PAdES B-level s dodatecne pridanym razitkem.pdf}:
     *       razítko leží ve slovníku DSS/VRI, tedy mimo podpis, a důkaz existence nezakládá
     *       (situace 52).</li>
     *   <li>{@code OTHER_SOURCE} — prokázaný okamžik existuje, ale opírá se o jiný údaj než
     *       o razítka prvku.</li>
     * </ul>
     */
    enum OwnTimestampStatus { NONE_VALID, PROVED_BY_OTHER_ELEMENT, PROVES_NOTHING, OTHER_SOURCE }

    static OwnTimestampStatus ownTimestampStatus(SignatureSummary sig,
                                                 java.time.Instant provenExistence) {
        if (earliestValidOwnTimestamp(sig) == null) return OwnTimestampStatus.NONE_VALID;
        if (sig.getDetectedSituations().contains(54) && sig.getCoveringProofDetail() != null) {
            return OwnTimestampStatus.PROVED_BY_OTHER_ELEMENT;
        }
        return provenExistence == null
                ? OwnTimestampStatus.PROVES_NOTHING : OwnTimestampStatus.OTHER_SOURCE;
    }

    /** Nejstarší razítko prvku, které obstálo; {@code null}, když žádné takové není. */
    /**
     * Evidenční záznam, o který se opírá rozhodný okamžik prvku — nebo null, když ho doložilo
     * vlastní razítko prvku, jiný údaj, nebo nic.
     */
    static EvidenceRecordEntry provingRecord(SignatureSummary sig, ValidationCourse vc) {
        java.time.Instant proven = vc != null ? vc.provenExistence() : null;
        if (proven == null) return null;
        SignatureSummary.TimestampEntry own = earliestValidOwnTimestamp(sig);
        if (own != null && !own.time().isAfter(proven)) return null;
        return sig.getEvidenceRecords().stream()
                .filter(EvidenceRecordEntry::providesProof)
                .filter(er -> proven.equals(er.earliestProof()))
                .findFirst().orElse(null);
    }

    static SignatureSummary.TimestampEntry earliestValidOwnTimestamp(SignatureSummary sig) {
        List<SignatureSummary.TimestampEntry> all = sig.getAllTimestamps();
        return all == null ? null : all.stream()
                .filter(SignatureSummary.TimestampEntry::passed)
                .filter(t -> t.time() != null)
                .min(java.util.Comparator.comparing(SignatureSummary.TimestampEntry::time))
                .orElse(null);
    }

    /**
     * Razítka z doby, kdy podpisový certifikát ještě platil, která se nepodařilo ověřit jako
     * platná — seřazená od nejstaršího.
     *
     * <p>Rozhoduje o tom, jestli závěr smí říct, že prvek vznikl až po skončení platnosti svého
     * certifikátu. Situace 25 to sama netvrdí: hlásí jen tolik, že za koncem platnosti leží
     * <b>rozhodný okamžik</b> — a ten se posouvá dopředu pokaždé, když starší razítko neobstojí.
     * Nese-li dokument razítko ještě z doby platnosti certifikátu a to neobstálo, není to
     * zjištění o době vzniku, nýbrž jeho nedostatek: dřívější existenci se prokázat nepodařilo.
     * Nález uživatele z 15. 9. 2026 nad {@code priklad6_2_Razitko_do_03_08_2034.pdf} — čtyři
     * razítka, dvě nejstarší neobstála a to úplně nejstarší nese čas 20. 1. 2012, tedy sedm
     * měsíců před koncem platnosti podpisového certifikátu.
     *
     * <p>Razítko, které neobstálo a leží <b>až za</b> koncem platnosti, se sem nepočítá: i kdyby
     * obstálo, rozhodný okamžik by pořád ležel za expirací a závěr by se nezměnil. Proto tu
     * nestojí prosté {@code situations.contains(27)}, kterým se řídí potlačení důvodu 20
     * v {@code ReportMapper}: to se ptá jen na to, zda některé razítko neobstálo, ne na to, zda na
     * tom závěr o době vzniku závisí.
     */
    private static List<SignatureSummary.TimestampEntry> unprovenTimestampsWithinValidity(
            SignatureSummary sig, java.time.Instant certUntil) {
        if (certUntil == null || sig.getAllTimestamps() == null) return List.of();
        return sig.getAllTimestamps().stream()
                .filter(t -> !t.passed())
                .filter(t -> t.time() != null && !t.time().isAfter(certUntil))
                .sorted(java.util.Comparator.comparing(SignatureSummary.TimestampEntry::time))
                .toList();
    }

    /**
     * Dokdy sahá řada <b>platných</b> razítek prvku — nejzazší bod, který ještě kryjí.
     *
     * <p>Do 18. 9. 2026 to byl konec platnosti certifikátu autority toho <b>nejnovějšího</b>
     * razítka, a bylo to špatně: mladší razítko s kratším certifikátem to starší nezkracuje.
     * Nad „podpis s razitkem-extended-pades-baseline-lta.pdf" z toho vycházelo, že krytí skončilo
     * 11. 11. 2025, ačkoli podpisové razítko stojí na certifikátu platném do 31. 12. 2028 —
     * a Úschovna Czech POINT týž dokument téhož dne uznala. Pravidlo drží
     * {@link cz.digitalnikontinuita.anep.model.TimestampChain}.
     */
    private static java.time.Instant validTimestampChainReach(SignatureSummary sig) {
        List<SignatureSummary.TimestampEntry> chain = sig.getAllTimestamps().stream()
                .filter(ts -> ts.time() != null && ts.passed())
                .sorted(java.util.Comparator.comparing(SignatureSummary.TimestampEntry::time))
                .toList();
        return cz.digitalnikontinuita.anep.model.TimestampChain.reachFromStart(chain);
    }

    /**
     * Podrobný výčet — původní obsah bloku „Průběh ověřování“, beze změny.
     *
     * <p>Složený a pod výkladem: čísla, jména certifikátů a data vydání jednotlivých odpovědí
     * jsou k ověření jednotlivostí, ne ke čtení. Výklad nad ním o nich mluví v celku.
     */
    private VBox courseDetail(SignatureSummary sig, ValidationCourse vc) {
        if (vc == null || vc.isEmpty()) return null;
        VBox body = new VBox(4);
        body.setPadding(new Insets(4, 12, 8, 12));

        if (vc.validationTime() != null) {
            body.getChildren().add(infoRow("Ověřeno dne:", TimeUtil.format(vc.validationTime())));
        }
        if (vc.customValidationTime() != null) {
            body.getChildren().add(infoRow("Zadaný čas ověření:",
                    TimeUtil.format(vc.customValidationTime())
                            + "  —  zvolen v nastavení, ověřování proběhlo k tomuto okamžiku"));
        }
        if (vc.provenExistence() != null) {
            String note = vc.provenExistenceNote() != null ? "  —  " + vc.provenExistenceNote() : "";
            body.getChildren().add(infoRow("Prokázaná existence " + genitiveType(sig) + ":",
                    TimeUtil.format(vc.provenExistence()) + note));
        }
        if (vc.dssBestSignatureTime() != null) {
            body.getChildren().add(infoRow("Rozhodný okamžik dle DSS:",
                    TimeUtil.format(vc.dssBestSignatureTime())));
        }
        if (vc.bstDivergenceNote() != null) {
            body.getChildren().add(courseNote(vc.bstDivergenceNote(), "#7a4a00"));
        }

        // Jeden nadpis a osud u každé položky, ne tři nadpisy o přípustnosti. Dřív tu stálo
        // „Revokační informace přípustné podle standardů" a pod tím ležel i údaj, o který se
        // závěr vůbec neopřel — přípustnost a využití totiž nejsou totéž: údaj může projít všemi
        // pravidly a přesto na verdikt nedosáhnout, protože proces nemá krok, ve kterém by na něm
        // záleželo. Přesně tak dopadla stažená odpověď OCSP u „PAdES B-LT neviditelny PS.pdf" —
        // hlásí odvolání k 28. 4. 2026, jenže základní ověření skončilo na OUT_OF_BOUNDS_NO_POE
        // a klauzule 5.5.4 krok 4)b) ten výsledek vrací beze změny, bez jakéhokoli porovnání
        // s revokačními údaji. Porovnat je umí jedině větev 4)a) pro REVOKED_NO_POE a Past
        // Signature Validation, která patří až k úrovni LTA. Dokud nadpis sliboval „přípustné",
        // četl to čtenář jako „bylo použito" a pak marně hledal, proč z toho nic neplyne.
        //
        // Barvy nesou týž rozdíl podruhé, aby ho bylo vidět bez čtení: zelená = závěr se o údaj
        // opřel, hnědá = údaj je v pořádku, ale závěr na něm nestojí, červená = použít nešel.
        if (!vc.revocationUsed().isEmpty() || !vc.revocationNotDecisive().isEmpty()
                || !vc.revocationRejected().isEmpty()) {
            addCourseHeading(body, "Revokační informace:");
            addRevocationBullets(body, vc.revocationUsed(), "#1a7a1a");
            addRevocationBullets(body, vc.revocationNotDecisive(), "#7a4a00");
            addRevocationBullets(body, vc.revocationRejected(), "#cc0000");
        }
        addCourseList(body, "Revokační informace, které chybí:", vc.revocationMissing(), "#7a4a00");
        // Neutral colour on purpose: nothing is wrong here. Without this line the whole block can
        // come up with no revocation section at all, which reads as a check that failed to run.
        addCourseList(body, "Revokace se nezjišťovala:", vc.revocationNotApplicable(), "#111111");

        if (!vc.contextNotes().isEmpty()) {
            Label ctxLbl = new Label("Podmínky ověřování:");
            ctxLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;"
                    + " -fx-padding: 6 0 0 0;");
            body.getChildren().add(ctxLbl);
            for (String line : vc.contextNotes()) {
                body.getChildren().add(courseBullet(line, "#111111"));
            }
        }
        return body;
    }

    /** A titled group of bullet lines; omitted entirely when there is nothing to list. */
    private static void addCourseList(VBox block, String title, java.util.List<String> lines,
                                       String color) {
        if (lines.isEmpty()) return;
        addCourseHeading(block, title);
        addCourseBullets(block, lines, color);
    }

    /**
     * Nadpis skupiny sám o sobě — pro výčet, jehož odrážky mají každá jinou barvu.
     *
     * <p>Oddělené od {@link #addCourseList}, protože revokační informace se od 25. 8. 2026 vypisují
     * pod jedním nadpisem ve třech barvách podle osudu údaje; tři nadpisy by ten rozdíl rozbily
     * na tři seznamy a čtenář by musel porovnávat, co ve kterém je.
     */
    private static void addCourseHeading(VBox block, String title) {
        Label lbl = new Label(title);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;"
                + " -fx-padding: 6 0 0 0;");
        block.getChildren().add(lbl);
    }

    /** Odrážky bez nadpisu; prázdný seznam nepřidá nic. */
    private static void addCourseBullets(VBox block, java.util.List<String> lines, String color) {
        for (String line : lines) {
            block.getChildren().add(courseBullet(line, color));
        }
    }

    /**
     * Odrážky revokačních údajů — každá s odkazy „Ukaž“ a „Ulož“.
     *
     * <p>Odkazy vynechá jen tam, kde není co ukázat: u údaje, který se do diagnostiky nedostal,
     * a tedy jeho bajty aplikace nedrží. Slibovat u něj zobrazení a pak hlásit chybu by bylo
     * horší než odkaz neuvést.
     */
    private void addRevocationBullets(VBox block,
                                       java.util.List<ValidationCourse.RevocationLine> lines,
                                       String color) {
        for (ValidationCourse.RevocationLine line : lines) {
            byte[] der = revocationBytes(line.revocationId());
            block.getChildren().add(der == null || der.length == 0
                    ? courseBullet(line.text(), color)
                    : revocationBullet(line, der, color));
        }
    }

    /**
     * Odrážka s textem a dvojicí odkazů <b>hned za textem</b>.
     *
     * <p>{@link TextFlow}, ne {@code HBox} s {@code selectable()}: TextArea si v HBoxu vezme celou
     * zbylou šířku, takže odkazy skončí u pravého okraje karty a u víceřádkové odrážky navíc
     * svisle uprostřed — tedy nikde poblíž textu, ke kterému patří. Ve flow jsou odkazy dalším
     * článkem téhož odstavce a zalomí se s ním; je to týž postup, jakým sází odkazy Rozšířený
     * přehled a Detailní rozbor.
     *
     * <p>Za ztrátu tažení myší přes text náhradou je nabídka „Kopírovat text" pod pravým
     * tlačítkem, kterou má flow stejně jako ostatní odstavce Analýzy.
     */
    private HBox revocationBullet(ValidationCourse.RevocationLine line, byte[] der, String color) {
        Label bullet = new Label("•");
        bullet.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        bullet.setMinWidth(12);

        Text body = new Text(line.text());
        body.setStyle("-fx-font-size: 12px; -fx-fill: " + color + ";");

        Hyperlink showLink = new Hyperlink("Ukaž");
        showLink.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + ";"
                + " -fx-border-color: transparent; -fx-background-color: transparent;"
                + " -fx-padding: 0 0 1 0;");
        showLink.setOnAction(e -> {
            showLink.setVisited(false);
            showRevocation(line, der);
        });
        Hyperlink saveLink = new Hyperlink("Ulož");
        saveLink.setStyle(showLink.getStyle());
        saveLink.setOnAction(e -> {
            saveLink.setVisited(false);
            saveBytes(der, defaultFileName(line), fileFilterDesc(line), fileFilterExt(line));
        });

        TextFlow flow = new TextFlow(body, new Text("  "), showLink, new Text(" "), saveLink);
        HBox.setHgrow(flow, Priority.ALWAYS);
        withCopyMenu(flow, line.text());

        HBox box = new HBox(4, bullet, flow);
        box.setPadding(new Insets(1, 0, 1, 12));
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private static String defaultFileName(ValidationCourse.RevocationLine line) {
        return line.ocsp() ? "ocsp-odpoved.ocsp" : "seznam.crl";
    }

    private static String fileFilterDesc(ValidationCourse.RevocationLine line) {
        return line.ocsp() ? "OCSP odpověď (*.ocsp)" : "CRL seznam (*.crl)";
    }

    private static String fileFilterExt(ValidationCourse.RevocationLine line) {
        return line.ocsp() ? "*.ocsp" : "*.crl";
    }

    /** OCSP odpověď textem, seznam CRL vlastním prohlížečem otevřeným na dotčeném certifikátu. */
    private void showRevocation(ValidationCourse.RevocationLine line, byte[] der) {
        Stage owner = root.getScene() != null && root.getScene().getWindow() instanceof Stage s
                ? s : null;
        if (line.ocsp()) {
            // Týž dialog, jaký otvírá Detailní rozbor. Vlastní textový náhled tu byl chudší —
            // chyběl v něm celý blok o podpisu respondéra, tedy právě to, co nad revokačním
            // údajem čtenáře zajímá: kdo odpověď podepsal a jestli mu jde věřit.
            DetailedReportView.showOcspDialogUnified(der, revocationById(line.revocationId()));
        } else {
            CrlView.show(der, line.certSerial(), revocationIssuerBytes(line.revocationId()), owner);
        }
    }

    /**
     * Bajty revokačního údaje podle jeho identifikátoru.
     *
     * <p>Prochází se obojí — {@code getAllRevocationData()} i záznamy u jednotlivých certifikátů.
     * První seznam totiž není úplný: údaj, který DSS zná jen jako záznam certifikátu, v něm chybí,
     * a přesně takové ve výčtu bývají.
     */
    private byte[] revocationBytes(String revId) {
        var rev = revocationById(revId);
        return rev != null ? rev.getBinaries() : null;
    }

    /** Certifikát vydavatele revokačního údaje, aby se dal ukázat z prohlížeče CRL. */
    private byte[] revocationIssuerBytes(String revId) {
        var rev = revocationById(revId);
        if (rev == null) return null;
        var issuer = rev.getSigningCertificate();
        return issuer != null ? issuer.getBinaries() : null;
    }

    private eu.europa.esig.dss.diagnostic.RevocationWrapper revocationById(String revId) {
        if (revId == null || lastValidationResult == null) return null;
        var dd = lastValidationResult.getDiagnosticData();
        if (dd == null) return null;
        for (var rev : dd.getAllRevocationData()) {
            if (revId.equals(rev.getId())) return rev;
        }
        for (var cert : dd.getUsedCertificates()) {
            for (var rev : cert.getCertificateRevocationData()) {
                if (revId.equals(rev.getId())) return rev;
            }
        }
        return null;
    }

    /** Uložení bajtů do souboru; společné s prohlížeči, aby se znění dialogu nerozešlo. */
    private void saveBytes(byte[] data, String defaultName, String filterDesc, String filterExt) {
        UiDialogs.saveBytes(data, defaultName, filterDesc, filterExt,
                root.getScene() != null ? root.getScene().getWindow() : null);
    }

    private static HBox courseBullet(String text, String color) {
        Label bullet = new Label("•");
        bullet.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        bullet.setMinWidth(12);
        TextArea val = ResultView.selectable(text,
                "-fx-text-fill: " + color + "; -fx-font-size: 12px;", false);
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox box = new HBox(4, bullet, val);
        box.setPadding(new Insets(1, 0, 1, 12));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static HBox courseNote(String text, String color) {
        Label icon = new Label("ⓘ");
        icon.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        icon.setMinWidth(16);
        TextArea val = ResultView.selectable(text,
                "-fx-text-fill: " + color + "; -fx-font-size: 12px;", false);
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox box = new HBox(4, icon, val);
        box.setPadding(new Insets(2, 0, 2, 12));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ── Blok „Způsob ověření“ ────────────────────────────────────────────────────────────────

    /**
     * Za jakých pravidel tenhle verdikt vznikl: podle standardů, nebo za zvláštních podmínek —
     * a za kterých. Blok stojí i tam, kde je vše v pořádku; věta „ověřeno zcela podle standardů“
     * je ta cennější polovina sdělení a jinak by se dala jen vyvodit z toho, že nic nestojí jinde.
     *
     * <h2>Tři otázky, ne jedna</h2>
     *
     * <p>Blok do 20. 8. 2026 sléval do jediného nadpisu pět různorodých věcí a nad každou z nich
     * tvrdil „neproběhlo zcela podle technických standardů“. U tří z nich to nebyla pravda. Text
     * se proto dělí podle toho, na kterou otázku která položka odpovídá:
     *
     * <ol>
     * <li><b>Porušilo se pravidlo?</b> {@link ReportMapper.Relaxation} — revokační údaj vydaný
     *     po expiraci, vydavatel revokace mimo dobu platnosti, SHA-1, PDF-NOT-ETSI — a k tomu
     *     ústupek, který si zadaný okamžik uděluje sám, protože ho nemá kdo zaškrtnout
     *     ({@code dateRelax}). Tohle a nic jiného standardy odmítají, a jen tohle smí nést nadpis
     *     o nesouladu.</li>
     * <li><b>Neposuzovalo se přísněji?</b> Přísný režim razítek. Není to ústupek, je to jeho opak
     *     — ve výčtu ústupků tvrdil pravý opak toho, co se stalo. Vlastní věta, ne odrážka.</li>
     * <li><b>Dojde k témuž někdo jiný?</b> Vlastní kotva důvěry, CRL z vlastního úložiště, zadaný
     *     okamžik ověření. Standardy tomu nebrání: kotva, revokační data i čas ověření jsou podle
     *     EN 319 102-1 <i>vstupy</i> procesu, ne jeho síťové operace, a RFC 5280 §6.3 bere na
     *     vstupu množinu CRL, aniž by se ptalo, odkud je. Sdělitelný je na nich původ — nejsou
     *     dané dokumentem, takže kdo je nemá, dojde jinam.</li>
     * </ol>
     *
     * <p>U CRL z vlastního úložiště to platí dvojnásob. {@code ReportMapper} nastavuje
     * {@link ValidationCourse#customCrlDecisive()} až <em>za</em> kontrolou použitelnosti, takže
     * rozhodující seznam z úložiště je z konstrukce údaj, který standardy uznávají — nepoužitelné
     * záznamy padají do {@code revocationUnused()} a sem se nedostanou. Týž {@code CustomCrlStore}
     * navíc plní {@code AugmentationService} při uchování a tytéž seznamy zapéká do podpisu úrovně
     * LTA, který AnEP vydává za konformní; tvrdit u ověření opak by si odporovalo. A samotný fakt
     * vznikl jako odpověď na otázku „dojde k témuž někdo jiný?“ — sloužil zrušené předpovědi pro
     * cizí validátory (viz níže) — takže se skupinou 3 se vrací tam, odkud je.
     *
     * <h2>Rozhodující versus jen zapnuté</h2>
     *
     * <p>Ústupky jsou dvojího druhu a text je nesmí míchat. <b>Rozhodující</b> ústupek verdikt
     * nese — bez něj by kladný nebyl. <b>Zapnutý, ale nerozhodující</b> ústupek neznamená nic;
     * kdyby stál ve výčtu podmínek, tvrdil by o výsledku něco, co není pravda. Ten rozdíl měří
     * {@code ReportMapper.computeDecisiveSettings()} skutečným přeověřením, zde se jen čte.
     * Proto se výčet podmínek řídí jen tím, co bylo rozhodující: samo zapnutí tolerance ještě
     * nedělá z ověření nestandardní běh.
     *
     * <h2>Co tu bývalo a už není</h2>
     *
     * <p>Blok měl do 19. 8. 2026 ještě dvě části — předpověď, jak dopadne ověření <b>jinde</b>:
     * u validátorů držících se standardů a u okruhu CzechPOINTu, podatelen a Signeru. Bylo to
     * hotové a odladěné, ale <b>odstraněno záměrně</b>: předpovídat cizí nástroj znamená znát
     * jeho verzi, nastavení i obsah jeho vlastních zásob revokačních informací, a to AnEP neví.
     * Každý jednotlivý případ šlo dotáhnout, jenže jich přibývalo rychleji, než ubývalo —
     * poslední byl podpis vzniklý po expiraci vlastního certifikátu, kde všechny tři části tvrdily
     * něco jiného, než jak to je.
     *
     * <p>Texty i celý rozbor variant jsou zachovány v {@code docs/zpusob-overeni-navrh.md},
     * včetně toho, které z nich se na čem rozbily. Data, na kterých ta část stála, se dál počítají
     * a jsou v pořádku — {@code ValidationCourse.revocationFetchedOnline()} nemá dnes odběratele
     * právě proto.
     */
    private void appendVerificationMode(VBox block, SignatureSummary sig, String verdict) {
        List<String> decisive = sig.getDecisiveSettings() != null
                ? sig.getDecisiveSettings() : List.of();
        boolean relNewerOcsp  = decisive.contains(
                ReportMapper.Relaxation.NEWER_OCSP.settingName());
        boolean relExpiredSrc = decisive.contains(
                ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName());
        boolean relSha1       = decisive.contains(ReportMapper.Relaxation.SHA1.settingName());
        boolean relPdfNotEtsi = decisive.contains(
                ReportMapper.Relaxation.PDF_NOT_ETSI.settingName());
        // Skupina 1: jediné, co standardy skutečně odmítají.
        boolean concessions = relNewerOcsp || relExpiredSrc || relSha1 || relPdfNotEtsi;
        // Zastupitelnost mění, co se o výčtu smí tvrdit. „Bez tohohle by výsledek nebyl kladný"
        // platí jen o ústupku, který nemá kým být nahrazen; nad dvojicí, kde stačí kterýkoli,
        // by každá taková věta popírala tu druhou. Měří to RelaxationMeasurement.measure()
        // druhým během, v němž pouští každý ústupek samostatně.
        boolean eitherWillDo = sig.isDecisiveSettingsInterchangeable();
        // Zda se výčet rozhodujících ústupků opravdu měřil. Prázdný výčet totiž znamená dvojí:
        // změřeno a nic nebylo potřeba, nebo neměřeno a odvození nic nenašlo. První je zjištění,
        // druhé je mezera — a věty pod tím blokem jsou věty o měření („bez nich by ověření dopadlo
        // stejně", „bez jakýchkoli ústupků"), takže je smí vyslovit jen to první.
        //
        // Není to teoretický rozdíl: odvození ({@code ReportMapper.computeDecisiveSettings})
        // hledá čtyři předem vyjmenované tvary vady a úplné být nemůže. Na PAdES‑BES, kde jediný
        // revokační údaj byl seznam CRL vydaný pět let před vydáním certifikátu, nesedl ani jeden
        // z nich — a přitom bez ústupku ten podpis neprojde (BBB_XCV_IARDPFC_ANS).
        boolean measured = sig.isDecisiveSettingsMeasured();
        String needTail = (eitherWillDo || !measured) ? "" :
                " Bez tohoto ústupku by stav revokace zůstal nezjištěný a výsledek by nebyl kladný.";

        List<String> alsoOn = new java.util.ArrayList<>();
        if (settings != null) {
            for (ReportMapper.Relaxation r : ReportMapper.Relaxation.enabledIn(settings)) {
                if (!decisive.contains(r.settingName())) alsoOn.add(r.settingName());
            }
        }
        boolean unmeasuredConcessions = concessionsUnmeasured(sig, settings);

        // Kladný verdikt rozhoduje, co se smí tvrdit: „bez těchto ústupků by výsledek nebyl
        // kladný" je měřený údaj a měří se jen tam, kde kladný výsledek je. Bere se z karty —
        // viz appendCourseNarrative.
        boolean passed = "TOTAL_PASSED".equals(verdict);

        ValidationCourse vc = sig.getValidationCourse();
        // Říká, oč se opřel kladný závěr. Bez kladného závěru se nic neopřelo o nic: seznam
        // z vlastního úložiště byl mezi použitelnými údaji, ale verdikt nenesl.
        boolean customCrl = passed && vc != null && vc.customCrlDecisive();
        // Skupina 2: přísněji, než standardy žádají. Stojí samostatně, aby se z opaku ústupku
        // nestala odrážka ve výčtu ústupků.
        boolean stricter  = !timestampStandardsMode;
        // Skupina 3: vstupy, které nejsou dané dokumentem.
        boolean ownAnchor = sig.isTrustedViaCustomStore();
        boolean setTime   = sig.isUsedCustomValidationTime();
        boolean ownInputs = customCrl || ownAnchor || setTime;

        // Ústupek, který si zadaný okamžik uděluje sám, a proto ho nemá kdo zaškrtnout: ověřuje-li
        // se ke dni v minulosti, je každý dnes pořízený revokační údaj vůči tehdejšímu certifikátu
        // opožděný. Bez tohohle řádku by blok nad ověřením k datu tvrdil, že proběhlo podle
        // standardů — a to není pravda. Hlásí se jen tehdy, když se takový údaj opravdu uplatnil,
        // a jen když ho neohlásilo už zaškrtávátko: to uděluje ústupek v plné šíři, tenhle zúženě.
        boolean dateRelax = setTime && !relNewerOcsp
                && vc != null && vc.postExpiryRevocationPresent();
        concessions = concessions || dateRelax;

        // ── 1. Porušilo se pravidlo? ────────────────────────────────────────────────────────
        if (concessions) {
            block.getChildren().add(boldMarkup(
                    eitherWillDo
                    ? "Ověřování **neproběhlo zcela podle technických standardů**. K uvedenému"
                      + " výsledku stačil **kterýkoli** z těchto ústupků — aktivované byly všechny"
                      + " a zastupují se navzájem, takže o žádný z nich se výsledek neopírá sám:"
                    : "Ověřování **neproběhlo zcela podle technických standardů**. K uvedenému"
                      + " výsledku se došlo za těchto ústupků:"));

            if (relNewerOcsp) {
                // Zaškrtávátko se jmenuje podle post-expiračního údaje, ale v politice DSS
                // sáhne šířeji: kromě RevocationHasInformationAboutCertificate sráží na WARN
                // i AcceptableRevocationDataFound, tedy bránu „nezbyl žádný použitelný údaj“.
                // Ta chytne i opačnou mez — údaj vydaný dřív, než certifikát vůbec vznikl
                // (BBB_XCV_REVOC_AFTER_CERT_NOT_BEFORE_ANS). Nad takovým dokumentem tvrdila
                // odrážka o certifikátu, že mu „platnost už skončila“, ač platí ještě roky:
                // potkáno 19. 9. 2026 na jednom reportu, kde CRL vznikla pět let před
                // certifikátem. Rozhoduje proto to, co v dokumentu opravdu je.
                block.getChildren().add(boldBullet(
                        vc != null && vc.postExpiryRevocationPresent()
                        ? "**Byly využity revokační informace vydané po skončení řádné doby"
                          + " platnosti certifikátu.** Standardy takový údaj nepřipouštějí:"
                          + " o certifikátu, jehož platnost už skončila, se podle nich nedá zpětně"
                          + " nic dokládat seznamem či odpovědí, které vznikly až potom."
                          + needTail
                        : "**Byl připuštěn revokační údaj, který o certifikátu nevypovídá.**"
                          + " Standardy žádají, aby seznam či odpověď o stavu certifikátu spadaly do"
                          + " doby jeho platnosti — údaj vzniklý dřív, než byl certifikát vydán,"
                          + " stejně jako údaj vzniklý až po jeho expiraci o něm žádné tvrzení"
                          + " neobsahuje. Zde se od toho odhlédlo a stav revokace se z takového"
                          + " údaje přesto vzal."
                          + needTail));
            }
            if (relExpiredSrc) {
                block.getChildren().add(boldBullet(
                        "**Byly přijaty revokační informace od vydavatele, jehož certifikát"
                        + " k rozhodnému okamžiku neplatil.** Standardy žádají, aby šlo vydavatele"
                        + " revokační informace k tomuto okamžiku ověřit. Nesplnit se to dá na obě"
                        + " strany: buď už jeho certifikát vypršel, nebo — ověřuje-li se"
                        + " k okamžiku v minulosti — tehdy ještě vydaný nebyl, protože odpověď"
                        + " podepsal respondér zřízený až později. Zde se od toho odhlédlo"
                        + " a vydavatel se posoudil k dnešku."
                        + needTail));
            }
            if (relSha1) {
                block.getChildren().add(boldBullet(
                        "**Nebylo přihlédnuto k zastaralosti algoritmu SHA-1.** Knihovna DSS"
                        + " považuje SHA-1 od roku 2022 za nedostatečný a podpisy či certifikáty,"
                        + " které jej používají, odmítá. Zde bylo od tohoto omezení odhlédnuto."
                        + ((eitherWillDo || !measured) ? ""
                            : " Bez tohoto ústupku by výsledek nebyl kladný.")));
            }
            if (relPdfNotEtsi) {
                block.getChildren().add(boldBullet(
                        "**Byl ověřen podpis ve formátu PDF-NOT-ETSI.** Jde o podpis podle starší"
                        + " specifikace Adobe, který nesplňuje požadavky ETSI na formát PAdES —"
                        + " chybí mu povinný atribut signing-certificate. Standardně jej DSS"
                        + " odmítá jako formátově nevyhovující; zde byl ověřen kryptograficky"
                        + " a obsahově a formátový nedostatek byl pominut."));
            }
            if (dateRelax) {
                block.getChildren().add(boldBullet(
                        "**Byly připuštěny revokační informace vydané po skončení platnosti"
                        + " certifikátu.** Ověřovalo se k okamžiku v minulosti, ale údaj o stavu"
                        + " revokace se pořizuje teď — vůči tehdejšímu certifikátu je tedy vždycky"
                        + " opožděný a standardy jej nepřipouštějí. AnEP jej připustil, ale jen"
                        + " zúženě: propustí údaj, který revokaci **dokládá**, kdežto údaj hlásící"
                        + " „nerevokován“ po expiraci zahazuje — podle RFC 5280 §3.3 už mezitím mohl"
                        + " být záznam ze seznamu odstraněn, takže nedokládá nic. Bez tohoto"
                        + " ústupku by k okamžiku v minulosti nešlo ověřit vůbec nic."));
            }
            appendPrimaryRoute(block, sig, decisive);
        } else {
            // „Zcela" si žádá čistý stůl: žádný ústupek rozhodující, žádný ani zapnutý, a ani
            // přísnější měřítko na razítka. Vlastní vstupy sem nepatří — o souladu nevypovídají.
            // A „bez jakýchkoli ústupků“ si žádá měření. Nezměřený běh se zapnutými
            // tolerancemi tuhle větu vyslovit nesmí: ústupky v něm v platnosti byly a jestli
            // se některý uplatnil, se nikdo nezeptal.
            String mode = unmeasuredConcessions
                    ? "Ověřování proběhlo **s ústupky, které byly pro tento běh zapnuté**"
                      + " (ETSI EN 319 102-1 a navazující je nepřipouštějí)."
                    : (alsoOn.isEmpty() && !stricter)
                    ? "Ověřování proběhlo **zcela podle technických standardů** (ETSI EN 319 102-1"
                      + " a navazujících), bez jakýchkoli ústupků."
                    : "Ověřování proběhlo **podle technických standardů** (ETSI EN 319 102-1"
                      + " a navazujících), bez jakýchkoli ústupků.";
            // Výčet toho, co bylo v pořádku, smí obsahovat jen to, co v pořádku doopravdy bylo:
            // věta o kotvě z EUTL nesmí zaznít nad vlastní kotvou (ani tam, kde ověření skončilo
            // na nesestaveném řetězci — situace 12). Řádek o revokaci naopak platí i nad seznamem
            // z vlastního úložiště: ten je použitelný údaj, jinak by se sem nedostal.
            //
            // O čase ověření tu nestojí nic. Bývala tu věta „ověřovalo se k aktuálnímu času“, ale
            // pod ní hned začíná vyprávění, jehož první odstavec je „Ověření k aktuálnímu času“ —
            // takže to jen dvakrát po sobě říkalo totéž. A zadaný okamžik má vlastní odrážku ve
            // třetí části, takže se ani při něm nic neztratí.
            List<String> affirm = new java.util.ArrayList<>();
            if (passed && !ownAnchor) {
                affirm.add("kotva důvěry pochází z důvěryhodných seznamů EU (EUTL)");
            }
            // „Z informací, které standardy připouštějí“ platí jen tam, kde se to změřilo. Nad
            // nezměřeným během to byla ta nejpřímější nepravda: seznam CRL vydaný pět let před
            // vydáním certifikátu standardy nepřipouštějí — právě proto ten podpis bez ústupku
            // neprojde — a věta o něm tvrdila opak.
            if (passed && !unmeasuredConcessions) {
                affirm.add("stav revokace byl zjištěn z informací, které standardy připouštějí");
            }
            block.getChildren().add(boldMarkup(
                    affirm.isEmpty() ? mode : mode + " " + sentence(czechList(affirm))));
        }
        // Podpis adbe.x509.rsa_sha1 knihovna DSS nezpracuje; ověřil ho AnEP sám
        // (Pkcs1SignatureValidator). Čtenář má vědět, že tu verdikt nevydala táž knihovna jako
        // u ostatních prvků — a co přesně se ověřovalo.
        if (cz.digitalnikontinuita.anep.core.Pkcs1PdfSignature.SUB_FILTER.equals(sig.getFormat())) {
            block.getChildren().add(boldMarkup("Tento typ podpisu (adbe.x509.rsa_sha1) knihovna"
                    + " DSS nezpracovává, **ověřila ho proto aplikace AnEP sama**: podpisovou"
                    + " hodnotu vlastní kryptografickou kontrolou proti podepsanému obsahu,"
                    + " certifikát (řetěz ke kotvě důvěry, revokaci a kvalifikovanost) knihovnou"
                    + " DSS, a použité algoritmy podle týchž kryptografických omezení, jaká DSS"
                    + " uplatňuje u ostatních podpisů."));
        }

        // Podle kterého seznamu a jeho verze (16. 10. 2026, pokyn uživatele). Hlavička karty říká
        // jen, kdy AnEP seznamy porovnal se zdrojem; z jakého stavu seznamu závěr o tomto prvku
        // vychází, říká až tahle věta. Nad vlastní kotvou mlčí — tu seznam nenese.
        SignatureSummary.TrustListRef tl = sig.getTrustList();
        if (tl != null && !ownAnchor) {
            List<String> version = new java.util.ArrayList<>();
            if (tl.sequenceNumber() != null) version.add("pořadové číslo " + tl.sequenceNumber());
            if (tl.issueDate() != null) version.add("vydán " + shortDateTime(tl.issueDate()));
            String country = tl.territory() != null && !tl.territory().isBlank()
                    ? " " + tl.territory() : "";
            block.getChildren().add(boldMarkup(
                    "Důvěra v certifikát a jeho kvalifikovanost se posuzovaly podle **národního"
                    + " důvěryhodného seznamu" + country + "**"
                    + (version.isEmpty() ? "." : " (" + String.join(", ", version) + ").")));
        }

        // ── 2. Neposuzovalo se přísněji? ────────────────────────────────────────────────────
        if (stricter) {
            block.getChildren().add(boldMarkup(
                    "Časová razítka se přitom posuzovala **přísněji, než žádají standardy**."
                    + " Razítko mohlo být ověřeno jako platné pouze tehdy, pokud jeho certifikát"
                    + " ještě neexpiroval. Standardy jsou v tomto shovívavější: razítko platí,"
                    + " dokud je příslušná služba vedena na důvěryhodných seznamech jako"
                    + " kvalifikovaná — až do tzv. sunsetu. V tomto nastavení se AnEP chová jako"
                    + " CzechPOINT a podatelny OVM, a může dojít k horšímu výsledku než validátor"
                    + " držící se standardů."));
        }

        // ── 3. Dojde k témuž někdo jiný? ────────────────────────────────────────────────────
        if (ownInputs) {
            block.getChildren().add(boldMarkup(
                    "Na výsledku se podílely vstupy, které nejsou dané dokumentem. Standardy"
                    + " jejich použití nebrání."
                    + " **K témuž výsledku ale dojde jen ten, kdo má totéž k dispozici:**"));

            if (ownAnchor) {
                String anchor = sig.getCustomTrustAnchorName();
                block.getChildren().add(boldBullet(
                        "**Důvěra se opírá o vlastní úložiště certifikátů, ne o důvěryhodné"
                        + " seznamy EU.** Řetězec certifikátů končí u certifikátu"
                        + (anchor != null && !anchor.isBlank() ? " „" + anchor + "“" : "")
                        + ", který byl do aplikace vložen ručně. To je rozhodnutí toho, kdo"
                        + " ověřuje — ne skutečnost daná dokumentem. Kdo je nesdílí, k témuž"
                        + " výsledku nedojde."));
            }
            if (customCrl) {
                block.getChildren().add(boldBullet(
                        "**Stav revokace byl zjištěn ze seznamu CRL ve vlastním úložišti.** Online"
                        + " zdroj použitelný údaj nevrátil — buď jej už neposkytuje, nebo vrátil"
                        + " odpověď, kterou standardy použít nedovolují; seznam z úložiště pak"
                        + " nastoupil právě na její místo. Sám je řádně vydaný a podepsaný"
                        + " vydavatelem a jeho použití standardy nijak nebrání — o uložená"
                        + " revokační data se opírá i každé ověření podpisu úrovně LT nebo LTA."
                        + " K témuž výsledku ale dojde jen ten, kdo má týž seznam k dispozici."));
            }
            if (setTime) {
                block.getChildren().add(boldBullet(
                        "**Ověřovalo se k zadanému okamžiku"
                        + (customValidationTime != null
                            ? " " + shortDateTime(customValidationTime
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant())
                            : "")
                        + ", ne k dnešku.** Slouží to k posouzení, jak podpis vypadal v minulosti."
                        + " Jiný nástroj obvykle ověřuje k času, kdy je spuštěn, a k jinému okamžiku může"
                        + " dojít k jinému závěru — zejména pokud byl certifikát mezitím revokován"
                        + " nebo mu skončila platnost."));
            }
        }

        // „Bez nich by dopadl stejně" je měřený údaj, ne úvaha — a měří se jen u kladného
        // ověření (computeDecisiveSettings vrací prázdno pro cokoli jiného). Tvrdit totéž nad
        // neznámým či záporným výsledkem by znamenalo vydávat chybějící měření za zjištění:
        // povolení formátu PDF-NOT-ETSI třeba rozhoduje o tom, že se k verdiktu vůbec došlo.
        if (!alsoOn.isEmpty()) {
            // „I další" jen tehdy, když nějaký ústupek opravdu rozhodoval. Nad větou „bez
            // jakýchkoli ústupků" by to znělo jako protimluv — a bylo: první věta mluvila
            // o žádných, druhá o dalších.
            String lead = concessions ? "Aktivovány byly i další ústupky" : "Aktivovány byly ústupky";
            // Tři různé věty pro tři různé stavy vědění. „Neprojevily se“ je závěr z měření a platí
            // jedině tam, kde měření proběhlo a nic nenašlo. Kde neproběhlo, se to řekne — tvrdit
            // nad chybějícím měřením, že by ověření dopadlo stejně, je nepravda tím nepříjemnější,
            // že zní jako zjištění.
            block.getChildren().add(boldMarkup(
                    !passed
                    ? lead + ": " + String.join(", ", alsoOn)
                      + ". Zda se na výsledku podílely, se měří jen u kladného ověření —"
                      + " k tomu zde nedošlo."
                    : unmeasuredConcessions
                    ? lead + ": " + String.join(", ", alsoOn)
                      + ". **Co z nich výsledek nesl, se "
                      + gendered(sig, "u tohoto podpisu", "u této pečeti",
                              "u tohoto razítka")
                      + " zjistit nepodařilo** — zjišťuje se to tím, že se ověření zopakuje bez"
                      + " nich, a to se tentokrát nepovedlo dokončit. Může tedy platit i to, že by"
                      + " bez nich ověření dopadlo jinak."
                    : lead + " (" + String.join(", ", alsoOn)
                      + "), na výsledku ověření " + genitiveType(sig) + " se ale neprojevily —"
                      + " bez nich by ověření dopadlo stejně."));
        }

    }

    /**
     * Spojí větné členy do českého výčtu: {@code a, b a c}.
     *
     * <p>Před poslední „a“ přibude čárka, končí-li předchozí člen vedlejší větou: vloženou vedlejší
     * větu je třeba uzavřít i tehdy, když za ní pokračuje spojka, před níž se jinak čárka nepíše.
     * Poznat ji podle čárky uvnitř členu je odhad, ale nad tímhle uzavřeným okruhem vět sedí
     * přesně. Dnešní dvojice členů na to nedosáhne — čárku má až ten poslední z nich; pravidlo
     * tu je pro ten, který sem přibude příště.
     */
    private static String czechList(List<String> parts) {
        if (parts.size() < 2) return parts.isEmpty() ? "" : parts.get(0);
        List<String> head = parts.subList(0, parts.size() - 1);
        String conj = head.get(head.size() - 1).contains(", ") ? ", a " : " a ";
        return String.join(", ", head) + conj + parts.get(parts.size() - 1);
    }

    /** Z větného členu udělá větu — velké první písmeno a tečka na konci. */
    private static String sentence(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1) + ".";
    }

    /**
     * Která cesta k platnosti se opravdu prošla, když je aktivovaných ústupků víc.
     *
     * <p>Měření nutnosti u zastupitelných ústupků odpoví „ani jeden“ — každý sám stačí — a výčet
     * je pak musí jmenovat všechny. Čtenáři to čte jako „muselo se odpustit obojí“, ačkoli platí
     * opak. Rozřešit to umí údaj, o který se závěr opřel: DSS ho vybírá samo a dává přednost
     * novějšímu, což je i věcně správně, protože odvolání je jednosměrné, kdežto „neodvolán“ platí
     * jen k okamžiku vydání.
     *
     * <p>Tři tvary podle toho, jaké vady vybraný údaj má — poměry naměřené na 918 podpisech
     * s aspoň jedním rozhodujícím ústupkem:
     *
     * <ul>
     *   <li>právě jedna vada (81 %) — hlavní ústupek je jednoznačný, ostatní jsou poznámka;</li>
     *   <li>obě naráz (6 %) — nesou to oba společně, hlavní cesta neexistuje;</li>
     *   <li>ani jedna (13 %) — ústupek se uplatnil jinde v řetězci a o téhle cestě nevypovídá,
     *       takže se nedopisuje nic a zůstává samotný výčet.</li>
     * </ul>
     */
    private void appendPrimaryRoute(VBox block, SignatureSummary sig, List<String> decisive) {
        ValidationCourse vc = sig.getValidationCourse();
        if (vc == null || vc.primaryRevocation() == null || decisive.size() < 2) return;
        ValidationCourse.PrimaryRevocation pr = vc.primaryRevocation();
        if (pr.noFlaw()) return;

        String newer   = ReportMapper.Relaxation.NEWER_OCSP.settingName();
        String expired = ReportMapper.Relaxation.EXPIRED_REVOCATION_SOURCES.settingName();

        if (pr.bothFlaws()) {
            block.getChildren().add(boldMarkup(
                    "O výsledku rozhodla " + pr.label() + " a ta má obě vady naráz: vznikla po"
                    + " skončení platnosti certifikátu a její vydavatel k rozhodnému okamžiku"
                    + " neplatil. Umožnily ji tedy oba aktivované ústupky společně, žádný z nich"
                    + " sám."));
            return;
        }
        String primary = pr.postExpiry() ? newer : expired;
        String other   = pr.postExpiry() ? expired : newer;
        boolean otherActive = decisive.contains(other);
        block.getChildren().add(boldMarkup(
                "O výsledku rozhodla " + pr.label() + " a umožnil ji ústupek **„" + primary + "“**."
                + (otherActive
                    ? " Aktivovaný byl i ústupek **„" + other + "“**; sám by k platnosti vedl"
                      + " také, ale jinou cestou — přes jinou revokační informaci."
                    : "")));
    }

    /** Odrážka, jejíž text umí tučné pasáže — {@link #boldMarkup} vedle {@code •}. */
    private static HBox boldBullet(String markup) {
        Label bullet = new Label("•");
        bullet.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
        bullet.setMinWidth(10);
        TextFlow flow = boldMarkup(markup);
        HBox.setHgrow(flow, Priority.ALWAYS);
        HBox row = new HBox(6, bullet, flow);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /**
     * Odstavec, v němž {@code **takhle**} označená slova vyjdou tučně.
     *
     * <p>{@link TextFlow}, ne {@code Label}: popisek umí jeden řez písma na celý text, každá tučná
     * pasáž by tedy musela být vlastní uzel a řádky by se lámaly na jejich hranicích místo na
     * okraji okna. Ve flow jsou běhy jeden text jako druhý a zalamuje se přes ně.
     *
     * <p>Bydlí tady, a ne v {@link ContinuityView}, odkud pochází — obě záložky ho potřebují
     * a {@code AdvisorView} už je místem, kam si {@code ContinuityView} chodí pro
     * {@link #collapsibleBlock}.
     */
    /**
     * Odstavec s tučnou hlavičkou v základní barvě a zbytkem v barvě zvolené.
     *
     * <p>{@link #boldMarkup} umí jen základní barvu — tady je potřeba, aby název položky držel
     * rytmus ostatních odstavců a červeně vyšlo jen samotné sdělení.
     */
    private static TextFlow labeledMarkup(String label, String body, String bodyColor) {
        TextFlow flow = new TextFlow();
        Text head = new Text(label);
        head.setStyle("-fx-font-size: 12px; -fx-fill: #111111; -fx-font-weight: bold;");
        Text rest = new Text(body);
        rest.setStyle("-fx-font-size: 12px; -fx-fill: " + bodyColor + ";");
        flow.getChildren().addAll(head, rest);
        return flow;
    }

    static TextFlow boldMarkup(String markup) {
        TextFlow flow = new TextFlow();
        appendMarkupRuns(flow, markup, "#111111");
        return flow;
    }

    /**
     * Odstavec, který pokračuje varováním v jiné barvě.
     *
     * <p>Jeden {@link TextFlow}, ne dva odstavce pod sebou: varování patří k témuž sdělení a mezi
     * dvěma odstavci by vypadalo jako samostatná položka. Zalamuje se přes obojí, takže barva se
     * mění uprostřed řádku tam, kde věta začíná.
     */
    private static TextFlow markupWithColoredTail(String markup, String tail, String tailColor) {
        TextFlow flow = new TextFlow();
        appendMarkupRuns(flow, markup, "#111111");
        appendMarkupRuns(flow, " " + tail, tailColor);
        return flow;
    }

    /**
     * Rozseká {@code **tučně**} a {@code *kurzívou*} označený text na běhy a přidá je do flow
     * v dané barvě.
     *
     * <p>Tučné se dělí první, takže {@code **} nikdy nepadne do dělení kurzívy. Jednoduchá
     * hvězdička uvnitř textu se tím stává značkou — v textech, které sem chodí, žádná není
     * (prověřeno nad celým balíkem {@code ui}; hvězdičky v něm nesou jen filtry souborů
     * a regulární výrazy, které tudy neprocházejí).
     */
    private static void appendMarkupRuns(TextFlow flow, String markup, String color) {
        boolean bold = false;
        for (String boldRun : markup.split(java.util.regex.Pattern.quote("**"), -1)) {
            boolean italic = false;
            for (String run : boldRun.split(java.util.regex.Pattern.quote("*"), -1)) {
                if (!run.isEmpty()) {
                    Text t = new Text(run);
                    t.setStyle("-fx-font-size: 12px; -fx-fill: " + color + ";"
                            + (bold ? " -fx-font-weight: bold;" : "")
                            + (italic ? " -fx-font-style: italic;" : ""));
                    flow.getChildren().add(t);
                }
                italic = !italic;
            }
            bold = !bold;
        }
    }

    // Blok „Vizualizace podpisu" tu stával od 4. 9. 2026 (addAppearanceBlock) a 8. 9. 2026 byl
    // na pokyn uživatele vypuštěn: týž blok nese Rozšířený přehled, kde zůstává, a v Analýze
    // dvakrát totéž jen odsouvalo nálezy, pro které je karta postavená. Vizualizace uvnitř bloku
    // „Podepsaná osoba a čas" zůstává — je to řádek toho bloku, ne samostatný blok.

    private void addFormatBlock(VBox card, SignatureSummary sig) {
        if (sig.getFormat() == null) return;
        VBox block = new VBox(4);
        block.setPadding(new Insets(8, 12, 8, 12));
        block.getChildren().add(infoRow(
                gendered(sig, "Formát podpisu:", "Formát pečeti:", "Formát razítka:"),
                formatLabel(sig.getFormat())));
        addFormatRows(block, sig);
        card.getChildren().add(collapsibleBlock(
                gendered(sig, "Formát podpisu", "Formát pečeti", "Formát razítka"), block, false));
        card.getChildren().add(blockSeparator());
    }

    /**
     * Adds "Druh formátu:", "Zapouzdření:", "Úroveň:" and "Referenční formát:" below the plain
     * format row. A standalone time-stamp gets the same four rows, but each of them answers
     * differently — see {@link #addTimestampFormatRows}. Until 3. 9. 2026 it got none of them
     * and its block said only "TimeStampToken", which left the two questions the reader carries
     * over from signatures — what level is it, is it a reference format — unanswered rather
     * than answered with "neither applies here".
     */
    private void addFormatRows(VBox body, SignatureSummary sig) {
        if ("TimeStampToken".equals(sig.getFormat())) {
            addTimestampFormatRows(body, sig);
            return;
        }

        // Řádek „Druh formátu:" jmenuje rodinu AdES, do které podpis patří. U PKCS #7
        // a PDF-NOT-ETSI se vynechává: ani jeden do žádné z těch rodin nepatří — PKCS #7 je
        // starší konstrukce, ze které CAdES teprve vzešel, a PDF-NOT-ETSI je podpis v PDF,
        // který PAdES nesplňuje. Řádek by jim tedy přisoudil příslušnost, kterou nemají;
        // pokyn uživatele z 12. 9. 2026.
        //
        // Vypouští se jen ten řádek, ne odpověď samotná: packagingText() se ptá
        // formatFamilyText() na to, jestli formát vůbec zná, a bez odpovědi by u obou formátů
        // zmizelo i „Zapouzdření:".
        String family = adesFamilyApplies(sig.getFormat()) ? formatFamilyText(sig) : null;
        if (family != null) body.getChildren().add(infoRow("Druh formátu:", family));

        String packaging = packagingText(sig);
        if (packaging != null) body.getChildren().add(infoRow("Zapouzdření:", packaging));

        String level = levelKey(sig.getFormat());
        if (level != null) {
            body.getChildren().add(infoRow("Úroveň:",
                    levelText(level, sig, levelsDefined(sig.getFormat()))));
        } else {
            // Starší profily PAdES do stupnice B/T/LT/LTA nepatří a dosadit jim písmeno znamená
            // tvrdit o nich něco, co neplatí — viz {@link #oldPadesLevelText}.
            String old = oldPadesLevelText(sig.getFormat());
            if (old != null) body.getChildren().add(infoRow("Úroveň:", old));
        }

        body.getChildren().add(infoRow("Referenční formát:",
                isReferenceFormat(sig.getFormat()) ? REFERENCE_FORMAT_YES : REFERENCE_FORMAT_NO));
    }

    /**
     * Popis formátu samostatného časového razítka — čtyři řádky jako u podpisu, jen s jinými
     * odpověďmi. Dvě z nich znějí „netýká se": úroveň i referenční formát jsou pojmy o podpisech
     * a mlčet o nich by čtenáře nechalo hádat, jestli je aplikace nezjistila, nebo nedávají smysl.
     */
    private void addTimestampFormatRows(VBox body, SignatureSummary sig) {
        body.getChildren().add(infoRow("Druh formátu:", TS_FAMILY));

        String packaging = timestampPackagingText(sig);
        if (packaging != null) body.getChildren().add(infoRow("Zapouzdření:", packaging));

        body.getChildren().add(infoRow("Úroveň:", TS_LEVEL));
        body.getChildren().add(infoRow("Referenční formát:", TS_REFERENCE_FORMAT));
    }

    private static final String TS_FAMILY =
            "Token dle RFC 3161 (Time-Stamp Protocol) — datová struktura, kterou autorita času"
            + " stvrzuje, že daná data existovala v uvedený okamžik. Uvnitř jde o strukturu CMS,"
            + " tedy tentýž nosič, jaký používá formát CAdES; podepsaným obsahem tu ale není"
            + " dokument sám, nýbrž jeho otisk spolu s časem.";

    private static final String TS_LEVEL =
            "Časová razítka se do úrovní (B, T, LT, LTA) nedělí. Ty popisují, co si podpis přibral"
            + " k sobě, aby zůstal ověřitelný i později — a časové razítko je právě to, co si"
            + " přibírá. O jeho vlastní ověřitelnost v čase se stará až další razítko nad ním.";

    private static final String TS_REFERENCE_FORMAT =
            "Netýká se. Prováděcí rozhodnutí Komise (EU) 2015/1506 vyjmenovává referenční formáty"
            + " elektronických podpisů a pečetí; o časových razítkách nemluví a žádný jejich"
            + " formát tedy jako referenční stanoven není. Kvalifikovanému elektronickému"
            + " časovému razítku přiznává nařízení eIDAS (čl. 41) domněnku správnosti data"
            + " a času a neporušenosti dat, se kterými je spojeno — a to bez ohledu na formát.";

    /**
     * Kde razítko leží vůči datům, která razítkuje. Tři případy, a rozhodují o tomtéž jako
     * u podpisů: jestli otevřený soubor stačí k ověření. Kontejner ASiC pozná
     * {@link #packagingText}, PDF se pozná podle souboru, který razítko nese.
     */
    private String timestampPackagingText(SignatureSummary sig) {
        if (asicContainer) return packagingText(sig);

        File carrier = signatureFile();
        String name = carrier != null ? carrier.getName().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".pdf")) {
            return "interní razítko (též: zabalené razítko, anglicky enveloped time-stamp) —"
                 + " razítko je vloženo přímo do dokumentu PDF, jako podpisové pole typu"
                 + " DocTimeStamp (SubFilter ETSI.RFC3161) připojené na konec souboru"
                 + " inkrementální aktualizací. Původní obsah dokumentu zůstává beze změny"
                 + " a k ověření stačí tento jediný soubor.";
        }
        return "externí razítko (též: oddělené razítko, anglicky detached time-stamp) — token"
             + " leží v samostatném souboru (obvykle s příponou .tst nebo .tsr) a orazítkovaná"
             + " data mimo něj. K ověření je proto potřeba obojí: soubor s razítkem i data,"
             + " ke kterým patří.";
    }

    /**
     * The certificate kind, with the QSCD flag spelled out for qualified certificates. The flag
     * only carries meaning there: it is what separates a qualified signature from a merely
     * advanced one based on a qualified certificate, so on a non-qualified certificate it would
     * say nothing useful. Unknown flag (null) is left unmentioned rather than guessed.
     */
    private static String certKindText(SignatureSummary sig) {
        String kind = sig.getCertKind();
        if (sig.isCertIssuedUnderDirective() && !sig.isCertSystem()) {
            kind = kind + ", vydaný ještě podle směrnice 1999/93/ES";
        }
        // Příznak QSCD patří k evropské kvalifikovanosti; u systémového certifikátu, kvalifikovaného
        // podle českého zákona, by věta „bez příznaku…“ zněla jako vada, kterou není.
        if (!kind.startsWith("kvalifikovaný") || sig.getCertQscd() == null
                || sig.isCertSystem()) return kind;
        return kind + (sig.getCertQscd()
                ? ", s příznakem uložení soukromého klíče na kvalifikovaném prostředku (QSCD)"
                : ", bez příznaku uložení soukromého klíče na kvalifikovaném prostředku (QSCD)");
    }

    /**
     * Which of the four AdES families the signature belongs to, and what kind of content that
     * family is meant for. Says nothing about packaging — that is the "Zapouzdření:" row, and
     * the two are independent: the same XAdES signature can be internal, enveloping, detached
     * or sit in a container.
     */
    private String formatFamilyText(SignatureSummary sig) {
        String fmt = sig.getFormat() == null ? "" : sig.getFormat().toUpperCase(Locale.ROOT);
        // An ASiC signature keeps its own family (XAdES or CAdES); the container it travels in
        // belongs to the "Zapouzdření:" row, not here.
        return baseFamilyText(fmt);
    }

    /** English noun used in the packaging terms: enveloped/detached/enveloping signature vs seal. */
    private static String englishNoun(SignatureSummary sig) {
        return gendered(sig, "signature", "seal", "time-stamp");
    }

    private static String baseFamilyText(String fmt) {
        if (fmt.startsWith("PADES") || fmt.startsWith("PDF") || fmt.startsWith("PKCS7"))
            return "PAdES (PDF Advanced Electronic Signatures) — formát určený pro dokumenty"
                 + " (soubory) v datovém formátu PDF.";
        if (fmt.startsWith("CADES"))
            return "CAdES (CMS Advanced Electronic Signatures) — formát určený pro libovolná data,"
                 + " tedy soubory jakéhokoli druhu.";
        if (fmt.startsWith("XADES"))
            return "XAdES (XML Advanced Electronic Signatures) — formát určený pro data ve formátu"
                 + " XML, tedy pro strukturovaná data, se kterými pracují počítačové systémy"
                 + " (formuláře, výpisy, zprávy mezi úřady).";
        if (fmt.startsWith("JADES"))
            return "JAdES (JSON Advanced Electronic Signatures) — formát určený pro data ve"
                 + " formátu JSON, se kterými pracují webové služby a mobilní aplikace.";
        return null;
    }

    /**
     * How the signature and the data it signs are put together. Four cases, and they are what
     * decides whether the opened file is enough to verify anything: only the detached one needs
     * a second file. The container case is reported first — an ASiC container is recognised
     * regardless of which family produced the signature inside it.
     */
    private String packagingText(SignatureSummary sig) {
        String fmt = sig.getFormat() == null ? "" : sig.getFormat().toUpperCase(Locale.ROOT);
        String noun = gendered(sig, "podpis", "pečeť", "razítko");
        // An unrecognised format leaves the last branch guessing; say nothing instead.
        if (formatFamilyText(sig) == null && !asicContainer && !fmt.startsWith("ASIC")) return null;

        if (asicContainer || fmt.startsWith("ASIC")) {
            String what = "ASiC-S".equals(asicContainerKind)
                    ? " Jde o kontejner ASiC-S, tedy Simple (jednoduchý): nese jediný podepsaný"
                    + " dokument spolu s podpisy a razítky, která k němu patří."
                    : "ASiC-E".equals(asicContainerKind)
                      ? " Jde o kontejner ASiC-E, tedy Extended (rozšířený): může nést několik"
                      + " podepsaných dokumentů spolu s podpisy a razítky, která k nim patří."
                      : "";
            return "kontejner (ASiC) — podepsané dokumenty a podpisy k nim jsou uloženy vedle sebe"
                 + " v jednom souboru typu ZIP." + what + " K ověření stačí tento jediný soubor.";
        }
        if (fmt.startsWith("PADES") || fmt.startsWith("PDF") || fmt.startsWith("PKCS7"))
            return "interní " + noun + " (též: " + gendered(sig, "zabalený podpis",
                    "zabalená pečeť", "zabalené razítko") + ", anglicky enveloped " + englishNoun(sig)
                 + ") — "
                 + gendered(sig, "podpis je vložen", "pečeť je vložena", "razítko je vloženo")
                 + " do podepisovaného obsahu a tvoří s ním jeden celek: jediný dokument (soubor)"
                 + " v datovém formátu PDF. K ověření proto stačí tento jediný soubor.";

        boolean detached = externalSignature || !signedDataInside;
        if (detached)
            return "externí " + noun + " (též: " + gendered(sig, "oddělený podpis",
                    "oddělená pečeť", "oddělené razítko") + ", anglicky detached "
                 + englishNoun(sig) + ") — "
                 + noun + " je uložen" + gendered(sig, "", "a", "o") + " v samostatném souboru"
                 + (fmt.startsWith("CADES") ? " (u formátu CAdES obvykle s příponou .p7s)" : "")
                 + " a podepsaný obsah leží mimo něj. K ověření je proto potřeba obojí: soubor"
                 + " s podpisem i původní podepsaný dokument.";

        if (fmt.startsWith("XADES"))
            return "interní nebo obalující " + noun + " — " + noun + " i podepsaná data jsou"
                 + " v jednom souboru XML, takže k ověření stačí tento jediný soubor. U formátu"
                 + " XML se obě varianty poznají až podle vnitřní struktury: buď je "
                 + noun + " vložen" + gendered(sig, "", "a", "o") + " do podepsaného XML"
                 + " (interní), nebo naopak data leží uvnitř " + gendered(sig, "podpisu",
                    "pečeti", "razítka") + " (obalující). Přípona souboru se u obou neliší.";

        return "obalující " + noun + " (anglicky enveloping " + englishNoun(sig) + ") —"
             + " podepsaný obsah je uložen"
             + " uvnitř " + gendered(sig, "podpisu", "pečeti", "razítka") + ", "
             + gendered(sig, "který", "která", "které")
             + " tak vůči podepsanému obsahu vystupuje jako jeho obálka (obal)."
             + " Obojí je tedy obsaženo v jediném souboru"
             + (fmt.startsWith("CADES") ? " (u formátu CAdES obvykle s příponou .p7m)" : "")
             + ". K ověření proto stačí tento jediný soubor.";
    }

    /**
     * Baseline level of a signature format: B, T, LT or LTA, or {@code null} when the format
     * carries no level this application is willing to translate. The legacy XAdES/CAdES levels
     * are mapped only where the correspondence is exact — BES/EPES hold the same content as B,
     * and -A the same as LTA. The intermediate -C and -X levels store references to revocation
     * data instead of the data itself, so they match no baseline level and are left out rather
     * than described inaccurately.
     */
    /**
     * Formát tak, jak se píše čtenáři — protějšek identifikátoru, který vrací DSS.
     *
     * <p>Liší se jen u PKCS #7, a to dvakrát. Jméno se píše s křížkem, tak jak ten formát jmenuje
     * RFC 2315 i všechna ostatní místa aplikace. A úroveň se z něj vypouští: DSS hlásí
     * {@code PKCS7-B/T/LT/LTA} podle toho, co podpis nese, jenže úrovně definují standardy ETSI
     * pro formáty AdES a pro PKCS #7 žádné nedefinují — „PKCS #7-T" by tedy čtenáři tvrdilo, že
     * taková úroveň existuje. Pokyn uživatele z 12. 9. 2026.
     *
     * <p>Ostatní formáty se vracejí beze změny. U „CAdES-BES" nebo „PAdES-LTV" je přípona
     * součástí jména profilu, ne úrovní, a uříznout ji by formát přejmenovalo.
     *
     * <p><b>Netýká se Detailního rozboru</b>, který vypisuje hodnoty z {@code DiagnosticData}
     * doslova; tam je smyslem ukázat, co říká DSS, ne co říkáme my.
     */
    static String formatLabel(String format) {
        if (format == null) return null;
        if (format.toUpperCase(Locale.ROOT).startsWith("PKCS7")) return "PKCS #7";
        // „TimeStampToken" je identifikátor od DSS, ne jméno, které by čtenáři něco řeklo —
        // zvlášť od 22. 9. 2026, kdy stojí i v závorce za právním typem („kvalifikované
        // elektronické časové razítko (…)"). Norma se jmenuje napřed, stejně jako u evidenčních
        // záznamů („XMLERS (RFC 6283)", „ERS (RFC 4998)"); v závorce za typem by vnořená druhá
        // závorka jen překážela, proto „RFC 3161 TimeStampToken" a ne obráceně.
        return "TimeStampToken".equals(format) ? "RFC 3161 TimeStampToken" : format;
    }

    /**
     * Definují pro tenhle formát standardy úrovně?
     *
     * <p>U PKCS #7 ne. Úrovně B/T/LT/LTA popisují profily AdES; PKCS #7 mezi ně nepatří a žádné
     * pro něj definované nejsou. DSS přesto podle obsahu podpisu hlásí {@code PKCS7-B/T/LT/LTA},
     * takže z toho jde poznat, <b>co podpis nese</b> — ne ale, že to je úroveň. Řádek „Úroveň:"
     * proto u takového formátu popisuje obsah a jména úrovní z něj mizí; pokyn uživatele
     * z 12. 9. 2026, týž jako u {@link #formatLabel}.
     */
    static boolean levelsDefined(String format) {
        return format != null && !format.toUpperCase(Locale.ROOT).startsWith("PKCS7");
    }

    /**
     * Patří tenhle formát do některé z rodin AdES (PAdES, CAdES, XAdES, JAdES)?
     *
     * <p>PKCS #7 ne — je to konstrukce starší než CAdES a ETSI EN 319 122-1 nesplňuje.
     * PDF-NOT-ETSI také ne — tak DSS označuje podpis v PDF, který nesplňuje PAdES. Oba tedy
     * v PDF žijí a {@link #isPdfSignature} je za PDF podpisy počítá, ale rodina AdES je jiná
     * otázka a odpověď na ni je u nich „žádná".
     */
    static boolean adesFamilyApplies(String format) {
        return format != null
                && !format.toUpperCase(Locale.ROOT).startsWith("PKCS7")
                && !isPdfNotEtsi(format);
    }

    /**
     * Písmeno stupnice B/T/LT/LTA, nebo {@code null}, když se ta stupnice na formát nevztahuje.
     *
     * <p><b>U starších profilů PAdES se nevztahuje</b>, a je to rozdíl proti CAdES a XAdES, kde
     * se BES na B přepsat dá. Nález uživatele z 20. 9. 2026: podpis hlášený jako {@code PAdES-BES}
     * nesl podpisové i dokumentové časové razítko a k tomu vloženou odpověď OCSP, a řádek
     * „Úroveň“ o něm tvrdil pravý opak — „obsahuje jen samotný podpis a certifikát podepsané
     * osoby. Není v něm žádný důkaz o čase ani uložená informace o tom, zda certifikát nebyl
     * zneplatněn.“
     *
     * <p>Ověřeno ve výčtu {@code SignatureLevel} knihovny DSS: nebaselinová rodina PAdES (ETSI
     * TS 102 778) má jen {@code BES}, {@code EPES} a {@code LTV} — <b>obdobu úrovně T v ní
     * nenajdete</b>, takže podpis s razítkem v ní zůstane BES. Rodiny CAdES a XAdES naproti tomu
     * {@code -T} mají ({@code CAdES-BES}, {@code CAdES-T}, {@code CAdES-LT}…), a tam tedy BES
     * opravdu znamená „bez razítka“ a písmeno sedí.
     *
     * <p>{@code PAdES-LTV} vracelo {@code null} i dosud, takže řádek u něj vůbec nebyl. Nově ho
     * dostane — jen s vysvětlením místo písmene, viz {@link #oldPadesLevelText}.
     */
    static String levelKey(String format) {
        if (format == null) return null;
        if (oldPadesLevel(format) != null) return null;
        String f = format.toUpperCase(Locale.ROOT).replace('_', '-');
        int baseline = f.indexOf("BASELINE-");
        String suffix = baseline >= 0
                ? f.substring(baseline + "BASELINE-".length())
                : f.substring(f.lastIndexOf('-') + 1);
        return switch (suffix) {
            case "B", "BES", "EPES" -> "B";
            case "T"                -> "T";
            case "LT", "XL"         -> "LT";
            case "LTA", "A"         -> "LTA";
            default                 -> null;
        };
    }

    /** {@code BES}, {@code EPES} nebo {@code LTV} u starších profilů PAdES; jinak {@code null}. */
    static String oldPadesLevel(String format) {
        if (format == null) return null;
        String f = format.toUpperCase(Locale.ROOT).replace('_', '-');
        if (!f.startsWith("PADES-") || f.contains("BASELINE")) return null;
        String suffix = f.substring(f.lastIndexOf('-') + 1);
        return switch (suffix) {
            case "BES", "EPES", "LTV" -> suffix;
            default -> null;
        };
    }

    /**
     * Co ta úroveň znamená u starších profilů PAdES — a hlavně co <b>ne</b>znamená.
     *
     * <p>Řádek se nevypouští, ačkoli by to bylo snazší: čtenář se na úroveň ptá a prázdné místo
     * by četl jako „aplikace to nezjistila“. Místo písmene proto stojí věta, která říká, že
     * stupnice B/T/LT/LTA je tu cizí — a rovnou i to, že z označení BES <b>neplyne</b>, že podpis
     * nemá časové razítko. Právě tenhle nevyslovený krok svedl čtenáře na scestí.
     */
    static String oldPadesLevelText(String format) {
        String level = oldPadesLevel(format);
        if (level == null) return null;
        String common = " Jde o profil podle starší specifikace ETSI TS 102 778, ne podle"
                + " baselinových profilů EN 319 142-1, a stupnice B / T / LT / LTA se ho"
                + " netýká: ta starší rodina zná jen BES a EPES, a nad nimi LTV pro podpis"
                + " s vloženými validačními daty. Obdobu úrovně T, tedy stupeň, který by"
                + " jmenoval časové razítko, v ní nenajdete.";
        return switch (level) {
            case "LTV" -> "LTV (Long Term Validation) —" + common
                    + " Samo LTV znamená, že dokument nese vložená validační data (slovník"
                    + " /DSS, případně /VRI), o která se ověření může opřít i později.";
            case "EPES" -> "EPES (Explicit Policy Electronic Signature) —" + common
                    + " Od BES se liší jen tím, že se v podpisu výslovně odkazuje na podpisovou"
                    + " politiku. Z označení EPES tedy neplyne, že podpis nemá časové razítko"
                    + " ani vložené revokační informace — co k sobě opravdu nese, říká blok"
                    + " „Časová razítka“ v Rozšířeném přehledu.";
            default -> "BES (Basic Electronic Signature) —" + common
                    + " Z označení BES tedy neplyne, že podpis nemá časové razítko ani vložené"
                    + " revokační informace — co k sobě opravdu nese, říká blok „Časová"
                    + " razítka“ v Rozšířeném přehledu.";
        };
    }

    /** True for the ETSI baseline profiles — the ones Decision (EU) 2015/1506 lists. */
    static boolean isReferenceFormat(String format) {
        return format != null && format.toUpperCase(Locale.ROOT).contains("BASELINE");
    }

    /**
     * Does this signature sit inside a PDF, and can it therefore carry revocation data in a
     * DSS/VRI dictionary?
     *
     * <p>Wider than {@link #isReferenceFormat}, deliberately. The DSS dictionary lives in the
     * document catalog and its VRI entries are keyed by the SHA-1 of a signature's {@code
     * /Contents} — a construction at the PDF level that knows nothing about SubFilter. Adobe's LTV
     * for {@code adbe.pkcs7.detached} is where it came from; the ETSI baseline profiles were laid
     * on top afterwards. So a non-reference signature can be given embedded revocation data just
     * as well, and gains exactly the same thing from it: the ability to be checked once the
     * certificate has expired and the CA has stopped publishing.
     *
     * <p>What it does not gain is a reference format. A PKCS7 signature becomes PKCS7-LT, and a
     * PDF-NOT-ETSI one gets no level at all — DSS reads its DSS dictionary but refuses to grade it.
     * Wording shown to the user must not promise otherwise.
     *
     * <p>Covers {@code PAdES-*} (including the pre-baseline BES/EPES/LTV), {@code PKCS7-*} and
     * {@code PDF-NOT-ETSI}. Detached CAdES/XAdES/JAdES and bare time-stamp tokens are excluded —
     * there is no PDF to put a dictionary in.
     */
    /**
     * A PDF signature that no conformant validator will accept, whatever is done to it.
     *
     * <p>DSS reports this level when the signature lacks the attributes PAdES requires —
     * {@code message-digest} / {@code SignedProperties} and {@code signing-certificate} — which is
     * what makes it return SIG_CONSTRAINTS_FAILURE. Preservation cannot supply an attribute the
     * signer never included, so the verdict is the same before and after: measured on
     * AnEP\PDF not ETSI\T kvalifikovany nereferencni.pdf, INDETERMINATE/SIG_CONSTRAINTS_FAILURE
     * either way. Offering uchování there promises something it cannot deliver.
     *
     * <p>PKCS7 is deliberately not included. It is equally non-reference, but preservation does
     * change its verdict: the same corpus file went from INDETERMINATE/OUT_OF_BOUNDS_NO_POE to
     * TOTAL_PASSED as PKCS7-LT, with every relaxation switched off.
     */
    static boolean isPdfNotEtsi(String format) {
        return format != null && format.toUpperCase(Locale.ROOT).startsWith("PDF-NOT-ETSI");
    }

    static boolean isPdfSignature(String format) {
        if (format == null) return false;
        String f = format.toUpperCase(Locale.ROOT);
        return f.startsWith("PADES") || f.startsWith("PKCS7") || f.startsWith("PDF-NOT-ETSI");
    }

    /**
     * Popis úrovně do řádku „Úroveň:".
     *
     * @param levelled zda se v textu smí mluvit o úrovních. U formátu, pro který standardy žádné
     *                 nedefinují (PKCS #7 — viz {@link #levelsDefined}), se vypouští jméno úrovně
     *                 na začátku i odkazy na sousední úrovně v závorkách; věta pak říká jen, co
     *                 podpis nese. Jinak by řádek tvrdil existenci úrovně, kterou blok o kus výš
     *                 popírá větou „pro který standardy úrovně nedefinují".
     */
    static String levelText(String level, SignatureSummary sig, boolean levelled) {
        return switch (level) {
            case "B" -> {
                String body =
                    gendered(sig, "obsahuje jen samotný podpis a certifikát podepsané osoby",
                                  "obsahuje jen samotnou pečeť a certifikát pečetící osoby",
                                  "obsahuje jen samotné razítko a certifikát autority, která je vydala")
                    + ". Není v něm žádný důkaz o čase ani uložená informace o tom, zda certifikát nebyl"
                    + " zneplatněn. "
                    // „Proto jen do konce platnosti certifikátu" bylo tvrzení bez výhrady, a od
                    // chvíle, kdy si AnEP umí odvodit důkaz existence z prvku, který tenhle kryje
                    // (situace 54), je to tvrzení nepravdivé: právě takový podpis se ověří
                    // k okamžiku o roky staršímu. Výhrada se proto píše vždycky — netýká se jen
                    // situace 54, nýbrž každého důkazu, který se najde jinde než v prvku samém.
                    // Pokyn uživatele z 13. 9. 2026.
                    + "Nenajde-li se jiný použitelný důkaz o existenci v čase, je spolehlivě "
                    + gendered(sig, "ověřitelný", "ověřitelná", "ověřitelné")
                    + " jen do konce platnosti certifikátu; potom už zpravidla nelze prokázat, že "
                    + gendered(sig, "podpis vznikl", "pečeť vznikla", "razítko vzniklo")
                    + " v době, kdy byl certifikát platný.";
                yield levelled ? "B (Basic) — " + body : capitalize(body);
            }
            case "T" -> {
                String body = "kromě "
                    + gendered(sig, "samotného podpisu", "samotné pečeti", "samotného razítka")
                    + (levelled ? " (jako u úrovně B)" : "")
                    + " obsahuje navíc kvalifikované elektronické časové razítko, které"
                    + " (v případě své platnosti) prokazuje, že "
                    + gendered(sig, "podpis existoval", "pečeť existovala", "razítko existovalo")
                    + " už v určitý okamžik. Díky tomu se principiální možnost ověřit platnost "
                    + gendered(sig, "podpisu", "pečeti", "razítka") + " prodlužuje do konce doby, po"
                    + " kterou lze ověřit platnost časového razítka. Neobsahuje ale revokační"
                    + " informace (informace o stavu revokace, resp. předčasného zneplatnění"
                    + " certifikátu, na kterém "
                    + gendered(sig, "je podpis založen", "je pečeť založena", "je razítko založeno")
                    + "). Úspěšné ověření platnosti tak zůstává závislé na tom, zda jsou tyto"
                    + " informace stále dostupné od vydavatele certifikátu.";
                yield levelled ? "T (Time) — " + body : capitalize(body);
            }
            case "LT" -> {
                String body = "kromě "
                    + gendered(sig, "samotného podpisu", "samotné pečeti", "samotného razítka")
                    + (levelled ? " (B) a časového razítka (T)" : " a časového razítka")
                    + " obsahuje navíc vložené revokační informace"
                    + " (seznamy CRL či odpovědi OCSP).";
                yield levelled ? "LT (Long Term) — " + body : capitalize(body);
            }
            case "LTA" -> {
                String body = "kromě "
                    + gendered(sig, "samotného podpisu", "samotné pečeti", "samotného razítka")
                    + (levelled ? " (B), časového razítka (T) a revokačních informací (LT)"
                                : ", časového razítka a revokačních informací")
                    + " obsahuje ještě jedno"
                    + " či více archivních časových razítek, která lze v čase dále přidávat. "
                    + (levelled
                        ? "Jde o úroveň určenou pro dlouhodobé uchovávání elektronicky"
                          + " podepsaných dokumentů."
                        : "Slouží k dlouhodobému uchovávání elektronicky podepsaných"
                          + " dokumentů.");
                yield levelled ? "LTA (Long Term with Archive time-stamps) — " + body
                               : capitalize(body);
            }
            default -> "";
        };
    }

    private static final String REFERENCE_FORMAT_YES =
            "Ano. Jde o jeden z formátů, které jako referenční stanoví prováděcí rozhodnutí Komise"
            + " (EU) 2015/1506. Orgány veřejné moci v celé EU musí umět takový podpis přijmout"
            + " a ověřit, pokud pro danou službu zaručený nebo kvalifikovaný podpis vyžadují"
            + " (čl. 27 a 37 nařízení eIDAS).";

    /**
     * Deliberately does not say the signature is invalid or that a public body must refuse it —
     * neither follows from the rules. A non-reference format is a processing problem, not a
     * validity problem.
     */
    private static final String REFERENCE_FORMAT_NO =
            "Ne. Po technické stránce může být podpis zcela v pořádku, ale nepoužívá žádný"
            + " z formátů, které jako referenční stanoví prováděcí rozhodnutí Komise (EU)"
            + " 2015/1506. Povinnost orgánů veřejné moci takový podpis bez dalšího přijmout se na"
            + " něj nevztahuje — přijmout ho mohou, ale musí být schopny jej ověřit; podle čl. 2"
            + " zmíněného rozhodnutí k tomu má být k dispozici možnost ověření použitelná"
            + " i přeshraničně. V praxi proto počítejte s tím, že takový dokument nemusí být na"
            + " úřadě zpracovatelný, i když je podpis platný. Pokud dokument někam odesíláte, je"
            + " bezpečnější podepsat jej znovu v některém z referenčních formátů.";

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Tlačítko jednorázového tolerantního ověření.
     *
     * <p><b>Nikdo ho dnes nestaví</b> — 25. 8. 2026 zmizel řádek „Doporučení“, ve kterém stálo, a
     * jinam se zatím nedalo. Ponecháno záměrně pro budoucí potřebu: obsluha je celá živá, {@code
     * MainView} pořád přes {@code setLenientVerifyCallback()} předává {@code performLenientVerify()},
     * takže tlačítko stačí někam vložit a funguje. Nemazat jako mrtvý kód.
     */
    private Button makeLenientButton() {
        Button btn = new Button("Zkus jednorázové tolerantní ověření");
        btn.setFocusTraversable(false);
        btn.setStyle(LENIENT_BTN_NORMAL);
        btn.setOnMouseEntered(e -> btn.setStyle(LENIENT_BTN_HOVER));
        btn.setOnMouseExited(e  -> btn.setStyle(LENIENT_BTN_NORMAL));
        btn.setOnMousePressed(e -> btn.setStyle(LENIENT_BTN_PRESSED));
        btn.setOnMouseReleased(e -> btn.setStyle(
                btn.isHover() ? LENIENT_BTN_HOVER : LENIENT_BTN_NORMAL));
        btn.setOnAction(e -> { if (lenientVerifyCallback != null) lenientVerifyCallback.run(); });
        return btn;
    }

    private static final String LENIENT_BTN_BASE =
            "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;"
            + " -fx-background-radius: 5; -fx-border-radius: 5;"
            + " -fx-border-color: #1b5e20; -fx-border-width: 1;"
            + " -fx-cursor: hand;"
            + " -fx-focus-color: transparent; -fx-faint-focus-color: transparent;";
    private static final String LENIENT_BTN_NORMAL =
            LENIENT_BTN_BASE
            + " -fx-padding: 4 10 4 10;"
            + " -fx-background-color: linear-gradient(to bottom, #66bb6a, #2e7d32);"
            + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.45), 3, 0.0, 0, 2);";
    private static final String LENIENT_BTN_HOVER =
            LENIENT_BTN_BASE
            + " -fx-padding: 4 10 4 10;"
            + " -fx-background-color: linear-gradient(to bottom, #81c784, #388e3c);"
            + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.55), 5, 0.0, 0, 3);";
    private static final String LENIENT_BTN_PRESSED =
            LENIENT_BTN_BASE
            + " -fx-padding: 5 10 3 10;"
            + " -fx-background-color: linear-gradient(to bottom, #2e7d32, #1b5e20);"
            + " -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.25), 1, 0.0, 0, 1);"
            + " -fx-translate-y: 1;";

    /**
     * The "Jde o:" block that opens every card, above the coloured verdict strip.
     *
     * <p>The value carries the same meaning as in Zjednodušený and Rozšířený přehled, only
     * spelled out instead of hidden behind an info icon: the legal type in bold, then — on its
     * own line, in normal type — the explanation those tabs show in the info popup.
     *
     * <p>A plain advanced signature gets one more paragraph. Such a signature is trusted for
     * integrity only; whether the stated identity is real depends entirely on how the
     * certificate was issued, which is exactly what the user needs to be told before reading
     * the verdict below.
     */
    private static VBox typeBlock(SignatureSummary sig, String header, String headerColor,
                                  boolean valid) {
        VBox block = new VBox(4);
        block.setPadding(new Insets(10, 12, 8, 12));
        if (header != null) {
            Label h = new Label(header);
            h.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;"
                    + " -fx-text-fill: " + headerColor + ";");
            block.getChildren().add(h);
        }

        String rawLegalType = sig.getLegalType();
        boolean isTs = "TimeStampToken".equals(sig.getFormat());
        String simplified = rawLegalType != null && !rawLegalType.isBlank()
                ? ExtendedView.simplifyLegalType(rawLegalType)
                : (isTs ? "časové razítko" : null);
        if (simplified == null) return block;
        String simplifiedText = ExtendedView.nominativeLegalType(simplified);
        String display = sig.isCertification() ? "(certifikační) " + simplifiedText : simplifiedText;

        // Za právním typem ještě formát, obyčejným písmem v závorce — pokyn uživatele
        // 22. 9. 2026. Čtenář má hned v prvním řádku karty obojí: čím ten prvek je podle práva
        // a v jakém formátu je zapsán. Bere se táž podoba, jakou vypisuje blok „Formát podpisu"
        // ({@link #formatLabel}), aby se obě místa nemohla rozejít — tedy „PKCS #7" bez úrovně
        // tam, kde ji standardy nedefinují.
        String fmt = formatLabel(sig.getFormat());
        block.getChildren().add(labelledRichRow(ExtendedView.legalTypeLabel(simplified),
                "**" + display + "**" + (fmt != null ? " (" + fmt + ")" : "")));

        String explanation = ExtendedView.legalTypeTooltipMarkup(simplified);
        if (explanation != null) {
            block.getChildren().add(labelledRichRow("V terminologii práva:", explanation));
        }

        // What a valid signature of this legal type actually guarantees. Only stated for a
        // signature that passed — on a failed or unknown verdict the guarantees do not hold,
        // so printing them next to the verdict would read as reassurance the card cannot give.
        if (valid) {
            String guarantee = guaranteeNote(simplified);
            if (guarantee != null) block.getChildren().add(unlabelledRow(guarantee));
        }

        // "zaručený elektronický podpis" is what simplifyLegalType() leaves of ADESIG — the
        // advanced signature NOT backed by a qualified certificate. The ADESIG_QC and QESIG
        // cases are deliberately excluded: there the identity is vouched for by a qualified
        // certificate, so the caveat below would be misleading.
        if ("zaručený elektronický podpis".equals(simplified)) {
            String caveat = ADES_IDENTITY_NOTE + " "
                    + (sig.isCertSelfSigned() ? SELF_SIGNED_NOTE : CA_POLICY_NOTE);
            block.getChildren().add(labelledRow("Upozornění:", caveat, false));
        }
        return block;
    }

    /**
     * The "Upřesnění:" / "Autor povolil:" pair shown for certification (author) signatures
     * and seals. Unlike {@link #typeBlock}, this sits <em>below</em> the coloured verdict
     * strip: it explains a property of the signature, not what it is, so it must not push
     * the verdict further down the card.
     *
     * @return {@code null} when the signature is not a certification one
     */
    private static void addCertificationBlock(VBox card, SignatureSummary sig) {
        VBox block = certificationBlock(sig);
        if (block == null) return;
        card.getChildren().add(collapsibleBlock(isSealSignature(sig)
                ? "Certifikační pečeť" : "Certifikační podpis", block, false));
        card.getChildren().add(blockSeparator());
    }

    /**
     * „Zámek dokumentu“ — co podpis zamkl a čím se to liší od certifikačního podpisu.
     *
     * <p>Vlastní blok vedle certifikačního, ne řádek v něm: zámek podle {@code /Lock}
     * a {@code FieldMDP} nese i podpis, který certifikační vůbec není, a v referenčním
     * „PAdES B-T kvalifikovnay zamknuty.pdf“ je to přesně tenhle případ — {@code /Perms /DocMDP}
     * tam chybí, a přesto je dokument uzamčen.
     */
    private static void addPdfLockBlock(VBox card, SignatureSummary sig) {
        SignatureSummary.PdfLock lock = sig.getPdfLock();
        // Jen zámek s entry /P, tedy ten, který sahá na celý dokument. Zámek nad vlastním polem
        // klade skoro každý podepisovací nástroj — ve sbírce ho tak má DocuSign i Signer — a je
        // to běžný chod věcí, ne nález; celý blok by z něj dělal víc, než je. Jedna šedá řádka
        // v obou přehledech mu stačí.
        if (lock == null || !lock.locksDocument()) return;
        boolean seal = isSealSignature(sig);
        String note = ExtendedView.pdfLockNote(lock, seal);

        VBox block = new VBox(4);
        block.setPadding(new Insets(8, 12, 8, 12));
        block.getChildren().add(labelledRow("Uzamčeno:", note, false));
        block.getChildren().add(labelledRow("Upřesnění:",
                (seal ? "Zámek nastavuje pečetící, a může jej spojit s kteroukoli pečetí"
                      : "Zámek nastavuje podepisující, a může jej spojit s kterýmkoli podpisem")
                + " — na rozdíl od certifikačního podpisu, který musí být v dokumentu první"
                + " a v celém dokumentu nejvýš jeden. O platnosti to neříká nic: uzamčený"
                + " dokument může být dál platný a tento zámek na výsledek ověření nemá vliv.",
                false));
        // Aplikace do takového dokumentu sama zapisuje, a bez téhle věty to vypadá jako přehmat.
        // Změřeno na témž dokumentu po uchování, a to třemi nástroji nezávisle na sobě: AnEP
        // („…_Do_02_05_2032.pdf“) i služba SecuSign Preservation („…_preserved_2026-09-06…“)
        // přidaly slovník /DSS, /Extensions a pole dokumentového razítka, DSS obojí klasifikuje
        // jako rozšíření podpisu (ExtensionChange) a výsledek zůstal platný; Adobe Acrobat Reader
        // nad výsledkem hlásí u revize 1 platný podpis s poznámkou „Dokument uzamkl(a):
        // Signature2“ a revizi 2 s razítkem přijímá. Ta tři svědectví do textu nepatří — čtenář
        // je nemá jak ověřit a věta o nich by blok jen natáhla — ale jsou důvod, proč ta jediná
        // věta, která zůstala, smí mluvit tak natvrdo.
        block.getChildren().add(labelledRow("Uchování a razítka:",
                "Doplnění revokačních údajů a připojení dokumentového časového razítka je"
                + " rozšířením podpisu, ne změnou dokumentu — zámek to nezakazuje a platnost"
                + " tím neutrpí.", false));
        card.getChildren().add(collapsibleBlock("Zámek dokumentu", block, false));
        card.getChildren().add(blockSeparator());
    }

    // -------------------------------------------------------------------------
    // "Možné právní účinky úředně ověřeného podpisu" — what a valid qualified electronic
    // signature means in law
    // -------------------------------------------------------------------------

    private static final String URL_EIDAS_ART_25 =
            "https://www.zakonyprolidi.cz/pravoeu/dokument?celex=32014R0910&text=910%2F2014&sit=1#i43";
    private static final String URL_ACT_12_2020_S6 = "https://www.zakonyprolidi.cz/cs/2020-12#p6-2";


    /**
     * Added only to a card that concluded the signature is valid <em>and</em> that it is one of
     * the two kinds of signature § 6 odst. 2 of Act 12/2020 Sb. can reach: a qualified electronic
     * signature or a recognised one. A seal or a timestamp never carries these effects, and a
     * plain advanced signature is not backed by a qualified certificate, so for those the block
     * would misstate the law and is left out.
     *
     * <p>Sits directly under the coloured verdict strip, ahead of every other block: once the
     * reader knows the signature holds, "what does that mean for me?" is the next question.
     */
    /**
     * Blok „Protipodpis (kontrasignatura)" — <b>první</b> na kartě, hned pod barevným pruhem
     * s verdiktem.
     *
     * <p>Stojí první schválně: než se čtenář začne ptát, co podpis znamená a jak dopadlo ověření,
     * musí vědět, že tenhle prvek nepodepsal dokument, nýbrž <i>jiný podpis</i>. Bez toho si ho
     * přečte jako další podpis v řadě a celý zbytek karty si vyloží špatně.
     *
     * <p>Objeví se jen u prvku, který protipodpisem opravdu je — vztah deklaruje sám formát
     * (nepodepsaný atribut uvnitř rodiče), nepočítá se z rozsahů bajtů.
     */
    private void addCounterSignatureBlock(VBox card, SignatureSummary sig) {
        Integer parent = ExtendedView.counterSignaturePosition(sig, lastResult);
        if (parent == null) return;
        VBox block = new VBox(8);
        block.setPadding(new Insets(8, 12, 10, 12));
        block.getChildren().add(richParagraph(
                "Tento prvek je **protipodpisem (kontrasignaturou) k autentizačnímu prvku č. "
                + parent + "**. Nepodepisuje obsah dokumentu, nýbrž **podpisovou hodnotu** onoho"
                + " prvku — vyjadřuje se tedy k podpisu, ne k dokumentu."));
        block.getChildren().add(richParagraph(
                "Tím se liší od běžného dalšího podpisu v řadě. Ten podepisuje obsah dokumentu"
                + " znovu a sám za sebe; dva takové podpisy o sobě navzájem nic netvrdí, jen"
                + " shodně kryjí totéž. Protipodpis naproti tomu bez svého rodiče nedává smysl:"
                + " kdyby se prvek č. " + parent + " z dokumentu odstranil, nemá se tento"
                + " protipodpis oč opřít."));
        block.getChildren().add(richParagraph(
                "Dvě věci z toho plynou. Vztah mezi oběma prvky **není odvozený, nýbrž"
                + " deklarovaný**: nese ho přímo struktura podpisu, a nemusí se tedy dovozovat"
                + " z toho, které bajty kdo podepsal. A protože protipodpis může vzniknout až"
                + " poté, co existoval jeho rodič, dokládá jeho **dřívější existenci** — je-li"
                + " opatřen časovým razítkem, může se o ně opřít i ověření onoho staršího prvku."));
        block.getChildren().add(richParagraph(
                "V praxi se tak podepisuje schválení či potvrzení cizího podpisu — souhlas"
                + " nadřízeného, svědectví, ověření. Co protipodpis **neříká**, je cokoli"
                + " o změnách, které v dokumentu přibyly po něm."));
        card.getChildren().add(collapsibleBlock("Protipodpis (kontrasignatura)", block, false));
        card.getChildren().add(blockSeparator());
    }

    private static void addLegalEffectsBlock(VBox card, SignatureSummary sig) {
        String rawLegalType = sig.getLegalType();
        if (rawLegalType == null || rawLegalType.isBlank()) return;
        String simplified = ExtendedView.simplifyLegalType(rawLegalType);

        boolean qualified = "kvalifikovaný elektronický podpis".equals(simplified);
        if (!qualified && !"uznávaný elektronický podpis".equals(simplified)) return;

        // Dva různé bloky pod dvěma různými názvy. Je-li doklad v certifikátu zapsán, není
        // to možnost, nýbrž nález — a název, který se čte i zavřený, to má říct napoprvé.
        boolean byDocument = identityDocumentRoute(sig);
        card.getChildren().add(
                collapsibleBlock(byDocument
                                ? "Podpis má právní účinky úředně ověřeného podpisu"
                                : "Možné právní účinky úředně ověřeného podpisu",
                        legalEffectsBlock(sig, qualified, byDocument), false));
        card.getChildren().add(blockSeparator());
    }

    /**
     * @param qualified whether this is a qualified signature rather than a recognised one. Only
     *                  a qualified signature is equivalent to a handwritten one outright
     *                  (eIDAS art. 25(2)); for both kinds the effects of an officially certified
     *                  signature are conditional and reached the same two ways, so the rest of
     *                  the block is shared.
     */
    private static VBox legalEffectsBlock(SignatureSummary sig, boolean qualified,
                                          boolean byDocument) {
        VBox block = new VBox(8);
        block.setPadding(new Insets(8, 12, 10, 12));

        if (qualified) {
            block.getChildren().add(richParagraph(
                    "Kvalifikované elektronické podpisy mají dle [článku 25](" + URL_EIDAS_ART_25 + ")"
                    + " odst. 2 nařízení 910/2014 (eIDAS) právní účinky rovnocenné s vlastnoručním"
                    + " podpisem."));
            block.getChildren().add(richParagraph(
                    "Tento konkrétní kvalifikovaný elektronický podpis " + (byDocument
                            ? "má" : "může mít")
                    + " dokonce účinky úředně ověřeného podpisu, a to podle [§ 6 odst. 2]("
                    + URL_ACT_12_2020_S6 + ") zákona č. 12/2020 Sb. o právu na digitální"
                    + " služby:"));
        } else {
            block.getChildren().add(richParagraph(
                    "Tento konkrétní uznávaný elektronický podpis (v terminologii práva: zaručený"
                    + " elektronický podpis, založený na kvalifikovaném certifikátu) " + (byDocument
                            ? "má" : "může mít")
                    + " účinky úředně ověřeného podpisu, a to podle [§ 6 odst. 2](" + URL_ACT_12_2020_S6
                    + ") zákona č. 12/2020 Sb. o právu na digitální služby:"));
        }

        block.getChildren().add(quotedParagraph(
                "… pokud lze s využitím údajů základního registru obyvatel (dále jen „registr"
                + " obyvatel“) nebo portálu veřejné správy ověřit, že kvalifikovaný certifikát pro"
                + " elektronický podpis, na jehož základě podepisující vytvořil uznávaný"
                + " elektronický podpis na dokumentu, patří podepisujícímu."));

        if (byDocument) {
            // Doklad v certifikátu je, takže se nevypisují dvě cesty, kterými by to šlo naplňovat:
            // jedna z nich je naplněná a druhá by čtenáře posílala hledat, co už má.
            block.getChildren().add(richParagraph(
                    "V certifikátu, na kterém je podpis založen, jsou zapsány tyto údaje: "
                    + DnUtil.identityDocument(sig.getSignerCertDN())
                    + ". Jde tedy o podpis s účinky úředně ověřeného podpisu, který patří osobě se jménem"
                    + " a dalšími atributy, uvedenými v předmětu certifikátu:"));
            for (String line : subjectAttributeLines(sig)) {
                block.getChildren().add(bulletParagraph(line));
            }
            block.getChildren().add(richParagraph(
                    "Nelze ale vyloučit, že osob stejného jména (a dalších atributů) je více."
                    + " Zjistit, zda tomu tak je, a případně které konkrétní osobě stejného jména"
                    + " a dalších atributů certifikát a podpis patří, ale není v silách této aplikace"
                    + " (k tomu je nutný přístup do příslušné evidence osobních dokladů)."));
            return block;
        }

        block.getChildren().add(richParagraph(
                "Prakticky to může být naplněno dvěma různými způsoby:"));

        block.getChildren().add(bulletParagraph(registryRouteText(sig)));
        block.getChildren().add(bulletParagraph(documentRouteText(sig)));
        return block;
    }

    /**
     * Nese certifikát číslo a druh osobního dokladu držitele, a je to <b>kvalifikovaný</b>
     * certifikát?
     *
     * <p>Obojí, a na kvalifikovanosti trval uživatel (15. 9. 2026). Blok se sice staví jen
     * u kvalifikovaného a uznávaného podpisu, a oba kvalifikovaný certifikát z definice
     * předpokládají — jenže zdejší zařazení podpisu vychází z kvalifikace podle důvěryhodných
     * seznamů, kdežto {@code isCertQcCompliant()} je údaj ze samotného certifikátu
     * (QcCompliance). Kde by se ty dva rozcházely, má silnější tvrzení ustoupit tomu
     * opatrnějšímu: „může mít“ není nikdy špatně, kdežto „má“ ano.
     *
     * <p>Číslo dokladu se čte jen ze schémat, která aplikace umí pojmenovat správně —
     * {@code IDCCZ-} a {@code PASCZ-} podle ETSI EN 319 412-1 čl. 5.1.3; viz
     * {@link DnUtil#identityDocument}.
     */
    private static boolean identityDocumentRoute(SignatureSummary sig) {
        return sig.isCertQcCompliant()
                && DnUtil.identityDocument(sig.getSignerCertDN()) != null;
    }

    /**
     * Každý atribut předmětu certifikátu na vlastní řádek, v pořadí, v jakém je vypísuje
     * Rozšířený přehled.
     *
     * <p>Všechny, včetně jména, příjmení i čísla dokladu. Věta nad seznamem slíbila „jméno
     * a další atributy, uvedené v předmětu certifikátu“, a vynechat některý proto, že už
     * zazněl o kus výš, by ten slib porušilo — čtenář porovnává výpis s certifikátem, ne
     * s odstavcem.
     *
     * <p>Popisky se berí od {@link DnUtil}; jméno, příjmení a obecné jméno dostávají vlastní,
     * protože {@code DnUtil.label} je nezná — jinde v aplikaci se vypísují pod nadpisem
     * „Podepsaná osoba“, nebo se přeskáčou jako zdvojení CN.
     */
    private static List<String> subjectAttributeLines(SignatureSummary sig) {
        record Item(int rank, String text) { }
        List<Item> items = new java.util.ArrayList<>();
        for (DnUtil.Attr a : DnUtil.attributes(sig.getSignerCertDN())) {
            String label;
            String value = a.value();
            if (DnUtil.isCommonName(a.key())) {
                label = "Jméno v certifikátu";
            } else if (DnUtil.isSubjectSerialNumber(a.key())) {
                // Z hodnoty téhle položky, ne z celého DN: položek serialNumber může být víc
                // a každý řádek musí ukázat tu svou (viz DnUtil.serialNumberEntries).
                String[] entry = DnUtil.serialNumberEntryOf(a.value());
                label = entry[0].replace(":", "");
                value = entry[1];
            } else if (DnUtil.isOrganizationIdentifier(a.key())) {
                String[] entry = DnUtil.organizationIdentifierEntry(a.value());
                label = entry == null ? "Identifikátor organizace"
                        : entry[0].replace(":", "");
                if (entry != null) value = entry[1];
            } else {
                label = DnUtil.label(a.key());
                if (matches(a.key(), "2.5.4.42")) label = "Křestní jméno";
                if (matches(a.key(), "2.5.4.4")) label = "Příjmení";
            }
            items.add(new Item(DnUtil.displayRank(a.key()), label + ": **" + value + "**"));
        }
        items.sort(java.util.Comparator.comparingInt(Item::rank));
        return items.stream().map(Item::text).toList();
    }

    /** Shoda klíče DN, ať ho DSS píše číslem, nebo zkratkou. */
    private static boolean matches(String key, String oid) {
        if (key == null) return false;
        String k = key.trim();
        if (k.regionMatches(true, 0, "OID.", 0, 4)) k = k.substring(4);
        return k.equalsIgnoreCase(oid);
    }

    /** First route: the certificate is recorded in its holder's account in the population register. */
    private static String registryRouteText(SignatureSummary sig) {
        StringBuilder text = new StringBuilder(
                "tím, že certifikát, na kterém je podpis založen, je zapsán do účtu svého držitele"
                + " v základním registru obyvatel.");

        String serial = formatSerialNumber(sig.getCertSerialNumber());
        if (serial != null) {
            text.append(" Jde o certifikát se sériovým číslem ").append(serial);
            if (sig.getCertNotBefore() != null) {
                text.append(", který byl vydán ").append(TimeUtil.format(sig.getCertNotBefore()));
            } else {
                text.append(", který byl vydán");
            }
            String issuer = DnUtil.dnField(sig.getCertIssuerDN(), "2.5.4.3", "CN");
            if (issuer != null && !issuer.isBlank()) {
                text.append(" kvalifikovaným poskytovatelem služeb vytvářejících důvěru ")
                    .append(issuer);
            }
            text.append('.');
        }

        text.append(" Ověření toho, zda tomu tak skutečně je, a která konkrétní osoba je držitelem"
                + " certifikátu, není v silách této aplikace a musí být provedeno jinak.");
        return text.toString();
    }

    /** Second route: the holder's identity document is written into the certificate itself. */
    private static String documentRouteText(SignatureSummary sig) {
        String document = DnUtil.identityDocument(sig.getSignerCertDN());
        String text = "tím, že do certifikátu, na kterém je podpis založen, je při jeho vydávání"
                + " zapsáno číslo a druh (osobního) dokladu jeho držitele.";
        if (document == null) {
            // The closing sentence of the other branch is deliberately left out here: with no
            // document in the certificate there is nobody to trace, and inviting the reader to
            // trace one would suggest the certificate says more than it does.
            return text + " V konkrétním případě tohoto podpisu nejsou žádné takovéto údaje"
                    + " v certifikátu zapsány.";
        }
        return text + " V konkrétním případě tohoto podpisu jsou v certifikátu zapsány tyto údaje: "
                + document + ". Zjištění, komu příslušné doklady patří, není v silách této aplikace"
                + " a musí být provedeno jinak.";
    }

    /** Decimal serial number as stored by DSS, followed by the hexadecimal form readers see in tools. */
    private static String formatSerialNumber(String decimal) {
        if (decimal == null || decimal.isBlank()) return null;
        try {
            String hex = cz.digitalnikontinuita.anep.core.ContainerInspector.hexNumber(decimal);
            return decimal + " (hexadecimálně " + hex + ")";
        } catch (NumberFormatException e) {
            return decimal;
        }
    }

    /** Matches a Markdown-style inline link, the only markup {@link #richParagraph} understands. */
    private static final java.util.regex.Pattern LINK_MARKUP =
            java.util.regex.Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");

    /**
     * A wrapped paragraph in which {@code [text](url)} becomes a link opening in the system
     * browser. A {@link TextFlow} is used rather than the selectable text area the other rows
     * use, because only a flow can mix plain runs with clickable ones; the copy item in the
     * context menu keeps the text reachable.
     */
    static TextFlow richParagraph(String markup) {
        TextFlow flow = new TextFlow();
        StringBuilder plain = new StringBuilder();
        java.util.regex.Matcher matcher = LINK_MARKUP.matcher(markup);
        int pos = 0;
        while (matcher.find()) {
            appendRun(flow, plain, markup.substring(pos, matcher.start()));
            flow.getChildren().add(inlineLink(matcher.group(1), matcher.group(2)));
            plain.append(matcher.group(1));
            pos = matcher.end();
        }
        appendRun(flow, plain, markup.substring(pos));
        withCopyMenu(flow, plain.toString());
        return flow;
    }

    private static void appendRun(TextFlow flow, StringBuilder plain, String run) {
        if (run.isEmpty()) return;
        // {@code !!text!!} je tučně a červeně — nesplněný požadavek; {@code ??text??} tučně
        // a hnědožlutě — co se nepodařilo zjistit nebo co zbývá ověřit jinde. Uvnitř se další
        // značky nečtou; kolem nich platí obyčejné značení.
        java.util.regex.Matcher m = COLOUR_MARKUP.matcher(run);
        int pos = 0;
        while (m.find()) {
            appendBoldRuns(flow, plain, run.substring(pos, m.start()));
            String piece = m.group(1) != null ? m.group(1) : m.group(2);
            if (!piece.isEmpty()) {
                Text text = new Text(piece);
                text.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-fill: "
                        + (m.group(1) != null ? "#cc0000" : "#9a6700") + ";");
                flow.getChildren().add(text);
                plain.append(piece);
            }
            pos = m.end();
        }
        appendBoldRuns(flow, plain, run.substring(pos));
    }

    private static final java.util.regex.Pattern COLOUR_MARKUP =
            java.util.regex.Pattern.compile("!!(.*?)!!|\\?\\?(.*?)\\?\\?");

    private static void appendBoldRuns(TextFlow flow, StringBuilder plain, String run) {
        if (run.isEmpty()) return;
        // Totéž značení tučného jako {@link #appendMarkupRuns}: odstavec s odkazem často potřebuje
        // i zdůrazněný pojem a dvě znacky vedle sebe se mají číst stejně.
        boolean bold = false;
        for (String part : run.split(java.util.regex.Pattern.quote("**"), -1)) {
            if (!part.isEmpty()) {
                Text text = new Text(part);
                text.setStyle("-fx-font-size: 12px; -fx-fill: #111111;"
                        + (bold ? " -fx-font-weight: bold;" : ""));
                flow.getChildren().add(text);
                plain.append(part);
            }
            bold = !bold;
        }
    }

    /**
     * Odstavec bloku Digitální kontinuity, v němž {@code [text](RESTAMP)} otevře příslušný
     * výklad na začátku záložky a odroluje k němu.
     *
     * <p>Odkaz místo opsaného výkladu: totéž vysvětlení stálo v obecném bloku nahoře i u každého
     * prvku, a v blocích u prvku zabíralo místo, které patří nálezu o <i>tomhle</i> dokumentu.
     *
     * <p>Bez obsluhy — sondy a snímky, které záložku nestaví celou — se odstavec vysází jako
     * prostý text bez odkazů. Mrtvý odkaz je horší než věta bez odkazu.
     */
    static javafx.scene.Node continuityLinked(String markup,
            java.util.function.Consumer<ContinuityView.Outlook> open) {
        if (open == null) return continuityText(LINK_MARKUP.matcher(markup).replaceAll("$1"));
        TextFlow flow = new TextFlow();
        StringBuilder plain = new StringBuilder();
        java.util.regex.Matcher matcher = LINK_MARKUP.matcher(markup);
        int pos = 0;
        while (matcher.find()) {
            appendRun(flow, plain, markup.substring(pos, matcher.start()));
            // valueOf schválně: neznámý cíl je chyba v textu, a ta má spadnout při stavbě karty,
            // ne se tiše projevit odkazem, který nikam nevede.
            ContinuityView.Outlook target = ContinuityView.Outlook.valueOf(matcher.group(2));
            Hyperlink link = new Hyperlink(matcher.group(1));
            link.setStyle("-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                    + " -fx-border-width: 0;");
            link.setOnAction(e -> open.accept(target));
            flow.getChildren().add(link);
            plain.append(matcher.group(1));
            pos = matcher.end();
        }
        appendRun(flow, plain, markup.substring(pos));
        withCopyMenu(flow, plain.toString());
        return flow;
    }

    private static Hyperlink inlineLink(String label, String url) {
        Hyperlink link = new Hyperlink(label);
        link.setStyle("-fx-font-size: 12px; -fx-text-fill: #0a5ca8; -fx-padding: 0;"
                + " -fx-border-width: 0;");
        Tooltip.install(link, new Tooltip(url));
        link.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            } catch (Exception ignored) {
                // No browser available — the URL is still in the tooltip.
            }
        });
        return link;
    }

    /**
     * The quoted statutory text, set apart from the surrounding explanation by a rule down its
     * left edge.
     *
     * <p>The label is returned bare rather than wrapped in an {@code HBox}: an HBox sizes itself
     * from the label's one-line preferred height, so the text ends up clipped to a single line
     * with an ellipsis however wide the window is. Placed straight into the block's VBox, the
     * label is asked for its height at the width it actually gets, and wraps.
     */
    private static Label quotedParagraph(String quote) {
        Label label = new Label(quote);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        // A Label's default minimum height is one line, so a parent short of space squeezes it
        // back to one line and ellipsizes despite wrapText. Tie the minimum to the wrapped height.
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.setStyle("-fx-font-size: 12px; -fx-font-style: italic; -fx-text-fill: #333333;"
                + " -fx-padding: 2 0 2 10;"
                + " -fx-border-color: transparent transparent transparent #c8d4e0;"
                + " -fx-border-width: 0 0 0 3;");
        return label;
    }

    /** One item of the two-way list, with the text hanging beside the bullet rather than under it. */
    static HBox bulletParagraph(String text) {
        Label bullet = new Label("•");
        bullet.setStyle("-fx-font-size: 12px; -fx-text-fill: #111111;");
        bullet.setMinWidth(10);
        TextFlow flow = richParagraph(text);
        HBox.setHgrow(flow, Priority.ALWAYS);
        HBox row = new HBox(6, bullet, flow);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /** Right-click to copy — a TextFlow cannot be selected the way the other rows can. */
    private static void withCopyMenu(TextFlow flow, String text) {
        javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem("Kopírovat text");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(copy);
        flow.setOnContextMenuRequested(e -> menu.show(flow, e.getScreenX(), e.getScreenY()));
    }

    /** Whether this finding is a seal rather than a signature, by its legal type. */
    private static boolean isSealSignature(SignatureSummary sig) {
        String rawLegalType = sig.getLegalType();
        return rawLegalType != null && !rawLegalType.isBlank()
                && ExtendedView.isSealLegalType(ExtendedView.simplifyLegalType(rawLegalType));
    }

    /**
     * Wraps one card block in a header the reader clicks to reveal it.
     *
     * <p>A card can carry five blocks at once, most of which answer questions the reader is not
     * asking on this particular document. Collapsed by default, the card shows its verdict and a
     * list of what else is known; opening a block is a deliberate step.
     *
     * <p>The verdict itself — the type strip and the coloured header — is never wrapped: it is
     * the one thing that must be readable without any interaction.
     */
    /** Strana rovnostranného trojúhelníku, kterým se kreslí rozklikávací šipka. */
    private static final double ARROW_SIDE = 13;

    /** Šířka sloupce se šipkou; drží nadpisy všech bloků na jedné svislici. */
    private static final double ARROW_COLUMN = 20;

    /**
     * Otočí trojúhelník podle stavu bloku: špičkou dolů u otevřeného, doprava u zavřeného.
     *
     * <p>Body se přepisují, místo aby se tvar otáčel přes {@code setRotate}. Otáčení jde kolem
     * středu opsaného obdélníku, kdežto těžiště trojúhelníku leží jinde, a šipka by se tím
     * proti nadpisu posunula.
     */
    private static void pointArrow(javafx.scene.shape.Polygon tri, boolean expanded) {
        double a = ARROW_SIDE, h = a * Math.sqrt(3) / 2;
        tri.getPoints().setAll(expanded
                ? java.util.List.of(0.0, 0.0, a, 0.0, a / 2, h)
                : java.util.List.of(0.0, 0.0, h, a / 2, 0.0, a));
    }

    static VBox collapsibleBlock(String title, javafx.scene.Node content, boolean expanded) {
        return collapsibleBlock(title, content, expanded, BlockTint.NEUTRAL);
    }

    /**
     * Podbarvení záhlaví rozbalovacího bloku.
     *
     * <p>Neutrální odstín říká jen tolik, kde blok začíná, a proto se u zavřeného bloku vůbec
     * nekreslí. Barevný odstín naproti tomu <b>nese sdělení</b> — jak dopadla otázka, kterou
     * nadpis klade — a musí být vidět i na zavřeném bloku, jinak by se čtenář ke sdělení
     * proklikával a smysl barvy by zmizel. Proto se barevné odstíny stavem bloku nemění.
     */
    enum BlockTint {
        /** Šedý podklad otevřeného bloku, u zavřeného nic — výchozí chování všech bloků v Analýze. */
        NEUTRAL("#e4e4e4", "#dcdcdc", "transparent", "#f0f4f8"),
        /** Ověřit lze. */
        GREEN("#d8ead8", "#c9e0c9", "#d8ead8", "#c9e0c9"),
        /** Ověřit už nelze, platnost zůstala neznámá. */
        BROWN("#e5dac6", "#dacfb6", "#e5dac6", "#dacfb6"),
        /** Neplatnost je prokázána. */
        RED("#ecd8d8", "#e0c8c8", "#ecd8d8", "#e0c8c8"),
        /** Co je třeba udělat. */
        BLUE("#d8e3f2", "#c8d6ec", "#d8e3f2", "#c8d6ec"),
        /**
         * Světle šedý podklad i u zavřeného bloku — pro blok, který <b>nenese verdikt</b>, ale
         * má být vidět, že tam je.
         *
         * <p>Neutrální odstín se u zavřeného bloku nekreslí, takže sbalený blok bez barvy splyne
         * s okolím a čtenář ho přehlédne. To u bloků na kartě nevadí, protože nad nimi stojí
         * barevná záhlaví ostatních; u „Rozboru podpisu“ v Digitální kontinuitě, který stojí sám
         * pod jménem prvku, by zmizel.
         */
        PLAIN("#e4e4e4", "#dcdcdc", "#ededed", "#e4e4e4");

        private final String openIdle, openHover, shutIdle, shutHover;

        BlockTint(String openIdle, String openHover, String shutIdle, String shutHover) {
            this.openIdle = openIdle;
            this.openHover = openHover;
            this.shutIdle = shutIdle;
            this.shutHover = shutHover;
        }
    }

    static VBox collapsibleBlock(String title, javafx.scene.Node content, boolean expanded,
                                 BlockTint tint) {
        return collapsibleBlock(title, content, expanded, tint, null);
    }

    /**
     * Blok s akcemi v záhlaví — odkazy, které stojí za nadpisem a jsou vidět jen u otevřeného
     * bloku, tedy na šedivé liště, kterou otevřený blok má.
     *
     * <p>Zavedeno 15. 10. 2026 pro „Ukaž“ a „Ulož“ u bloku Certifikát v Rozšířeném přehledu
     * (přání uživatele). U zavřeného bloku se schovávají: lišta tam nemá podklad a odkazy by
     * vedle nadpisu působily jako jeho součást. Kliknutí na ně blok nezavírá — událost se
     * spotřebuje dřív, než dojde k záhlaví.
     *
     * @param actions uzel s akcemi, nebo {@code null}
     */
    static VBox collapsibleBlock(String title, javafx.scene.Node content, boolean expanded,
                                 BlockTint tint, javafx.scene.Node actions) {
        // Šipka se kreslí jako tvar, ne jako znak. Znaková dvojice ▾/▸ byla nesouměrná —
        // zavřená kreslila znatelně slaběji než otevřená a při zvětšování se ten rozdíl
        // zvýrazňoval — a velikost znaku navíc nevychází z písma předvídatelně: ▶ o téže
        // velikosti jako ▼ vyjde v Segoe UI drobný. Trojúhelník jako Polygon má stranu přesně
        // tu, která se zadá, a obě polohy váží stejně (měřeno ArrowSizeSnapshot 9. 9. 2026).
        javafx.scene.shape.Polygon tri = new javafx.scene.shape.Polygon();
        tri.setFill(javafx.scene.paint.Color.web("#555555"));
        pointArrow(tri, expanded);
        StackPane arrow = new StackPane(tri);
        arrow.setMinWidth(ARROW_COLUMN);
        arrow.setPrefWidth(ARROW_COLUMN);

        // Stejně velké písmo jako řádky uvnitř bloku. Nadpis držený pod velikostí obsahu vypadá
        // jako poznámka k němu, ne jako to, co ho pojmenovává — a čtenář hýže očima po nadpisech,
        // dokud nenajde ten svůj, takže jsou to právě ony, co má být vidět nejdřív.
        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 13px;");
        titleLbl.setWrapText(true);

        HBox header = new HBox(4, arrow, titleLbl);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 12, 6, 12));
        header.setCursor(javafx.scene.Cursor.HAND);
        if (actions != null) {
            // Až za nadpisem, ne na pravém okraji: na široké kartě by odkazy u kraje od nadpisu
            // utekly a nebylo by poznat, k čemu patří.
            HBox.setMargin(actions, new Insets(0, 0, 0, 14));
            actions.setCursor(javafx.scene.Cursor.DEFAULT);
            actions.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    javafx.event.Event::consume);
            actions.visibleProperty().bind(content.visibleProperty());
            actions.managedProperty().bind(content.visibleProperty());
            header.getChildren().add(actions);
        }

        // Otevřený blok má záhlaví podložené šedivě, zavřený nemá nic. Karta jich nese deviět
        // pod sebou a bez toho podkladu není poznat, kde otevřený blok končí a začíná další —
        // řádky obsahu splynou s nadpisem pod nimi. Odstín je jen o kousek tmavší než běžné
        // pozadí (#f4f4f4 z modeny), aby to byl podklad, a ne další barevný prvek na kartě.
        final String openIdle  = "-fx-background-color: " + tint.openIdle + ";";
        final String openHover = "-fx-background-color: " + tint.openHover + ";";
        final String shutIdle  = "-fx-background-color: " + tint.shutIdle + ";";
        final String shutHover = "-fx-background-color: " + tint.shutHover + ";";
        header.setStyle(expanded ? openIdle : shutIdle);
        header.setOnMouseEntered(e ->
                header.setStyle(content.isVisible() ? openHover : shutHover));
        header.setOnMouseExited(e ->
                header.setStyle(content.isVisible() ? openIdle : shutIdle));

        content.setVisible(expanded);
        content.setManaged(expanded);
        Runnable toggle = () -> {
            boolean nowVisible = !content.isVisible();
            content.setVisible(nowVisible);
            content.setManaged(nowVisible);
            pointArrow(tri, nowVisible);
            // Ukazatel myši po kliknutí nad záhlavím zůstává, takže se překresluje na barvu pod
            // kurzorem; klidový odstín vrátí až odchod myši. Jinak by otevřený blok chvilku
            // svítil klidovou barvou pod kurzorem, který na něm pořád stojí.
            header.setStyle(nowVisible ? openHover : shutHover);
        };
        header.setOnMouseClicked(e -> toggle.run());

        VBox box = new VBox(header, content);
        // Otevření zvenčí — pro odkazy, které čtenáře posílají do jiného bloku. Jde to touž
        // cestou jako kliknutí, a ne jen nastavením visible/managed: šipka i podklad záhlaví
        // se jinak rozejdou se stavem těla a blok pak vypadá zavřený, přestože otevřený je.
        box.getProperties().put(OPEN_BLOCK, (Runnable) () -> {
            if (!content.isVisible()) toggle.run();
        });
        return box;
    }

    /** Klíč, pod kterým si {@link #collapsibleBlock} ukládá „otevři se". */
    private static final String OPEN_BLOCK = "anep.openBlock";

    /**
     * Otevře blok postavený {@link #collapsibleBlock}, je-li zavřený; jinak nechá být.
     *
     * <p>Pro odkazy z jednoho bloku do druhého: čtenáře nemá smysl poslat na nadpis, který si
     * musí sám rozkliknout. Uzly, které tímhle neprošly, tiše ignoruje.
     */
    static void openBlock(javafx.scene.Node block) {
        if (block == null) return;
        Object open = block.getProperties().get(OPEN_BLOCK);
        if (open instanceof Runnable r) r.run();
    }

    /**
     * Předěl uvnitř bloku „Průběh ověřování“ — před výkladem postupu a před závěrem.
     *
     * <p>Užší a světlejší než {@link #blockSeparator}, který odděluje celé bloky karty. Blok je
     * jedno sdělení ve třech dílech (za jakých pravidel → jak se postupovalo → co z toho vyšlo),
     * ne tři samostatná témata, a stejně výrazná čára by je od sebe odtrhla víc, než patří.
     */
    private static Separator partSeparator() {
        Separator sep = new Separator();
        sep.setPadding(new Insets(6, 40, 2, 40));
        sep.setStyle("-fx-opacity: 0.5;");
        return sep;
    }

    /** Horizontal rule between the top-level blocks of a card. */
    private static Separator blockSeparator() {
        Separator sep = new Separator();
        sep.setPadding(new Insets(0, 12, 0, 12));
        return sep;
    }

    private static VBox certificationBlock(SignatureSummary sig) {
        if (!sig.isCertification()) return null;
        String rawLegalType = sig.getLegalType();
        boolean seal = rawLegalType != null && !rawLegalType.isBlank()
                && ExtendedView.isSealLegalType(ExtendedView.simplifyLegalType(rawLegalType));

        VBox block = new VBox(4);
        block.setPadding(new Insets(8, 12, 8, 12));
        block.getChildren().add(labelledRow("Upřesnění:",
                seal ? CERTIFICATION_NOTE_SEAL : CERTIFICATION_NOTE_SIG, false));
        block.getChildren().add(labelledRow("Autor povolil:",
                certificationPermissionText(sig.getCertificationPermission(), seal), false));
        return block;
    }

    /**
     * What a valid signature or seal of the given legal type guarantees, and how the two
     * qualified/recognised pairs differ in the risk of misuse. Returns {@code null} for the
     * remaining types — a plain advanced signature is already covered by the "Upozornění:" row,
     * and for electronic marks and timestamps the sentence would not be true as written.
     */
    private static String guaranteeNote(String simplified) {
        return switch (simplified) {
            case "kvalifikovaný elektronický podpis" ->
                "Platné kvalifikované elektronické podpisy zaručují jak identitu podepsané osoby,"
                + " tak i neporušenou integritu (neměnnost, autenticitu) podepsaného obsahu."
                + " Oproti uznávaným elektronickým podpisům je u nich menší riziko zneužití díky"
                + " povinné ochraně soukromého klíče proti okopírování (jeho umístěním"
                + " v kvalifikovaném prostředku / QSCD).";
            case "uznávaný elektronický podpis" ->
                "Platné uznávané elektronické podpisy zaručují jak identitu podepsané osoby, tak"
                + " i neporušenou integritu (neměnnost, autenticitu) podepsaného obsahu. Oproti"
                + " kvalifikovaným elektronickým podpisům je u nich větší riziko zneužití (protože"
                + " soukromý klíč nemusí být chráněn proti okopírování).";
            case "kvalifikovanou elektronickou pečeť" ->
                "Platné kvalifikované elektronické pečeti zaručují jak identitu původce obsahu"
                + " (pečetící osoby), tak i neporušenou integritu (neměnnost, autenticitu)"
                + " podepsaného obsahu. Oproti uznávaným elektronickým pečetím je u nich menší"
                + " riziko zneužití díky povinné ochraně soukromého klíče proti okopírování"
                + " (jeho umístěním v kvalifikovaném prostředku / QSCD / HSM).";
            case "uznávanou elektronickou pečeť" ->
                "Platné uznávané elektronické pečeti zaručují jak identitu původce obsahu"
                + " (pečetící osoby), tak i neporušenou integritu (neměnnost, autenticitu)"
                + " podepsaného obsahu. Oproti kvalifikovaným elektronickým pečetím je u nich"
                + " větší riziko zneužití (protože soukromý klíč nemusí být chráněn proti"
                + " okopírování).";
            default -> null;
        };
    }

    /** Row of the "Jde o:" block without a label: the text runs across the full width. */
    private static HBox unlabelledRow(String value) {
        TextArea val = ResultView.selectable(value, "-fx-text-fill: #111111; -fx-font-size: 12px;");
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox box = new HBox(val);
        box.setPadding(new Insets(3, 0, 1, 0));
        return box;
    }

    /**
     * Řádek jako {@link #labelledRow}, jehož text umí {@code **tučně**} a {@code [text](url)}.
     *
     * <p>{@link TextFlow} místo {@code selectable()}: klikací odkaz musí plynout s větou, což
     * textová oblast neumí. Kopírovat text jde z kontextové nabídky, kterou {@link #richParagraph}
     * přidává.
     */
    private static HBox labelledRichRow(String labelText, String markup) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
        lbl.setMinWidth(170);
        lbl.setMaxWidth(170);
        lbl.setWrapText(true);
        TextFlow flow = richParagraph(markup);
        HBox.setHgrow(flow, Priority.ALWAYS);
        HBox row = new HBox(8, lbl, flow);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /** One row of the "Jde o:" block: bold label in the usual 170px column, then the text. */
    private static HBox labelledRow(String labelText, String value, boolean boldValue) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
        lbl.setMinWidth(170);
        lbl.setMaxWidth(170);
        lbl.setWrapText(true);
        TextArea val = ResultView.selectable(value,
                "-fx-text-fill: #111111; -fx-font-size: 12px;"
                + (boldValue ? " -fx-font-weight: bold;" : ""));
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox row = new HBox(8, lbl, val);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /** Why a plain advanced signature says nothing certain about who signed. */
    private static final String ADES_IDENTITY_NOTE =
            "Platné zaručené elektronické podpisy zaručují pouze integritu (neměnnost)"
            + " podepsaného obsahu. Identitu podepsané osoby zaručovat nemusí. Ta může odpovídat"
            + " skutečnosti, ale také nemusí - záleží na druhu certifikátu a podmínkách pro jeho"
            + " vystavení.";

    /** Continuation of {@link #ADES_IDENTITY_NOTE} for a certificate issued by a CA. */
    private static final String CA_POLICY_NOTE =
            "U certifikátů od certifikačních autorit jsou tyto podmínky popsány v dokumentu"
            + " nazývaném Certifikační politika. Pro každý druh certifikátu bývá samostatná"
            + " Certifikační politika.";

    /** Continuation of {@link #ADES_IDENTITY_NOTE} for a self-signed certificate. */
    private static final String SELF_SIGNED_NOTE =
            "Tento konkrétní podpis je založen na certifikátu s vlastním podpisem. Tedy takovém,"
            + " který si někdo vydal sám sobě, a mohl si v něm uvést cokoli. Včetně něčeho, co"
            + " vůbec neodpovídá skutečnosti.";

    /**
     * What a certification (author) signature adds over an ordinary one, in lay terms.
     *
     * <p>Deliberately left out: any claim that the permitted changes leave *other* signatures
     * intact - the permission governs this signature only; and the blue ribbon Adobe Reader
     * draws for such documents - that is one application's behaviour and does not appear for
     * untrusted chains, so mentioning it would mislead.
     */
    private static final String CERTIFICATION_NOTE_SIG =
            "Jde o tzv. certifikační podpis (v PDF též autorský). Kromě běžného podepsání"
            + " dokumentu jím autor (podepisující osoba) navíc určuje, co s dokumentem smí"
            + " kdokoli další dělat, aniž"
            + " by tím podpis porušil. V dokumentu může být jen jeden a musí být vložen jako"
            + " první - proto ho typicky připojuje ten, kdo dokument vydává. Právně nejde o"
            + " silnější podpis: platí pro něj úplně stejná pravidla jako pro kterýkoli jiný,"
            + " rozdíl je jen v tom, že hlídá další úpravy dokumentu.";

    /** Feminine counterpart of {@link #CERTIFICATION_NOTE_SIG} for seals and marks. */
    private static final String CERTIFICATION_NOTE_SEAL =
            "Jde o tzv. certifikační pečeť (v PDF též autorskou). Kromě běžného zapečetění"
            + " dokumentu jí autor (pečetící osoba) navíc určuje, co s dokumentem smí kdokoli"
            + " další dělat,"
            + " aniž by tím pečeť porušil. V dokumentu může být jen jedna a musí být vložena"
            + " jako první - proto ji typicky připojuje ten, kdo dokument vydává. Právně nejde"
            + " o silnější pečeť: platí pro ni úplně stejná pravidla jako pro kteroukoli jinou,"
            + " rozdíl je jen v tom, že hlídá další úpravy dokumentu.";

    /**
     * The certification permission level spelled out: what the author allowed, and what
     * happens if someone goes further. Rozšířený přehled shows the same three levels as a
     * bare list of permitted operations; here the consequence is stated too, because that is
     * the part a lay reader actually needs.
     */
    private static String certificationPermissionText(String permission, boolean seal) {
        String subject = seal ? "tuto pečeť" : "tento podpis";
        return switch (permission != null ? permission : "") {
            case "NO_CHANGE_PERMITTED" ->
                "žádné změny. Cokoli s dokumentem uděláte - i vyplnění políčka nebo přidání"
                + " dalšího podpisu - " + subject + " zneplatní.";
            case "MINIMAL_CHANGES_PERMITTED" ->
                "vyplňování formulářových polí a připojování dalších podpisů a pečetí. Jiný"
                + " zásah do obsahu " + subject + " zneplatní.";
            case "CHANGES_PERMITTED" ->
                "vyplňování formulářových polí, připojování dalších podpisů a pečetí a vkládání"
                + " či úpravu poznámek (anotací). Zásah do vlastního textu dokumentu " + subject
                + " zneplatní.";
            default ->
                "neznámá úroveň oprávnění (" + permission + ") - z dokumentu se nepodařilo"
                + " zjistit, jaké změny autor připustil.";
        };
    }

    private static HBox coloredHeader(String text, String bgColor, String fgColor) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + fgColor + ";");
        lbl.setWrapText(true);
        HBox box = new HBox(lbl);
        box.setPadding(new Insets(8, 12, 8, 12));
        box.setStyle("-fx-background-color: " + bgColor + ";");
        return box;
    }

    private static HBox infoRow(String labelText, String value) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 12px;");
        lbl.setMinWidth(170);
        lbl.setMaxWidth(170);
        lbl.setWrapText(true);
        TextArea val = ResultView.selectable(value, "-fx-text-fill: #111111; -fx-font-size: 12px;");
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox box = new HBox(8, lbl, val);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    /**
     * Certifikát, na kterém prvek stojí — pojmenovaný podle druhu prvku, v požadovaném pádě.
     *
     * <p>Věty per prvek psaly „podpisový certifikát" natvrdo, i když {@link #gendered} vedle nich
     * v téže větě skloňovala „podpis / pečeť / razítko". U pečeti pak odstavec začínal
     * „U podpisového certifikátu…", zatímco verdikt nad ním mluvil o pečeti — kontrola textů
     * 21. 9. 2026 jich našla 22. V ženském a středním rodě se skloňuje jen samo slovo
     * „certifikát" — 1. pád zní „certifikát pečeti", zbylé tři „certifikátu pečeti" —, kdežto
     * mužský tvar se mění ve všech čtyřech. Jedním voláním {@code gendered()} to tedy nejde.
     */
    static String elementCert(SignatureSummary sig, Case c) {
        return switch (c) {
            case NOMINATIV -> gendered(sig, "podpisový certifikát", "certifikát pečeti",
                    "certifikát razítka");
            case GENITIV   -> gendered(sig, "podpisového certifikátu", "certifikátu pečeti",
                    "certifikátu razítka");
            case DATIV     -> gendered(sig, "podpisovému certifikátu", "certifikátu pečeti",
                    "certifikátu razítka");
            case LOKAL     -> gendered(sig, "podpisovém certifikátu", "certifikátu pečeti",
                    "certifikátu razítka");
        };
    }

    /** Pády, ve kterých texty certifikát prvku potřebují. */
    enum Case {
        /** 1. pád — „podpisový certifikát byl revokován". */
        NOMINATIV,
        /** 2. pád — „za platnosti podpisového certifikátu". */
        GENITIV,
        /** 3. pád — „podpisovému certifikátu skončila platnost". */
        DATIV,
        /** 6. pád — „o podpisovém certifikátu". */
        LOKAL
    }

    static String gendered(SignatureSummary sig, String masc, String fem, String neut) {
        if ("TimeStampToken".equals(sig.getFormat())) return neut;
        String lt = sig.getLegalType();
        if (lt != null) {
            String low = lt.toLowerCase();
            if ((low.contains("pečeť") || low.contains("značk")) && !low.contains("podpis"))
                return fem;
        }
        return masc;
    }

    /** Returns the genitive form of the authentication-element type for use in sentences. */
    static String genitiveType(SignatureSummary sig) {
        if ("TimeStampToken".equals(sig.getFormat())) return "razítka";
        String lt = sig.getLegalType();
        if (lt != null) {
            String low = lt.toLowerCase();
            if ((low.contains("pečeť") || low.contains("značk")) && !low.contains("podpis"))
                return "pečeti";
        }
        return "podpisu";
    }

    /**
     * Věta „V čem je problém", která smí být jiná podle toho, co se o tomhle podpisu ví.
     *
     * <p>Doposud se brala rovnou z {@link ResultView.ReasonEntry#problem()}, tedy jedna věta na
     * číslo důvodu. U důvodu 9 to nestačí: situace 2, ze které vzniká, má dvě větve — buď
     * <b>razítko dodalo rozhodný okamžik</b> a ten padl až za odvolání certifikátu, nebo je
     * zapnutý ústupek k post-expiračním revokačním údajům a rozhodným okamžikem zůstal čas
     * ověření. Věta popisovala jen tu první.
     *
     * <p>Nad „PAdES B kvalifikovany s razitkem v dokumentu.pdf" se to rozešlo viditelně: jediné
     * razítko toho dokumentu je ve slovníku VRI a nekryje je nic, takže žádný důkaz existence
     * nezakládá (situace 52, důvod 27, přidané téhož dne). Karta pak v jednom bloku tvrdila
     * „rozhodný okamžik sem dodalo časové razítko" a o kus níž, v Průběhu ověřování, „žádný důkaz
     * existence k dispozici není, takže se podpis posuzuje k okamžiku ověření".
     *
     * <p>Rozlišuje se podle {@link SignatureSummary#getBestSignatureTime()}: je-li prázdný,
     * rozhodným okamžikem je čas ověření a razítko do věty nepatří.
     */
    static String reasonProblem(SignatureSummary sig, int rn,
                                         ResultView.ReasonEntry re) {
        if (rn == 3) {
            return unknownCertProblem(sig, re);
        }
        if (rn == 2) {
            return expiryProblem(sig, re, 2, sig.getNewerRevocationCertNotAfter());
        }
        if (rn == 4) {
            // Prvek, který kryje pozdější prvek s platným razítkem, má dřívější existenci
            // doloženou — a katalogové znění o něm tvrdí opak („není opatřen časovým razítkem…
            // ztrátu digitální kontinuity, kterou již nejde obnovit"). Nález uživatele
            // 22. 9. 2026: dokument razítko nese, AnEP z něj důkaz
            // odvodil a ověření k němu zopakoval — jen to nestačilo. Situaci 54 se ptát nelze:
            // ta se hlásí jen tam, kde přepočet vyšel kladně, a tam žádné důvody nejsou.
            if (sig.getCoveringProofDetail() != null && sig.getCertNotAfter() != null) {
                String covered = ReasonTexts.get("4/kryto", "problem");
                if (covered != null) {
                    return ReasonTexts.fill(covered, "{konec}",
                            TimeUtil.format(sig.getCertNotAfter()));
                }
            }
            return expiryProblem(sig, re, 4, sig.getCertNotAfter());
        }
        if (rn == 6) {
            return expiryProblem(sig, re, 6, sig.getExpiredResponderNotAfter());
        }
        if (rn == 24 || rn == 25) {
            return revocationSubjectProblem(sig, rn, re);
        }
        if (rn == 21) {
            String named = timestampFailureProblem(sig);
            if (named != null) return named;
        }
        if (rn == 31) {
            String named = standaloneTimestampProblem(sig);
            if (named != null) return named;
        }
        if (rn == 32) {
            String named = evidenceRecordProblem(sig);
            if (named != null) return named;
        }
        if (rn == 9 && sig.getBestSignatureTime() == null) {
            String named = ReasonTexts.get("9/bez-dukazu", "problem");
            if (named != null) return named;
        }
        return re.problem();
    }

    /**
     * Věta o neznámém certifikátu, doplněná o jeho vydavatele.
     *
     * <p>Samotné „certifikát není této aplikaci znám" nedává čtenáři nic, čeho by se chytil. Jméno
     * vydavatele ano: právě jeho certifikát se vkládá do vlastního úložiště, a podle jména se pozná,
     * jde-li o autoritu, které chce čtenář důvěřovat, nebo o interní či zkušební službu, u které
     * je odpověď jiná. Vedle věty o kotvě důvěry v Průběhu ověřování, která totéž říká o průběhu,
     * to tady patří k radě.
     *
     * <p>Nepodaří-li se jméno z DN vyčíst, platí znění bez něj — věta s prázdnými uvozovkami by
     * vypadala jako chyba aplikace.
     *
     * <p>A u certifikátu s vlastním podpisem platí znění třetí: vydavatelem je tam podepsaný sám,
     * takže jmenovat autoritu by znamenalo jmenovat jeho. Zpráva pro čtenáře je jiná — za obsah
     * takového certifikátu neručí nikdo, kdežto u neznámé autority jde o to, že ji aplikace zatím
     * nezná a může ji poznat.
     */
    private static String unknownCertProblem(SignatureSummary sig, ResultView.ReasonEntry re) {
        // Certifikát s vlastním podpisem vydavatele nemá: v DN stojí podepsaný sám, takže věta
        // „vydala jej autorita …" by jmenovala jeho a zněla by jako doporučení od sebe samého.
        if (sig.isCertSelfSigned()) {
            String own = ReasonTexts.get("3/vlastni-podpis", "problem");
            if (own != null) return own;
        }
        String issuer = certIssuerName(sig);
        if (issuer == null) {
            String plain = ReasonTexts.get("3/bez-vydavatele", "problem");
            if (plain != null) return plain;
            // Chybí-li i náhradní znění, ať se čtenáři neukáže samo {vydavatel}.
            return ReasonTexts.fill(re.problem(), "{vydavatel}", "neznámá");
        }
        return ReasonTexts.fill(re.problem(), "{vydavatel}", issuer);
    }

    /**
     * Věta u důvodů 2, 4 a 6, doplněná o den a hodinu, kdy ten certifikát expiroval.
     *
     * <p>Přání uživatele z 21. 9. 2026: věty říkaly, že revokační údaj přišel až po expiraci nebo
     * že jeho vydavatel mezitím expiroval, ale kdy k té expiraci došlo, si čtenář musel najít
     * v jiné záložce. Důvod 4 přibyl 22. 9. 2026 z téhož přání — věta „je založen na certifikátu,
     * který již expiroval“ den expirace neřekla, ačkoli je to přesně ten údaj, podle kterého
     * čtenář pozná, zda mu ověřování k zadanému datu (rada pod větou) může být co platné.
     *
     * <p>Každý z těch tří důvodů mluví o <b>jiném</b> certifikátu — dvojka o tom, k němuž se údaj
     * vztahuje, šestka o tom, kterým je podepsaný —, a u dvojky ani šestky to nemusí být
     * podpisový certifikát: situace 5 i 6 chodí po podpisovém řetězu i po řetězech razítek, takže
     * {@code getCertNotAfter()} by u nálezu na razítku dosadil do věty cizí datum. Jádro proto
     * posílá datum z téhož nálezu, kterým ten důvod našlo; sazba je pak společná. U čtyřky je to
     * naopak podpisový certifikát a nic jiného: situace 3, ze které vzniká, se hlásí jen tehdy,
     * když prvek nemá jediné razítko a expiroval právě {@code getSigningCertificate()} — takže se
     * bere týž údaj, jaký tu situaci našel.
     *
     * <p>Nepodaří-li se je určit, platí znění bez data — prázdná závorka nebo holé „K tomu došlo .“
     * by vypadalo jako chyba.
     */
    private static String expiryProblem(SignatureSummary sig, ResultView.ReasonEntry re,
                                        int rn, java.time.Instant expiry) {
        if (expiry == null) {
            String plain = ReasonTexts.get(rn + "/bez-data", "problem");
            if (plain != null) return plain;
            return ReasonTexts.fill(re.problem(), "{konec}", "");
        }
        return ReasonTexts.fill(re.problem(), "{konec}", TimeUtil.format(expiry));
    }

    /**
     * Věta u důvodů 24 a 25, která jmenuje certifikát, kterého se nález týká.
     *
     * <p>Obě začínaly holým „o certifikátu" a čtenář neměl jak poznat, o který jde — kontrola
     * textů 21. 9. 2026. Dopsat „podpisovém" nešlo: situace 46 a 47, ze kterých ty důvody
     * vznikají, chodí po podpisovém řetězu <b>i po řetězech razítek</b>, takže to může být
     * i certifikát razítkovací autority. Jméno proto určuje jádro z téhož výčtu certifikátů,
     * kterým situaci našlo, a text si ho jen dosadí.
     *
     * <p>Prázdný údaj by znamenal, že situace nastala nad prázdným výčtem; pro ten případ je
     * v souboru znění bez jména, aby ve větě nezůstalo viset zástupné slovo.
     */
    private static String revocationSubjectProblem(SignatureSummary sig, int rn,
                                                    ResultView.ReasonEntry re) {
        String subject = sig.getReasonCertificates().get(rn);
        if (subject == null || subject.isBlank()) {
            String plain = ReasonTexts.get(rn + "/bez-certifikatu", "problem");
            if (plain != null) return plain;
            return ReasonTexts.fill(re.problem(), "{certifikat}", "certifikátu");
        }
        return ReasonTexts.fill(re.problem(), "{certifikat}", subject);
    }

    /**
     * Jméno vydavatele certifikátu tohoto prvku (položka CN), nebo {@code null}, není-li v DN.
     *
     * <p>Jeden zdroj pro dvě místa — odstavec „Kotva důvěry" v Průběhu ověřování a větu u důvodu 3.
     * Dvě znění téhož údaje by se časem rozešla a čtenář by četl dvě jména na jedné kartě.
     */
    static String certIssuerName(SignatureSummary sig) {
        String cn = DnUtil.dnField(sig.getCertIssuerDN(), "2.5.4.3", "CN");
        return cn != null && !cn.isBlank() ? cn : null;
    }

    /**
     * Rada u důvodu, přepsaná podle toho, co je o tomhle prvku známo.
     *
     * <p>Zatím jediný případ: <b>SHA-1 (důvod 15) vedle vyřazené razítkovací služby
     * (situace 43)</b>. Rada zní „zapněte toleranci k SHA-1", jenže situace 43 říká sama
     * o sobě, že rozhodný okamžik by musel stát na razítku od služby s odňatým statusem
     * a že <i>právě proto</i> výsledek není kladný — a to žádné nastavení nespraví.
     *
     * <p>Změřeno 11. 9. 2026 na „B LTA z roku 2012_preserved_2019-10-11_…_preserved_2022-10-12_….pdf":
     * přísně důvody 15 a 21, se zapnutou tolerancí k SHA-1 zůstal důvod 21, obě stará
     * razítka dál neprošla a verdikt se nepohnul (INDETERMINATE/OUT_OF_BOUNDS_NOT_REVOKED
     * v obou bězích). Rada tedy vedla k přepínači, po kterém ubude řádek a nic víc.
     *
     * <p>Rada se neruší, jen se k ní dopíše, co z ní neplyne: tolerance odstraní tenhle
     * důvod, ne překážku. Zamlčet ji celou by bylo horší — SHA-1 v dokumentu je a čtenář
     * se to má dozvědět i s tím, kde se to přepíná.
     */
    static String reasonSolution(SignatureSummary sig, int rn,
                                         ResultView.ReasonEntry re) {
        if (rn == 15 && re.solution() != null
                && sig.getDetectedSituations().contains(43)) {
            // Dřív tu stálo „a to žádné nastavení nezmění". Měřením je to vyvrácené: připnutí
            // certifikátu TSU spolu s touhle tolerancí překlopilo
            // „certifikacni_a_uznavany_s_razitky_s_revinfo.pdf" na TOTAL_PASSED (14. 9. 2026).
            // Věta proto říká jen to, co platí — tolerance sama nestačí — a kde je zbytek:
            // u důvodu 21, který od téhož dne radí připnutí. Bez toho odkazu by si obě rady na
            // jedné kartě protiřečily, jedna „nic nepomůže" a druhá „tohle pomůže".
            String note = ReasonTexts.get("15/vyrazena-sluzba",
                    sig.getDetectedReasons().contains(21)
                            ? "dodatek-s-duvodem-21" : "dodatek-bez-duvodu-21");
            if (note != null) return re.solution() + ". " + note;
        }
        if (rn == 21) {
            String named = timestampFailureSolution(sig);
            if (named != null) return named;
        }
        if (rn == 31) {
            String named = standaloneTimestampSolution(sig);
            if (named != null) return named;
        }
        if (rn == 32) {
            String named = evidenceRecordSolution(sig);
            if (named != null) return named;
        }
        return re.solution();
    }

    static String getReasonDetail(SignatureSummary sig, int rn, ResultView.ReasonEntry re) {
        // Výklad o revokaci — co znamená a proč k ní dochází. Stával v řádku „Důvod neplatnosti:"
        // na kartě neplatného podpisu; od zrušení přepisu verdiktu se takový podpis na tu kartu
        // nedostane, a text by jinak zmizel čtenáři úplně.
        if (rn == 9)  return sig.isSeal() ? REASON_S2_SEAL : REASON_S2_SIGNATURE;
        if (rn == 17) return "Podpis používá nepodporovaný SubFilter: " + sig.getFormat();
        if (rn == 15) return sig.getSha1Detail();
        if (rn == 12) return sig.getNoRevocationDetail();
        if (rn == 2)  return sig.getNewerRevocationDetail() != null ? sig.getNewerRevocationDetail() : re.detail();
        if (rn == 6)  return sig.getExpiredResponderDetail() != null ? sig.getExpiredResponderDetail() : re.detail();
        if (rn == 21) {
            String named = timestampFailureDetail(sig);
            return named != null ? named : re.detail();
        }
        return re.detail();
    }

    /**
     * Příčiny, kvůli kterým razítka neobstála — v pořadí, v jakém razítka na prvku jsou, a každá
     * jednou. Prázdné, dokud razítka ověřením prošla nebo dokud se příčina nepodařila pojmenovat.
     */
    private static java.util.List<SignatureSummary.TimestampFailure> timestampFailures(
            SignatureSummary sig) {
        java.util.LinkedHashSet<SignatureSummary.TimestampFailure> causes =
                new java.util.LinkedHashSet<>();
        for (SignatureSummary.TimestampEntry ts : sig.getAllTimestamps()) {
            if (!ts.passed() && ts.failureCause() != null) causes.add(ts.failureCause());
        }
        return java.util.List.copyOf(causes);
    }

    /** První razítko s danou příčinou — kvůli jménu autority a konci platnosti jejího certifikátu. */
    private static SignatureSummary.TimestampEntry firstWithCause(
            SignatureSummary sig, SignatureSummary.TimestampFailure cause) {
        return sig.getAllTimestamps().stream()
                .filter(ts -> !ts.passed() && ts.failureCause() == cause)
                .findFirst().orElse(null);
    }

    /**
     * Dokládá pozdější razítko, že to neúspěšné vzniklo ještě za platnosti certifikátu své
     * autority?
     *
     * <p>Rozhoduje o tom, které znění důvodu 21 čtenář uvidí. Věty „nic nedokládá, že razítko
     * vzniklo ještě za ní" a „to měl zajistit archivní zásah ještě v době, kdy certifikát platil"
     * jsou nad takovým dokumentem nepravdivé: ten zásah proběhl a proběhl včas. Nález uživatele
     * z 20. 9. 2026 — podpisové razítko z 9. 9. 2022 (certifikát autority do 21. 10. 2023)
     * a dokumentové razítko z 3. 10. 2022, které je kryje; DSS ten čas samo vede jako
     * {@code TIMESTAMP_POE_TIME}.
     *
     * <p>Odpověď počítá {@link ExtendedView#provenInTime} — táž, podle které se řídí popisek
     * v Rozšířeném přehledu. Dvě místa, jedno pravidlo; kdyby si každé počítalo po svém, řekla
     * by o témž razítku dvě různé věci.
     */
    private static boolean laterTimestampProvesIt(SignatureSummary sig,
                                                  SignatureSummary.TimestampFailure cause) {
        java.util.List<SignatureSummary.TimestampEntry> all = sig.getAllTimestamps();
        for (int i = 0; i < all.size(); i++) {
            SignatureSummary.TimestampEntry ts = all.get(i);
            if (!ts.passed() && ts.failureCause() == cause) {
                return ExtendedView.provenInTime(all, i);
            }
        }
        return false;
    }

    /**
     * Věta „V čem je problém" u důvodu 31 — samostatné časové razítko, které neobstálo.
     *
     * <p>Znění se řídí příčinou, zařazenou z klíčů hlášení protokolu DSS
     * ({@link SignatureSummary.TimestampFailure#of}). Texty schválil uživatel 14. 9. 2026;
     * jejich zdrojová podoba je v {@code docs/duvody-z-protokolu-navrh.md} a při úpravách se
     * mění na obou místech, aby si dokument a aplikace odpovídaly.
     *
     * <p>Mluví se tu o <b>celém prvku</b>, ne o „přiloženém razítku" jako u důvodu 21: tohle
     * razítko stojí nad dokumentem samo a žádný podpis nad sebou nemá.
     */
    static String standaloneTimestampProblem(SignatureSummary sig) {
        SignatureSummary.TimestampFailure cause = sig.getStandaloneTimestampFailure();
        if (cause == null) return null;
        boolean dated = sig.getCertNotAfter() != null;
        // Bez data se věta obejde jinak, ne že by v ní zůstalo prázdné místo; proto vlastní znění.
        String text = dated ? null : ReasonTexts.variant(31, cause.name(), "problem-bez-data");
        if (text == null) text = ReasonTexts.variant(31, cause.name(), "problem");
        return ReasonTexts.fill(text, "{konec}",
                dated ? TimeUtil.format(sig.getCertNotAfter()) : "");
    }


    /** Rada u důvodu 31; {@code null} tam, kde se příčinu nepodařilo zařadit. */
    static String standaloneTimestampSolution(SignatureSummary sig) {
        SignatureSummary.TimestampFailure cause = sig.getStandaloneTimestampFailure();
        if (cause == null) return null;
        return ReasonTexts.variant(31, cause.name(), "pomoc");
    }

    /**
     * Věty u důvodu 32 — evidenčnímu záznamu něco schází. Znění se řídí tím, která ze situací
     * 37–40 je indikována; nastane-li jich víc, mluví věta o té první v tomhle pořadí.
     */
    static String evidenceRecordProblem(SignatureSummary sig) {
        return evidenceRecordText(sig, "problem");
    }

    /** Rada u důvodu 32, týmž pořadím jako {@link #evidenceRecordProblem}. */
    static String evidenceRecordSolution(SignatureSummary sig) {
        return evidenceRecordText(sig, "pomoc");
    }

    /**
     * Pořadí je záměrné, ne abecední: nastane-li víc těchto situací naráz, mluví věta o té
     * první v řadě — od nejzávažnější vady záznamu k nejúžší.
     */
    private static String evidenceRecordText(SignatureSummary sig, String key) {
        for (int situation : new int[]{37, 39, 40, 38}) {
            if (sig.getDetectedSituations().contains(situation)) {
                return ReasonTexts.variant(32, "s" + situation, key);
            }
        }
        return null;
    }

    /** Jméno razítkovací autority v uvozovkách, nebo obecné pojmenování, když se nezjistilo. */
    private static String tsaName(SignatureSummary.TimestampEntry ts) {
        return ts != null && ts.issuer() != null && !ts.issuer().isBlank()
                ? "„" + ts.issuer() + "“" : "razítkovací autority";
    }

    /**
     * Věta „V čem je problém" u důvodu 21, doplněná o pojmenovanou příčinu.
     *
     * <p>Důvod 21 uměl říct jen to, že razítko neobstálo — příčinu měl čtenář najít sám
     * v podrobném protokolu DSS. Protokol ji vydá, jenže za cenu expertního módu, orientace
     * v klíčích typu {@code PSV_IPTVC_ANS} a znalosti toho, <i>která</i> z hlášených chyb
     * rozhoduje. A to poslední z protokolu nekouká: u razítek z roku 2012 stojí SHA-1 nad vším
     * ostatním, ačkoli verdikt drží expirovaný certifikát razítkovací autority.
     *
     * <p>Příčinu proto pojmenuje aplikace sama — viz
     * {@link cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampFailure}. Vrací
     * {@code null}, když se ji pojmenovat nepodařilo; pak zůstane původní obecná věta.
     */
    static String timestampFailureProblem(SignatureSummary sig) {
        java.util.List<SignatureSummary.TimestampFailure> causes = timestampFailures(sig);
        if (causes.size() != 1 || causes.get(0) == SignatureSummary.TimestampFailure.OTHER) {
            return null;
        }
        SignatureSummary.TimestampEntry ts = firstWithCause(sig, causes.get(0));
        boolean dated = ts != null && ts.notAfter() != null;
        // Kryje-li razítko pozdější, věta „nic nedokládá, že vzniklo ještě za ní" je nepravda —
        // viz {@link #laterTimestampProvesIt}.
        String cause = dated && laterTimestampProvesIt(sig, causes.get(0))
                ? ReasonTexts.variant(21, causes.get(0).name(), "pricina-s-dokladem") : null;
        // Bez data se věta obejde jinak, než aby v ní zůstalo prázdné místo; příčiny, které
        // datum nezmiňují, druhé znění nemají a berou to první.
        if (cause == null && !dated) {
            cause = ReasonTexts.variant(21, causes.get(0).name(), "pricina-bez-data");
        }
        if (cause == null) cause = ReasonTexts.variant(21, causes.get(0).name(), "pricina");
        if (cause == null) return null;
        cause = ReasonTexts.fill(cause, "{autorita}", tsaName(ts),
                "{konec}", dated ? TimeUtil.format(ts.notAfter()) : "");
        return ReasonTexts.variant(21, "ramec", "problem-uvod") + ": " + cause + ". "
                + ReasonTexts.variant(21, "ramec", "problem-zaver");
    }

    /**
     * Rozvedení pod odkazem <b>Podrobněji …</b>: jak se k té příčině došlo a proč jí není to,
     * co v protokolu bije do očí nejvíc.
     */
    static String timestampFailureDetail(SignatureSummary sig) {
        java.util.List<SignatureSummary.TimestampFailure> causes = timestampFailures(sig);
        if (causes.size() != 1) return null;
        String text = laterTimestampProvesIt(sig, causes.get(0))
                ? ReasonTexts.variant(21, causes.get(0).name(), "technicky-s-dokladem") : null;
        if (text == null) text = ReasonTexts.variant(21, causes.get(0).name(), "technicky");
        if (text == null) return null;
        SignatureSummary.TimestampEntry ts = firstWithCause(sig, causes.get(0));
        return ReasonTexts.fill(text, "{konec}", ts != null && ts.notAfter() != null
                ? TimeUtil.format(ts.notAfter()) : "svého dne");
    }

    /**
     * Rada u důvodu 21, odvozená z pojmenované příčiny.
     *
     * <p>Nahrazuje odkaz do protokolů DSS. Text se drží toho, co bylo změřeno: u
     * {@code EXPIRED_NO_POE} nepomůže ani připnutí certifikátu razítkovací autority samo o sobě,
     * ani ústupek k SHA-1 sám o sobě — teprve obojí naráz. Změřeno 14. 9. 2026 na
     * „certifikacni_a_uznavany_s_razitky_s_revinfo.pdf“ (3 podpisy): samostatně
     * {@code OUT_OF_BOUNDS_NO_POE} resp. {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE}, dohromady
     * {@code TOTAL_PASSED}.
     *
     * <p>Proč připnutí zabere: certifikát z vlastního úložiště vystupuje jako kotva důvěry a jeho
     * vlastní platnost už se nezkoumá, takže odpadne požadavek doložit, že razítko vzniklo ještě
     * za ní. Je to ústupek, ne oprava dokumentu, a věta to má říct.
     */
    /**
     * Dovětek k radě „připněte certifikát razítkovací autority": připnutí odstraní jednu překážku
     * a druhá pod ní může vystoupit.
     *
     * <p>Není to opatrnost do zásoby, nýbrž změřený případ. Na obou zkoušených dokumentech —
     * „certifikacni_a_uznavany_s_razitky_s_revinfo.pdf" (expirovaný certifikát TSU)
     * i „DEMO casove razitko Praha 5.pdf" (autorita mimo seznamy) — se po připnutí indikace
     * změnila na {@code CRYPTO_CONSTRAINTS_FAILURE_NO_POE} a teprve s tolerancí k SHA-1 razítko
     * prošlo (14. 9. 2026). Rada, která by skončila u připnutí, by poslala čtenáře zapnout jedno
     * ze dvou potřebných zaškrtávátek a nechala ho u nezměněného výsledku.
     */
    private static String afterPinningNote(SignatureSummary sig) {
        return ReasonTexts.get("21/po-pripnuti",
                sig.getDetectedReasons().contains(15) ? "s-duvodem-15" : "bez-duvodu-15");
    }

    static String timestampFailureSolution(SignatureSummary sig) {
        java.util.List<SignatureSummary.TimestampFailure> causes = timestampFailures(sig);
        if (causes.size() != 1) return null;
        String name = causes.get(0).name();
        // Zvláštní znění pro souběh s důvodem 15 mají jen některé příčiny; ostatní si vystačí
        // s jedním a liší se nejvýš dovětkem {po-pripnuti}.
        String text = sig.getDetectedReasons().contains(15)
                ? ReasonTexts.variant(21, name, "pomoc-s-duvodem-15") : null;
        if (text == null && laterTimestampProvesIt(sig, causes.get(0))) {
            text = ReasonTexts.variant(21, name, "pomoc-s-dokladem");
        }
        if (text == null) text = ReasonTexts.variant(21, name, "pomoc");
        if (text == null) return null;
        return fillCauseText(sig, causes.get(0), text);
    }

    /**
     * Doplní do znění vybraného podle příčiny to, co zná teprve prvek: jméno razítkovací autority
     * a dovětek o připnutí. Čte ho {@link #timestampFailureSolution} i {@link #expertTip}, aby se
     * rada pro zkušenější nelišila od té hlavní v tom, co si umí dosadit.
     */
    private static String fillCauseText(SignatureSummary sig,
                                        SignatureSummary.TimestampFailure cause, String text) {
        SignatureSummary.TimestampEntry ts = firstWithCause(sig, cause);
        String tsa = ts != null && ts.issuer() != null && !ts.issuer().isBlank()
                ? "„" + ts.issuer() + "“" : "té razítkovací autority";
        return ReasonTexts.fill(text, "{autorita}", tsa,
                "{po-pripnuti}", afterPinningNote(sig));
    }

    /**
     * The visual appearance, shown only when the signature actually carries one.
     *
     * <p>Pořadí je výklad → obrázek → co s ním je v tomhle dokumentu. Výklad stojí první,
     * protože rozhoduje o tom, jak se má čtenář na obrázek pod ním dívat: že vizuální podobu
     * podpis nemá a tohle je jen berlička. Kdyby přišel až za obrázkem, čtenář by ho míjel
     * s dojmem, že vidí podpis.
     *
     * <p>Úvodní věta „Podpis má následující vizualizaci…" odsud zmizela 4. 9. 2026: obrázek
     * hned pod ní říkal totéž a její druhá půlka byla doslova třetí odstavec výkladu.
     */
    private static VBox buildAppearanceSection(SignatureSummary sig, byte[] pngBytes) {
        return buildAppearanceSection(sig, pngBytes, true);
    }

    /**
     * Je o vizualizaci co říct? Jen když nějaká je.
     *
     * <p>Chybějící vizualizace se nehlásí nikde — je to u podpisů běžný stav (v korpusu jich tak
     * vzniklo přes polovinu, protože se podepisuje neviditelně) a věta „bez vizualizace" na
     * každé druhé kartě by byla šum, ne nález. Zkoušeno 4. 9. 2026 a zase vráceno.
     */
    static boolean hasAppearanceToReport(SignatureSummary sig) {
        byte[] png = sig.getSignatureAppearancePng();
        return png != null && png.length > 0;
    }

    /**
     * @param labelled zda výklad dostane popisek „Vizualizace podpisu:" v levém sloupci.
     *     Uvnitř bloku „Podepsaná osoba a čas" ano — je to jedna z několika věcí, které blok
     *     nese, a popisek říká, o kterou jde. Ve vlastním bloku ne: tam už totéž říká nadpis
     *     nad ním a popisek by ho zopakoval o řádek níž.
     *
     *     <p>Statická a package-private schválně: touž podobu sází i Rozšířený přehled
     *     ({@code ExtendedView.appearanceBlock}). Dvě kopie téhož bloku by se při první úpravě
     *     rozešly, a rozejít se nemají — čtenář má na obou záložkách vidět totéž.
     */
    static VBox buildAppearanceSection(SignatureSummary sig, byte[] pngBytes, boolean labelled) {
        HBox imageRow = pngBytes == null ? null : appearanceImageRow(pngBytes);
        if (imageRow == null) return new VBox();

        VBox section = new VBox(4);
        section.setFillWidth(true);
        section.setPadding(new Insets(4, 0, 0, 0));
        String notes = APPEARANCE_NOTE_1 + "\n\n" + APPEARANCE_NOTE_2 + "\n\n" + APPEARANCE_NOTE_3;
        section.getChildren().add(labelled
                ? infoRow(gendered(sig, "Vizualizace podpisu:", "Vizualizace pečeti:",
                                        "Vizualizace razítka:"), notes)
                : unlabelledRow(notes));
        section.getChildren().add(imageRow);
        // Až za obrázkem: výklad nad ním mluví o vizualizacích obecně, tohle o tom jednom, na
        // který se čtenář právě dívá — a dává smysl teprve tehdy, když ho už viděl. Rozklad
        // příznaků /F tu nestojí; větu, kterou by dokládal, píše FieldVisibilityText slovy.
        String caveat = FieldVisibilityText.appearanceCaveat(sig);
        if (caveat != null) {
            section.getChildren().add(unlabelledRow(caveat));
        }
        return section;
    }

    /** Rámeček s obrázkem, který si nese i bajty pro protokol. */
    private static HBox appearanceImageRow(byte[] pngBytes) {
        Image image;
        try {
            image = new Image(new ByteArrayInputStream(pngBytes));
        } catch (Exception e) {
            return null;
        }
        ImageView imageView = new ImageView(image);
        // Bajty s sebou, jinak obrázek do protokolu nedojde: {@code PrintUtil} ho sází z nich,
        // ne z {@code Image}. Analýze tenhle řádek chyběl, takže její protokol vizualizaci
        // vynechával — objeveno 4. 9. 2026 při slučování s Rozšířeným přehledem, který ho měl.
        imageView.getProperties().put(PrintUtil.PNG_BYTES, pngBytes);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitWidth(Math.min(image.getWidth(), 600));
        imageView.setFitHeight(200);
        StackPane imagePane = new StackPane(imageView);
        imagePane.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1; " +
                           "-fx-background-color: #ffffff; -fx-padding: 4;");
        imagePane.setAlignment(Pos.CENTER_LEFT);
        HBox row = new HBox(imagePane);
        row.setPadding(new Insets(2, 0, 4, 0));
        return row;
    }

    static final String APPEARANCE_NOTE_1 =
            "Kvalifikované, uznávané i zaručené elektronické podpisy, stejně jako pečeti, nemají"
            + " žádnou vizuální podobu. Jsou to jen data (posloupnost 1 a 0), a pracuje se s nimi"
            + " jen prostřednictvím programových nástrojů.";

    static final String APPEARANCE_NOTE_2 =
            "Pro uživatele, kteří jsou zvyklí vidět něco jako podpis přímo v těle dokumentu, se"
            + " jako určitá „berlička“ přidává na vhodné místo v dokumentu konkrétní vizuální"
            + " ztvárnění (vizualizace) těchto elektronických podpisů či pečetí, v podobě obrázku"
            + " (obdélníkového tvaru). Toto vizuální ztvárnění může být libovolné, a volba jeho"
            + " provedení je zcela na podepisující, resp. pečetící osobě.";

    static final String APPEARANCE_NOTE_3 =
            "Někdy, nejčastěji u pečetí, je obrázek, představující vizualizaci, prezentován jako"
            + " prostý elektronický podpis jiné osoby, než té které patří samotná elektronická"
            + " pečeť.";

    /**
     * True when the newest time-stamp in the file rests on a certificate that has already expired.
     *
     * <p>Used to withhold renewal from a container that carries only time-stamps. Once that
     * certificate is gone, no usable revocation data for it can be fetched any more, so a fresh
     * time-stamp would attest to a chain that already has a hole in it — and the file would look
     * renewed without being it. Fixation is the honest offer there: it freezes what there is and
     * claims nothing about continuity.
     *
     * <p>Situation 33 does not catch this on its own. A TSU certificate listed in a Trusted List is
     * a trust anchor, and trust anchors are deliberately exempt from the revocation requirement
     * (EN 319 142-1 asks only for what validation used), so S33 reports "available" for a chain
     * that in fact can no longer be carried forward. Measured on
     * jiz uchovane\kontejnery\B 2019_10_23_ICA_do_2025_09_26.asice, whose TSU certificate expired
     * in September 2025.
     */
    static boolean lastTimestampChainExpired(ValidationResult result) {
        DiagnosticData dd = result == null ? null : result.getDiagnosticData();
        if (dd == null) return false;
        Date now = new Date();
        return dd.getTimestampList().stream()
                .filter(ts -> ts.getProductionTime() != null)
                .max(Comparator.comparing(TimestampWrapper::getProductionTime))
                .map(ts -> hasExpiredCertInTsChain(ts, dd, now))
                .orElse(false);
    }

    private static boolean hasExpiredCertInTsChain(TimestampWrapper ts, DiagnosticData dd, Date validationTime) {
        CertificateWrapper signingCert = ts.getSigningCertificate();
        if (signingCert != null) {
            CertificateWrapper full = dd.getUsedCertificateById(signingCert.getId());
            if (full == null) full = signingCert;
            if (full.getNotAfter() != null && full.getNotAfter().before(validationTime)) return true;
        }
        for (CertificateWrapper c : ts.getCertificateChain()) {
            CertificateWrapper full = dd.getUsedCertificateById(c.getId());
            if (full == null) full = c;
            if (full.getNotAfter() != null && full.getNotAfter().before(validationTime)) return true;
        }
        return false;
    }

    /*
     * Tady stával stampWithFallback(): při odmítnutí razítka nabídl zopakování s SHA-256 a čekal,
     * až to uživatel potvrdí. Ptal se v nejhorší možný okamžik — uprostřed uchování, o kterém si
     * uživatel myslel, že běží — a ptal se na věc, kterou nemohl posoudit: jestli tahle autorita
     * bere silnější otisk, není otázka na něj.
     *
     * Zjišťuje se to teď jednou, při potvrzení zdroje v nastavení (TimestampingService.probe),
     * a nastavení pak nabízí jen funkce, na které zdroj odpověděl. Odmítnutí kvůli otisku tak
     * nemá jak nastat, a když nastane z jiného důvodu, je to chyba a hlásí se jako chyba.
     */

    /**
     * Uchování — doplní revokační data a přidá archivní časové razítko.
     *
     * <p>Two implementations behind one button. The user is being offered one action, and which
     * machinery carries it out is not something they should have to know, so the split happens
     * here rather than in the toolbar.
     */
    /**
     * What Prodloužení buys and what it does not, put to the user before it runs. Returns false
     * when they decline.
     *
     * <p>This text was written for the expert-mode warning that used to accompany preservation
     * without situation 32, and it says exactly what the reader of this action needs to know, so it
     * stayed when that case became an action of its own. The one thing a button label cannot carry
     * is that the result divides its audience: sound for a validator following the standards,
     * refused by one that judges time-stamps by certificate validity. That is not a caveat to bury
     * in a tooltip.
     *
     * <p>Two openings, because the chain can be broken in two ways and the reader is entitled to
     * know which one they are looking at. Either the last certificate has expired — the first
     * prolongation's case — or the break is further back, which is what a prolonged document has:
     * its newest certificate is good for years, but the stamp bearing it was added after the
     * previous one had expired. The rest of the text is the same, because the consequence is.
     */
    /**
     * Certifikační podpis „žádné změny“ (DocMDP P=1) a úkon, který přidá revizi.
     *
     * <p>Změřeno 17. 10. 2026 na přerazítkovaných úředních výpisech s certifikační pečetí P=1: Adobe
     * Acrobat Reader přidané dokumentové razítko vyhodnotil jako nepovolenou změnu a pečeť jako
     * neplatnou, kdežto Úschovna CzechPOINT tutéž podobu uznala a DSS kontrolu DocMDP
     * ({@code BBB_FC_ISVADMDPD}) vyhodnotil jako OK. Normy to dovolují výslovně: EN 319 142-1
     * (5.4.2.3, 5.4.3 — „a value of 1 does not allow any change but allows Document Time-stamps“)
     * a ISO 32000-2 v tabulce 257 u DocMDP. Acrobat se drží znění ISO 32000-1, které výjimku
     * nemá; Adobe to v komunitním fóru (2026) potvrdil a opravu neohlásil.
     * Úkon se proto nezakazuje (jeho cílová skupina výsledek uzná), jen se před ním řekne, co
     * Acrobat může ukázat. Ve sbírkách šlo o 17 ze 179 dokumentů s nabídkou přerazítkování.
     */
    private boolean confirmLockedCertification() {
        if (lastResult == null || continuityMode == null) return true;
        List<SignatureSummary> locked = lastResult.stream()
                .filter(sig -> "NO_CHANGE_PERMITTED".equals(sig.getCertificationPermission()))
                .collect(Collectors.toList());
        if (locked.isEmpty()) return true;
        boolean seal = locked.stream().allMatch(SignatureSummary::isSeal);
        String what = seal ? "certifikační pečeť" : "certifikační podpis";
        String text = "Dokument nese " + what + (seal ? ", jejíž" : ", jehož")
                + " autor nepovolil žádné změny. Úkon "
                + continuityMode.noun.toLowerCase(Locale.ROOT) + " k němu přidá novou revizi"
                + " s časovým razítkem. Normy pro podpisy v PDF (ETSI EN 319 142-1 i ISO 32000-2)"
                + " časové razítko dokumentu za změnu nepovažují a ověřovatelé, kteří se jimi"
                + " řídí, mohou "
                + (seal ? "pečeť" : "podpis") + " i nadále uznat. Adobe Acrobat Reader ale může"
                + " přidané razítko vyhodnotit jako nepovolenou změnu a " + what + " označit za"
                + (seal ? " neplatnou." : " neplatný.")
                + " Výsledek se uloží jako nový soubor; původní dokument zůstane beze změny.";

        ButtonType go = new ButtonType(continuityMode.verb,
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Zrušit",
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(continuityMode.noun);
        alert.setHeaderText("Dokument je certifikován bez povolených změn");
        alert.getButtonTypes().setAll(go, cancel);
        Label content = new Label(text);
        content.setWrapText(true);
        content.setMaxWidth(430);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(480);
        return alert.showAndWait().orElse(cancel) == go;
    }

    private boolean confirmProlongation() {
        if (lastResult == null) return true;
        List<SignatureSummary> affected = lastResult.stream()
                .filter(sig -> !"TimeStampToken".equals(sig.getFormat()))
                .filter(sig -> !sig.getDetectedSituations().contains(32)
                        || !sig.getDetectedSituations().contains(31))
                .collect(Collectors.toList());
        if (affected.isEmpty()) return true;

        // Chybějící situace 32 znamená, že po expiraci je celá řada, ne jen její poslední článek:
        // od 18. 9. 2026 se 32 ptá, kam řada sahá, a mladší razítko s kratším certifikátem ji
        // nezkracuje.
        boolean chainExpired = affected.stream()
                .anyMatch(sig -> !sig.getDetectedSituations().contains(32));

        boolean seal = affected.stream().allMatch(SignatureSummary::isSeal);
        String subject = seal ? "této pečeti" : "tohoto podpisu";
        String itNominative = seal ? "pečeť" : "podpis";
        String itAccusative = seal ? "pečeť" : "podpis";
        String preserved = seal ? "takto zachovanou pečeť" : "takto zachovaný podpis";
        String judged = seal ? "vyhodnotit jako platnou" : "vyhodnotit jako platný";
        String notJudged = seal ? "neověří jako platnou" : "neověří jako platný";

        String opening = chainExpired
                ? "Certifikátům všech časových razítek u " + subject
                  + " již skončila řádná doba jejich platnosti (tj. již expirovaly)."
                : "Posloupnost časových razítek u " + subject + " je již přerušena:"
                  + " některé razítko vzniklo až poté, co skončila platnost certifikátů všech"
                  + " razítek, která mu předcházejí. Řada sice dosud sahá do budoucnosti, ale"
                  + " mezeru dřívější to nezacelí.";

        String text = opening
                + " Samotné časové razítko bylo vyhodnoceno jako platné díky tzv. sunsetu"
                + " (že certifikát jeho vydavatele je stále ještě uveden v důvěryhodných seznamech"
                + " jako tzv. kotva důvěry, a nenastal u něj ještě tzv. sunset)."
                + " Podle standardů je tedy možné digitální kontinuitu prodloužit, a konformní"
                + " validátor by měl " + preserved + " " + judged + "."
                + " Nové razítko navíc doloží, že dokument v této podobě existoval ještě před"
                + " případným sunsetem, takže platnost obstojí i poté, co sunset nastane."
                + " Ale pro takový validátor, který se u časových razítek (nad rámec standardů)"
                + " řídí pouze řádnou dobou platnosti jejich certifikátů (což jsou např. CzechPOINTy"
                + " a řada podatelen OVM), nebude posloupnost časových razítek souvislá, a "
                + itAccusative + " tak " + notJudged + ".";

        ButtonType preserve = new ButtonType(ContinuityMode.PROLONG.verb,
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Zrušit",
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(ContinuityMode.PROLONG.noun);
        alert.setHeaderText("Posloupnost časových razítek nebude pro všechny validátory souvislá");
        alert.getButtonTypes().setAll(preserve, cancel);
        Label content = new Label(text);
        content.setWrapText(true);
        content.setMaxWidth(430);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(480);
        return alert.showAndWait().orElse(cancel) == preserve;
    }

    /**
     * Two actions, one operation. Preservation and Prodloužení both add validation data and an
     * archive time-stamp and differ only in which document they are offered for — situation 32
     * present or absent — and therefore in what the result is called and what the user is told
     * about who will accept it. Splitting the machinery would mean two copies of the two-round
     * dance from level B, kept in step by hand.
     */
    private enum PreserveMode {
        PRESERVE(ContinuityMode.PRESERVE),
        // Rozlišení tu zůstává kvůli confirmProlongation() a kvůli tomu, na co se která cesta
        // ptá — a od 7. 9. 2026 i kvůli slovům: uchovaný a prodloužený dokument nejsou týž
        // výsledek, takže se nesmí jmenovat stejně.
        PROLONG(ContinuityMode.PROLONG);

        /** Varianta, kterou tahle cesta provádí; odtud jsou všechna její slova. */
        final ContinuityMode action;

        PreserveMode(ContinuityMode action) {
            this.action = action;
        }

        String saveTitle() { return action.saveTitle(); }
        String failTitle() { return action.failTitle(); }
        /** The action's name, for dialog titles and running text. */
        String noun() { return action.noun; }
        /** The imperative on the button that goes ahead with it. */
        String verb() { return action.verb; }

        /** The noun mid-sentence. */
        String nounLower() { return action.noun.toLowerCase(Locale.ROOT); }
    }

    private void runPreservation() {
        runPreservation(PreserveMode.PRESERVE);
    }

    /** The spinner belongs beside the button that was pressed; the other one is not even visible. */
    private ProgressIndicator progressFor(PreserveMode mode) {
        return preserveProgress;
    }

    private void runPreservation(PreserveMode mode) {
        if (mode == PreserveMode.PROLONG && !confirmProlongation()) return;
        if (extensionService != null && lastValidationResult != null
                && SignatureExtensionService.handles(lastValidationResult, signatureFile())) {
            runExtension(mode);
            return;
        }
        if (augmentationService == null || timestampingService == null
                || settings == null || sourceFile == null) return;
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) return;

        ProgressIndicator progress = progressFor(mode);
        setAllActionButtonsDisabled(true);
        progress.setVisible(true);
        progress.setManaged(true);

        File inputFile = sourceFile;
        char[] pwd = pdfPassword;
        AugmentationService augSvc = augmentationService;
        TimestampingService tsaSvc = timestampingService;

        java.util.List<byte[]> extraCrls = new java.util.ArrayList<>();
        for (cz.digitalnikontinuita.anep.model.SignatureSummary sig : lastResult) {
            if (sig.getDetectedSituations().contains(33)
                    && isPdfSignature(sig.getFormat())) {
                extraCrls.addAll(sig.getS33CrlBytes());
            }
        }
        // …and what the validation itself leaned on. Preservation asks different sources than
        // validation did and can miss the list that carried the verdict — see
        // ReliedUponRevocation. Handed over here rather than fixed inside the preservation
        // verifier: this adds material to an input that already exists, where changing which
        // source answers would re-tune machinery that works.
        extraCrls.addAll(cz.digitalnikontinuita.anep.core.ReliedUponRevocation.crlBytes(
                lastValidationResult != null ? lastValidationResult.getDiagnosticData() : null));
        final java.util.List<byte[]> finalExtraCrls = extraCrls;

        // Situation 44 is the same question asked once: the verdict rests on a revocation that
        // precedes the signature. It decides whether preservation may answer from the document's
        // own OCSP responses instead of going online — there the embedded response says "good" and
        // the evidence for the verdict is only out on the network, so it has to be fetched and
        // embedded. Anywhere else the embedded response is the whole answer and fetching a current
        // revocation list adds megabytes without adding proof.
        final boolean revocationDecisive = lastResult.stream()
                .anyMatch(sig -> sig.getDetectedSituations().contains(44));

        DigestAlgorithm primaryAlg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());

        final boolean fromBaseline = notAnchored;
        final java.util.Set<String> verbatim = verbatimSigIds;
        final RestampingService restampSvc = restampingService;
        final eu.europa.esig.dss.diagnostic.DiagnosticData ddForVerbatim =
                lastValidationResult != null ? lastValidationResult.getDiagnosticData() : null;
        final ActionRecord record = beginRecord(mode.action, profile);

        Task<TimestampingService.TimestampResult> task = new Task<>() {
            @Override
            protected TimestampingService.TimestampResult call() {
                record.input = ActionRecord.FileFact.of(inputFile);
                // A signature with no time-stamp needs two of them, and this path stamps once per
                // round, so it runs twice. The first stamp is what the level T requires; only then
                // does validation data become worth embedding, because it can at last speak about a
                // moment the signature is anchored to. The second stamp is the archive one, and it
                // covers that data — EN 319 142-1 clause 6.3 requirement x) puts the material in
                // before the document time-stamp, which is exactly this order.
                //
                // DSS does the same thing for the other formats inside a single extendDocument()
                // call, fetching two time-stamps a second apart. Here the two rounds are visible
                // because the PDF path is hand-built; the result is the same, B-LTA in one click.
                DSSDocument augmented;
                if (fromBaseline) {
                    DSSDocument anchored = tsaSvc.addTimestamp(
                            new eu.europa.esig.dss.model.FileDocument(inputFile), pwd, profile,
                            primaryAlg).doc();
                    augmented = augSvc.augment(anchored, pwd, finalExtraCrls, revocationDecisive);
                } else {
                    augmented = augSvc.augment(inputFile, pwd, finalExtraCrls, revocationDecisive);
                }
                // Druhá polovina hromádky: materiál, který ověření vidělo, pro podpisy, jejichž
                // verdikt stojí na volnějším čtení — od 17. 10. 2026 jen ten, který o certifikátu
                // něco vypovídá (RestampingService.speaks). Post-expirační „nerevokován“ bez
                // závazku vydavatele se nevkládá: CzechPOINT si ho nebere z dokumentu, Acrobat ho
                // odmítá a standardy ho nepočítají (měření viz RestampingService). Zbývá údaj od
                // vydavatele s mezitím prošlým certifikátem a revokace — to přísná augmentace
                // zahazuje, a dokument by bez toho vyšel z úkonu chudší.
                //
                // Pořadí je měřené, ne odhadnuté: přísně, pak doslova. Obojí projde (UnionMaterialProbe,
                // čtyři dokumenty, osm průchodů, ani jedna změna verdiktu), ale opačné pořadí vyrábí
                // výrazně větší soubory — u pet podpisu LT.pdf 6,29 MB proti 3,55 MB.
                //
                // Razítko je jedno a přijde až za tím vším, takže z toho není druhé kolo: EN 319 142-1
                // požadavek x) chce materiál v dokumentu dřív než razítko dokumentu, a tohle je
                // přesně ten sled.
                if (!verbatim.isEmpty() && restampSvc != null && ddForVerbatim != null) {
                    augmented = restampSvc.embedAll(augmented, pwd, ddForVerbatim,
                            java.util.List.of(), verbatim).document();
                }
                // Asked here, between the validation data going in and the archive time-stamp being
                // fetched. Both halves of that are deliberate. By now the growth is an exact number
                // read off the finished document rather than an estimate — nothing has been written
                // to disk, so there is still a decision to make. And on the B-T path, which is the
                // common one, no time-stamp has been spent yet, so declining costs the user only a
                // download. (A signature with no time-stamp at all had to be anchored before the
                // validation data could say anything, so there one stamp is already gone.)
                if (!confirmPreservationGrowth(inputFile, augmented, pwd, mode)) {
                    throw new PreservationDeclined();
                }
                return tsaSvc.addTimestamp(augmented, pwd, profile, primaryAlg);
            }
        };

        task.setOnSucceeded(e -> {
            progress.setVisible(false);
            progress.setManaged(false);
            setAllActionButtonsDisabled(false);

            TimestampingService.TimestampResult result = task.getValue();
            DSSDocument preserved = result.doc();
            FileChooser chooser = new FileChooser();
            chooser.setTitle(mode.saveTitle());
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PDF dokumenty", "*.pdf"));
            String origName = inputFile.getName();
            String baseName = actionBaseName(origName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    ? origName.substring(0, origName.length() - 4) : origName);
            java.util.Date notAfter = rememberedNotAfter(result.tsaCertNotAfter());
            java.util.Date promised = promisedUntil(notAfter);
            chooser.setInitialFileName(baseName
                    + continuityMark(mode.action, promised, demoStamp()) + ".pdf");

            Stage stage = (Stage) preserveButton.getScene().getWindow();
            File dest = chooser.showSaveDialog(stage);
            if (dest == null) return;

            try (OutputStream os = Files.newOutputStream(dest.toPath())) {
                preserved.writeTo(os);
            } catch (Exception ex) {
                showError("Nepodařilo se uložit soubor", ex.getMessage());
                return;
            }
            finishRecord(record, dest, notAfter, promised, dest, null, pwd);
        });

        task.setOnFailed(e -> {
            progress.setVisible(false);
            progress.setManaged(false);
            setAllActionButtonsDisabled(false);
            Throwable ex = task.getException();
            // Declining the size increase is an answer, not a failure — the user has just read the
            // numbers and said no, so repeating them back as an error would be nonsense.
            if (ex instanceof PreservationDeclined) return;
            String revoked = revokedCertificateExplanation(ex,
                    lastResult.stream().anyMatch(SignatureSummary::isSeal));
            if (revoked != null) {
                // Not an error dialog: nothing went wrong, the answer is simply no.
                showNotice("Uchovat nelze", revoked);
                return;
            }
            showError(mode.failTitle(), ex != null ? ex.getMessage() : "Neznámá chyba");
        });

        Thread thread = new Thread(task, "preservation");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Přerazítkování — vloží revokační data, která o certifikátu něco vypovídají, a přidá časové
     * razítko.
     *
     * <p>The same two steps as the PDF preservation path and in the same order, which EN 319 142-1
     * clause 6.3 requirement x) prescribes: the material goes in first, the time-stamp covers it.
     * What differs is what counts as material — here, what the validation saw, filtered only by
     * {@code RestampingService.speaks} — and that no strict cleanup follows.
     *
     * <p>Never starts from an unanchored signature. Situations 31 and 32 gate the offer and both
     * require a time-stamp, so by the time this runs the document has one; the two-round dance
     * {@link #runPreservation()} performs for level B has no counterpart to do here.
     */
    /**
     * Datum do jména výsledku uchování a prodloužení: {@code _Uchovano_do_DD_MM_RRRR}, viz
     * {@link #continuityMark}. Obě cesty uchování (PDF ručně, ostatní formáty přes DSS) jsou dvě
     * poloviny jednoho tlačítka a datum počítají tady.
     *
     * <p>Datum je <b>skutečný horizont dokumentu po úkonu</b>, tedy pozdější z dvojice „dokdy
     * dokument vydrží dosud" a „dokdy platí certifikát vybraného zdroje razítek". Nové razítko
     * dosáhne jen tam, kam sahá certifikát autority, ale nezkracuje to, co dokument už má —
     * u balíčku platného do 2034 se zdrojem končícím 2029 by {@code _do_2029} tvrdilo zkrácení,
     * ke kterému nedošlo. Jméno má říkat, dokdy je dokument ověřitelný, ne co udělalo poslední
     * razítko.
     *
     * <p><b>Varianta ve jméně.</b> Do 7. 9. 2026 nesla značka holé datum, protože se všechny
     * cesty považovaly za jeden úkon. Nesou ale různý výsledek: uchovaný dokument ověří všechny
     * validátory, prodloužený jen ty podle standardů, přerazítkovaný naopak jen ty s vlastní
     * zásobou revokačních informací. Dva soubory s týmž jménem tak mohly být ověřitelné na
     * protilehlých okruzích nástrojů — a to je právě to, co jméno má říct.
     *
     * <p>Bez data zbývá slovo. Datum se nepodařilo přečíst a holé {@code _do_} by neřeklo nic,
     * jméno se ale od vstupního lišit musí — jinak by uložení mířilo rovnou na originál.
     *
     * <p>Samostatně, ne uvnitř skládání jména, od 4. 10. 2026: totéž datum čte záznam o úkonu
     * a srovnává ho s tím, co dá ověření výsledného souboru. Dvě kopie výpočtu by se mohly
     * rozejít a záznam by pak hlásil rozchod, který ve jméně není.
     */
    java.util.Date promisedUntil(java.util.Date tsaCertNotAfter) {
        java.time.Instant docUntil = documentVerifiableUntil(lastResult);
        if (tsaCertNotAfter != null && docUntil != null
                && docUntil.isAfter(tsaCertNotAfter.toInstant())) {
            return java.util.Date.from(docUntil);
        }
        return tsaCertNotAfter;
    }

    /**
     * Stav „před úkonem“ — zachycený v okamžiku stisku, z téhož výpočtu, který čtenář viděl
     * v záložce Digitální kontinuita. Později už nemusí platit: uživatel mezitím může otevřít
     * jiný soubor nebo výsledný soubor tlačítkem v záznamu.
     */
    ActionRecord beginRecord(ContinuityMode mode, TsaProfile profile) {
        java.util.List<ActionRecord.ElementState> before = lastElements.stream()
                .map(ActionRecord.ElementState::of).toList();
        ActionRecord record = new ActionRecord(mode, profile != null ? profile.getName() : null,
                profile != null ? profile.getQuality() : null, before,
                documentVerifiableUntil(lastResult));
        record.beforeRevocations = ActionRecord.revocations(lastValidationResult != null
                ? lastValidationResult.getDiagnosticData() : null);
        record.beforeStamps = ActionRecord.stamps(lastValidationResult != null
                ? lastValidationResult.getDiagnosticData() : null, cryptoPolicy());
        record.inputFormat = formatsWords();
        record.fromBaseline = notAnchored;
        record.tsaProfile = profile;
        return record;
    }

    /**
     * Formát a úroveň právě zobrazeného souboru jednou řádkou — pro oddíl Soubory v záznamu
     * o úkonu. Jména formátů jsou tatáž jako v řádku „Formát" ({@link #formatLabel}), takže
     * PKCS #7 i tady zůstává bez úrovně. Podpisy s různou úrovní se vyjmenují s počtem; razítka
     * se počítají jen v souboru bez podpisů, jinak by úroveň podpisu přehlušila.
     */
    String formatsWords() {
        if (lastResult == null || lastResult.isEmpty()) return null;
        List<SignatureSummary> sigs = lastResult.stream()
                .filter(sig -> !"TimeStampToken".equals(sig.getFormat())).toList();
        if (sigs.isEmpty()) sigs = lastResult;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (SignatureSummary sig : sigs) {
            String label = formatLabel(sig.getFormat());
            counts.merge(label != null ? label : "neznámý formát", 1, Integer::sum);
        }
        String formats = counts.size() == 1 ? counts.keySet().iterator().next()
                : counts.entrySet().stream().map(e -> e.getValue() + "× " + e.getKey())
                        .collect(Collectors.joining(", "));
        if (asicContainer) {
            return "kontejner " + (asicContainerKind != null ? asicContainerKind : "ASiC")
                    + "; " + formats;
        }
        return formats;
    }

    /**
     * Uložený výsledek: doplní do záznamu výsledný soubor, nechá ho ověřit na pozadí a pak otevře
     * okno se záznamem. Hlavní okno přitom dál ukazuje vstupní soubor.
     *
     * <p>Stav „po“ se čte ze skrytého pohledu, který výsledek vyhodnotil týmž kódem jako tenhle
     * ({@link #show} a {@code addContinuityBlock}) — tedy z téhož, co čtenář uvidí v záložkách,
     * až si výsledný soubor otevře.
     *
     * <p>Po dobu ověření zůstávají tlačítka úkonů zamčená a ukazatel průběhu běží: úkon není
     * hotový, dokud nevznikne záznam. Potom se odemknou. Pruh „úkon proveden“ nad záložkou se
     * zamčenými tlačítky tu krátce byl a 4. 10. 2026 na pokyn uživatele zase vypadl — cestu
     * k výslednému souboru nabízí okno záznamu.
     */
    private void finishRecord(ActionRecord record, File dest, java.util.Date tsaCertNotAfter,
                              java.util.Date promised, File verifyDocument, File verifyExternal,
                              char[] password) {
        record.performedAt = java.time.Instant.now();
        record.tsaCertNotAfter = tsaCertNotAfter;
        record.promisedUntil = promised;
        record.output = ActionRecord.FileFact.of(dest);
        if (resultVerifier == null) return;
        ResultVerifier verifier = resultVerifier;
        setAllActionButtonsDisabled(true);
        preserveProgress.setVisible(true);
        preserveProgress.setManaged(true);
        Runnable openResult = () -> verifier.open(verifyDocument, verifyExternal, password);
        verifier.verify(verifyDocument, verifyExternal, password, checked -> {
            preserveProgress.setVisible(false);
            preserveProgress.setManaged(false);
            checked.fillAfter(record);
            // Zjištěnou kvalifikovanost si zdroj zapamatuje (AppSettings.rememberTsaQuality).
            if (settings != null) settings.rememberTsaQuality(record.tsaProfile, record.tsaQuality);
            setAllActionButtonsDisabled(false);
            ActionRecordWindow.show(ownerWindow(), record, protocolExtras, openResult);
        }, ex -> {
            preserveProgress.setVisible(false);
            preserveProgress.setManaged(false);
            setAllActionButtonsDisabled(false);
            showError("Výsledný soubor se nepodařilo ověřit",
                    "Výsledný soubor " + dest.getName() + " je uložen, záznam o úkonu ale bez"
                    + " jeho ověření nevznikne. Soubor lze otevřít v AnEP a ověřit znovu.\n\n"
                    + (ex != null && ex.getMessage() != null ? ex.getMessage() : "Neznámá chyba"));
        });
    }

    /**
     * Politika ověřování kvůli její kryptografické sadě — odkud se bere, dokdy je algoritmus
     * razítka spolehlivý. Sada se ústupky nemění, takže nevadí, že nastavení může být v tu chvíli
     * už vrácené do výchozího stavu. {@code null}, když se postavit nedá.
     */
    private eu.europa.esig.dss.model.policy.ValidationPolicy cryptoPolicy() {
        if (settings == null) return null;
        try {
            return cz.digitalnikontinuita.anep.core.ValidationPolicyFactory.build(settings);
        } catch (Exception e) {
            return null;
        }
    }

    private javafx.stage.Window ownerWindow() {
        return preserveButton.getScene() != null ? preserveButton.getScene().getWindow() : null;
    }

    /**
     * Stav „po“ z právě zobrazeného výsledku. Přístupné z balíčku kvůli sondě
     * {@code ActionRecordProbe}, která ho měří na dvojicích souborů bez razítkovací služby.
     */
    void fillAfter(ActionRecord record) {
        record.after = lastElements.stream().map(ActionRecord.ElementState::of).toList();
        record.afterDocUntil = documentVerifiableUntil(lastResult);
        record.afterRevocations = ActionRecord.revocations(lastValidationResult != null
                ? lastValidationResult.getDiagnosticData() : null);
        record.afterStamps = ActionRecord.stamps(lastValidationResult != null
                ? lastValidationResult.getDiagnosticData() : null, cryptoPolicy());
        record.outputFormat = formatsWords();
        // Z prvků, ne z nastavení: to je v tuhle chvíli už vrácené do výchozího stavu
        // (resetValidationPolicy běží před onComplete), kdežto prvky nesou nastavení,
        // za kterého se ověřovalo.
        record.afterEnabledLenient = lastElements.stream()
                .filter(el -> el.dev() != null)
                .flatMap(el -> el.dev().enabledLenient().stream())
                .distinct().toList();
        record.afterCustomTime = continuityRun().customValidationTime();
        record.afterTrustListsOffline = lastValidationResult != null
                && lastValidationResult.isTrustListsOffline();
        if (record.tsaQuality == TsaProfile.Quality.UNKNOWN) {
            record.tsaQuality = addedStampQuality(record);
        }
    }

    /**
     * Kvalifikovanost zdroje podle razítka, které úkon právě přidal — nejmladšího z přidaných,
     * protože to je to, které vydal vybraný zdroj (u dvou razítek z úrovně B jsou od téhož
     * zdroje obě). Táž odpověď, kterou dává test zdroje v nastavení: DSS posoudí razítko proti
     * důvěryhodným seznamům a kvalifikované je jen razítko služby v nich uvedené.
     *
     * <p>Bez načtených seznamů „nevím" — nekvalifikovanost se z nezměřeného tvrdit nesmí (táž
     * pojistka jako v {@code TimestampingService.measureQuality}).
     *
     * <p>Doplněno 13. 10. 2026: zdroje založené před 18. 9. 2026, odkdy se kvalifikovanost měří
     * při zadání, ji měly nezjištěnou napořád a záznam to psal i u zdroje Sectigo Qualified.
     */
    private TsaProfile.Quality addedStampQuality(ActionRecord record) {
        if (record.afterTrustListsOffline || lastResult == null) return TsaProfile.Quality.UNKNOWN;
        List<ActionRecord.StampFact> added = record.stampsAdded();
        if (added.isEmpty() || added.get(added.size() - 1).time() == null) {
            return TsaProfile.Quality.UNKNOWN;
        }
        java.time.Instant when = added.get(added.size() - 1).time();
        return lastResult.stream()
                .flatMap(sig -> sig.getAllTimestamps().stream())
                .filter(t -> when.equals(t.time()))
                .findFirst()
                .map(t -> t.qualified() ? TsaProfile.Quality.QUALIFIED
                        : TsaProfile.Quality.UNQUALIFIED)
                .orElse(TsaProfile.Quality.UNKNOWN);
    }

    /**
     * Jméno tlačítka, které pro tenhle dokument svítí — pro texty, které k němu čtenáře posílají.
     *
     * <p>Brány se vylučují, takže platí právě jedna; když neplatí žádná, vrací se sloveso
     * uchování, protože text, který se na tlačítko odvolává, se staví jen tam, kde tlačítko je.
     */
    static String actionVerb(ContinuityElementBlocks.Gates gates) {
        if (gates.restamp()) return ContinuityMode.RESTAMP.verb;
        if (gates.prolong()) return ContinuityMode.PROLONG.verb;
        return ContinuityMode.PRESERVE.verb;
    }

    /**
     * Jméno varianty doprostřed věty („nabízí možnost <b>uchování</b>“) — z {@code ContinuityMode},
     * ne opisem, aby se nadpis bloku nemohl rozejít s tlačítkem dole.
     *
     * <p>Všechna tři jména jsou dějová podstatná jména na -í, takže druhý pád se tvarem shoduje
     * s prvním a stačí je zbavit velkého písmena.
     */
    static String actionNoun(ContinuityElementBlocks.Gates gates) {
        ContinuityMode mode = gates.restamp() ? ContinuityMode.RESTAMP
                : gates.prolong() ? ContinuityMode.PROLONG
                : ContinuityMode.PRESERVE;
        return mode.noun.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Totéž v 7. pádu — „s <b>uchováním</b> počkat“.
     *
     * <p>Všechna tři jména jsou dějová podstatná jména středního rodu na -í, u kterých se 7. pád
     * tvoří příponou -m: uchování → uchováním, prodloužení → prodloužením, přerazítkování →
     * přerazítkováním. Platí to pro celý {@code ContinuityMode}; přibude-li varianta jiného
     * tvaru, musí sem přijít vlastní tvar, ne další písmeno.
     */
    static String actionNounInstr(ContinuityElementBlocks.Gates gates) {
        return actionNoun(gates) + "m";
    }

    /**
     * Přípona jména pro soubor, jehož razítko <b>není kvalifikované</b>.
     *
     * <p>Zadání uživatele z 18. 9. 2026, a je to zadání dobré: takový soubor vzniká při zkoušení
     * nad testovací autoritou a od ostrého výsledku ho jinak nerozezná nic, co by bylo vidět ve
     * správci souborů. Datum ve značce přitom slibuje ověřitelnost do roku 2034 — u razítka,
     * které za důkaz existence v čase nikdo nevezme.
     *
     * <p>Stojí <b>za</b> datem, ne před ním: značka {@code _Uchovano_do_…} má zůstat, jak byla,
     * aby ji poznaly nástroje i oči zvyklé na dosavadní tvar, a výhrada se přidává k hotovému.
     */
    static final String DEMO_MARK = " (DEMO)";

    /**
     * Značka jedné varianty s datem, nebo bez data holé slovo. Tvar jména je na jednom místě pro
     * všechny tři cesty — jinak by se rozešly, jak se to už jednou stalo přerazítkování.
     */
    static String continuityMark(ContinuityMode mode, java.util.Date until) {
        return continuityMark(mode, until, false);
    }

    /**
     * @param demo razítko, kterým úkon skončil, není kvalifikované. Neptá se na to uživatele:
     *             plyne to z měření, které si aplikace pořídila při zadání zdroje
     *             ({@code TsaProfile.Quality}). Zdroj, u kterého se měřit nepodařilo, sem přichází
     *             jako {@code false} — jméno souboru není místo pro dohad.
     */
    static String continuityMark(ContinuityMode mode, java.util.Date until, boolean demo) {
        String suffix = demo ? DEMO_MARK : "";
        if (until == null) return mode.mark + suffix;
        java.time.LocalDate d = until.toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return String.format("%s_do_%02d_%02d_%04d%s", mode.mark,
                d.getDayOfMonth(), d.getMonthValue(), d.getYear(), suffix);
    }

    /**
     * Skončí úkon razítkem, které není kvalifikované?
     *
     * <p>Čte se z vybraného zdroje, a jen změřená odpověď se počítá: „nezjištěno" znamená, že se
     * do jména nepíše nic. Tvrdit nekvalifikovanost z nezdařeného měření by bylo horší než mlčet
     * — a v názvu souboru, který někam poputuje, obzvlášť.
     */
    private boolean demoStamp() {
        TsaProfile tsa = settings != null ? settings.getSelectedTsa() : null;
        return tsa != null && tsa.getQuality() == TsaProfile.Quality.UNQUALIFIED;
    }

    /**
     * The date part of a forensic package's name: {@code _Do_DD_MM_RRRR}, after the expiry of the
     * time-stamp's certificate — the day the package has to be renewed by, exactly as with the
     * other three actions.
     *
     * <p>No word before the date here, unlike {@code _Uchovano_do_} and its siblings: the
     * {@code FB_} prefix has already said what the file is, and saying it twice in one name earns
     * nothing. Empty when the certificate cannot be read, because a name is no place for a guess.
     */
    static String fixationSuffix(java.util.Date tsaCertNotAfter) {
        return fixationSuffix(tsaCertNotAfter, false);
    }

    /**
     * @param demo razítko nad balíčkem není kvalifikované — týž údaj a týž důvod jako
     *             u {@link #continuityMark}: balíček vzniklý při zkoušení se od ostrého jinak
     *             nepozná a datum ve jméně přitom říká, dokdy ho obnovit.
     *
     * <p><b>Bez data se značka nepřipisuje</b>, ačkoli u úkonů augmentace ano. Tam ji nese slovo
     * ({@code _Uchovano (DEMO)}), na které se {@code actionBaseName} zachytí a při dalším úkonu
     * ji odstraní. Tady by zbylo holé {@code FB_smlouva_pdf (DEMO)} — konec jména, který se nedá
     * odlišit od názvu, jaký si zvolil uživatel. Buď by se při zafixování balíčku podruhé vršilo
     * {@code (DEMO) (DEMO)}, nebo by pravidlo muselo ukrajovat „(DEMO)“ z konce každého jména,
     * a tím i ze souboru, který se tak jmenuje odjakživa. Datum je ta kotva, která obojí řeší.
     */
    static String fixationSuffix(java.util.Date tsaCertNotAfter, boolean demo) {
        if (tsaCertNotAfter == null) return "";
        java.time.LocalDate d = tsaCertNotAfter.toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return String.format("_Do_%02d_%02d_%04d%s",
                d.getDayOfMonth(), d.getMonthValue(), d.getYear(), demo ? DEMO_MARK : "");
    }

    /**
     * Typ zabaleného dokumentu v jméně balíčku: {@code dokument.pdf} → {@code _pdf}.
     *
     * <p>Balíček je vždy {@code .asice}, ať se do něj zabalí cokoli — bez tohohle kousku se typ
     * vstupu ze jména ztratí a z {@code dokument.pdf} i {@code dokument.asice} vzniknou dva
     * balíčky téhož jména. Tečka se mění na podtržítko, aby jméno mělo jednu příponu, a ne dvě.
     *
     * <p>Balíčku, který jde na vstup podruhé, se nepřidává nic: jméno typ původního dokumentu už
     * nese a {@code FB_dokument_pdf_asice_Do_…} by přidalo typ obalu místo typu obsahu. Týž
     * důvod, kvůli kterému nepřibývá podruhé předpona {@code FB_}.
     */
    static String fixationTypeMark(String origName) {
        if (origName == null || origName.startsWith("FB_")) return "";
        int dot = origName.lastIndexOf('.');
        if (dot <= 0 || dot == origName.length() - 1) return "";
        return "_" + origName.substring(dot + 1);
    }

    /**
     * Značka, kterou na konec jména připsala některá z akcí — a která se před připsáním nové
     * odstraní.
     *
     * <p>Všechny čtyři akce jsou opakovatelné: uchovaný dokument se uchová znovu, než certifikátu
     * jeho archivního razítka skončí platnost, a zafixovaný balíček se zafixuje znovu. Jméno se
     * proto potkává samo se sebou a bez tohoto kroku se značky vrší —
     * {@code X_Do_03_08_2034_Do_03_08_2034}. To není jen šum: první datum už neplatí, a jméno,
     * které má říct, dokdy je artefakt v pořádku, by uvádělo dvě odpovědi.
     *
     * <p>Nahrazuje se i značka po jiné akci. Soubor po přerazítkování, který se pak uchová, je
     * uchovaný dokument — a jméno má říkat, čím je teď; co všechno se s ním dělo, nese sám dokument
     * ve svých razítkách.
     */
    private static final java.util.regex.Pattern ACTION_MARK = java.util.regex.Pattern.compile(
            // Výčet nese značky ze všech dosavadních verzí, ne jen ty dnešní: soubory s nimi
            // existují a přijdou zpátky na vstup, protože všechny akce jsou opakovatelné.
            // Vyhodit z něj kteroukoli by znamenalo začít vrstvit X_Zachovano_do_…_Uchovano_do_…
            // Dnes aplikace píše Uchovano, Prodlouzeno a Prerazitkovano; Zachovano a Razitko
            // psala dřív, holé _Do_ mezi 30. 8. a 7. 9. 2026.
            // Značka „ (DEMO)" stojí až za datem, takže ji musí umět odstranit každá z větví —
            // jinak by se při druhém úkonu nad týmž souborem vršilo
            // X_Uchovano_do_… (DEMO)_Uchovano_do_… (DEMO). Je nepovinná i tam, kde se dnes
            // připisuje: soubor mohl vzniknout s kvalifikovaným razítkem a druhý úkon proběhnout
            // s testovacím, nebo naopak.
            "_(?:Zachovano|Uchovano|Prodlouzeno|Prerazitkovano|Razitko)"
            + "(?:_do_\\d{2}_\\d{2}_\\d{4})?(?: \\(DEMO\\))?$"
            + "|_Do_\\d{2}_\\d{2}_\\d{4}(?: \\(DEMO\\))?$");

    /**
     * Jméno bez přípony souboru, na kterém další akce staví: bez značek, které na konec připsaly
     * akce předchozí. Odstraňuje je dokola, aby uklidilo i po sobě — jména se dvěma značkami už
     * vznikla.
     */
    static String actionBaseName(String baseName) {
        String s = baseName;
        while (true) {
            java.util.regex.Matcher m = ACTION_MARK.matcher(s);
            if (!m.find()) return s;
            s = s.substring(0, m.start());
        }
    }

    /**
     * Totéž pro forenzní balíček, kde značka není jen na konci: {@code FB_} na začátku říká, co
     * soubor je, a zafixování ho připojuje samo. Zafixuje-li se balíček znovu — kontejner do
     * kontejneru — nesmí předpona přibýt podruhé.
     */
    static String fixationBaseName(String baseName) {
        String s = actionBaseName(baseName);
        while (s.startsWith("FB_")) s = s.substring(3);
        return s;
    }

    /**
     * Konec platnosti razítkovacího certifikátu, který se má vepsat do jména — a při té
     * příležitosti se v nastavení srovná zapamatovaný údaj se skutečností.
     *
     * <p>Přednost má to, co přišlo v razítku: je to čerstvé pozorování, kdežto zapamatovaná
     * hodnota je jen poslední známá. Když se z razítka přečíst nedá — některé autority
     * certifikát do tokenu nevkládají — nastoupí zapamatovaná, což je jediné místo, kde se
     * ty dvě hodnoty rozcházejí a kde tenhle záznam jménu souboru skutečně pomůže.
     */
    private java.util.Date rememberedNotAfter(java.util.Date fromStamp) {
        TsaProfile profile = settings != null ? settings.getSelectedTsa() : null;
        if (fromStamp != null) {
            if (settings != null) settings.rememberTsaCertNotAfter(profile, fromStamp);
            return fromStamp;
        }
        return profile != null ? profile.getCertNotAfter() : null;
    }

    /** The time-stamped document together with what the embedding round put into it. */
    private record RestampOutcome(TimestampingService.TimestampResult stamped,
                                  RestampingService.RestampReport report) {}

    private void runRestamping() {
        if (restampingService == null || timestampingService == null
                || settings == null || sourceFile == null || lastValidationResult == null) return;
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) return;

        setAllActionButtonsDisabled(true);
        preserveProgress.setVisible(true);
        preserveProgress.setManaged(true);

        File inputFile = sourceFile;
        char[] pwd = pdfPassword;
        RestampingService restampSvc = restampingService;
        TimestampingService tsaSvc = timestampingService;
        eu.europa.esig.dss.diagnostic.DiagnosticData dd = lastValidationResult.getDiagnosticData();

        // Both lists, because the offer stands on either situation. S33's bytes are the lists a
        // strict walk downloaded and S45's the ones only the looser reading accepts; a document can
        // have produced one set or the other, never both, and here they are treated alike — the
        // only filter is RestampingService.speaks, which drops post-expiry silence (17. 10. 2026).
        java.util.List<byte[]> extraCrls = new java.util.ArrayList<>();
        for (SignatureSummary sig : lastResult) {
            if (!isPdfSignature(sig.getFormat())) continue;
            extraCrls.addAll(sig.getS33CrlBytes());
            extraCrls.addAll(sig.getS45CrlBytes());
        }
        final java.util.List<byte[]> finalExtraCrls = extraCrls;

        DigestAlgorithm primaryAlg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());
        final ActionRecord record = beginRecord(ContinuityMode.RESTAMP, profile);

        Task<RestampOutcome> task = new Task<>() {
            @Override
            protected RestampOutcome call() {
                record.input = ActionRecord.FileFact.of(inputFile);
                RestampingService.Restamped embedded =
                        restampSvc.embedAll(inputFile, pwd, dd, finalExtraCrls);
                // An empty report is not a reason to stop. A document that already carries
                // everything is exactly the one whose re-stamping is the time-stamp alone — that
                // is what renewing a chain consists of, and withholding it here would cap how long
                // the document stays acceptable to the readers this serves.
                return new RestampOutcome(
                        tsaSvc.addTimestamp(embedded.document(), pwd, profile, primaryAlg),
                        embedded.report());
            }
        };

        task.setOnSucceeded(e -> {
            preserveProgress.setVisible(false);
            preserveProgress.setManaged(false);
            setAllActionButtonsDisabled(false);

            RestampOutcome outcome = task.getValue();
            TimestampingService.TimestampResult result = outcome.stamped();
            FileChooser chooser = new FileChooser();
            // What went in belongs where the user is about to decide whether to keep the result,
            // and it is the one number that matters: which material this round put into the
            // document. Anything more would be a dialog for its own sake.
            String added = outcome.report().describe();
            int withheld = outcome.report().withheld();
            String title = ContinuityMode.RESTAMP.saveTitle();
            chooser.setTitle(added != null
                    ? title + " (vloženo: " + added + ")"
                    : withheld > 0
                        ? title + " (revokační údaje se nevkládají — o certifikátu nic nedokládají)"
                        : title + " (revokační data už v dokumentu byla)");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PDF dokumenty", "*.pdf"));
            String origName = inputFile.getName();
            String baseName = actionBaseName(origName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    ? origName.substring(0, origName.length() - 4) : origName);
            // Značku sází táž metoda jako u obou zbylých cest — jen s jinou variantou. Dokud si ji
            // tahle cesta formátovala sama, rozešla se: jméno neslo holé datum i po sjednocení
            // ostatních.
            java.util.Date stampedUntil = rememberedNotAfter(result.tsaCertNotAfter());
            chooser.setInitialFileName(baseName
                    + continuityMark(ContinuityMode.RESTAMP, stampedUntil, demoStamp())
                    + ".pdf");
            if (inputFile.getParentFile() != null && inputFile.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(inputFile.getParentFile());
            }

            Stage stage = (Stage) preserveButton.getScene().getWindow();
            File dest = chooser.showSaveDialog(stage);
            if (dest == null) return;

            try (OutputStream os = Files.newOutputStream(dest.toPath())) {
                result.doc().writeTo(os);
            } catch (Exception ex) {
                showError("Nepodařilo se uložit soubor", ex.getMessage());
                return;
            }
            record.embedded = added != null ? added
                    : withheld > 0 ? "nic — dostupné revokační údaje o certifikátu nic nedokládají"
                    : "nic — revokační data už v dokumentu byla";
            record.withheldRevocations = withheld;
            finishRecord(record, dest, stampedUntil, stampedUntil, dest, null, pwd);
        });

        task.setOnFailed(e -> {
            preserveProgress.setVisible(false);
            preserveProgress.setManaged(false);
            setAllActionButtonsDisabled(false);
            Throwable ex = task.getException();
            showError(ContinuityMode.RESTAMP.failTitle(),
                    ex != null ? ex.getMessage() : "Neznámá chyba");
        });

        Thread thread = new Thread(task, "restamping");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Preservation for containers and for CAdES / XAdES / JAdES signatures, where DSS extends the
     * signature itself: validation data and archive time-stamp in a single call.
     *
     * <p>What is written out is the signature file, not the signed document. For a detached
     * signature the document is untouched — it is an input to the archive time-stamp, not a
     * product of it — so the user gets a new {@code .p7s} / {@code .xml} / {@code .json} and keeps
     * the original document as it was. The file name says so.
     */
    private void runExtension(PreserveMode mode) {
        TsaProfile profile = settings == null ? null : settings.getSelectedTsa();
        if (extensionService == null || profile == null || lastValidationResult == null) return;
        File signatureFile = signatureFile();
        if (signatureFile == null) return;

        ProgressIndicator progress = progressFor(mode);
        setAllActionButtonsDisabled(true);
        progress.setVisible(true);
        progress.setManaged(true);

        SignatureExtensionService svc = extensionService;
        ValidationResult result = lastValidationResult;
        File content = detachedContentFile();
        DigestAlgorithm primaryAlg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());

        final ActionRecord record = beginRecord(mode.action, profile);
        final char[] pwd = pdfPassword;

        Task<SignatureExtensionService.ExtensionResult> task = new Task<>() {
            @Override
            protected SignatureExtensionService.ExtensionResult call() {
                record.input = ActionRecord.FileFact.of(signatureFile);
                if (content != null) record.detachedContent = ActionRecord.FileFact.of(content);
                return svc.preserve(signatureFile, content, result, profile, primaryAlg);
            }
        };

        task.setOnSucceeded(e -> {
            progress.setVisible(false);
            progress.setManaged(false);
            setAllActionButtonsDisabled(false);

            SignatureExtensionService.ExtensionResult res = task.getValue();

            String origName = signatureFile.getName();
            int dot = origName.lastIndexOf('.');
            String baseName = actionBaseName(dot > 0 ? origName.substring(0, dot) : origName);
            String extension = dot > 0 ? origName.substring(dot) : "";
            java.util.Date notAfter = rememberedNotAfter(res.tsaCertNotAfter());
            java.util.Date promised = promisedUntil(notAfter);
            String suffix = continuityMark(mode.action, promised, demoStamp());

            FileChooser chooser = new FileChooser();
            chooser.setTitle(content != null
                    ? mode.saveTitle() + " (podepsaný dokument zůstává beze změny)"
                    : mode.saveTitle());
            if (!extension.isEmpty()) {
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                        "Stejný typ jako vstup (*" + extension + ")", "*" + extension));
            }
            chooser.setInitialFileName(baseName + suffix + extension);
            if (signatureFile.getParentFile() != null && signatureFile.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(signatureFile.getParentFile());
            }

            Stage stage = (Stage) preserveButton.getScene().getWindow();
            File dest = chooser.showSaveDialog(stage);
            if (dest == null) return;

            try (OutputStream os = Files.newOutputStream(dest.toPath())) {
                res.document().writeTo(os);
            } catch (Exception ex) {
                showError("Nepodařilo se uložit soubor", ex.getMessage());
                return;
            }
            // U odloučeného podpisu se ověřuje dvojice: nezměněný obsah a nový podpis.
            finishRecord(record, dest, notAfter, promised,
                    content != null ? content : dest, content != null ? dest : null, pwd);
        });

        task.setOnFailed(e -> {
            progress.setVisible(false);
            progress.setManaged(false);
            setAllActionButtonsDisabled(false);
            Throwable ex = task.getException();
            showError(mode.failTitle(),
                    ex != null ? ex.getMessage() : "Neznámá chyba");
        });

        Thread thread = new Thread(task, "preservation-extension");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Wraps the document, untouched, in a time-stamped ASiC container together with the material
     * the validation gathered. Unlike preservation this changes nothing about the document, so it
     * is offered even where preservation is refused.
     */
    private void runFixation() {
        if (fixationService == null || sourceFile == null || settings == null
                || lastValidationResult == null) return;
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) return;

        fixButton.setDisable(true);
        fixProgress.setVisible(true);
        fixProgress.setManaged(true);

        File inputFile = sourceFile;
        File secondary = secondaryFile;
        FixationService svc = fixationService;
        ValidationResult result = lastValidationResult;
        DigestAlgorithm alg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());

        Task<FixationService.FixationResult> task = new Task<>() {
            @Override
            protected FixationService.FixationResult call() {
                return svc.fix(inputFile, secondary, result, profile, alg);
            }
        };

        task.setOnSucceeded(e -> {
            fixProgress.setVisible(false);
            fixProgress.setManaged(false);
            fixButton.setDisable(false);

            FixationService.FixationResult res = task.getValue();

            FileChooser chooser = new FileChooser();
            chooser.setTitle("Uložit forenzní balíček");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Kontejnery ASiC-E", "*.asice"));
            String origName = inputFile.getName();
            int dot = origName.lastIndexOf('.');
            String baseName = dot > 0 ? origName.substring(0, dot) : origName;
            // Předpona na začátku, ne přípona na konci: v adresáři vedle originálu se tím balíčky
            // seřadí k sobě a poznají se, i když je jméno dlouhé a v seznamu se zprava odřízne.
            // Přípona "_zafixovano" už k tomu nic nepřidává, FB_ říká totéž a dřív.
            //
            // Datum na konci má týž význam jako u uchování, přerazítkování a prodloužení: do kdy
            // platí certifikát přidaného razítka, tedy dokdy je balíček potřeba obnovit. Bez slova
            // před ním, na rozdíl od těch tří — co je zač, říká už FB_ na začátku.
            //
            // Mezi jménem a datem stojí typ zabaleného dokumentu (_pdf, _asice, _zfo): balíček je
            // vždy .asice, takže jinak by se typ vstupu ze jména ztratil. Viz fixationTypeMark().
            chooser.setInitialFileName("FB_" + fixationBaseName(baseName)
                    + fixationTypeMark(origName)
                    + fixationSuffix(rememberedNotAfter(res.tsaCertNotAfter()), demoStamp())
                    + ".asice");
            if (inputFile.getParentFile() != null && inputFile.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(inputFile.getParentFile());
            }

            Stage stage = (Stage) fixButton.getScene().getWindow();
            File dest = chooser.showSaveDialog(stage);
            if (dest == null) return;

            try (OutputStream os = Files.newOutputStream(dest.toPath())) {
                res.container().writeTo(os);
            } catch (Exception ex) {
                showError("Nepodařilo se uložit forenzní balíček", ex.getMessage());
                return;
            }

            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle("Forenzní balíček");
            done.setHeaderText("Forenzní balíček byl vytvořen a opatřen časovým razítkem");
            done.setContentText("Obsahuje dokument beze změny, "
                    + res.revocations() + "x revokační informace a "
                    + res.certificates() + "x certifikát, protokoly ověření, popis a soupis"
                    + " s uvedením původu.\n\n"
                    + "Razítko dokládá, že tento materiál existoval v této podobě v tuto chvíli."
                    + " Nedokládá platnost podpisu.");
            done.initOwner(stage);
            done.showAndWait();
        });

        task.setOnFailed(e -> {
            fixProgress.setVisible(false);
            fixProgress.setManaged(false);
            fixButton.setDisable(false);
            Throwable ex = task.getException();
            showError("Zafixování selhalo", ex != null ? ex.getMessage() : "Neznámá chyba");
        });

        Thread thread = new Thread(task, "fixation");
        thread.setDaemon(true);
        thread.start();
    }

    private void setAllActionButtonsDisabled(boolean disabled) {
        preserveButton.setDisable(disabled);
        fixButton.setDisable(disabled);
    }

    /**
     * Growth beyond which preservation stops and asks. Set on the scale of what is being weighed:
     * an OCSP response is single-digit kilobytes and a time-stamp a few more, so an increase this
     * large is a CRL — the one thing preservation adds that a reader might not want at the price.
     */
    private static final long PRESERVE_ASK_ABOVE_BYTES = 200L * 1024;

    /** Unwinds the preservation task when the user declines the size increase. Not an error. */
    private static final class PreservationDeclined extends RuntimeException {
        PreservationDeclined() { super("Zachování ověřitelnosti zrušeno uživatelem"); }
    }

    /**
     * True when preservation may go ahead: either the document barely grew, or the user looked at
     * how much it grew and accepted it.
     *
     * <p>Fails open. When the size cannot be established the operation proceeds unasked, because a
     * dialog that cannot state a number has nothing to offer the reader.
     */
    private boolean confirmPreservationGrowth(File input, DSSDocument augmented, char[] pwd,
                                              PreserveMode mode) {
        long before = input.length();
        long after = documentSize(augmented);
        if (after < 0 || after - before <= PRESERVE_ASK_ABOVE_BYTES) return true;

        List<AugmentationService.EmbeddedCrl> added =
                AugmentationService.newlyEmbeddedCrls(input, augmented, pwd);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        final long finalAfter = after;
        Platform.runLater(() ->
                future.complete(askPreservationGrowth(before, finalAfter, added, mode)));
        try {
            // Generous compared with the time-stamp fallback question next door, which is a quick
            // yes/no about an algorithm. This one asks the reader to weigh sizes against permanence.
            return future.get(10, TimeUnit.MINUTES);
        } catch (Exception ex) {
            return false;
        }
    }

    /** Must run on the JavaFX application thread. */
    private boolean askPreservationGrowth(long before, long after,
                                          List<AugmentationService.EmbeddedCrl> added,
                                          PreserveMode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append(mode.noun()).append(" zvětší dokument z ")
          .append(ContainerInspector.formatSize(before))
          .append(" na ").append(ContainerInspector.formatSize(after))
          .append(" (+").append(ContainerInspector.formatSize(after - before)).append(").\n\n");
        for (AugmentationService.EmbeddedCrl crl : added) {
            sb.append("Vkládá se seznam CRL");
            if (crl.issuer() != null && !crl.issuer().isBlank()) {
                sb.append(" vydavatele ").append(crl.issuer());
            }
            sb.append(" o velikosti ").append(ContainerInspector.formatSize(crl.size())).append(".\n");
        }
        if (!added.isEmpty()) sb.append('\n');
        // Said up front so nobody goes hunting for a setting that would make this smaller. The
        // augmentation verifier already asks OCSP first and postProcessDss() drops the signing
        // certificate's CRL whenever an OCSP for it is present, so a CRL that survives to here is
        // a CRL there was no alternative to.
        sb.append("Menší varianta pro tento dokument není: " + mode.nounLower()
                + " dává přednost odpovědím OCSP,"
                + " které mají jednotky kilobajtů, a seznam CRL vkládá jen tam, kde OCSP použít"
                + " nelze.\n\n"
                + "Vložené bajty už z dokumentu nepůjdou odebrat — přírůstky se do PDF zapisují"
                + " připojením na konec, takže odstranění ze slovníku DSS soubor nezmenší.\n\n"
                + "K tomu ještě přibude archivní časové razítko, jednotky kilobajtů.");

        ButtonType preserve = new ButtonType(mode.verb(),
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Zrušit",
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(mode.noun());
        alert.setHeaderText("Dokument výrazně naroste");
        alert.getButtonTypes().setAll(preserve, cancel);
        Label content = new Label(sb.toString());
        content.setWrapText(true);
        content.setMaxWidth(430);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(480);
        return alert.showAndWait().orElse(cancel) == preserve;
    }

    /** Byte length of a document, or -1 when it cannot be read. */
    private static long documentSize(DSSDocument doc) {
        try (java.io.InputStream is = doc.openStream()) {
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = is.read(buf)) > 0) total += read;
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The Czech explanation for the one preservation failure that is not a fault but a rule, or
     * null when the failure is something else.
     *
     * <p>DSS refuses to add validation data and a time-stamp to a signature whose certificate is
     * revoked, and it is right to: EN 319 122-1 clause 6.3 requirement m) has the tokens carried in
     * a signature time-stamp created before the certificate was revoked or expired, and EN 319
     * 102-1 clause 4.3.3.4 NOTE 2 says the same. What reached the reader, though, was
     * {@code Revoked/Suspended certificate(s) detected} with a token identifier — a sentence that
     * names no certificate they have heard of and gives no reason. AnEP knows the reason: it has
     * just reported the signature itself as resting on a revocation.
     */
    static String revokedCertificateExplanation(Throwable ex, boolean seal) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) continue;
            if (!message.contains("Revoked/Suspended certificate")
                    && !message.contains("Certificate is revoked")) {
                continue;
            }
            return (seal ? "Tuto pečeť" : "Tento podpis") + " nelze uchovat: certifikát byl"
                    + " revokován dřív, než " + (seal ? "pečeť" : "podpis")
                    + " prokazatelně existoval.\n\n"
                    + "Zachování ověřitelnosti by k dokumentu doplnilo časové razítko vzniklé až po revokaci."
                    + " Norma to nepřipouští (ETSI EN 319 122-1, čl. 6.3 písm. m) a dokument by"
                    + " tím působil průkazněji, než jaký ve skutečnosti je — jeho ověření by se"
                    + " opíralo o razítko, které samo o platnosti v době podpisu nic neříká.\n\n"
                    + "Chcete-li dokument zachovat pro pozdější potřebu i s výsledkem ověření,"
                    + " použijte Vytvořit forenzní balíček: vznikne kontejner s dokumentem"
                    + " a jeho podklady, aniž"
                    + " se do samotného dokumentu zasahuje.";
        }
        return null;
    }

    /** Same shape as {@link #showError}, for an outcome that is a rule rather than a fault. */
    private void showNotice(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.getDialogPane().setPrefWidth(560);
            alert.showAndWait();
        });
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
