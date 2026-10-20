package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.AppClassLoader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens {@link ContainerView} on the given ZFO files and reports what the window actually
 * contains: its title, the state of every button in the bar, which element the tree opened on,
 * and how many detail rows that element produced.
 *
 * <p>The point is the button states. A window that draws can still have a control that is dead or
 * missing, and that cannot be seen from the console output of the inspector — but it can be read
 * off the live scene graph, which is what this does. Pass {@code -show} to leave the windows up
 * and press them by hand; without it the probe prints and exits.
 *
 * <pre>
 *   java --module-path &lt;javafx&gt; --add-modules javafx.controls,javafx.graphics \
 *        -cp ... cz.digitalnikontinuita.anep.ui.IsdsMessageViewPreview [-show] &lt;zfo&gt;…
 * </pre>
 */
public class IsdsMessageViewPreview extends Application {

    @Override
    public void start(Stage stage) {
        Thread.currentThread().setContextClassLoader(AppClassLoader.get());
        List<String> args = new ArrayList<>(getParameters().getRaw());
        boolean show = args.remove("-show");
        if (!args.isEmpty() && new File(args.get(args.size() - 1)).isDirectory()) {
            snapshotDir = new File(args.remove(args.size() - 1));
        }

        for (String path : args) {
            File file = new File(path);
            System.out.println("=".repeat(90));
            System.out.println(file.getName());
            if (!ContainerView.open(null, file)) System.out.println("  NEOTEVŘENO");
        }
        if (show) return;

        // One pulse later the windows are laid out and the initial selection has been applied.
        Platform.runLater(() -> Platform.runLater(() -> {
            for (Window window : Window.getWindows()) {
                if (window.getScene() == null) continue;
                describe(window);
            }
            Platform.exit();
        }));
    }

    private static void describe(Window window) {
        System.out.println("-".repeat(90));
        System.out.println("okno: " + (window instanceof Stage s ? s.getTitle() : window.getClass()));

        List<Node> all = collect(window.getScene().getRoot(), new ArrayList<>());

        for (Node node : all) {
            if (node instanceof Button b && b.getText() != null && !b.getText().isBlank()) {
                System.out.printf("  tlačítko %-24s %s%s%n", "„" + b.getText() + "“",
                        b.isDisabled() ? "NEAKTIVNÍ" : "aktivní",
                        b.isManaged() ? "" : "  (skryto)");
            }
        }
        for (Node node : all) {
            if (node instanceof TreeView<?> tree) {
                TreeItem<?> selected = tree.getSelectionModel().getSelectedItem();
                System.out.println("  vybráno: " + name(selected == null ? null : selected.getValue()));
                System.out.println("  strom (" + count(tree.getRoot()) + " prvků):");
                dump(tree.getRoot(), "    ");
            }
        }
        for (Node node : all) {
            if (node instanceof Label label && label.getText() != null
                    && (label.getText().startsWith("Náhled")
                        || label.getText().startsWith("Údaje z obálky")
                        || label.getText().startsWith("Obsah tohoto prvku"))) {
                System.out.println("  pravý panel: " + shorten(label.getText()));
            }
        }
        // A mark without a handler would look right and do nothing, which is the failure this
        // check exists for; firing it is not an option, since the dialog it opens is modal.
        Label caveat = (Label) all.stream()
                .filter(n -> n instanceof Label l && "⚠".equals(l.getText()))
                .findFirst().orElse(null);
        System.out.println("  ikona u času doručení: " + (caveat == null ? "není"
                : caveat.getOnMouseClicked() != null ? "je, reaguje na kliknutí"
                : "je, ALE BEZ OBSLUHY KLIKNUTÍ"));

        snapshot(window, "okno-zpravy.png");
        if (caveat != null && caveat.getOnMouseClicked() != null) measureCaveatDialog(caveat);

        // Selecting the envelope by hand is the only way to see what its pane says; it is behind
        // a shut folder, so the window never opens on it.
        for (Node node : all) {
            if (node instanceof TreeView<?> tree) {
                selectByName(tree, "obalka-zpravy.xml");
                // Forcing the layout here rather than waiting for the next pulse: the caller
                // exits as soon as this returns, so a deferred snapshot would never be taken.
                window.getScene().getRoot().applyCss();
                window.getScene().getRoot().layout();
                System.out.println("  po výběru obálky:");
                for (Node n : collect(window.getScene().getRoot(), new ArrayList<>())) {
                    if (n instanceof Label label && label.getText() != null
                            && (label.getText().startsWith("Náhled")
                                || label.getText().startsWith("Údaje z obálky"))) {
                        System.out.println("    pravý panel: " + shorten(label.getText()));
                    }
                    if (n instanceof javafx.scene.control.ScrollPane sp
                            && sp.getContent() instanceof javafx.scene.layout.GridPane) {
                        System.out.printf("    seznam údajů: výška %.0f px%n", sp.getHeight());
                    }
                    if (n instanceof javafx.scene.layout.StackPane sp && sp.getHeight() > 0
                            && sp.getChildren().stream().anyMatch(c -> c instanceof TextArea)) {
                        System.out.printf("    náhled: výška %.0f px%n", sp.getHeight());
                    }
                }
                snapshot(window, "okno-obalka.png");

                // A certificate attachment is the case that used to be mistaken for a CMS
                // envelope: the badge offered it as a container and opening it then failed.
                TreeItem<?> cert = findBySuffix(tree.getRoot(), ".cer");
                if (cert != null) {
                    selectByName(tree, name(cert.getValue()));
                    window.getScene().getRoot().applyCss();
                    window.getScene().getRoot().layout();
                    System.out.println("  po výběru " + name(cert.getValue()) + ":");
                    for (Node n : collect(window.getScene().getRoot(), new ArrayList<>())) {
                        if (n instanceof Button b && "Otevřít jako kontejner".equals(b.getText())) {
                            System.out.println("    tlačítko „Otevřít jako kontejner“: "
                                    + (b.isDisabled() ? "NEAKTIVNÍ (správně)" : "aktivní"));
                        }
                        if (n instanceof Label l && l.getText() != null && l.getText().startsWith("⧉")) {
                            System.out.println("    odznak v řádku: " + l.getText());
                        }
                    }
                    snapshot(window, "okno-certifikat.png");
                }
            }
        }
    }

