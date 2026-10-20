package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.AppClassLoader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opens {@link ContainerView} on the given containers, selects each element that should be
 * readable in words — an OCSP response, a certificate, a CRL — and prints what the preview pane
 * really holds afterwards.
 *
 * <p>Reading a formatter's output on its own proves the text; it does not prove that the pane
 * shows it. Selecting the element in a live window and reading the {@code TextArea} back does —
 * the branch could sit behind an earlier one, or the title could be set on a pane that stays
 * hidden, and neither would be visible from the console output of the formatter.
 *
 * <p>JavaFX has to be on the module path, not just the class path — a main class extending
 * {@code Application} is refused otherwise. The platform jars ({@code *-win.jar}) are the ones
 * that carry the classes; the plain ones are empty. From the project root:
 *
 * <pre>
 *   MP=$(tr ';' '\n' &lt; cp.txt | grep -i "javafx.*-win.jar" | tr '\n' ';')
 *   java --module-path "$MP" --add-modules javafx.controls,javafx.graphics,javafx.base,javafx.web \
 *        -cp "&lt;kam se přeložilo&gt;;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.ui.ContainerPreviewProbe [-show] &lt;kontejner&gt;… [adresář pro snímky]
 * </pre>
 */
public class ContainerPreviewProbe extends Application {

    private static File snapshotDir = new File(".");

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

        Platform.runLater(() -> Platform.runLater(() -> {
            int i = 0;
            for (Window window : Window.getWindows()) {
                if (window.getScene() == null) continue;
                describe(window, ++i);
            }
            Platform.exit();
        }));
    }

    private static void describe(Window window, int index) {
        System.out.println("-".repeat(90));
        System.out.println("okno: " + (window instanceof Stage s ? s.getTitle() : window.getClass()));

        TreeView<?> tree = null;
        for (Node n : collect(window.getScene().getRoot(), new ArrayList<>())) {
            if (n instanceof TreeView<?> t) tree = t;
        }
        if (tree == null) {
            System.out.println("  strom nenalezen");
            return;
        }
        int shot = 0;
        for (String suffix : new String[]{".ocsp", ".ors", ".cer", ".crt", ".crl"}) {
            for (TreeItem<?> item : findAllBySuffix(tree.getRoot(), suffix, new ArrayList<>())) {
                ((TreeView) tree).getSelectionModel().select((TreeItem) item);
                window.getScene().getRoot().applyCss();
                window.getScene().getRoot().layout();
                report(window, name(item.getValue()));
                snapshot(window, "kontejner-" + index + "-" + (++shot) + ".png");
            }
        }
        if (shot == 0) System.out.println("  v kontejneru není nic k přečtení v řeči");
    }

    private static void report(Window window, String selected) {
        System.out.println("  vybráno: " + selected);
        for (Node n : collect(window.getScene().getRoot(), new ArrayList<>())) {
            if (n instanceof Label l && l.getText() != null && l.isVisible()
                    && (l.getText().startsWith("Náhled") || l.getText().startsWith("Odpověď")
                        || l.getText().startsWith("Certifikát") || l.getText().startsWith("Seznam"))) {
                System.out.println("    titulek panelu: " + l.getText());
            }
            if (n instanceof TextArea ta && ta.isVisible()) {
                System.out.println("    panel (viditelný, " + (int) ta.getWidth() + "×"
                        + (int) ta.getHeight() + " px, "
                        + (ta.isWrapText() ? "zalomený" : "nezalomený") + ", "
                        + (ta.isEditable() ? "editovatelný" : "jen ke čtení") + "):");
                for (String row : ta.getText().split("\n")) System.out.println("      | " + row);
            }
        }
    }

    private static List<TreeItem<?>> findAllBySuffix(TreeItem<?> item, String suffix,
                                                    List<TreeItem<?>> into) {
        for (TreeItem<?> child : item.getChildren()) {
            if (name(child.getValue()).toLowerCase(Locale.ROOT).endsWith(suffix)) into.add(child);
            findAllBySuffix(child, suffix, into);
        }
        return into;
    }

    private static String name(Object value) {
        return value instanceof cz.digitalnikontinuita.anep.core.ContainerInspector.Node n
                ? n.getName() : String.valueOf(value);
    }

    private static List<Node> collect(Node node, List<Node> into) {
        into.add(node);
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, into);
        }
        return into;
    }

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
            System.out.println("  snímek: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("  snímek se nepodařil: " + e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
