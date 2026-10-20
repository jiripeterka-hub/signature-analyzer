package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import javafx.application.Platform;
import javafx.scene.Node;
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
 * Snímek <b>druhého</b> prohlížeče odpovědi OCSP — toho, který otvírá odkaz „Ukaž“ v Analýze
 * a v Detailním rozboru ({@code DetailedReportView.showOcspDialogUnified}).
 *
 * <p>Odpověď OCSP se v aplikaci ukazuje dvěma okny a je to záměr: tohle nese navíc blok o podpisu
 * respondéra z diagnostiky DSS, kdežto {@code ElementTextView} vypisuje jen to, co stojí v samotné
 * odpovědi, a otevře se i nad souborem, který se do ověřování vůbec nedostal. Když se jedno z nich
 * upraví, je potřeba vidět i druhé — jinak se tatáž odpověď začne číst jinde jinak.
 *
 * <p>Sonda nedodává {@code RevocationWrapper}, takže blok o podpisu respondéra vynechá; ptá se
 * na sazbu řádků, a ta je u obou bloků táž.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.OcspDialogPreview \
 *       -Dexec.args="-width 460 -out D:\out\ocsp.png D:\cesta\ke\kontejneru.asice"
 * </pre>
 */
public final class OcspDialogPreview {

    private OcspDialogPreview() { }

    public static void main(String[] args) throws Exception {
        Path out = null;
        String source = null;
        double width = 0;
        for (int i = 0; i < args.length; i++) {
            if ("-out".equals(args[i])) out = Path.of(args[++i]);
            else if ("-width".equals(args[i])) width = Double.parseDouble(args[++i]);
            else source = args[i];
        }
        if (source == null) {
            System.out.println("usage: OcspDialogPreview [-width <px>] [-out <png>] <soubor>");
            return;
        }
        byte[] der = firstOcsp(Files.readAllBytes(Path.of(source)), source);
        if (der == null) {
            System.out.println("v souboru se nenašel údaj OCSP");
            return;
        }

        final Path png = out;
        final double sceneWidth = width;
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            DetailedReportView.showOcspDialogUnified(der, null);
            Stage stage = Stage.getWindows().stream()
                    .filter(w -> w instanceof Stage).map(w -> (Stage) w)
                    .filter(s -> "OCSP odpověď".equals(s.getTitle()))
                    .findFirst().orElse(null);
            if (stage == null) {
                System.out.println("okno se neotevřelo");
                done.countDown();
                return;
            }
            if (sceneWidth > 0) stage.setWidth(sceneWidth);
            // Kol je víc, než by se zdálo potřeba: po změně šířky si selectable() přepočítává
            // výšku ve dvou odložených kolech a měřit dřív znamená číst stav z předchozí šířky.
            after(ROUNDS, () -> {
                Node root = stage.getScene().getRoot();
                // Snímek napřed, měření po něm — týž důvod jako u ElementTextSnapshot: výšku
                // hodnoty dopočítává selectable() v odložených kolech a snímek si vynutí
                // rozvržení hned.
                if (png != null) {
                    try {
                        ImageIO.write(toBufferedImage(root.snapshot(null, null)), "png",
                                new File(png.toString()));
                        System.out.println("### snímek: " + png);
                    } catch (Exception e) {
                        System.out.println("snímek se nepodařil: " + e);
                    }
                }
                report(root, stage.getScene().getWidth());
                done.countDown();
            });
        });
        done.await();
        Platform.exit();
    }

    /** Kolik odložených kol se počká, než se okno změří. */
    private static final int ROUNDS = 8;

    /** Spustí úkol až po {@code rounds} kolech smyčky událostí. */
    private static void after(int rounds, Runnable task) {
        if (rounds <= 0) task.run();
        else Platform.runLater(() -> after(rounds - 1, task));
    }

    private static void report(Node root, double sceneWidth) {
        int values = 0, wrapped = 0, tooWide = 0;
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n instanceof javafx.scene.Parent p) queue.addAll(p.getChildrenUnmodifiable());
            if (!(n instanceof javafx.scene.control.TextArea ta)) continue;
            values++;
            int lines = (int) Math.max(1, Math.round(ta.getHeight() / 16.0));
            if (lines > 1) wrapped++;
            if (ta.getWidth() > sceneWidth) tooWide++;
            String v = ta.getText();
            System.out.printf("  %2d řádk%s  šířka %4.0f  %s%n", lines, lines == 1 ? "a " : "y ",
                    ta.getWidth(), v.length() > 60 ? v.substring(0, 57) + "…" : v);
        }
        System.out.println("### hodnot=" + values + ", zalomených=" + wrapped
                + ", širších než okno=" + tooWide);
    }

    private static byte[] firstOcsp(byte[] bytes, String name) {
        if (ContainerInspector.describeOcspResponse(bytes) != null) return bytes;
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
            if (data != null && data.length > 0
                    && ContainerInspector.describeOcspResponse(data) != null) {
                return data;
            }
        }
        return null;
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