    /**
     * Clicks the caveat mark for real and measures the dialog it opens.
     *
     * <p>The dialog is modal, so the click blocks this thread inside a nested event loop. The
     * measurement is queued before the click and runs inside that loop, which is the only way to
     * see a modal window's actual size from code — and its size is the whole point: the previous
     * version laid the paragraph out on one line and opened a window wider than the screen.
     */
    private static void measureCaveatDialog(Label mark) {
        Platform.runLater(() -> {
            for (Window window : Window.getWindows()) {
                if (window.getScene() == null || !window.isShowing()) continue;
                List<Node> nodes = collect(window.getScene().getRoot(), new ArrayList<>());
                if (nodes.stream().noneMatch(n -> n instanceof DialogPane)) continue;
                System.out.printf("  dialog s upozorněním: šířka %.0f px, výška %.0f px%n",
                        window.getWidth(), window.getHeight());
                for (Node n : nodes) {
                    if (n instanceof TextArea ta) {
                        System.out.printf("    text: šířka %.0f px, výška %.0f px, %s, %s%n",
                                ta.getWidth(), ta.getHeight(),
                                ta.isWrapText() ? "zalomený" : "NEZALOMENÝ",
                                ta.isEditable() ? "editovatelný" : "jen ke čtení a kopírování");
                    }
                }
                // One more pulse, so the helper's deferred re-measure has landed and the picture
                // shows the height the reader will actually get.
                Platform.runLater(() -> {
                    snapshot(window, "dialog-cas-doruceni.png");
                    window.hide();
                });
                return;
            }
            System.out.println("  dialog s upozorněním: NEOTEVŘEL SE");
        });
        Event.fireEvent(mark, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false, true, false, false,
                true, false, false, null));
    }

    /**
     * Writes a PNG of a window's scene. Converts the pixels by hand rather than through
     * {@code SwingFXUtils}, which lives in {@code javafx.swing} — a module this project does not
     * depend on and should not gain one for the sake of a probe.
     */
    private static void snapshot(Window window, String fileName) {
        try {
            WritableImage image = window.getScene().snapshot(null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            PixelReader reader = image.getPixelReader();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) out.setRGB(x, y, reader.getArgb(x, y));
            }
            File file = new File(snapshotDir, fileName);
            ImageIO.write(out, "png", file);
            System.out.println("    snímek: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("    snímek se nepodařil: " + e);
        }
    }

    /** Where {@link #snapshot} writes; set from the command line, current directory otherwise. */
    private static File snapshotDir = new File(".");

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void selectByName(TreeView<?> tree, String name) {
        TreeItem<?> found = find(tree.getRoot(), name);
        if (found != null) ((TreeView) tree).getSelectionModel().select((TreeItem) found);
    }

    private static TreeItem<?> findBySuffix(TreeItem<?> item, String suffix) {
        for (TreeItem<?> child : item.getChildren()) {
            if (name(child.getValue()).toLowerCase().endsWith(suffix)) return child;
            TreeItem<?> deeper = findBySuffix(child, suffix);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private static TreeItem<?> find(TreeItem<?> item, String name) {
        for (TreeItem<?> child : item.getChildren()) {
            if (name.equals(name(child.getValue()))) return child;
            TreeItem<?> deeper = find(child, name);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private static void dump(TreeItem<?> item, String indent) {
        for (TreeItem<?> child : item.getChildren()) {
            boolean folder = !child.getChildren().isEmpty();
            System.out.println(indent + (folder ? (child.isExpanded() ? "[-] " : "[+] ") : "    ")
                    + name(child.getValue()));
            if (folder) dump(child, indent + "    ");
        }
    }

    private static String name(Object value) {
        return value instanceof cz.digitalnikontinuita.anep.core.ContainerInspector.Node n
                ? n.getName() : String.valueOf(value);
    }

    private static String shorten(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 90 ? flat : flat.substring(0, 90) + "…";
    }

    private static List<Node> collect(Node node, List<Node> into) {
        into.add(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, into);
        }
        return into;
    }

    private static int count(TreeItem<?> item) {
        if (item == null) return 0;
        int total = 1;
        for (TreeItem<?> child : item.getChildren()) total += count(child);
        return total;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
