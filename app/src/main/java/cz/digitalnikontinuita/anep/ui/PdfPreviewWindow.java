package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.DocumentVersions;
import cz.digitalnikontinuita.anep.core.PdfSource;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Okno s náhledem obsahu dokumentu.
 *
 * <p>Etapy dvě až pět podle {@code docs/nahled-pdf-navrh.md}:
 *
 * <ul>
 *   <li><b>2</b> — okno, jedna stránka, listování;</li>
 *   <li><b>3</b> — <b>přepínač podob</b>: dnešní soubor proti tomu, co kryjí jednotlivé podpisy
 *       a razítka;</li>
 *   <li><b>4</b> — ukazatel stran s rozdílem a skok na ně, z okna i z bloku „Po podepsání se
 *       v dokumentu změnil obsah" v Analýze;</li>
 *   <li><b>5</b> — zvětšení a pruh miniatur.</li>
 * </ul>
 *
 * <h2>Podoba se otevírá z prefixu bajtů</h2>
 *
 * <p>Krytá revize není samostatný soubor: je to <b>prvních {@code n} bajtů</b> toho, který je na
 * disku, kde {@code n} vymezuje {@code /ByteRange} příslušného prvku. Takový prefix je úplné,
 * samostatně načtitelné PDF — obsahuje i {@code /Contents} a končí značkou konce té revize.
 * Otevírá se proto úplně stejně jako celý soubor, jen z kratšího pole bajtů.
 *
 * <p>Otevřená je vždy <b>jen jedna</b> podoba; při přepnutí se ta předchozí zavře a cache
 * vyprázdní. Držet obě znamená dva {@code PDDocument} a dvě sady rastrů, a paměť je tu to
 * omezení, ne chuť.
 *
 * <p><b>Jedno okno, a nové ověření je zavře.</b> Rozhodnutí uživatele z 18. 9. 2026. Další stisk
 * tlačítka vytáhne dopředu to stávající, nezaloží druhé. Nechat okno otevřené nad souborem, který
 * se právě přestal ověřovat, by znamenalo mít na obrazovce dvě tvrzení o dvou různých dokumentech,
 * aniž by to na kterémkoli z nich bylo napsané — proto {@link #close()} volá {@code MainView} před
 * každým novým ověřením a bylo-li okno otevřené, po dokončení je otevře znovu.
 *
 * <h2>Proč to nezamrzne</h2>
 *
 * <p><b>Změřeno 18. 9. 2026</b> ({@code PdfRenderCostProbe}, 214 souborů z {@code D:\tmp},
 * měřítko 1,5): vykreslení stránky trvá medián 32 ms a v nejhorším případě 454 ms, převod na PNG
 * dalších 33 resp. 105 ms. Medián by se na obslužném vlákně ztratil, nejhorší případ ne — půl
 * vteřiny zamrzlého okna je vidět. Vykresluje se proto na vlákně na pozadí.
 *
 * <p><b>Vlákna jsou dvě, ale do PDFBoxu smí vždy jen jedno.</b> Stránka a miniatury se o jedno
 * vlákno dělit nemohou: miniatur je tolik, kolik má dokument stran, a fronta plná miniatur by tu
 * jednu stránku, na kterou se čtenář dívá, odsunula za ně.
 *
 * <p>Sahat z obou na týž {@link PDDocument} ale <b>nelze</b>, a stálo to nález uživatele: v náhledu
 * měla vizualizace podpisu přeházená písmena („RNDr. Ing. Jiří Peterka“ → „RNdr. Lng. Jiví
 * P%terkb“), zatímco týž soubor vykreslený mimo aplikaci vyšel správně. Prefix byl přitom bajt po
 * bajtu shodný s originálem, takže ořezem to nebylo.
 *
 * <p>Změřeno {@code ConcurrentRenderProbe}: táž stránka z téhož dokumentu, vykreslená dvanáctkrát,
 * zatímco druhé vlákno vykresluje miniatury — <b>2 rastry z 12 vyšly jinak</b>. {@code PDDocument}
 * není bezpečný pro souběžné použití a písma jsou to první, co se z toho sype: kódy zůstanou, ale
 * sáhnou vedle.
 *
 * <p>Všechno, co se dokumentu dotkne, proto běží v {@link #pdfbox} jako v jednocestné závoře.
 * Dvě fronty zůstávají a mají smysl dál: požadavek na stránku počká nanejvýš na jednu rozdělanou
 * miniaturu (těch pár desítek milisekund), ne na celou frontu.
 *
 * <p>Rastr téže sbírky zabírá 3,2 MB na stránku (medián i nejhorší případ; návrh odhadoval 8,7 MB,
 * ale to je 150 DPI, kdežto tohle je 1,5násobek, tedy asi 108 DPI). Cache je proto omezená
 * <b>počtem</b>, ne velikostí: {@value #CACHE_PAGES} stránek je při téhle velikosti asi 26 MB
 * a pokryje listování tam a zpět. Miniatury mají cache vlastní — při {@value #THUMB_SCALE}
 * měřítka jsou asi stokrát menší a vytlačovat kvůli nim stránky by bylo obráceně.
 *
 * <p><b>Čím sbírka neposloužila:</b> 214 dokumentů má dohromady 644 stran, tedy zhruba tři na
 * dokument. Chování nad dokumentem o stovkách stran se z toho měření usoudit nedá; cache omezená
 * počtem, vykreslování jen viditelné stránky a virtualizovaný pruh miniatur ({@link ListView}
 * staví jen ty buňky, které jsou vidět) jsou opatření proti případu, který se nenaměřil.
 */
final class PdfPreviewWindow {

    /** Kolik vykreslených stránek se drží. Viz javadoc třídy — 3,2 MB za kus. */
    private static final int CACHE_PAGES = 8;

    /**
     * Měřítko vykreslení. 1,5 je asi 108 DPI, což se na obrazovce čte a podle měření stojí
     * medián 32 ms. Násobí se měřítkem displeje, jinak je rastr na obrazovce s vysokým rozlišením
     * rozmazaný — obrázek se totiž roztahuje na šířku okna.
     */
    private static final float BASE_SCALE = 1.5f;

    /** Měřítko miniatur. Stránka A4 z toho vyjde asi 120 px široká, tedy na pruh vlevo. */
    private static final float THUMB_SCALE = 0.2f;

    /** Meze zvětšení. Pod 25 % už není co poznat, nad 500 % to přestává být náhled. */
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 5.0;

    /** Nezlomitelná mezera. Ve zdrojáku by byla k nerozeznání od obyčejné, proto číslem. */
    private static final char NBSP = 160;

    /** Body PDF na obrazovkové body: PDF počítá v 1/72 palce, obrazovka v 1/96. */
    private static final double POINTS_TO_PIXELS = 96.0 / 72.0;

    private static PdfPreviewWindow open;

    private final Stage stage;
    private final ExecutorService renderer = daemon("pdf-preview-render");
    /** Druhé vlákno pro miniatury; viz javadoc třídy. */
    private final ExecutorService aside = daemon("pdf-preview-aside");

    private final Map<Integer, Image> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Image> eldest) {
            return size() > CACHE_PAGES;
        }
    };
    private final Map<Integer, Image> thumbs = new java.util.concurrent.ConcurrentHashMap<>();

    private final ImageView view = new ImageView();
    private final StackPane canvas = new StackPane(view);
    private final ScrollPane scroll = new ScrollPane(canvas);
    private final Label status = new Label();
    private final Button prev = new Button("◀");
    private final Button next = new Button("▶");
    private final Button toDifference = new Button("Na rozdíl ▸");

    private final Button zoomOut = new Button("−");
    private final Button zoomIn = new Button("+");
    private final ToggleButton fitWidth = new ToggleButton("Přizpůsobit šířce");
    private final Label zoomLabel = new Label();
    /** Políčko, které se objeví na místě údaje o měřítku, když se na něj klikne. */
    private final TextField zoomField = new TextField();
    private final StackPane zoomBox = new StackPane(zoomLabel, zoomField);


    private final ListView<Integer> thumbStrip = new ListView<>();

    /** Pruh s vysvětlením, proč je stránka prázdná; schovaný, dokud není co vysvětlovat. */
    private final Label unreadableText = new Label();
    private final Button openInSystem = new Button("Otevřít v přidružené aplikaci");
    private final HBox unreadableBar = new HBox(8, unreadableText, openInSystem);

    private final File file;
    private final char[] password;
    private final List<DocumentVersions.Version> versions;
    private final ComboBox<DocumentVersions.Version> versionBox = new ComboBox<>();
    /**
     * Strany, na kterých se některá krytá revize liší od dnešní podoby — sjednocení přes všechny
     * podoby, protože ukazatel má platit, ať se čtenář dívá na kteroukoli.
     */
    private final SortedSet<Integer> differing = new TreeSet<>();

    /**
     * Závora před PDFBoxem. Viz javadoc třídy — dvě vlákna nad jedním {@link PDDocument} kazí
     * vykreslený obraz, a nejdřív písma.
     */
    private final Object pdfbox = new Object();

    private PDDocument document;
    private PDFRenderer pdfRenderer;
    private int pageCount;
    private int pageIndex;
    private final float scale;
    private double zoom = 1.0;
    private double viewportWidth;
    /** Kolikátý pokus o otevření podoby běží; starší odpovědi se zahazují. */
    private int loadToken;
    /** Strana, na kterou se má skočit, až bude dokument otevřený. Od jedné; 0 = nikam. */
    private int wantedPage;
    /** Právě se přepisuje výběr v pruhu programem, ne uživatelem. */
    private boolean syncingStrip;

    // ------------------------------------------------------------------ vnější rozhraní

    static boolean isOpen() {
        return open != null && open.stage.isShowing();
    }

    /**
     * Otevře náhled nad souborem, nebo vytáhne dopředu ten, který už běží.
     *
     * <p>Ne-PDF sem nemá chodit — tlačítko se u nich neukazuje — ale kdyby přece, otevře se okno
     * s hláškou místo obrázku. Mlčet a nic neudělat by vypadalo jako zaseklé tlačítko.
     */
    static void open(Window owner, File file, char[] password,
                     List<DocumentVersions.Version> versions) {
        open(owner, file, password, versions, 0, false);
    }

    /**
     * Otevře náhled a rovnou zamíří, kam je potřeba.
     *
     * <p>Odtud vede odkaz z bloku v Analýze: klik na číslo strany otevře <b>tu podobu, kterou
     * kryje podpis</b>, a na té straně. Je to ta polovina srovnání, kterou čtenář jinde nevidí —
     * dnešní podobu mu ukáže kterákoli prohlížečka, kdežto to, co bylo podepsáno, žádná.
     * Přepnout zpátky na dnešek je pak jedno kliknutí v nabídce vedle.
     *
     * @param page          strana od jedné; 0 znamená „nikam nemiř"
     * @param coveredFirst  zobrazit nejstarší podobu, která se na té straně liší, místo dnešní
     */
    static void open(Window owner, File file, char[] password,
                     List<DocumentVersions.Version> versions, int page, boolean coveredFirst) {
        if (isOpen()) {
            open.stage.toFront();
            open.stage.requestFocus();
            open.aim(page, coveredFirst);
            return;
        }
        open = new PdfPreviewWindow(owner, file, password, versions);
        open.aim(page, coveredFirst);
    }

    /**
     * Otevře náhled v <b>dnešní</b> podobě souboru na dané straně — i když už okno běží a ukazuje
     * podobu krytou podpisem. Odtud vede odkaz „Rychlý náhled … po změnách“ z bloku o změnách
     * po podpisu; {@link #open(Window, File, char[], List, int, boolean)} s {@code coveredFirst
     * = false} by v otevřeném okně podobu nepřepnul.
     */
    static void openCurrent(Window owner, File file, char[] password,
                            List<DocumentVersions.Version> versions, int page) {
        if (isOpen()) {
            open.stage.toFront();
            open.stage.requestFocus();
        } else {
            open = new PdfPreviewWindow(owner, file, password, versions);
        }
        open.aimCurrent(page);
    }

    static void close() {
        if (open != null) {
            open.dispose();
            open = null;
        }
    }

    /**
     * Přepne na žádanou podobu a stranu.
     *
     * <p>Na stranu se dá zamířit, ještě než se dokument otevře — proto se cíl schová do
     * {@link #wantedPage} a použije se, až bude na čem. Čekat na dokončení otevírání by znamenalo
     * buď blokovat, nebo psát další stav.
     */
    private void aim(int page, boolean coveredFirst) {
        DocumentVersions.Version target = coveredFirst ? coveredShowing(page) : null;
        if (target != null && target != versionBox.getValue()) {
            versionBox.getSelectionModel().select(target);
            switchTo(target);
        }
        if (page > 0) {
            wantedPage = page;
            if (document != null) goTo(page - 1);
        }
    }

    /** Přepne na dnešní podobu a zamíří na stranu. */
    private void aimCurrent(int page) {
        DocumentVersions.Version current = versions.stream()
                .filter(DocumentVersions.Version::current).findFirst().orElse(null);
        if (current != null && current != versionBox.getValue()) {
            versionBox.getSelectionModel().select(current);
            switchTo(current);
        }
        aim(page, false);
    }

    /** Nejstarší podoba, která se na té straně liší; {@code null}, když žádná. */
    private DocumentVersions.Version coveredShowing(int page) {
        for (DocumentVersions.Version v : versions) {
            if (!v.current() && (page <= 0 || v.changedPages().contains(page))) return v;
        }
        return null;
    }

    // ------------------------------------------------------------------ okno

    private PdfPreviewWindow(Window owner, File file, char[] password,
                             List<DocumentVersions.Version> versions) {
        this.file = file;
        this.password = password;
        // Prázdný seznam sem chodit nemá, ale kdyby přece, zbude dnešní podoba a přepínač se
        // neukáže — totéž, co u dokumentu, kde není z čeho vybírat.
        this.versions = versions == null || versions.isEmpty()
                ? List.of(new DocumentVersions.Version("Dnešní podoba souboru", file.length(),
                        true, List.of()))
                : versions;
        stage = new Stage();
        stage.setTitle("Náhled — " + file.getName());
        if (owner != null) stage.initOwner(owner);
        // Bez modality: náhled má stát vedle Analýzy, ne místo ní. To je celý smysl vlastního
        // okna — dokument a verdikt na dvou monitorech vedle sebe.
        stage.setResizable(true);

        double outputScale = owner != null && owner.getScene() != null
                ? owner.getOutputScaleX() : 1.0;
        scale = (float) (BASE_SCALE * Math.max(1.0, outputScale));

        for (DocumentVersions.Version v : this.versions) differing.addAll(v.changedPages());

        buildCanvas();
        VBox root = new VBox(buildTopBar(), buildZoomBar(), buildUnreadableBar(), buildBody());
        stage.setScene(new Scene(root, 1060, 780));
        stage.setOnHidden(e -> {
            // Zavřít smí i uživatel křížkem, ne jen MainView. Úklid tedy visí na okně, ne na
            // tlačítku — jinak by po zavření zůstal otevřený dokument a běžící vlákno.
            dispose();
            if (open == this) open = null;
        });
        installKeys();

        stage.show();
        switchTo(versionBox.getValue());
    }

    private void buildCanvas() {
        view.setPreserveRatio(true);
        view.setSmooth(true);

        canvas.setStyle("-fx-background-color: #6e6e6e;");
        canvas.setPadding(new Insets(12));
        // Bez pevné šířky by se plátno smrsklo na obrázek a ten by se při zvětšení nedal
        // odrolovat doleva; tímhle drží aspoň šířku výřezu a obrázek zůstává uprostřed.
        canvas.setMinWidth(0);

        scroll.setFitToWidth(false);
        // Svislý posuvník je vidět vždy. Kdyby se objevoval až podle potřeby, zúžil by výřez
        // právě ve chvíli, kdy dorazí vykreslená stránka, a „Přizpůsobit šířce“ by měřítko
        // spočítalo dvakrát — jednou pro výřez bez posuvníku, podruhé s ním (105 % → 104 %).
        // Odtud nestabilní PdfPreviewWindowTest.zoomingLeavesFitToWidth (4. 10. 2026).
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        scroll.setPannable(true);
        scroll.setStyle("-fx-background-color: transparent;");
        scroll.viewportBoundsProperty().addListener((obs, was, is) -> {
            viewportWidth = is.getWidth();
            canvas.setPrefWidth(viewportWidth);
            if (fitWidth.isSelected()) applyZoom();
        });

        // Ctrl + kolečko zvětšuje, jak je zvykem všude jinde; bez Ctrl se roluje.
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown()) return;
            zoomBy(e.getDeltaY() > 0 ? 1.1 : 1 / 1.1);
            e.consume();
        });
    }

    private HBox buildTopBar() {
        prev.setOnAction(e -> goTo(pageIndex - 1));
        next.setOnAction(e -> goTo(pageIndex + 1));
        status.setStyle("-fx-font-size: 12px;");

        toDifference.setVisible(!differing.isEmpty());
        toDifference.setManaged(!differing.isEmpty());
        toDifference.setOnAction(e -> goTo(nextIn(differing) - 1));
        javafx.scene.control.Tooltip.install(toDifference, new javafx.scene.control.Tooltip(
                "Skočit na další stranu, na které se obsah po podpisu liší"));

        versionBox.getItems().setAll(versions);
        versionBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(DocumentVersions.Version v) {
                return v == null ? "" : v.label();
            }

            @Override
            public DocumentVersions.Version fromString(String s) {
                return null;
            }
        });
        // Výchozí je dnešní soubor — to je ten, který má uživatel na disku. Rozdíl se neschovává
        // do nabídky, říká ho blok v Analýze; nabídka je to, čím si ho čtenář ověří.
        versionBox.getSelectionModel().select(versions.size() - 1);
        versionBox.setOnAction(e -> switchTo(versionBox.getValue()));
        boolean many = versions.size() > 1;
        versionBox.setVisible(many);
        versionBox.setManaged(many);

        HBox bar = new HBox(8, prev, next, toDifference, status);
        if (many) {
            bar.getChildren().addAll(grow(), new Label("Podoba:"), versionBox);
        }
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 4, 10));
        return bar;
    }

    private HBox buildZoomBar() {
        zoomOut.setOnAction(e -> zoomBy(1 / 1.25));
        zoomIn.setOnAction(e -> zoomBy(1.25));
        buildZoomEntry();

        // Náhled se otevírá na 100 % — skutečná velikost stránky (pokyn uživatele 4. 10. 2026).
        // Přizpůsobení šířce je volba na tlačítku a na Ctrl+0.
        fitWidth.setSelected(false);
        fitWidth.setOnAction(e -> applyZoom());

        HBox bar = new HBox(6, zoomOut, zoomBox, zoomIn, fitWidth);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 10, 6, 10));
        return bar;
    }

    /**
     * Pruh, který řekne, proč je stránka prázdná, a nabídne, co s tím.
     *
     * <p>Bez něj náhled mlčky ukáže bílou plochu a čtenář neví, jestli je dokument prázdný, nebo
     * se něco nepovedlo. Nález uživatele 21. 9. 2026 nad dokumentem Evropské komise: sken uložený
     * v JPEG 2000, ke kterému Java nemá čtečku, takže z celé stránky vyšlo 0,1 % nebílých bodů.
     *
     * <p>Skrytý je i z rozvržení ({@code setManaged}), ne jen neviditelný — jinak by nad
     * stránkou zůstal prázdný proužek u každého dokumentu.
     */
    private HBox buildUnreadableBar() {
        unreadableText.setWrapText(true);
        unreadableText.setStyle("-fx-font-size: 12px; -fx-text-fill: #7a4a00;");
        HBox.setHgrow(unreadableText, Priority.ALWAYS);
        openInSystem.setFocusTraversable(false);
        openInSystem.setOnAction(e -> UiDialogs.openFileInSystemViewer(file, stage));
        openInSystem.setTooltip(new Tooltip(
                "Otevře původní soubor v aplikaci, kterou má systém přidruženou k PDF."));
        unreadableBar.setAlignment(Pos.CENTER_LEFT);
        unreadableBar.setPadding(new Insets(6, 10, 8, 10));
        unreadableBar.setStyle("-fx-background-color: #fff4d6; -fx-border-color: #e0c37a;"
                + " -fx-border-width: 1 0 1 0;");
        unreadableBar.setVisible(false);
        unreadableBar.setManaged(false);
        return unreadableBar;
    }

    /**
     * Ukázat ten pruh nad právě zobrazenou stránkou, nebo ne?
     *
     * <p>Ptá se na stránku, ne na dokument: sken bývá i jen na některých stranách a věta mluví
     * o té, na kterou se čtenář dívá.
     */
    private void updateUnreadableBar(int index) {
        boolean show = document != null && unreadableImages(pageAt(index));
        if (show) {
            unreadableText.setText("Stránka obsahuje obrázek ve formátu JPEG 2000, který Java"
                    + " neumí rozbalit, takže z něj náhled nevykreslí nic. Dokument tím není"
                    + " poškozený — otevřete jej v prohlížeči PDF, který máte v systému"
                    + " nastavený (typicky Adobe Acrobat Reader).");
        }
        unreadableBar.setVisible(show);
        unreadableBar.setManaged(show);
    }

    private PDPage pageAt(int index) {
        synchronized (pdfbox) {
            if (document == null || index < 0 || index >= document.getNumberOfPages()) return null;
            return document.getPage(index);
        }
    }

    /**
     * Nese stránka obrázek, který vykreslování nepřečte?
     *
     * <p>Jediný takový případ je dnes JPEG 2000: PDFBox pro něj potřebuje čtečku {@code ImageIO},
     * kterou běhové prostředí Javy nemá, a přibalit ji nejde — knihovna {@code
     * jai-imageio-jpeg2000} stojí na licenci JJ2000, která omezuje obor užití a je neslučitelná
     * s GPL-3; z téhož důvodu ji nepřibaluje ani samo PDFBox. Ptáme se proto i na to, zda čtečka
     * náhodou registrovaná není — kdo si ji do prostředí přidá, nemá důvod vidět tuhle větu.
     *
     * <p>PDFBox tu výjimku jen zaloguje a kreslí dál, takže se na ni nedá počkat u vykreslování;
     * čte se proto rovnou to, co je ve zdrojích stránky. Prohlédnou se i formulářové objekty,
     * protože obrázek bývá zabalený v nich; hloubka je omezená kvůli dokumentům, které na sebe
     * odkazují dokola.
     */
    static boolean unreadableImages(PDPage page) {
        if (page == null) return false;
        if (javax.imageio.ImageIO.getImageReadersByFormatName("JPEG2000").hasNext()) return false;
        return hasJpxImage(page.getResources(), 0);
    }

    private static boolean hasJpxImage(PDResources res, int depth) {
        if (res == null || depth > 3) return false;
        for (COSName name : res.getXObjectNames()) {
            PDXObject xo;
            try {
                xo = res.getXObject(name);
            } catch (Exception e) {
                continue;   // nečitelný objekt sám o sobě nic netvrdí
            }
            if (xo instanceof PDImageXObject img) {
                if ("jpx".equals(img.getSuffix())) return true;
            } else if (xo instanceof PDFormXObject form
                    && hasJpxImage(form.getResources(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private HBox buildBody() {
        thumbStrip.setPrefWidth(160);
        thumbStrip.setMinWidth(160);
        thumbStrip.setFocusTraversable(false);
        thumbStrip.setCellFactory(list -> new ThumbCell());
        thumbStrip.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (!syncingStrip && is != null) goTo(is);
        });

        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        HBox body = new HBox(thumbStrip, scroll);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private void installKeys() {
        stage.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            // Psaní měřítka nesmí listovat stránkami ani zvětšovat. Esc si políčko obslouží samo
            // (zruší zadání), takže sem nedojde a okno se nezavře.
            if (zoomField.isFocused()) return;
            if (e.isControlDown()) {
                switch (e.getCode()) {
                    case PLUS, ADD, EQUALS -> { zoomBy(1.25); e.consume(); }
                    case MINUS, SUBTRACT -> { zoomBy(1 / 1.25); e.consume(); }
                    case DIGIT0, NUMPAD0 -> { fitWidth.setSelected(true); applyZoom(); e.consume(); }
                    default -> { }
                }
                return;
            }
            switch (e.getCode()) {
                case LEFT, PAGE_UP -> { goTo(pageIndex - 1); e.consume(); }
                case RIGHT, PAGE_DOWN, SPACE -> { goTo(pageIndex + 1); e.consume(); }
                case HOME -> { goTo(0); e.consume(); }
                case END -> { goTo(pageCount - 1); e.consume(); }

                case ESCAPE -> stage.close();
                default -> { }
            }
        });
    }

    // ------------------------------------------------------------------ podoby

    /**
     * Přepne na jinou podobu dokumentu.
     *
     * <p>Zavře tu dosavadní a vyprázdní cache: rastry patřily jinému dokumentu a ukázat je pod
     * novým popiskem by byla ta nejhorší možná chyba — čtenář se dívá právě proto, aby viděl
     * rozdíl. Totéž platí o miniaturách.
     */
    private void switchTo(DocumentVersions.Version version) {
        if (version == null) return;
        closeDocument();
        cache.clear();
        thumbs.clear();
        endZoomEntry();
        view.setImage(null);
        pageCount = 0;
        pageIndex = 0;
        thumbStrip.getItems().clear();
        load(version);
    }

    /**
     * Otevře dokument — na pozadí, protože i otevření může trvat (naměřeno 227 ms v nejhorším
     * případě) a okno už je vidět.
     */
    private void load(DocumentVersions.Version version) {
        status.setText("Otevírá se…");
        prev.setDisable(true);
        next.setDisable(true);
        final int token = ++loadToken;
        renderer.submit(() -> {
            PDDocument doc = null;
            String failure = null;
            try {
                doc = PdfSource.of(bytesOf(version)).open(password);
            } catch (Exception e) {
                failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final PDDocument opened = doc;
            final String error = failure;
            Platform.runLater(() -> {
                // Mezitím mohl uživatel přepnout jinam. Starší odpověď se zahodí i s dokumentem,
                // který otevřela — jinak by zůstal viset otevřený až do zavření okna.
                if (token != loadToken) {
                    closeQuietly(opened);
                    return;
                }
                if (opened == null) {
                    status.setText("Dokument se nepodařilo otevřít: " + error);
                    return;
                }
                document = opened;
                pdfRenderer = new PDFRenderer(opened);
                pageCount = opened.getNumberOfPages();
                List<Integer> pages = new java.util.ArrayList<>(pageCount);
                for (int i = 0; i < pageCount; i++) pages.add(i);
                thumbStrip.getItems().setAll(pages);
                int target = wantedPage > 0 && wantedPage <= pageCount ? wantedPage - 1 : 0;
                wantedPage = 0;
                goTo(target);
            });
        });
    }

    /**
     * Bajty vybrané podoby: celý soubor, nebo jeho prefix.
     *
     * <p>Prefix se čte do paměti, ne přes {@code RandomAccessRead} nad souborem: PDFBox by sice
     * uměl číst z výřezu, ale dokumenty téhle sbírky mají medián kolem 200 kB a starost s tím
     * by převážila nad úsporou.
     */
    private byte[] bytesOf(DocumentVersions.Version version) throws java.io.IOException {
        byte[] all = java.nio.file.Files.readAllBytes(file.toPath());
        long end = version.endOffset();
        if (version.current() || end <= 0 || end >= all.length) return all;
        return java.util.Arrays.copyOf(all, (int) end);
    }

    // ------------------------------------------------------------------ stránky

    private void goTo(int index) {
        if (document == null || index < 0 || index >= pageCount) return;
        pageIndex = index;
        prev.setDisable(index == 0);
        next.setDisable(index == pageCount - 1);
        // Ukazatel platí, ať se čtenář dívá na kteroukoli podobu: ptá se, zda se ta strana mezi
        // podobami liší, ne zda se liší právě zobrazená.
        boolean here = differing.contains(index + 1);
        status.setText("Strana " + (index + 1) + " z " + pageCount
                + (here ? "  •  na této straně se obsah po podpisu liší" : ""));
        status.setStyle(here ? "-fx-font-size: 12px; -fx-text-fill: #a15c00;"
                             : "-fx-font-size: 12px;");

        updateUnreadableBar(index);

        syncingStrip = true;
        thumbStrip.getSelectionModel().select(Integer.valueOf(index));
        thumbStrip.scrollTo(index);
        syncingStrip = false;

        Image cached = cache.get(index);
        if (cached != null) {
            show(cached);
            return;
        }
        // Předchozí obrázek zůstává, dokud nový nedorazí. Vyprázdnit ho by při listování blikalo
        // šedivou plochou, a to i tam, kde vykreslení trvá těch naměřených 32 ms.
        final int token = loadToken;
        renderer.submit(() -> {
            Image image = render(index, scale);
            Platform.runLater(() -> {
                if (image == null || token != loadToken) return;
                cache.put(index, image);
                if (pageIndex == index) show(image);
            });
        });
    }

    private void show(Image image) {
        view.setImage(image);
        applyZoom();
    }

    /**
     * Další z množiny za tou právě zobrazenou; od začátku, když už žádná další není.
     *
     * <p>Dokola schválně: stran s rozdílem bývá pár a tlačítko, které na poslední z nich přestane
     * reagovat, vypadá jako porouchané.
     */
    private int nextIn(SortedSet<Integer> pages) {
        for (Integer p : pages) {
            if (p > pageIndex + 1) return p;
        }
        return pages.isEmpty() ? pageIndex + 1 : pages.first();
    }

    // ------------------------------------------------------------------ zvětšení

    /**
     * Údaj o měřítku, na který se dá kliknout a zadat si vlastní hodnotu.
     *
     * <p>Zadání od uživatele (20. 9. 2026): tlačítka {@code −} a {@code +} skáčou po
     * pětadvaceti procentech a na „přesně sto procent" se jimi trefit nejde. Vstupní pole je
     * proto <b>na tomtéž místě</b>, kde stojí údaj — kliknutím se údaj v poli otevře k přepsání,
     * takže se nikam nechodí a lišta si nepřidává další ovládací prvek. Tak to dělají prohlížeče
     * i Acrobat a čtenář to nemusí hledat.
     *
     * <p>Obojí drží {@link StackPane} a vidět je vždy jen jedno. Kdyby políčko bylo v liště
     * pořád, byl by to nejnápadnější prvek lišty — a přitom se použije zřídka.
     *
     * <h2>Co se s napsanou hodnotou stane</h2>
     *
     * <ul>
     *   <li><b>Enter</b> a <b>ztráta zaměření</b> hodnotu uplatní. Kliknout jinam a čekat, že to
     *       platí, je běžné očekávání; zahodit to potichu by vypadalo jako porucha.</li>
     *   <li><b>Esc</b> zadání zruší a vrátí původní měřítko.</li>
     *   <li>Nesmysl ani prázdno hlášku nedostane — políčko se prostě zavře a měřítko zůstane.
     *       Dialog s chybou nad překlepem v jednom čísle je víc rámusu než užitku.</li>
     *   <li>Hodnota mimo meze se <b>ořízne</b> na {@link #MIN_ZOOM}–{@link #MAX_ZOOM} (25–500 %),
     *       stejně jako u tlačítek; napsat 5000 a dostat 500 je srozumitelnější než odmítnutí.</li>
     * </ul>
     */
    private void buildZoomEntry() {
        zoomLabel.setStyle("-fx-font-size: 12px;");
        zoomLabel.setMinWidth(52);
        zoomLabel.setAlignment(Pos.CENTER);
        // Aby bylo poznat, že to není jen popisek. Podtržení až pod myší: trvalé by z údaje
        // dělalo odkaz, a on jím není.
        zoomLabel.setCursor(javafx.scene.Cursor.HAND);
        zoomLabel.setTooltip(new Tooltip("Klikněte a zadejte měřítko v procentech"));
        zoomLabel.setOnMouseEntered(e -> zoomLabel.setStyle(
                "-fx-font-size: 12px; -fx-underline: true;"));
        zoomLabel.setOnMouseExited(e -> zoomLabel.setStyle("-fx-font-size: 12px;"));
        zoomLabel.setOnMouseClicked(e -> beginZoomEntry());

        zoomField.setStyle("-fx-font-size: 12px;");
        zoomField.setPrefWidth(52);
        zoomField.setMinWidth(52);
        zoomField.setAlignment(Pos.CENTER);
        zoomField.setVisible(false);
        zoomField.setOnAction(e -> commitZoomEntry());
        zoomField.focusedProperty().addListener((obs, was, is) -> {
            if (was && !is && zoomField.isVisible()) commitZoomEntry();
        });
        zoomField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                endZoomEntry();
                e.consume();
            }
        });
    }

    /** Otevře údaj k přepsání. Bez obrázku není co měnit — měřítko se ještě nespočítalo. */
    private void beginZoomEntry() {
        if (view.getImage() == null) return;
        zoomField.setText(String.valueOf(Math.round(zoom * 100)));
        zoomField.setVisible(true);
        zoomLabel.setVisible(false);
        zoomField.requestFocus();
        zoomField.selectAll();
    }

    private void commitZoomEntry() {
        Double percent = parsePercent(zoomField.getText());
        endZoomEntry();
        if (percent == null) return;
        // Ruční hodnota je rozhodnutí čtenáře — přizpůsobení šířce mu do něj od té chvíle nesmí
        // mluvit. Táž úvaha jako u tlačítek, viz zoomBy().
        fitWidth.setSelected(false);
        zoom = clamp(percent / 100.0);
        applyZoom();
    }

    private void endZoomEntry() {
        zoomField.setVisible(false);
        zoomLabel.setVisible(true);
    }

    /**
     * Procenta z toho, co čtenář napsal — nebo {@code null}, nedá-li se to přečíst.
     *
     * <p>Bere „100“, „100 %“, „100%“ i „12,5“: znak procenta patří k údaji vedle a čárka
     * je česká desetinná značka, takže obojí napíše kdekdo. Nezlomitelná mezera vypadá jako
     * obyčejná a z lišty se dá zkopírovat, takže padá do téže kategorie.
     */
    private static Double parsePercent(String text) {
        if (text == null) return null;
        String t = text.replace("%", "").replace(',', '.')
                .replace(NBSP, ' ').replace(" ", "").trim();
        if (t.isEmpty()) return null;
        try {
            double value = Double.parseDouble(t);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void zoomBy(double factor) {
        // Ruční zvětšení znamená, že se čtenář rozhodl sám; automatické přizpůsobení mu do toho
        // od té chvíle nesmí mluvit.
        fitWidth.setSelected(false);
        zoom = clamp(zoom * factor);
        applyZoom();
    }

    private static double clamp(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    /**
     * Nastaví šířku obrázku podle zvětšení, nebo podle výřezu.
     *
     * <p>Procenta se počítají vůči <b>přirozené velikosti stránky</b>, ne vůči rastru: rastr je
     * vykreslený v {@link #scale}, tedy zvětšený, a „150 %“ nad stránkou, která vypadá jako
     * v Acrobatu při sto procentech, by čtenáře mátlo.
     */
    private void applyZoom() {
        Image image = view.getImage();
        if (image == null) {
            zoomLabel.setText("");
            return;
        }
        double naturalWidth = image.getWidth() / scale * POINTS_TO_PIXELS;
        double target;
        if (fitWidth.isSelected() && viewportWidth > 0) {
            target = Math.max(60, viewportWidth - 40);
            zoom = clamp(target / naturalWidth);
            target = naturalWidth * zoom;
        } else {
            target = naturalWidth * zoom;
        }
        view.setFitWidth(target);
        canvas.setPrefWidth(Math.max(viewportWidth, target + 24));
        zoomLabel.setText(Math.round(zoom * 100) + " %");
    }

    // ------------------------------------------------------------------ vykreslování

    /**
     * Vykreslí stránku a udělá z ní {@link Image}.
     *
     * <p>Přes PNG, ne přes {@code SwingFXUtils}: ten je v modulu {@code javafx.swing}, který
     * aplikace v závislostech nemá, a cesta „rastr → PNG → Image“ už je v aplikaci vyšlapaná
     * u vizualizací podpisů. Stojí to podle měření medián 33 ms navíc.
     */
    private Image render(int index, float atScale) {
        BufferedImage page;
        // Jen vykreslení patří do závory; převod na PNG už s dokumentem nemá co do činění
        // a držet v ní i jeho by zbytečně zdrželo to druhé vlákno o naměřených 33 ms.
        synchronized (pdfbox) {
            PDFRenderer r = pdfRenderer;
            if (r == null) return null;
            try {
                page = r.renderImage(index, atScale);
            } catch (Throwable e) {
                return null;
            }
        }
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(page, "png", png);
            return new Image(new ByteArrayInputStream(png.toByteArray()));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Buňka pruhu miniatur.
     *
     * <p>Vykresluje se až tehdy, když se buňka objeví — {@link ListView} staví jen ty, které jsou
     * vidět, takže stostránkový dokument nevykreslí sto miniatur, nýbrž deset. Proto taky pruh
     * není obyčejný {@code VBox}: ten by je postavil všechny.
     */
    private final class ThumbCell extends ListCell<Integer> {
        private final ImageView thumb = new ImageView();
        private final Label caption = new Label();
        private final VBox box = new VBox(2, thumb, caption);

        ThumbCell() {
            thumb.setPreserveRatio(true);
            thumb.setFitWidth(120);
            box.setAlignment(Pos.CENTER);
            caption.setStyle("-fx-font-size: 11px;");
            setGraphic(box);
        }

        @Override
        protected void updateItem(Integer index, boolean empty) {
            super.updateItem(index, empty);
            if (empty || index == null) {
                setGraphic(null);
                return;
            }
            setGraphic(box);
            int page = index + 1;
            boolean diff = differing.contains(page);
            caption.setText(page + (diff ? "  •" : ""));
            caption.setStyle("-fx-font-size: 11px;"
                    + (diff ? " -fx-text-fill: #a15c00; -fx-font-weight: bold;" : ""));

            Image cached = thumbs.get(index);
            if (cached != null) {
                thumb.setImage(cached);
                return;
            }
            thumb.setImage(null);
            final int token = loadToken;
            aside.submit(() -> {
                Image image = render(index, THUMB_SCALE);
                if (image == null) return;
                thumbs.put(index, image);
                Platform.runLater(() -> {
                    // Buňka se mezitím mohla přesunout na jinou stranu — ListView je recykluje.
                    if (token == loadToken && index.equals(getItem())) thumb.setImage(image);
                });
            });
        }
    }

    // ------------------------------------------------------------------ úklid

    private void dispose() {
        renderer.shutdownNow();
        aside.shutdownNow();
        cache.clear();
        thumbs.clear();
        closeDocument();
        if (stage.isShowing()) stage.close();
    }

    private void closeDocument() {
        // I zavření patří do závory: zavřít dokument pod rukama vykreslujícího vlákna je táž
        // chyba jako vykreslovat z něj dvakrát, jen hlučnější.
        synchronized (pdfbox) {
            closeQuietly(document);
            document = null;
            pdfRenderer = null;
        }
    }

    private static void closeQuietly(PDDocument doc) {
        if (doc == null) return;
        try {
            doc.close();
        } catch (Exception ignored) {
            // Drží to nanejvýš paměť do konce běhu a uživateli by nepomohlo, kdyby se kvůli
            // tomu okno nezavřelo.
        }
    }

    private static Region grow() {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return gap;
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }
}
