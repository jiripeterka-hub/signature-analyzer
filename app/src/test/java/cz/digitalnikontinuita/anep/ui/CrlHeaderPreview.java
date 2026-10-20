package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
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
 * Vypíše záhlaví prohlížeče seznamů CRL — řádek po řádku, tak jak je uvidí čtenář.
 *
 * <p>Nález uživatele ze 16. 9. 2026: řádek „Algoritmus podpisu" ukazoval holé OID
 * ({@code 1.2.840.113549.1.1.11}), ačkoli textový rozbor téhož seznamu vedle něj psal jméno.
 * Na takovou otázku odpoví jedině skutečně postavené okno: záhlaví si {@code CrlView} sází samo
 * a z kódu se nepozná, čím prošla která hodnota.
 *
 * <p>Seznam si bere ze souboru — buď je to rovnou {@code .crl}, nebo se hledá uvnitř kontejneru.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.CrlHeaderPreview \
 *       -Dexec.args="-out D:\out\crl.png D:\cesta\ke\kontejneru.asice"
 * </pre>
 */
public final class CrlHeaderPreview {

    private CrlHeaderPreview() { }

    public static void main(String[] args) throws Exception {
        Path out = null;
        String source = null;
        for (int i = 0; i < args.length; i++) {
            if ("-out".equals(args[i])) out = Path.of(args[++i]);
            else source = args[i];
        }
        if (source == null) {
            System.out.println("usage: CrlHeaderPreview [-out <png>] <soubor>");
            return;
        }
        byte[] der = firstCrl(Files.readAllBytes(Path.of(source)), Path.of(source).getFileName().toString());
        if (der == null) {
            System.out.println("v souboru se nenašel seznam CRL");
            return;
        }

        final Path png = out;
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            CrlView.show(der, null, null, null);
            Stage stage = Stage.getWindows().stream()
                    .filter(w -> w instanceof Stage).map(w -> (Stage) w)
                    .filter(s -> "Seznam CRL".equals(s.getTitle()))
                    .findFirst().orElse(null);
            if (stage == null) {
                System.out.println("okno se neotevřelo");
                done.countDown();
                return;
            }
            after(20, () -> {
                Node root = stage.getScene().getRoot();
                if (png != null) {
                    try {
                        ImageIO.write(toBufferedImage(root.snapshot(null, null)), "png",
                                new File(png.toString()));
                        System.out.println("### snímek: " + png);
                    } catch (Exception e) {
                        System.out.println("snímek se nepodařil: " + e);
                    }
                }
                printHeader(root);
                done.countDown();
            });
        });
        done.await();
        Platform.exit();
    }

    /** Popisek končí dvojtečkou, hodnota stojí hned za ním — stačí projít popisky v pořadí. */
    private static void printHeader(Node root) {
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (!(n instanceof javafx.scene.Parent p)) continue;
            var kids = p.getChildrenUnmodifiable();
            queue.addAll(kids);
            for (int i = 0; i + 1 < kids.size(); i++) {
                if (kids.get(i) instanceof Label k && k.getText() != null
                        && k.getText().endsWith(":")
                        && kids.get(i + 1) instanceof javafx.scene.control.TextArea v) {
                    System.out.printf("  %-30s %s%n", k.getText(), v.getText());
                }
            }
        }
    }

    private static byte[] firstCrl(byte[] bytes, String name) {
        if (ContainerInspector.describeCrl(bytes) != null) return bytes;
        ContainerInspector.Result result;
        try {
            result = ContainerInspector.inspect(bytes, name);
        } catch (Exception e) {
            return null;
        }
        if (result == null || result.root() == null) return null;
        Deque<ContainerInspector.Node> queue = new ArrayDeque<>();
        queue.add(result.root());
        while (!queue.isEmpty()) {
            ContainerInspector.Node n = queue.poll();
            queue.addAll(n.getChildren());
            byte[] data = n.getData();
            if (data != null && data.length > 0 && ContainerInspector.describeCrl(data) != null) {
                return data;
            }
        }
        return null;
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
