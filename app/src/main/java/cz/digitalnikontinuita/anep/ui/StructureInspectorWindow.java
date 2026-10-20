package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.StructureAnnotations;
import cz.digitalnikontinuita.anep.core.StructureInspector;
import cz.digitalnikontinuita.anep.core.StructureNode;
import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inspektor podpisových struktur — okno se stromem podpisů, razítek, {@code /DSS}, VRI a revizí
 * a s detailem vybraného prvku.
 *
 * <p>Vstup je v záhlaví Detailního rozboru, tedy jen v expertním režimu (zadání uživatele
 * z 13. 10. 2026). Strom začíná sbalený: na nejvyšší úrovni jsou jen druhy struktur a čtenář
 * si rozbaluje, co ho zajímá. Nad stromem je u PDF pruh celého souboru — revize a rozsah, který
 * vybraný podpis kryje —, protože to je údaj, který se z čísel {@code /ByteRange} čte nejhůř.
 *
 * <p>Nemodální jako ostatní prohlížeče prvků: čtenář se dívá do struktury a vedle má výsledek.
 * Zavírá se s dalším ověřováním ({@link #closeAll()}), protože patří k jednomu souboru.
 */
public final class StructureInspectorWindow {

    private static final String MONO = "-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 12px;";
    private static final String HDR = "#003189";
    private static final Color COVERED = Color.web("#3b6fd8");
    private static final Color GAP = Color.web("#e0702a");

    private static final Set<Stage> OPEN = new LinkedHashSet<>();

    private final Stage stage = new Stage();
    private final TreeView<StructureNode> tree = new TreeView<>();
    private final VBox detail = new VBox(10);
    private final Pane bar = new Pane();
    private final Map<String, List<TreeItem<StructureNode>>> byKey = new HashMap<>();
    private final Map<TreeItem<StructureNode>, String> paths = new HashMap<>();
    private StructureInspector.Inspection inspection;

    /** Zavře všechna okna Inspektoru — volá se, když se začíná ověřovat další soubor. */
    public static void closeAll() {
        for (Stage s : new ArrayList<>(OPEN)) s.close();
        OPEN.clear();
    }

    /**
     * Otevře Inspektor nad souborem. Čte se na pozadí: PDF s mnoha revizemi se otevírá tolikrát,
     * kolik má revizí, a to na vlákně okna nemá co dělat.
     */
    public static void open(Window owner, File file, File original, char[] password,
                            StructureAnnotations.Context context) {
        StructureInspector.Format format = StructureInspector.formatOf(file);
        StructureInspectorWindow w = new StructureInspectorWindow();
        w.stage.setTitle((format != null ? format.title() : "Inspektor") + " — " + file.getName());
        if (owner != null) w.stage.initOwner(owner);
        Label loading = new Label("Načítám podpisovou strukturu…");
        loading.setStyle("-fx-font-size: 14px; -fx-text-fill: #555555;");
        StackPane wait = new StackPane(loading);
        w.stage.setScene(new Scene(wait, 1180, 760));
        OPEN.add(w.stage);
        w.stage.setOnHidden(e -> OPEN.remove(w.stage));
        w.stage.show();

        char[] pwd = password != null ? password.clone() : null;
        Thread t = new Thread(() -> {
            StructureInspector.Inspection in = StructureInspector.inspect(file, original, pwd);
            if (in.error() == null) StructureAnnotations.apply(in.roots(), context);
            Platform.runLater(() -> w.show(in));
        }, "anep-inspektor");
        t.setDaemon(true);
        t.start();
    }

    private void show(StructureInspector.Inspection in) {
        if (!stage.isShowing()) return;
        this.inspection = in;
        if (in.error() != null && in.roots().isEmpty()) {
            Label err = new Label(in.error());
            err.setWrapText(true);
            err.setStyle("-fx-text-fill: #a00000; -fx-font-size: 13px;");
            StackPane p = new StackPane(err);
            p.setPadding(new Insets(20));
            stage.getScene().setRoot(p);
            return;
        }

        // Záhlaví: formát, soubor, souhrn.
        Label title = new Label((in.format() != null ? in.format().title() : "Inspektor") + " — " + in.fileName());
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: white;");
        FlowPane summary = new FlowPane(18, 4);
        for (StructureNode.Row r : in.summary()) {
            Label l = new Label(r.label() + ": " + r.value());
            l.setStyle("-fx-text-fill: #dfe7fb;");
            summary.getChildren().add(l);
        }
        VBox header = new VBox(4, title, summary);
        header.setPadding(new Insets(10, 12, 10, 12));
        header.setStyle("-fx-background-color: " + HDR + ";");

        VBox top = new VBox(header);
        if (in.format() == StructureInspector.Format.PDF && in.fileLength() > 0) {
            top.getChildren().add(barBox());
        }

        // Strom.
        TreeItem<StructureNode> root = new TreeItem<>(new StructureNode(Kind.GROUP, in.fileName()));
        for (StructureNode n : in.roots()) root.getChildren().add(item(n, ""));
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new NodeCell());
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> select(b));

        TextField search = new TextField();
        search.setPromptText("Hledat ve stromu (Enter = další)");
        search.setOnAction(e -> findNext(search.getText()));
        Button collapse = new Button("Sbalit vše");
        collapse.setOnAction(e -> {
            for (TreeItem<StructureNode> c : root.getChildren()) setExpanded(c, false);
        });
        HBox treeTools = new HBox(6, search, collapse);
        HBox.setHgrow(search, Priority.ALWAYS);
        treeTools.setPadding(new Insets(6));
        VBox left = new VBox(treeTools, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);

        detail.setPadding(new Insets(12));
        ScrollPane detailScroll = new ScrollPane(detail);
        detailScroll.setFitToWidth(true);

        SplitPane split = new SplitPane(left, detailScroll);
        split.setDividerPositions(0.42);

        Button close = new Button("Zavřít");
        close.setOnAction(e -> stage.close());
        HBox bottom = new HBox(close);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(6, 10, 8, 10));

        VBox main = new VBox(top, split, bottom);
        VBox.setVgrow(split, Priority.ALWAYS);
        stage.getScene().setRoot(main);
        showOverview();
    }

    private TreeItem<StructureNode> item(StructureNode n, String parentPath) {
        TreeItem<StructureNode> it = new TreeItem<>(n);
        String path = parentPath.isEmpty() ? n.title() : parentPath + " › " + n.title();
        paths.put(it, path);
        if (n.key() != null) byKey.computeIfAbsent(n.key(), k -> new ArrayList<>()).add(it);
        for (StructureNode c : n.children()) it.getChildren().add(item(c, path));
        it.setExpanded(n.expanded());
        return it;
    }

    private static void setExpanded(TreeItem<StructureNode> it, boolean v) {
        it.setExpanded(v);
        for (TreeItem<StructureNode> c : it.getChildren()) setExpanded(c, v);
    }

    // ------------------------------------------------------------------ strom

    /** Buňka: značka druhu, název, šedé shrnutí a tečka podle nejhorší poznámky. */
    private static final class NodeCell extends TreeCell<StructureNode> {
        @Override
        protected void updateItem(StructureNode n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label badge = badge(n.kind());
            Label name = new Label(n.title());
            name.setStyle(n.kind() == Kind.GROUP ? "-fx-font-weight: bold;" : "");
            // Skupina značku nemá: šipku rozbalení kreslí strom sám a druhá vedle ní mátla.
            HBox box = n.kind() == Kind.GROUP ? new HBox(6, name) : new HBox(6, badge, name);
            box.setAlignment(Pos.CENTER_LEFT);
            if (n.subtitle() != null) {
                Label sub = new Label(n.subtitle());
                sub.setStyle("-fx-text-fill: #777777;");
                box.getChildren().add(sub);
            }
            Severity worst = n.worstSeverity();
            if (worst != null) {
                Label dot = new Label("●");
                dot.setStyle("-fx-text-fill: " + severityColor(worst) + ";");
                box.getChildren().add(dot);
            }
            setText(null);
            setGraphic(box);
        }
    }

    private static Label badge(Kind kind) {
        String[] b = switch (kind) {
            case SIGNATURE -> new String[]{"P", "#1f5fbf"};
            case DOC_TIMESTAMP, TIMESTAMP -> new String[]{"R", "#7a4fc2"};
            case USAGE_RIGHTS -> new String[]{"U", "#888888"};
            case EMPTY_FIELD -> new String[]{"–", "#aaaaaa"};
            case CMS -> new String[]{"CMS", "#355070"};
            case SIGNER -> new String[]{"SI", "#355070"};
            case ATTRIBUTE -> new String[]{"A", "#6c757d"};
            case CERTIFICATE -> new String[]{"C", "#2a7f62"};
            case CRL -> new String[]{"CRL", "#b5651d"};
            case OCSP -> new String[]{"OCSP", "#b5651d"};
            case DSS -> new String[]{"DSS", "#003189"};
            case VRI -> new String[]{"VRI", "#003189"};
            case REVISION -> new String[]{"Rev", "#5a5a5a"};
            case MANIFEST -> new String[]{"M", "#6c757d"};
            case FILE -> new String[]{"F", "#6c757d"};
            case VALUE -> new String[]{"#", "#6c757d"};
            case GROUP -> new String[]{"▸", "#003189"};
        };
        Label l = new Label(b[0]);
        l.setMinWidth(Region.USE_PREF_SIZE);
        if (kind == Kind.GROUP) {
            l.setStyle("-fx-text-fill: " + b[1] + ";");
        } else {
            l.setStyle("-fx-background-color: " + b[1] + "; -fx-text-fill: white; -fx-font-size: 10px;"
                    + " -fx-font-weight: bold; -fx-padding: 0 4 0 4; -fx-background-radius: 3;");
        }
        return l;
    }

    private static String severityColor(Severity s) {
        return switch (s) {
            case OK -> "#2e7d32";
            case WARNING -> "#d08b00";
            case ERROR -> "#c62828";
            case INFO -> "#5a6b8c";
        };
    }

    private void findNext(String text) {
        if (text == null || text.isBlank()) return;
        String q = text.toLowerCase(Locale.ROOT);
        List<TreeItem<StructureNode>> all = new ArrayList<>();
        collect(tree.getRoot(), all);
        TreeItem<StructureNode> current = tree.getSelectionModel().getSelectedItem();
        int start = current != null ? all.indexOf(current) + 1 : 0;
        for (int i = 0; i < all.size(); i++) {
            TreeItem<StructureNode> it = all.get((start + i) % all.size());
            if (matches(it.getValue(), q)) {
                reveal(it);
                return;
            }
        }
    }

    private static boolean matches(StructureNode n, String q) {
        if (n.title().toLowerCase(Locale.ROOT).contains(q)) return true;
        if (n.subtitle() != null && n.subtitle().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (StructureNode.Row r : n.rows()) {
            if (r.value().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private static void collect(TreeItem<StructureNode> it, List<TreeItem<StructureNode>> out) {
        for (TreeItem<StructureNode> c : it.getChildren()) {
            out.add(c);
            collect(c, out);
        }
    }

    private void reveal(TreeItem<StructureNode> it) {
        for (TreeItem<StructureNode> p = it.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
        tree.getSelectionModel().select(it);
        int row = tree.getRow(it);
        if (row >= 0) tree.scrollTo(Math.max(0, row - 3));
    }

    private void goTo(String key) {
        List<TreeItem<StructureNode>> list = byKey.get(key);
        if (list != null && !list.isEmpty()) reveal(list.get(0));
    }

    // ------------------------------------------------------------------ detail

    private void showOverview() {
        detail.getChildren().clear();
        Label h = new Label("Vyberte prvek ve stromu");
        h.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Label t = new Label("Strom ukazuje strukturu tak, jak je uložena v souboru. U prvků, které se"
                + " podařilo spárovat s výsledkem ověření, je v detailu i výklad podle DSS; barevná"
                + " tečka ve stromu značí zjištění (zelená v pořádku, oranžová upozornění, červená chyba).");
        t.setWrapText(true);
        detail.getChildren().addAll(h, t);
        drawBar(null);
    }

    private void select(TreeItem<StructureNode> it) {
        if (it == null) {
            showOverview();
            return;
        }
        StructureNode n = it.getValue();
        detail.getChildren().clear();

        Label title = new Label(n.title());
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + HDR + ";");
        Label path = new Label(paths.getOrDefault(it, ""));
        path.setWrapText(true);
        path.setStyle("-fx-text-fill: #777777; -fx-font-size: 11px;");
        detail.getChildren().addAll(title, path);

        List<StructureNode.Note> own = n.notes().stream().filter(x -> !x.fromValidation()).toList();
        List<StructureNode.Note> val = n.notes().stream().filter(StructureNode.Note::fromValidation).toList();
        if (!own.isEmpty()) detail.getChildren().add(notesBox("Zjištění o souboru", own));
        if (!val.isEmpty()) detail.getChildren().add(notesBox("Podle výsledku ověření", val));

        if (!n.rows().isEmpty()) detail.getChildren().add(rowsGrid(n));

        // Další výskyty téhož prvku — certifikát v CMS, v /DSS, ve VRI, v dřívějších revizích.
        if (n.key() != null) {
            List<TreeItem<StructureNode>> others = byKey.getOrDefault(n.key(), List.of()).stream()
                    .filter(o -> o != it).toList();
            if (!others.isEmpty()) {
                VBox box = new VBox(2);
                box.getChildren().add(sectionLabel("Týž prvek je v souboru také zde (" + others.size() + ")"));
                for (TreeItem<StructureNode> o : others) {
                    Hyperlink l = new Hyperlink(paths.getOrDefault(o, o.getValue().title()));
                    l.setWrapText(true);
                    l.setOnAction(e -> reveal(o));
                    box.getChildren().add(l);
                }
                detail.getChildren().add(box);
            }
        }

        if (!n.children().isEmpty()) {
            VBox box = new VBox(2);
            box.getChildren().add(sectionLabel("Obsahuje"));
            for (TreeItem<StructureNode> c : it.getChildren()) {
                Hyperlink l = new Hyperlink(c.getValue().title()
                        + (c.getValue().subtitle() != null ? " — " + c.getValue().subtitle() : ""));
                l.setWrapText(true);
                l.setOnAction(e -> reveal(c));
                box.getChildren().add(l);
            }
            detail.getChildren().add(box);
        }

        HBox buttons = new HBox(8);
        if (n.data() != null) {
            String[] type = fileType(n.kind());
            Button save = new Button("Uložit…");
            save.setOnAction(e -> UiDialogs.saveBytes(n.data(), n.saveName() != null ? n.saveName() : "prvek.der",
                    type[0], type[1], stage));
            buttons.getChildren().add(save);
            if (n.kind() == Kind.CRL) {
                Button view = new Button("Prohlížeč seznamu CRL…");
                view.setOnAction(e -> CrlView.show(n.data(), null, null, stage));
                buttons.getChildren().add(view);
            } else if (n.kind() == Kind.CERTIFICATE || n.kind() == Kind.OCSP) {
                Button view = new Button("Otevřít v samostatném okně");
                view.setOnAction(e -> {
                    ContainerInspector.Readable r = ContainerInspector.readable(n.data());
                    if (r != null) {
                        ElementTextView.show(r.title(), r.text(), n.data(), n.saveName(), type[0], type[1], stage);
                    }
                });
                buttons.getChildren().add(view);
            }
        }
        Button copy = new Button("Kopírovat detail");
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(plainText(n));
            Clipboard.getSystemClipboard().setContent(cc);
        });
        buttons.getChildren().add(copy);
        detail.getChildren().add(buttons);

        // Úplný výpis certifikátu a odpovědi OCSP přímo v detailu; seznam CRL může mít tisíce
        // záznamů, ten má vlastní prohlížeč s hledáním.
        if (n.data() != null && (n.kind() == Kind.CERTIFICATE || n.kind() == Kind.OCSP)) {
            ContainerInspector.Readable r = ContainerInspector.readable(n.data());
            if (r != null) {
                detail.getChildren().add(sectionLabel("Úplný výpis"));
                detail.getChildren().add(ElementTextView.grid(r.text()));
            }
        }
        drawBar(spansOf(it));
    }

    private VBox notesBox(String heading, List<StructureNode.Note> notes) {
        VBox box = new VBox(4);
        box.getChildren().add(sectionLabel(heading));
        for (StructureNode.Note note : notes) {
            Label l = new Label(note.text());
            l.setWrapText(true);
            String c = severityColor(note.severity());
            l.setStyle("-fx-border-color: transparent transparent transparent " + c + ";"
                    + " -fx-border-width: 0 0 0 4; -fx-padding: 3 6 3 8; -fx-background-color: #f6f7fa;");
            l.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(l);
        }
        return box;
    }

    private GridPane rowsGrid(StructureNode n) {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(2);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setHgrow(Priority.NEVER);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(10);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.NEVER);
        g.getColumnConstraints().addAll(c0, c1, c2);
        int r = 0;
        for (StructureNode.Row row : n.rows()) {
            Label l = new Label(row.label());
            l.setStyle("-fx-text-fill: #444444;");
            l.setMinWidth(Region.USE_PREF_SIZE);
            GridPane.setValignment(l, VPos.TOP);
            g.add(l, 0, r);
            var v = ResultView.selectable(row.value(), MONO);
            GridPane.setValignment(v, VPos.TOP);
            g.add(v, 1, r);
            if (row.link() != null && byKey.containsKey(row.link())) {
                Hyperlink go = new Hyperlink("přejít →");
                go.setMinWidth(Region.USE_PREF_SIZE);
                go.setOnAction(e -> goTo(row.link()));
                GridPane.setValignment(go, VPos.TOP);
                g.add(go, 2, r);
            }
            r++;
        }
        return g;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: " + HDR + "; -fx-padding: 4 0 0 0;");
        return l;
    }

    private static String[] fileType(Kind kind) {
        return switch (kind) {
            case CERTIFICATE -> new String[]{"Certifikát (*.cer)", "*.cer"};
            case CRL -> new String[]{"Seznam CRL (*.crl)", "*.crl"};
            case OCSP -> new String[]{"Odpověď OCSP (*.ocsp)", "*.ocsp"};
            case TIMESTAMP -> new String[]{"Časové razítko (*.tst)", "*.tst"};
            case CMS -> new String[]{"Podpis CMS (*.p7s)", "*.p7s"};
            default -> new String[]{"Soubor (*.*)", "*.*"};
        };
    }

    private static String plainText(StructureNode n) {
        StringBuilder sb = new StringBuilder(n.title()).append('\n');
        for (StructureNode.Note note : n.notes()) sb.append("[").append(note.severity()).append("] ").append(note.text()).append('\n');
        for (StructureNode.Row r : n.rows()) sb.append(r.label()).append(": ").append(r.value()).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ pruh souboru

    private VBox barBox() {
        bar.setPrefHeight(30);
        bar.setMinHeight(30);
        bar.widthProperty().addListener((o, a, b) -> drawBar(currentSpans));
        bar.setOnMouseClicked(e -> {
            long offset = (long) (e.getX() / Math.max(1, bar.getWidth()) * inspection.fileLength());
            List<Long> ends = inspection.revisionEnds();
            for (int i = 0; i < ends.size(); i++) {
                if (offset < ends.get(i)) {
                    goTo("rev:" + (i + 1));
                    return;
                }
            }
        });
        Tooltip.install(bar, new Tooltip("Celý soubor zleva doprava; svislé čáry jsou konce revizí."
                + " Klepnutím se vybere revize."));
        Label legend = new Label("Modře úsek vybraného prvku (rozsah podpisu či razítka, bajty revize),"
                + " oranžově hodnota /Contents, kterou rozsah vynechává; svislé čáry oddělují revize.");
        legend.setStyle("-fx-text-fill: #555555; -fx-font-size: 11px;");
        VBox box = new VBox(3, bar, legend);
        box.setPadding(new Insets(8, 12, 6, 12));
        box.setStyle("-fx-background-color: #eef1f8;");
        return box;
    }

    private List<StructureNode.Span> currentSpans;

    private static List<StructureNode.Span> spansOf(TreeItem<StructureNode> it) {
        for (TreeItem<StructureNode> p = it; p != null; p = p.getParent()) {
            if (p.getValue() != null && !p.getValue().spans().isEmpty()) return p.getValue().spans();
        }
        return null;
    }

    private void drawBar(List<StructureNode.Span> spans) {
        currentSpans = spans;
        if (inspection == null || inspection.format() != StructureInspector.Format.PDF) return;
        bar.getChildren().clear();
        double w = bar.getWidth();
        if (w <= 0) return;
        double h = 22;
        double len = Math.max(1, inspection.fileLength());
        Rectangle track = new Rectangle(0, 4, w, h);
        track.setFill(Color.web("#d9dee8"));
        bar.getChildren().add(track);
        List<Long> ends = inspection.revisionEnds();
        long prev = 0;
        for (int i = 0; i < ends.size(); i++) {
            double x0 = prev / len * w, x1 = ends.get(i) / len * w;
            Rectangle seg = new Rectangle(x0, 4, Math.max(1, x1 - x0), h);
            seg.setFill(i % 2 == 0 ? Color.web("#c9d1e2") : Color.web("#dde3ef"));
            bar.getChildren().add(seg);
            if (x1 - x0 > 26) {
                Text t = new Text(x0 + 4, 19, "R" + (i + 1));
                t.setFill(Color.web("#333333"));
                bar.getChildren().add(t);
            }
            prev = ends.get(i);
        }
        if (spans != null) {
            for (StructureNode.Span s : spans) {
                double x0 = s.start() / len * w, x1 = s.end() / len * w;
                Rectangle r = new Rectangle(x0, 8, Math.max(2, x1 - x0), h - 8);
                r.setFill(s.gap() ? GAP : COVERED);
                r.setOpacity(0.85);
                bar.getChildren().add(r);
            }
        }
        for (Long e : ends) {
            double x = e / len * w;
            Line l = new Line(x, 0, x, h + 8);
            l.setStroke(Color.web("#333333"));
            bar.getChildren().add(l);
        }
    }
}
