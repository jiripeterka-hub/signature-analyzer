package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.ui.ContainerView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;

/**
 * Snímek karty prvku v prohlížeči kontejnerů — a k němu zpráva, jestli se některý popisek
 * nezkrátil třemi tečkami.
 *
 * <p>Nález uživatele ze 16. 9. 2026 nad datovou zprávou ISDS: při zúžení se popisky v kartě
 * krátily do nečitelnosti („ID datové zp…"), místo aby se zalamovaly hodnoty vedle nich.
 *
 * <p><b>Zužuje se příčkou, ne oknem.</b> Změnit šířku okna programově se v tomhle běhu ukázalo
 * jako slepá cesta — okno šířku přijme, ale scéna se nepřerozvrhne, dokud nepřijde událost od
 * okenního správce. Příčka mezi stromem a kartou je přitom to, čím čtenář kartu zužuje doopravdy,
 * a {@code setDividerPositions} funguje hned.
 *
 * <p>Zkrácení se pozná porovnáním skutečné šířky popisku s tou, kterou by potřeboval
 * ({@code prefWidth(-1)}). Je to měření bez čekání na odložená kola: {@code Label} se rozvrhne
 * rovnou, na rozdíl od hodnot, jejichž výšku dopočítává {@code ResultView.selectable}.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.ContainerDetailSnapshot \
 *       -Dexec.args="-divider 0.62 -out D:\out\karta.png D:\cesta\ke\zprave.zfo"
 * </pre>
 */
public final class ContainerDetailSnapshot {

    private ContainerDetailSnapshot() { }

    public static void main(String[] args) throws Exception {
        Path out = null;
        String source = null;
        double divider = 0.62;
        for (int i = 0; i < args.length; i++) {
            if ("-out".equals(args[i])) out = Path.of(args[++i]);
            else if ("-divider".equals(args[i])) divider = Double.parseDouble(args[++i]);
            else source = args[i];
        }
        if (source == null) {
            System.out.println("usage: ContainerDetailSnapshot [-divider <0..1>] [-out <png>] <soubor>");
            return;
        }
        byte[] bytes = Files.readAllBytes(Path.of(source));
        String name = Path.of(source).getFileName().toString();

        final Path png = out;
        final double pos = divider;
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            if (!ContainerView.open(null, bytes, name)) {
                System.out.println("soubor se neotevřel jako kontejner");
                done.countDown();
                return;
            }
            Stage stage = Stage.getWindows().stream()
                    .filter(w -> w instanceof Stage).map(w -> (Stage) w)
                    .findFirst().orElse(null);
            if (stage == null) {
                System.out.println("okno se neotevřelo");
                done.countDown();
                return;
            }
            Node root = stage.getScene().getRoot();
            // Vybrat prvek s nejvíc údaji — na něm je karta nejbohatší a popisky nejdelší.
            selectRichest(root);
            if (root.lookup(".split-pane") instanceof SplitPane split) split.setDividerPositions(pos);

            after(30, () -> {
                if (png != null) {
                    try {
                        ImageIO.write(toBufferedImage(root.snapshot(null, null)), "png",
                                new File(png.toString()));
                        System.out.println("### snímek: " + png);
                    } catch (Exception e) {
                        System.out.println("snímek se nepodařil: " + e);
                    }
                }
                report(root);
                done.countDown();
            });
        });
        done.await();
        Platform.exit();
    }

    /** Popisky karty jsou {@code Label} končící dvojtečkou; hodnoty vedle nich dvojtečku nemají. */
    private static void report(Node root) {
        int labels = 0, cut = 0;
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n instanceof javafx.scene.Parent p) queue.addAll(p.getChildrenUnmodifiable());
            if (!(n instanceof Label l) || l.getText() == null || !l.getText().endsWith(":")) continue;
            labels++;
            double need = l.prefWidth(-1);
            if (l.getWidth() + 0.5 < need) {
                cut++;
                System.out.printf("  ZKRÁCENO  má %4.0f, potřebuje %4.0f  %s%n",
                        l.getWidth(), need, l.getText());
            }
        }
        System.out.println("### popisků=" + labels + ", zkrácených=" + cut);
    }

    /** Vybere ve stromu položku s nejvíc řádky karty. */
    private static void selectRichest(Node root) {
        if (!(root.lookup(".tree-view") instanceof TreeView<?> tree)) return;
        TreeItem<?> best = null;
        int bestCount = -1;
        Deque<TreeItem<?>> queue = new ArrayDeque<>();
        if (tree.getRoot() != null) queue.add(tree.getRoot());
        while (!queue.isEmpty()) {
            TreeItem<?> item = queue.poll();
            item.setExpanded(true);
            queue.addAll(item.getChildren());
            if (item.getValue() instanceof cz.digitalnikontinuita.anep.core.ContainerInspector.Node n
                    && n.getInfo().size() > bestCount) {
                bestCount = n.getInfo().size();
                best = item;
            }
        }
        if (best != null) {
            @SuppressWarnings("unchecked")
            TreeView<Object> t = (TreeView<Object>) tree;
            @SuppressWarnings("unchecked")
            TreeItem<Object> b = (TreeItem<Object>) best;
            t.getSelectionModel().select(b);
            System.out.println("### vybrán prvek s " + bestCount + " údaji");
        }
    }

    private static void after(int rounds, Runnable task) {
        if (rounds <= 0) task.run();
        else Platform.runLater(() -> after(rounds - 1, task));
    }

    private static java.awt.image.BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.setRGB(x, y, px.getArgb(x, y));
        }
        return out;
    }
}
