package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import cz.digitalnikontinuita.anep.core.PdfAttachmentInspector;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A window that shows what is inside a container — an ASiC or other ZIP, a CMS/PKCS #7 object,
 * an XML document — and lets the user walk it, look at any part, and take any part out.
 * <p>
 * The application opens the platform viewer for a PDF, because a PDF is a document a person can
 * read. A container is not: it is a folder in a single file, and the only way to answer "what
 * did I actually sign?" is to open it. That is what this window is for, and it deliberately shows
 * the container as stored — including entries no signature covers.
 * <p>
 * Windows are independent (not modal): a container inside a container opens beside its parent.
 */
public class ContainerView {

    private static final String MONO = "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 11px;";
    private static final int PREVIEW_TEXT_LIMIT = 400_000;
    /** Above this, XML is previewed as it is stored rather than re-formatted; see updatePreview. */
    private static final int MAX_PRETTY_PRINT_BYTES = 4 * 1024 * 1024;
    private static final int HEX_PREVIEW_BYTES = 8192;

    private final Stage stage = new Stage();
    private final Result result;
    private final String sourceName;

    private final TreeView<Node> tree = new TreeView<>();
    /** Cache behind {@link #badgeFor}; "" means "checked, nothing hidden inside". */
    private final Map<Node, String> nestedBadges = new java.util.IdentityHashMap<>();
    private final GridPane detailGrid = new GridPane();
    private final ScrollPane detailScroll = new ScrollPane(detailGrid);
    private final TextArea textPreview = new TextArea();
    private final ImageView imagePreview = new ImageView();
    private final ScrollPane imageScroll = new ScrollPane(imagePreview);
    private final Label previewPlaceholder = new Label();
    private final StackPane previewPane = new StackPane();
    private final Label previewTitle = new Label();

    private final Button saveOneBtn = new Button("Uložit prvek…");
    private final Button openExternalBtn = new Button("Otevřít v aplikaci");
    private final Button openNestedBtn = new Button("Otevřít jako kontejner");
    private final Button openInAnepBtn = new Button("Ověřit v AnEP");
    private final Button copyHashBtn = new Button("Kopírovat SHA-256");

    /**
     * Set once by {@link MainView}: hands an element back to the application so it can be
     * verified in its own right. An attachment is very often a signed document itself, and
     * getting to it should not mean saving it to disk and dropping it back in by hand.
     */
    private static java.util.function.BiConsumer<byte[], String> openInAnepHandler;

    public static void setOpenInAnepHandler(java.util.function.BiConsumer<byte[], String> handler) {
        openInAnepHandler = handler;
    }

    /**
     * Vysvětlení nad stromem, proč se okno otevřelo místo výsledku ověření; {@code null}, když
     * si prohlížeč vyžádal uživatel sám. Viz {@link #openRejectedArchive}.
     */
    private final String notice;
    /** Akce tlačítka „Forenzní balíček…“, nebo {@code null}, když se tlačítko nenabízí. */
    private final Runnable forensicPackageAction;

    private ContainerView(Window owner, Result result, String sourceName) {
        this(owner, result, sourceName, null, null);
    }

    private ContainerView(Window owner, Result result, String sourceName,
                          String notice, Runnable forensicPackageAction) {
        this.result = result;
        this.sourceName = sourceName;
        this.notice = notice;
        this.forensicPackageAction = forensicPackageAction;
        buildUi(owner);
    }

