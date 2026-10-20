package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.StructureNode;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kouřová zkouška Inspektoru přes skutečné ovládání: ověří soubor, postaví Detailní rozbor,
 * stiskne tlačítko „Inspektor …“ ({@code fire()}), počká na okno, projde výběrem všechny uzly
 * stromu a u každého stiskne odkazy „přejít →“. Hlásí výjimky a počty; na přání uloží snímek
 * okna (jen pro vlastní kontrolu vzhledu, nikam do repozitáře).
 *
 * <pre>java --module-path … --add-modules javafx.controls,javafx.graphics,javafx.base,javafx.swing
 *      -cp … cz.digitalnikontinuita.anep.ui.InspectorSmoke &lt;soubor&gt; [snímek.png]</pre>
 */
public class InspectorSmoke extends Application {

    private static File file;
    private static File external;
    private static Path snapshot;

    /** Argumenty: {@code <soubor> [--external <externí podpis>] [snímek.png]}. */
    public static void main(String[] args) {
        file = new File(args[0]);
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--external")) external = new File(args[++i]);
            else snapshot = Path.of(args[i]);
        }
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.out.println("VÝJIMKA na " + t.getName() + ": " + e);
            e.printStackTrace(System.out);
        });
        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        // Jako MainView: při externím podpisu se ověřuje podpis a dokument je podepsaný obsah.
        var loader = new DocumentLoader();
        var outcome = external != null
                ? new ValidatorFactory(settings).validate(loader.load(external), loader.load(file), verifier, null)
                : new ValidatorFactory(settings).validate(loader.load(file), null, verifier, null);
        ValidationResult result = new ReportMapper().map(file.getName(), "x", outcome, settings,
                Map.of(), List.of(), false, Map.of());

        DetailedReportView view = new DetailedReportView();
        view.setFileInfo(file, external);
        stage.setScene(new Scene(view.getRoot(), 1000, 700));
        stage.show();
        view.show(result);

        // Záhlaví karet — označení prvků, jak je vidí čtenář.
        for (javafx.scene.control.Label l : find(view.getRoot(), javafx.scene.control.Label.class)) {
            String t = l.getText();
            if (t != null && t.matches("(Podpis|Pečeť|Značka|Protipodpis|Časové razítko).*")) {
                System.out.println("karta: " + t);
            }
        }
        Button btn = find(view.getRoot(), Button.class).stream()
                .filter(b -> b.getText().startsWith("Inspektor") && b.isVisible()).findFirst().orElse(null);
        if (btn == null) {
            System.out.println("TLAČÍTKO INSPEKTORU NENÍ");
            Platform.exit();
            return;
        }
        System.out.println("tlačítko: " + btn.getText());
        List<Window> before = new ArrayList<>(Window.getWindows());
        btn.fire();

        new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    Thread.sleep(200);
                    final boolean[] ready = {false};
                    Platform.runLater(() -> ready[0] = inspectorTree(before) != null);
                    Thread.sleep(50);
                    if (ready[0]) break;
                }
                Platform.runLater(() -> exercise(before));
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
        }).start();
    }

    @SuppressWarnings("unchecked")
    private static TreeView<StructureNode> inspectorTree(List<Window> before) {
        for (Window w : Window.getWindows()) {
            if (before.contains(w) || w.getScene() == null) continue;
            for (TreeView<?> t : find(w.getScene().getRoot(), TreeView.class)) return (TreeView<StructureNode>) t;
        }
        return null;
    }

    private void exercise(List<Window> before) {
        TreeView<StructureNode> tree = inspectorTree(before);
        if (tree == null) {
            System.out.println("OKNO INSPEKTORU SE NEOTEVŘELO");
            Platform.exit();
            return;
        }
        Window w = tree.getScene().getWindow();
        System.out.println("okno: " + ((Stage) w).getTitle());
        for (TreeItem<StructureNode> r : tree.getRoot().getChildren()) {
            StructureNode md = findNode(r.getValue(), "Otisk obsahu");
            if (md != null) System.out.println("message-digest: " + md.notes());
        }
        System.out.println("kořeny (sbalené?): ");
        for (TreeItem<StructureNode> r : tree.getRoot().getChildren()) {
            System.out.println("  " + r.getValue().title() + " rozbaleno=" + r.isExpanded());
            if (r.getValue().title().startsWith("Podpisy")) {
                for (TreeItem<StructureNode> c : r.getChildren()) System.out.println("    - " + c.getValue().title());
            }
        }
        List<TreeItem<StructureNode>> all = new ArrayList<>();
        collect(tree.getRoot(), all);
        int links = 0, selected = 0;
        for (TreeItem<StructureNode> it : all) {
            tree.getSelectionModel().select(it);
            selected++;
            for (Hyperlink h : find(tree.getScene().getRoot(), Hyperlink.class)) {
                if ("přejít →".equals(h.getText())) {
                    h.fire();
                    links++;
                    tree.getSelectionModel().select(it);
                    break;
                }
            }
        }
        System.out.println("vybráno uzlů: " + selected + ", stisknuto odkazů: " + links);
        // Na závěr podpis rozbalený, ať snímek ukáže strom i detail s pruhem.
        for (TreeItem<StructureNode> it : all) {
            StructureNode.Kind k = it.getValue().kind();
            if (k == StructureNode.Kind.SIGNATURE || k == StructureNode.Kind.DOC_TIMESTAMP) {
                tree.getSelectionModel().select(it);
                it.setExpanded(true);
                for (TreeItem<StructureNode> p = it.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
                break;
            }
        }
        if (snapshot != null) {
            Platform.runLater(() -> {
                try {
                    var img = w.getScene().snapshot(null);
                    int iw = (int) img.getWidth(), ih = (int) img.getHeight();
                    var out = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < ih; y++) {
                        for (int x = 0; x < iw; x++) out.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                    }
                    javax.imageio.ImageIO.write(out, "png", snapshot.toFile());
                    System.out.println("snímek: " + snapshot);
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                }
                Platform.exit();
                System.exit(0);
            });
        } else {
            Platform.exit();
            System.exit(0);
        }
    }

    private static StructureNode findNode(StructureNode n, String prefix) {
        if (n.title().startsWith(prefix)) return n;
        for (StructureNode c : n.children()) {
            if (c.kind() == StructureNode.Kind.TIMESTAMP) continue;
            StructureNode f = findNode(c, prefix);
            if (f != null) return f;
        }
        return null;
    }

    private static void collect(TreeItem<StructureNode> it, List<TreeItem<StructureNode>> out) {
        for (TreeItem<StructureNode> c : it.getChildren()) {
            out.add(c);
            collect(c, out);
        }
    }

    private static <T> List<T> find(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(root)) out.add(type.cast(root));
        if (root instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) out.addAll(find(c, type));
        if (root instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) out.addAll(find(sp.getContent(), type));
        if (root instanceof javafx.scene.control.SplitPane sp) for (Node c : sp.getItems()) out.addAll(find(c, type));
        return out;
    }
}