    /**
     * Otevře prohlížeč nad obyčejným archivem ZIP, který DSS odmítla jako nepodporovaný formát.
     *
     * <p>Archiv, který není kontejnerem ASiC, nemá podpis, který by šlo ověřit — ale soubory
     * v něm ho mít mohou, a odborník takové archivy dostává běžně (smlouva s přílohami poslaná
     * zabalená). Do 15. 10. 2026 končil takový soubor jen nabídkou forenzního balíčku, ačkoli
     * prohlížeč archiv přečíst uměl a jednotlivé prvky předat k ověření tlačítkem „Ověřit
     * v AnEP“; nesouhlasilo to ani s manuálem, který slibuje čtení i prostých souborů .zip.
     * Forenzní balíček zůstává jako tlačítko: archiv jako celek je pořád neověřený doklad.
     *
     * <p>Jen pro archiv bez jiného určení. Nepodepsaný dokument ODF či OOXML je také ZIP,
     * ale čtenář v něm hledá dokument, ne soubory k ověření — ten dál dostane rovnou balíček.
     *
     * @return {@code false}, když soubor není obyčejný archiv nebo v něm není co ukázat
     *         (prázdný či poškozený) — volající pak nabídne forenzní balíček jako dřív
     */
    public static boolean openRejectedArchive(Window owner, File file, Runnable forensicPackage) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            return false;
        }
        if (ContainerInspector.detectKind(bytes) != ContainerInspector.Kind.ZIP) return false;
        Result result = ContainerInspector.inspect(bytes, file.getName());
        if (!"archiv ZIP".equals(result.typeLabel()) || !result.isBrowsable()) return false;
        String notice = "Soubor je obyčejný archiv ZIP, nikoli kontejner podpisu (ASiC), takže"
                + " jako celek ověřit nelze. Podepsané mohou být soubory v něm: vyberte prvek"
                + " a tlačítkem „Ověřit v AnEP“ jej předejte k ověření. Archiv jako celek můžete"
                + " zajistit časovým razítkem tlačítkem „Forenzní balíček…“.";
        Stage stage = new ContainerView(owner, result, file.getName(), notice, forensicPackage).stage;
        OPEN_WINDOWS.add(stage);
        stage.setOnHidden(e -> OPEN_WINDOWS.remove(stage));
        stage.show();
        return true;
    }

    /**
     * Opens the browser for {@code file}. Returns {@code false} when the file is not a container
     * at all, which lets the caller fall back to the platform viewer.
     */
    public static boolean open(Window owner, File file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            error("Soubor se nepodařilo načíst:\n" + e.getMessage());
            return false;
        }
        return open(owner, bytes, file.getName());
    }

    /** Opens the browser for bytes already in memory — used for a container nested in another. */
    public static boolean open(Window owner, byte[] bytes, String name) {
        return open(owner, bytes, name, true);
    }

    /**
     * @param explain whether to say why nothing opened. A reader who asked for the container view
     *                by name deserves the reason; one who double-clicked an element and is about
     *                to get the platform viewer instead does not — for them the message would be
     *                an error dialog standing in front of the thing they wanted, which then opens
     *                anyway the moment they dismiss it.
     */
    public static boolean open(Window owner, byte[] bytes, String name, boolean explain) {
        ContainerInspector.Kind kind = ContainerInspector.detectKind(bytes);
        if (!ContainerInspector.isContainerKind(kind)) return false;
        return show(owner, ContainerInspector.inspect(bytes, name), name, explain);
    }

    /**
     * Opens a PDF as what it is when it carries attachments: a container. The password is needed
     * only for the document the user submitted — attachments come out already decrypted.
     */
    public static boolean openPdfAttachments(Window owner, File file, char[] password) {
        try {
            return openPdfAttachments(owner, Files.readAllBytes(file.toPath()),
                    file.getName(), password);
        } catch (Exception e) {
            error("Soubor se nepodařilo načíst:\n" + e.getMessage());
            return false;
        }
    }

    public static boolean openPdfAttachments(Window owner, byte[] bytes, String name, char[] password) {
        return show(owner, PdfAttachmentInspector.inspect(bytes, name, password), name);
    }

    private static boolean show(Window owner, Result result, String name) {
        return show(owner, result, name, true);
    }

    private static boolean show(Window owner, Result result, String name, boolean explain) {
        if (result.error() != null && !result.isBrowsable()) {
            if (explain) error("Obsah souboru " + name + " se nepodařilo přečíst:\n" + result.error());
            return false;
        }
        Stage stage = new ContainerView(owner, result, name).stage;
        OPEN_WINDOWS.add(stage);
        stage.setOnHidden(e -> OPEN_WINDOWS.remove(stage));
        stage.show();
        return true;
    }

    /** Every browser window currently on screen, so {@link #closeAll()} can find them. */
    private static final java.util.Set<Stage> OPEN_WINDOWS = new java.util.LinkedHashSet<>();

    /**
     * Zavře všechny otevřené prohlížeče — volá se, když se začíná ověřovat další soubor.
     *
     * <p>Prohlížeč patří k dokumentu, který se právě přestal ověřovat. Nechat ho otevřený by
     * znamenalo mít vedle sebe přílohy jednoho souboru a verdikt o jiném, a po několika souborech
     * i několik prohlížečů, z nichž žádný neříká, ke kterému ověření patří. Stejně se zachází
     * s náhledem PDF ({@link PdfPreviewWindow#close()}).
     *
     * <p>Zavírá i okno, ze kterého přišel pokyn „Ověřit v AnEP“: vložený prvek je od té chvíle
     * ověřovaným souborem a přílohy jeho nadřazeného dokumentu k němu nepatří.
     */
    public static void closeAll() {
        for (Stage s : new java.util.ArrayList<>(OPEN_WINDOWS)) s.close();
        OPEN_WINDOWS.clear();
    }

    /** True when this file would open in the container browser rather than the platform viewer. */
    public static boolean isContainer(File file) {
        try (var in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(16);
            return ContainerInspector.isContainerKind(ContainerInspector.detectKind(head));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- layout

    private void buildUi(Window owner) {
        // For a data message the type label already says what it is, down to whether the copy is
        // the sender's or the recipient's — so the title says that too rather than repeating
        // "Obsah kontejneru" over a window that shows a message.
        stage.setTitle((isIsdsMessage() ? capitalized(stripFormatSuffix(result.typeLabel()))
                : "Obsah kontejneru") + " — " + sourceName);
        if (owner != null) stage.initOwner(owner);

        VBox header = new VBox(6);
        header.setPadding(new Insets(12, 12, 8, 12));
        Label title = new Label(sourceName);
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Label subtitle = new Label(result.typeLabel());
        subtitle.setStyle("-fx-text-fill: #555555;");
        header.getChildren().addAll(title, subtitle);
        if (notice != null) {
            Label info = new Label(notice);
            info.setWrapText(true);
            info.setPadding(new Insets(6, 8, 6, 8));
            info.setMaxWidth(Double.MAX_VALUE);
            info.setStyle("-fx-background-color: #e8f0fe; -fx-border-color: #9ab4e0; "
                    + "-fx-border-radius: 3; -fx-background-radius: 3; -fx-text-fill: #111111;");
            header.getChildren().add(info);
        }
        header.getChildren().add(summaryGrid());

        if (!result.warnings().isEmpty()) {
            header.getChildren().add(warningsBox());
        }
        if (result.error() != null) {
            Label err = new Label("Část obsahu se nepodařilo přečíst: " + result.error());
            err.setWrapText(true);
            err.setStyle("-fx-text-fill: #a00000;");
            header.getChildren().add(err);
        }

        SplitPane split = new SplitPane(treePane(), detailPane());
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox root = new VBox(header, new Separator(), split, buttonBar());

        Scene scene = new Scene(root, 1080, 720);
        stage.setScene(scene);
        selectFirstContentNode();
    }

    /**
     * The one field in a data message that reports a legal conclusion rather than a fact. ISDS
     * computes it from its own records alone, so it can be right about what the system did and
     * still be wrong about what happened — hence the mark beside it.
     */
    private static final String ACCEPTANCE_TIME_KEY = "Čas doručení";
    private static final String ACCEPTANCE_TIME_CAVEAT =
            "Jde o „názor stroje“, resp. o závěr, učiněný samotným systémem datových schránek, "
            + "který nemůže brát v úvahu věcný obsah datové zprávy ani další okolnosti "
            + "(např. zda bylo doručováno do správné datové schránky). Závěry právního posouzení "
            + "se mohou lišit, jak co do data a času, tak i co do skutečnosti, zda bylo či nebylo "
            + "řádně doručeno.";

    private GridPane summaryGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(3);
        int row = 0;
        for (Map.Entry<String, String> e : result.summary().entrySet()) {
            Label k = new Label(e.getKey() + ":");
            k.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");
            Label v = new Label(e.getValue());
            v.setStyle("-fx-font-size: 11px;");
            v.setWrapText(true);
            grid.add(k, 0, row);
            if (ACCEPTANCE_TIME_KEY.equals(e.getKey())) {
                // The value and the mark share a row so the mark reads as qualifying this time
                // and not the line under it; CENTER_LEFT keeps them on one baseline.
                HBox valueRow = new HBox(6, v, caveatMark());
                valueRow.setAlignment(Pos.CENTER_LEFT);
                grid.add(valueRow, 1, row);
            } else {
                grid.add(v, 1, row);
            }
            row++;
        }
        return grid;
    }

    /** Width the caveat is laid out at — a comfortable reading measure for a short paragraph. */
    private static final double CAVEAT_WIDTH = 460;

    /** Clickable ⚠ that opens {@link #ACCEPTANCE_TIME_CAVEAT}; also readable on hover. */
    private Label caveatMark() {
        Label mark = new Label("⚠");
        mark.setStyle("-fx-text-fill: #b06000; -fx-font-size: 15px; -fx-cursor: hand;");
        Tooltip tooltip = new Tooltip(ACCEPTANCE_TIME_CAVEAT);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(CAVEAT_WIDTH);
        Tooltip.install(mark, tooltip);
        mark.setOnMouseClicked(e -> showCaveat());
        return mark;
    }

    /**
     * The caveat as its own dialog.
     *
     * <p>An {@link Alert} takes its width from what its content asks for, and a {@code Label}
     * asks for however wide its text is on one line — a paragraph therefore produced a window
     * several screens across, with wrapping that never got a chance to happen. A text area laid
     * out at a stated width wraps inside that width instead, and carries the text away with
     * Ctrl+C, which the reader of a legal caveat is quite likely to want.
     */
    private void showCaveat() {
        TextArea body = ResultView.selectable(ACCEPTANCE_TIME_CAVEAT, "-fx-text-fill: #111111;");
        body.setPrefWidth(CAVEAT_WIDTH);
        body.setMaxWidth(CAVEAT_WIDTH);
        // The helper measures the wrapped text itself, but only once the text area has been laid
        // out — by which time the window has already settled on a height and the last line ends
        // up cut off. Measuring here, before anything is shown, is what the dialog sizes from;
        // the helper's later pass then arrives at the same answer.
        double height = wrappedTextHeight(ACCEPTANCE_TIME_CAVEAT, CAVEAT_WIDTH - 12);
        body.setPrefHeight(height);
        body.setMinHeight(height);

        VBox content = new VBox(body);
        content.setPadding(new Insets(2, 2, 2, 0));
        content.setPrefHeight(height);
        VBox.setVgrow(body, Priority.ALWAYS);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Čas doručení");
        alert.setHeaderText("Co znamená čas doručení uvedený v datové zprávě");
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(CAVEAT_WIDTH + 60);
        alert.setResizable(true);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    /** How tall {@code text} is once wrapped at {@code width}, with room for the text area's chrome. */
    private static double wrappedTextHeight(String text, double width) {
        javafx.scene.text.Text measurer = new javafx.scene.text.Text(text);
        measurer.setFont(javafx.scene.text.Font.font(13));
        measurer.setWrappingWidth(width);
        return measurer.getLayoutBounds().getHeight() + 14;
    }

    private VBox warningsBox() {
        VBox box = new VBox(2);
        box.setPadding(new Insets(6, 8, 6, 8));
        box.setStyle("-fx-background-color: #fff8e1; -fx-border-color: #e0c060; -fx-border-radius: 3; "
                + "-fx-background-radius: 3;");
        Label head = new Label(isIsdsMessage()
                ? "Poznámky k datové zprávě" : "Poznámky ke struktuře kontejneru");
        head.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        box.getChildren().add(head);
        for (String w : result.warnings()) {
            Label l = new Label("• " + w);
            l.setWrapText(true);
            l.setStyle("-fx-font-size: 11px;");
            box.getChildren().add(l);
        }
        return box;
    }

    private VBox treePane() {
        TextField filter = new TextField();
        filter.setPromptText("Filtr podle názvu…");
        filter.textProperty().addListener((o, old, text) -> rebuildTree(text));

        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new NodeCell());
        tree.getSelectionModel().selectedItemProperty().addListener((o, old, item) ->
                showDetail(item == null ? null : item.getValue()));
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Node n = selected();
                if (n != null && n.getData() != null) {
                    if (!openNested(n, false)) openExternally(n);
                }
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        rebuildTree("");

        VBox box = new VBox(6, filter, tree);
        box.setPadding(new Insets(8));
        return box;
    }

    private VBox detailPane() {
        detailGrid.setHgap(14);
        detailGrid.setVgap(3);
        // Popisek se nesmí zkracovat, hodnota se má zalamovat. Bez těchto dvou pravidel dělá
        // mřížka při zúžení okna pravý opak: ubírá šířku popisku, až z „ID datové zprávy“
        // zbude „ID datové zp…“, kdežto hodnota vedle něj se láme jen do toho, co zbylo.
        // Popisek je přitom to jediné, co řádek pojmenovává — zkrácený popisek u čitelné
        // hodnoty je horší než opačně. Nález uživatele nad datovou zprávou ISDS 16. 9. 2026.
        ColumnConstraints keys = new ColumnConstraints();
        keys.setHgrow(Priority.NEVER);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setFillWidth(true);
        // Bez dolní meze si sloupec drží šířku nejdelší nezalomené hodnoty a mřížka vyjede
        // z rámečku dřív, než se text stihne zalomit.
        values.setMinWidth(10);
        detailGrid.getColumnConstraints().setAll(keys, values);

        textPreview.setEditable(false);
        textPreview.setStyle(MONO);
        textPreview.setWrapText(false);

        imagePreview.setPreserveRatio(true);
        imageScroll.setFitToWidth(true);
        imageScroll.setFitToHeight(true);

        previewPlaceholder.setStyle("-fx-text-fill: #888888;");
        previewPlaceholder.setWrapText(true);

        previewPane.getChildren().addAll(textPreview, imageScroll, previewPlaceholder);
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        previewTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

        detailScroll.setFitToWidth(true);
        detailScroll.setMinHeight(120);
        detailScroll.setPrefHeight(190);

        VBox box = new VBox(8, detailScroll, previewTitle, previewPane);
        box.setPadding(new Insets(8));
        return box;
    }

    /**
     * Splits the right-hand pane between the field list and the preview.
     *
     * <p>Normally the preview takes whatever the fields leave over, because for a file the bytes
     * are the point. For an element that has no preview — the message envelope, whose whole
     * content is the fields — that share is empty space above a single explanatory line, so the
     * two swap: the fields take the pane and the preview keeps just its own line.
     */
    private void giveHeightToDetails(boolean toDetails) {
        VBox.setVgrow(detailScroll, toDetails ? Priority.ALWAYS : Priority.NEVER);
        VBox.setVgrow(previewPane, toDetails ? Priority.NEVER : Priority.ALWAYS);
        previewPane.setMaxHeight(toDetails ? Region.USE_PREF_SIZE : Double.MAX_VALUE);
    }

    /** Hides the preview heading rather than leaving a blank line where it would have been. */
    private void setPreviewTitle(String text) {
        previewTitle.setText(text);
        previewTitle.setVisible(!text.isEmpty());
        previewTitle.setManaged(!text.isEmpty());
    }

    private HBox buttonBar() {
        saveOneBtn.setOnAction(e -> saveSelected());
        openExternalBtn.setOnAction(e -> openExternally(selected()));
        openNestedBtn.setOnAction(e -> openNested(selected(), true));
        openInAnepBtn.setOnAction(e -> openInAnep(selected()));
        copyHashBtn.setOnAction(e -> copyHash());

        Button saveAllBtn = new Button("Uložit vše…");
        Tooltip.install(saveAllBtn, new Tooltip(
                "Rozbalí všechny prvky kontejneru do zvoleného adresáře se zachováním struktury."));
        saveAllBtn.setOnAction(e -> saveAll());

        // A data message is read for its písemnosti, not for its envelope and seal. Saving those
        // alone is the action a reader wants, and picking them out of "Uložit vše" by hand is a
        // chore — so it gets a button, present only when there is something to put in it.
        List<Node> attachments = collectAttachments(result.root(), new ArrayList<>());
        Button saveAttachmentsBtn = new Button("Uložit písemnosti…");
        Tooltip.install(saveAttachmentsBtn, new Tooltip(
                "Uloží všechny písemnosti datové zprávy do zvoleného adresáře pod názvy, "
                + "které jim dal odesílatel."));
        saveAttachmentsBtn.setOnAction(e -> saveAttachments(attachments));
        saveAttachmentsBtn.setVisible(!attachments.isEmpty());
        saveAttachmentsBtn.setManaged(!attachments.isEmpty());

        Tooltip.install(openExternalBtn, new Tooltip(
                "Uloží prvek do dočasného souboru a otevře jej v aplikaci přiřazené jeho typu."));
        Tooltip.install(openNestedBtn, new Tooltip(
                "Otevře vybraný prvek v novém okně jako samostatný kontejner — ať už jde o archiv, "
                + "objekt CMS, XML, nebo o PDF s vlastními přílohami."));
        Tooltip.install(openInAnepBtn, new Tooltip(
                "Předá vybraný prvek aplikaci k ověření, jako byste jej sami vložili do vstupního pole."));

        Button closeBtn = new Button("Zavřít");
        closeBtn.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, saveOneBtn, saveAttachmentsBtn, saveAllBtn, openExternalBtn,
                openNestedBtn, openInAnepBtn, copyHashBtn, spacer);
        if (forensicPackageAction != null) {
            Button packageBtn = new Button("Forenzní balíček…");
            Tooltip.install(packageBtn, new Tooltip(
                    "Zabalí archiv beze změny do kontejneru ASiC a opatří jej časovým razítkem."));
            packageBtn.setOnAction(e -> forensicPackageAction.run());
            bar.getChildren().add(packageBtn);
        }
        bar.getChildren().add(closeBtn);
        bar.setPadding(new Insets(8, 12, 12, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ---------------------------------------------------------------- tree

    private void rebuildTree(String filterText) {
        String needle = filterText == null ? "" : filterText.trim().toLowerCase(Locale.ROOT);
        TreeItem<Node> root = buildItem(result.root(), needle);
        tree.setRoot(root != null ? root : new TreeItem<>(result.root()));
        if (tree.getRoot() != null) tree.getRoot().setExpanded(true);
    }

    /** Keeps a node when it matches, or when any descendant does — an ancestor must stay visible. */
    private TreeItem<Node> buildItem(Node node, String needle) {
        List<TreeItem<Node>> kids = new ArrayList<>();
        for (Node child : node.getChildren()) {
            TreeItem<Node> item = buildItem(child, needle);
            if (item != null) kids.add(item);
        }
        // Obě jména. Kdo hledá podle toho, co vidí ve stromu, musí najít; kdo zná skutečné
        // jméno položky z jiného nástroje, taky.
        boolean matches = needle.isEmpty()
                || node.getName().toLowerCase(Locale.ROOT).contains(needle)
                || node.getDisplayName().toLowerCase(Locale.ROOT).contains(needle);
        if (!matches && kids.isEmpty()) return null;

        TreeItem<Node> item = new TreeItem<>(node);
        item.getChildren().setAll(kids);
        // A filter overrides the shut folders: with a needle typed, hiding a match inside a
        // collapsed node would look like the filter had found nothing.
        item.setExpanded(!needle.isEmpty()
                || (!node.isCollapsed() && node.getChildren().size() <= 30));
        return item;
    }

    /** True when this window is showing an ISDS data message rather than a plain container. */
    private boolean isIsdsMessage() {
        return result.kind() == ContainerInspector.Kind.ZFO;
    }

    /** Drops the "(ZFO)" and anything after it — a title bar has no room for the file format. */
    private static String stripFormatSuffix(String typeLabel) {
        int bracket = typeLabel.indexOf(" (ZFO)");
        return bracket < 0 ? typeLabel : typeLabel.substring(0, bracket);
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static List<Node> collectAttachments(Node node, List<Node> into) {
        for (Node child : node.getChildren()) {
            if (child.isAttachment() && child.getData() != null) into.add(child);
            collectAttachments(child, into);
        }
        return into;
    }

    /**
     * Opens on the signed payload rather than on whatever sorts first: the question a user
     * brings to a container is "what is the document in here?", and META-INF is not the answer.
     * For a data message the answer is different — the first thing a reader wants is who sent
     * what to whom, so it opens on the envelope, which is the first element of that tree.
     */
    private void selectFirstContentNode() {
        TreeItem<Node> root = tree.getRoot();
        if (root == null) return;
        TreeItem<Node> first = null;
        // A data message opens on the first document it carries — the první písemnost, or the
        // delivery history when there are none. Its own parts are behind a shut folder, and
        // selecting something the reader cannot see would leave the tree looking untouched
        // while the panes beside it filled up.
        if (isIsdsMessage()) {
            first = firstVisibleWithData(root);
        }
        for (int pass = 0; pass < 3 && first == null; pass++) {
            first = firstWithData(root, pass);
        }
        tree.getSelectionModel().select(first != null ? first
                : (root.getChildren().isEmpty() ? null : root.getChildren().get(0)));
    }

    /** First element carrying bytes that is actually on screen — folders left shut are skipped. */
    private TreeItem<Node> firstVisibleWithData(TreeItem<Node> item) {
        for (TreeItem<Node> child : item.getChildren()) {
            Node n = child.getValue();
            if (!n.isDirectory() && n.getData() != null) return child;
            if (!child.isExpanded()) continue;
            TreeItem<Node> deeper = firstVisibleWithData(child);
            if (deeper != null) return deeper;
        }
        return null;
    }

    /** Pass 0: signed payload. Pass 1: any payload. Pass 2: anything with bytes. */
    private TreeItem<Node> firstWithData(TreeItem<Node> item, int pass) {
        for (TreeItem<Node> child : item.getChildren()) {
            Node n = child.getValue();
            if (n.getData() != null && !n.isDirectory() && matchesPass(n, pass)) return child;
            TreeItem<Node> deeper = firstWithData(child, pass);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private static boolean matchesPass(Node node, int pass) {
        if (pass >= 2) return true;
        String role = node.getInfo().get("Role");
        boolean payload = role != null && role.startsWith("podep");
        if (pass == 1) return payload;
        String coverage = node.getInfo().get("Kryto podpisem");
        return payload && coverage != null && coverage.startsWith("ano");
    }

    /**
     * Renders name, role, whether a signature covers the entry, and — the point of the badge —
     * whether the entry hides more inside it. Without that, an attachment that is itself a
     * container looks like any other file and a user has no reason to try opening it.
     */
    private class NodeCell extends TreeCell<Node> {

        /**
         * Držen stranou, aby šlo přebarvit roli bez přestavby řádku.
         *
         * <p>{@code updateItem} se nevolá, když se změní výběr nebo zaměření stromu — a právě
         * tehdy se pod textem vymění pozadí za tmavě modré. Pevná šedá, která je na bílém
         * správně potlačená, na něm klesla na kontrast 2,15 : 1 a přestala být čitelná zrovna
         * na tom jediném řádku, na který se uživatel dívá.
         */
        private Label roleLabel;

        NodeCell() {
            selectedProperty().addListener((o, was, is) -> tintRole());
            tree.focusedProperty().addListener((o, was, is) -> tintRole());
        }

        @Override
        protected void updateItem(Node node, boolean empty) {
            super.updateItem(node, empty);
            roleLabel = null;
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            String coverage = node.getInfo().get("Kryto podpisem");
            boolean broken = coverage != null && coverage.contains("✘");
            String marker = node.isDirectory() ? "📁" : "📄";
            if (broken) marker = "⚠";
            else if (coverage != null && coverage.contains("✔")) marker = "🔏";

            Label nameLabel = new Label(marker + " " + node.getDisplayName());
            nameLabel.setStyle(broken ? "-fx-text-fill: #a00000;"
                    : node.isDirectory() ? "-fx-font-weight: bold;" : null);

            HBox row = new HBox(6, nameLabel);
            row.setAlignment(Pos.CENTER_LEFT);

            String role = node.getInfo().get("Role");
            if (role != null) {
                roleLabel = new Label("— " + role);
                tintRole();
                row.getChildren().add(roleLabel);
            }

            String badge = badgeFor(node);
            if (badge != null) {
                Label badgeLabel = new Label(badge);
                badgeLabel.setStyle("-fx-text-fill: #0a5ca8; -fx-font-size: 10px; "
                        + "-fx-background-color: #e2eefb; -fx-border-color: #9dc2e8; "
                        + "-fx-border-radius: 3; -fx-background-radius: 3; -fx-padding: 0 5 0 5;");
                row.getChildren().add(badgeLabel);
            }

            setText(null);
            setGraphic(row);

            StringBuilder tip = new StringBuilder(node.getPath().isEmpty() ? node.getName() : node.getPath());
            if (node.getSize() >= 0) tip.append("\n").append(ContainerInspector.formatSize(node.getSize()));
            if (coverage != null) tip.append("\nKryto podpisem: ").append(coverage);
            if (badge != null) tip.append("\nDvojklikem otevřete v novém okně.");
            setTooltip(new Tooltip(tip.toString()));
        }

        /**
         * Šedá na světlém pozadí, světlá na zvýrazněném — na každém z nich by ta druhá zmizela.
         *
         * <p>Zvýraznění nosí modrou jen tehdy, když strom má zaměření; bez něj zůstává světle
         * šedé a platí tam táž barva jako na bílé.
         */
        private void tintRole() {
            if (roleLabel == null) return;
            boolean onHighlight = isSelected() && tree.isFocused();
            roleLabel.setStyle(onHighlight ? "-fx-text-fill: #e8f0f5;" : "-fx-text-fill: #777777;");
        }
    }

    /**
     * Label for an element that opens into a window of its own, or {@code null}.
     * <p>
     * Deciding this means parsing the element, and for a PDF that is not free, so the answer is
     * cached and computed only for rows the user actually scrolls into view.
     */
    private String badgeFor(Node node) {
        if (node.isDirectory() || node.getData() == null) return null;
        String cached = nestedBadges.get(node);
        if (cached == null) {
            String badge = computeBadge(node.getData());
            cached = badge == null ? "" : badge;
            nestedBadges.put(node, cached);
        }
        return cached.isEmpty() ? null : cached;
    }

    private static String computeBadge(byte[] data) {
        return switch (ContainerInspector.detectKind(data)) {
            // XML and JSON are shown in full in the preview already — a badge there would only
            // add noise. These three are the cases where something is genuinely hidden inside.
            case PDF -> PdfAttachmentInspector.hasAttachments(data) ? "⧉ přílohy" : null;
            case ZIP -> "⧉ kontejner";
            case CMS -> ContainerInspector.isIsdsMessage(data) ? "⧉ datová zpráva ISDS" : "⧉ obálka CMS";
            default  -> null;
        };
    }

    private Node selected() {
        TreeItem<Node> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    // ---------------------------------------------------------------- detail + preview

    private void showDetail(Node node) {
        detailGrid.getChildren().clear();
        int[] row = {0};
        if (node == null) {
            saveOneBtn.setDisable(true);
            openExternalBtn.setDisable(true);
            openNestedBtn.setDisable(true);
            openInAnepBtn.setDisable(true);
            copyHashBtn.setDisable(true);
            showPreviewPlaceholder("Vyberte prvek v levém stromu.");
            setPreviewTitle("");
            giveHeightToDetails(false);
            return;
        }

        addDetail(row, "Název", node.getDisplayName());
        // Jen když se jména rozcházejí, tedy u validačních dat se strojovým jménem. Jinde by
        // to byl týž řádek dvakrát.
        if (!node.getDisplayName().equals(node.getName())) {
            addDetail(row, "Název v kontejneru", node.getName());
        }
        if (!node.getPath().isEmpty() && !node.getPath().equals(node.getName())) {
            addDetail(row, "Cesta v kontejneru", node.getPath());
        }
        if (node.getSize() >= 0) addDetail(row, "Velikost", ContainerInspector.formatSize(node.getSize()));
        if (node.getCompressedSize() >= 0) {
            String ratio = node.getSize() > 0
                    ? String.format(Locale.ROOT, " (%.0f %% původní velikosti)",
                            100.0 * node.getCompressedSize() / node.getSize())
                    : "";
            addDetail(row, "V archivu zabírá",
                    ContainerInspector.formatSize(node.getCompressedSize()) + ratio);
        }
        if (node.getMethod() != null) addDetail(row, "Komprese", node.getMethod());
        if (node.getCrc32() != null) {
            addDetail(row, "CRC-32", String.format("%08X", node.getCrc32()));
        }
        if (node.getModified() != null) {
            addDetail(row, "Změněno", ContainerInspector.formatDate(node.getModified()));
        }
        for (Map.Entry<String, String> e : node.getInfo().entrySet()) {
            String key = e.getKey();
            if (Node.isSeparatorKey(key)) {
                addDetailSeparator(row);
                continue;
            }
            String heading = Node.headingOf(key);
            if (heading != null) {
                addDetailHeading(row, heading);
                continue;
            }
            String label = Node.labelOf(key);
            // Táž výhrada jako v záhlaví zprávy: obálka nese týž údaj, a bez značky by tady
            // vypadal jako zjištění, které v záhlaví není (16. 10. 2026, pokyn uživatele).
            addDetail(row, label, e.getValue(),
                    isIsdsMessage() && ACCEPTANCE_TIME_KEY.equals(label) ? caveatMark() : null);
        }

        byte[] data = node.getData();
        if (data != null) {
            addDetail(row, "SHA-256", ContainerInspector.sha256Hex(data));
            addDetail(row, "SHA-1", ContainerInspector.digestHex(data, "SHA-1"));
        } else if (!node.isDirectory()) {
            addDetail(row, "Obsah", "příliš velký pro zobrazení; použijte rozbalení do adresáře");
        }

        boolean hasData = data != null && data.length > 0;
        saveOneBtn.setDisable(!hasData);
        openExternalBtn.setDisable(!hasData);
        copyHashBtn.setDisable(!hasData);
        openNestedBtn.setDisable(!canOpenNested(data));
        openInAnepBtn.setDisable(!hasData || openInAnepHandler == null);

        updatePreview(node);
    }

    private void addDetail(int[] row, String key, String value) {
        addDetail(row, key, value, null);
    }

    /** Vodorovná čára přes oba sloupce detailu — konec jednoho oddílu údajů a začátek dalšího. */
    private void addDetailSeparator(int[] row) {
        Separator sep = new Separator();
        sep.setPadding(new Insets(3, 0, 3, 0));
        detailGrid.add(sep, 0, row[0], 2, 1);
        row[0]++;
    }

    /** Tučný nadpis oddílu bez hodnoty, přes oba sloupce. */
    private void addDetailHeading(int[] row, String text) {
        Label h = new Label(text);
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        detailGrid.add(h, 0, row[0], 2, 1);
        row[0]++;
    }

    private void addDetail(int[] row, String key, String value, javafx.scene.Node trailing) {
        if (value == null || value.isEmpty()) return;
        Label k = new Label(key + ":");
        k.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");
        // Popisek zůstává celý: bez tohohle mu mřížka při zúžení ubere šířku a Label se sám
        // zkrátí třemi tečkami. Sloupec s popisky tím dostane šířku toho nejdelšího z nich
        // a dál neroste, protože roztahovat se má hodnota vedle.
        k.setMinWidth(Region.USE_PREF_SIZE);
        GridPane.setValignment(k, VPos.TOP);
        Label v = new Label(value);
        v.setStyle(key.startsWith("SHA") || "CRC-32".equals(key) ? MONO : "-fx-font-size: 11px;");
        v.setWrapText(true);
        GridPane.setHgrow(v, Priority.ALWAYS);
        detailGrid.add(k, 0, row[0]);
        if (trailing != null) {
            // Hodnota a značka v jednom řádku, aby se značka četla jako výhrada k tomuto času,
            // stejně jako v záhlaví (summaryGrid).
            HBox valueRow = new HBox(6, v, trailing);
            valueRow.setAlignment(Pos.CENTER_LEFT);
            GridPane.setHgrow(valueRow, Priority.ALWAYS);
            detailGrid.add(valueRow, 1, row[0]);
        } else {
            detailGrid.add(v, 1, row[0]);
        }
        row[0]++;
    }

    private void updatePreview(Node node) {
        byte[] data = node.getData();
        // A fragment does not parse on its own, so any preview of it would be a wall of unbroken
        // XML under a heading promising something readable. Everything it holds is in the detail
        // grid above, and the whole document it was cut from is an element away — so the fields
        // take the pane and this keeps the one line it needs.
        if (node.isFragment()) {
            setPreviewTitle("");
            showPreviewPlaceholder("Údaje z obálky jsou vypsány výše. Obálka je částí "
                    + "podepsaného XML, které je celé v prvku „datova-zprava.xml“.");
            giveHeightToDetails(true);
            return;
        }
        giveHeightToDetails(false);
        if (data == null || data.length == 0) {
            setPreviewTitle("Náhled");
            showPreviewPlaceholder(node.isDirectory()
                    ? "Adresář neobsahuje vlastní data."
                    : "Prvek nemá obsah, který by šlo zobrazit.");
            return;
        }

        ContainerInspector.Kind kind = ContainerInspector.detectKind(data);
        if (isImage(data)) {
            try {
                Image image = new Image(new ByteArrayInputStream(data));
                if (!image.isError()) {
                    imagePreview.setImage(image);
                    imagePreview.setFitWidth(Math.min(image.getWidth(), 900));
                    setPreviewTitle("Náhled obrázku");
                    show(imageScroll);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the byte view.
            }
        }

        if (kind == ContainerInspector.Kind.XML) {
            // Formatting means building a DOM and transforming it. A data message carrying a few
            // dozen megabytes of base64 attachments would freeze the window for seconds doing
            // that, and the preview is truncated below anyway — so past a point it is shown raw.
            String pretty = data.length <= MAX_PRETTY_PRINT_BYTES
                    ? ContainerInspector.prettyPrintXml(data) : null;
            String text = pretty != null ? pretty : asTruncatedText(data);
            setPreviewTitle(pretty != null ? "Náhled XML (formátováno)" : "Náhled textu");
            showText(text);
            return;
        }
        if (kind == ContainerInspector.Kind.JSON) {
            String pretty = data.length <= MAX_PRETTY_PRINT_BYTES
                    ? ContainerInspector.prettyPrintJson(data) : null;
            setPreviewTitle(pretty != null ? "Náhled JSON (formátováno)" : "Náhled textu");
            showText(pretty != null ? pretty : asTruncatedText(data));
            return;
        }
        if (kind == ContainerInspector.Kind.PDF) {
            setPreviewTitle("Náhled");
            showPreviewPlaceholder("Prvek je dokument PDF. Tlačítkem „Uložit prvek…“ jej vyjmete "
                    + "na disk, „Ověřit v AnEP“ jej necháte ověřit jako samostatný dokument a "
                    + "„Otevřít v aplikaci“ jej zobrazí v prohlížeči PDF. Má-li vlastní přílohy, "
                    + "otevře je „Otevřít jako kontejner“.");
            return;
        }
        // Certificates, CRLs and OCSP responses are the elements of a container a reader has a
        // real question about — whose certificate, valid until when, which certificate is on the
        // list, what state was it in — and a hex dump answers none of it. Read out in words they
        // go ahead of the byte view. Recognised by content, like everything else here: an element
        // may have come from a field inside CMS, where it has no file name to go by.
        ContainerInspector.Readable readable = ContainerInspector.readable(data);
        if (readable != null) {
            setPreviewTitle(readable.title());
            showText(readable.text());
            return;
        }
        if (ContainerInspector.looksTextual(data)) {
            setPreviewTitle("Náhled textu");
            showText(asTruncatedText(data));
            return;
        }
        // In a data message the remaining elements are attachments and a time-stamp token: things
        // to open, save or verify, never to read as bytes. A hex dump of a PDF answers no question
        // a reader of a message has, so it is left to the container browser's other windows.
        if (isIsdsMessage()) {
            setPreviewTitle("Náhled");
            showPreviewPlaceholder("Obsah tohoto prvku není text ani XML. Tlačítkem "
                    + "„Otevřít v aplikaci“ jej zobrazíte v programu přiřazeném jeho typu, "
                    + "„Ověřit v AnEP“ jej předáte k ověření a „Uložit prvek…“ uloží na disk.");
            return;
        }
        setPreviewTitle("Náhled binárního obsahu (prvních "
                + ContainerInspector.formatSize(Math.min(data.length, HEX_PREVIEW_BYTES)) + ")");
        showText(hexDump(data, HEX_PREVIEW_BYTES));
    }

    /**
     * Decodes only as much as the preview can show. Decoding a 90 MB attachment in full and then
     * throwing all but the first 400 000 characters away costs a copy of the whole file for
     * nothing — and the cut is on a byte boundary, so a multi-byte character straddling it comes
     * out as a replacement char rather than corrupting what follows.
     */
    private static String asTruncatedText(byte[] data) {
        if (data.length <= PREVIEW_TEXT_LIMIT) return new String(data, StandardCharsets.UTF_8);
        return new String(data, 0, PREVIEW_TEXT_LIMIT, StandardCharsets.UTF_8)
                + "\n\n… (náhled zkrácen)";
    }

    private void showText(String text) {
        if (text.length() > PREVIEW_TEXT_LIMIT) {
            text = text.substring(0, PREVIEW_TEXT_LIMIT) + "\n\n… (náhled zkrácen)";
        }
        textPreview.setText(text);
        textPreview.positionCaret(0);
        show(textPreview);
    }

    private void showPreviewPlaceholder(String message) {
        previewPlaceholder.setText(message);
        show(previewPlaceholder);
    }

    private void show(javafx.scene.Node visible) {
        for (javafx.scene.Node n : previewPane.getChildren()) {
            n.setVisible(n == visible);
            n.setManaged(n == visible);
        }
    }

    private static boolean isImage(byte[] data) {
        if (data.length < 8) return false;
        int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF, b2 = data[2] & 0xFF, b3 = data[3] & 0xFF;
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') return true;          // PNG
        if (b0 == 0xFF && b1 == 0xD8) return true;                                    // JPEG
        if (b0 == 'G' && b1 == 'I' && b2 == 'F') return true;                         // GIF
        return b0 == 'B' && b1 == 'M';                                                // BMP
    }

    private static String hexDump(byte[] data, int limit) {
        int len = Math.min(data.length, limit);
        StringBuilder sb = new StringBuilder(len * 4);
        for (int offset = 0; offset < len; offset += 16) {
            sb.append(String.format("%08X  ", offset));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (offset + i < len) {
                    int b = data[offset + i] & 0xFF;
                    sb.append(String.format("%02X ", b));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
                if (i == 7) sb.append(' ');
            }
            sb.append(' ').append(ascii).append('\n');
        }
        if (data.length > len) sb.append("… (zobrazeno prvních ").append(len).append(" bajtů)\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- actions

    private void saveSelected() {
        Node node = selected();
        if (node == null || node.getData() == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Uložit prvek kontejneru");
        // Čtenář ukládá to, co vidí ve stromu — a jméno jako
        // {@code SCIsSnProducedAt!…!20201111203255Z.ors} není nic, co by chtěl mít na disku.
        // Skutečné jméno zůstává na kartě vedle něj.
        chooser.setInitialFileName(ContainerInspector.safeFileName(node.getDisplayName()));
        File target = chooser.showSaveDialog(stage);
        if (target == null) return;
        try {
            Files.write(target.toPath(), node.getData());
        } catch (Exception e) {
            error("Prvek se nepodařilo uložit:\n" + e.getMessage());
        }
    }

    /**
     * Writes every písemnost of a data message into one chosen directory, flat and under the
     * names the sender gave them. An existing file is never overwritten: the point of the button
     * is to get evidence out of the message, and silently replacing something already on disk
     * would be the one outcome nobody could recover from.
     */
    private void saveAttachments(List<Node> attachments) {
        if (attachments.isEmpty()) return;
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Vyberte adresář pro uložení písemností");
        File dir = chooser.showDialog(stage);
        if (dir == null) return;

        List<String> failures = new ArrayList<>();
        int written = 0;
        for (Node attachment : attachments) {
            try {
                Path target = uniquePath(dir.toPath(),
                        ContainerInspector.safeFileName(attachment.getName()));
                Files.write(target, attachment.getData());
                written++;
            } catch (Exception e) {
                failures.add(attachment.getName() + " — " + e.getMessage());
            }
        }

        StringBuilder msg = new StringBuilder("Uloženo " + written + " z "
                + attachments.size() + " písemností do:\n" + dir);
        if (!failures.isEmpty()) {
            msg.append("\n\nNepodařilo se uložit:\n");
            failures.stream().limit(10).forEach(f -> msg.append("• ").append(f).append('\n'));
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION, msg.toString());
        alert.setHeaderText(null);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    /** {@code name}, or {@code name (2)}, … — whichever is not taken yet in {@code dir}. */
    private static Path uniquePath(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate)) return candidate;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int suffix = 1;
        do {
            candidate = dir.resolve(stem + " (" + (++suffix) + ")" + extension);
        } while (Files.exists(candidate));
        return candidate;
    }

    private void saveAll() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Vyberte adresář pro rozbalení kontejneru");
        File dir = chooser.showDialog(stage);
        if (dir == null) return;

        String baseName = ContainerInspector.safeFileName(stripExtension(sourceName));
        Path target = dir.toPath().resolve(baseName);
        int suffix = 1;
        while (Files.exists(target)) target = dir.toPath().resolve(baseName + " (" + (++suffix) + ")");

        List<String> failures = new ArrayList<>();
        int[] written = {0};
        try {
            Files.createDirectories(target);
            writeTree(result.root(), target, failures, written);
        } catch (Exception e) {
            error("Rozbalení se nezdařilo:\n" + e.getMessage());
            return;
        }

        StringBuilder msg = new StringBuilder("Uloženo " + written[0] + " prvků do:\n" + target);
        if (!failures.isEmpty()) {
            msg.append("\n\nNepodařilo se uložit:\n");
            failures.stream().limit(10).forEach(f -> msg.append("• ").append(f).append('\n'));
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION, msg.toString());
        alert.setHeaderText(null);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    /**
     * Writes every element that carries bytes, keeping the container's own directory layout.
     * Directory nodes that also carry bytes (an XML element, which is both a subtree and a
     * document in its own right) are skipped — their content is already inside their ancestor.
     */
    private void writeTree(Node node, Path targetRoot, List<String> failures, int[] written) {
        for (Node child : node.getChildren()) {
            if (!child.isDirectory() && child.getData() != null) {
                try {
                    Path out = resolveSafely(targetRoot, child.getPath().isEmpty()
                            ? child.getName() : child.getPath());
                    Files.createDirectories(out.getParent());
                    Files.write(out, child.getData());
                    written[0]++;
                } catch (Exception e) {
                    failures.add(child.getPath() + " — " + e.getMessage());
                }
            }
            writeTree(child, targetRoot, failures, written);
        }
    }

    /** Sanitises every path segment and keeps the result inside {@code root} — no zip-slip. */
    private static Path resolveSafely(Path root, String relative) {
        Path out = root;
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            out = out.resolve(ContainerInspector.safeFileName(segment));
        }
        Path normalized = out.normalize();
        if (!normalized.startsWith(root.normalize())) {
            return root.resolve(ContainerInspector.safeFileName(relative.replace('/', '_')));
        }
        return normalized;
    }

    private void openExternally(Node node) {
        if (node == null || node.getData() == null) return;
        try {
            String name = ContainerInspector.safeFileName(node.getName());
            Path dir = Files.createTempDirectory("anep-kontejner-");
            dir.toFile().deleteOnExit();
            Path tmp = dir.resolve(name);
            Files.write(tmp, node.getData());
            tmp.toFile().deleteOnExit();
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", tmp.toAbsolutePath().toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", tmp.toAbsolutePath().toString()).start();
            } else {
                new ProcessBuilder("xdg-open", tmp.toAbsolutePath().toString()).start();
            }
        } catch (Exception e) {
            error("Prvek se nepodařilo otevřít:\n" + e.getMessage());
        }
    }

    /**
     * Opens the selected element in a window of its own. A PDF carrying attachments recurses into
     * the attachment inspector; anything else goes to the container browser. Attachments extracted
     * from a document are already decrypted, so no password travels with them.
     */
    /**
     * @param explain whether a failure is worth a dialog. Pressing "Otevřít jako kontejner" is a
     *                request for this window and nothing else, so a failure has to be explained
     *                or the button looks broken. A double-click asks for "show me this", and the
     *                platform viewer answers that just as well — there the dialog would only
     *                stand in the way of what opens the moment it is dismissed.
     */
    private boolean openNested(Node node, boolean explain) {
        if (node == null || node.getData() == null) return false;
        byte[] data = node.getData();
        if (ContainerInspector.detectKind(data) == ContainerInspector.Kind.PDF) {
            return PdfAttachmentInspector.hasAttachments(data)
                    && openPdfAttachments(stage, data, node.getName(), null);
        }
        return open(stage, data, node.getName(), explain);
    }

    /** True when {@link #openNested} would have something to show for this element. */
    private static boolean canOpenNested(byte[] data) {
        if (data == null || data.length == 0) return false;
        ContainerInspector.Kind kind = ContainerInspector.detectKind(data);
        if (kind == ContainerInspector.Kind.PDF) return PdfAttachmentInspector.hasAttachments(data);
        return ContainerInspector.isContainerKind(kind);
    }

    private void openInAnep(Node node) {
        if (node == null || node.getData() == null || openInAnepHandler == null) return;
        openInAnepHandler.accept(node.getData(), ContainerInspector.safeFileName(node.getName()));
    }

    private void copyHash() {
        Node node = selected();
        if (node == null || node.getData() == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(ContainerInspector.sha256Hex(node.getData()));
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
