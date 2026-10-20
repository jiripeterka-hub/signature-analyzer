package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.AppClassLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;

/**
 * Writes PNGs of the two places the stale-Trusted-List warning appears, so a change to it can be
 * looked at without opening a document.
 *
 * <p>{@code bar-*.png} renders {@link TrustListInfoBar} on its own with synthetic state — every
 * combination it can be asked for, including ones that need a genuinely stale Trusted List to
 * reproduce. {@code status-*.png} is the real {@link MainView} toolbar after its Trusted-List load
 * settles, which is the one that depends on live state and therefore cannot be faked.
 *
 * <pre>
 *   java --module-path &lt;javafx&gt; --add-modules javafx.controls,javafx.graphics -cp ... \
 *        cz.digitalnikontinuita.anep.ui.TrustListWarningPreview &lt;výstupní adresář&gt;
 * </pre>
 */
public class TrustListWarningPreview extends Application {

    private File outDir;

    @Override
    public void start(Stage stage) throws Exception {
        Thread.currentThread().setContextClassLoader(AppClassLoader.get());
        outDir = new File(getParameters().getRaw().isEmpty()
                ? "." : getParameters().getRaw().get(0));

        writeBarVariants();
        showMainView(stage);
    }

    /** The header bar alone, with made-up state, so all its branches can be seen at once. */
    private void writeBarVariants() throws Exception {
        long now = System.currentTimeMillis();
        long day = 24L * 3600 * 1000;

        VBox root = new VBox(14);
        root.setStyle("-fx-background-color: white; -fx-padding: 12;");
        root.getChildren().add(bar(now - 3 * 3600 * 1000, false, List.of()));
        root.getChildren().add(bar(now - 3 * 3600 * 1000, true, List.of()));
        root.getChildren().add(bar(now - 3 * 3600 * 1000, false, List.of(
                new TrustConfig.StaleTrustList("https://tl.example.cz/tl.xml", "CZ", now - 95 * day, 95))));
        root.getChildren().add(bar(now - 3 * 3600 * 1000, true, List.of(
                new TrustConfig.StaleTrustList("https://tl.example.cz/tl.xml", "CZ", now - 95 * day, 95),
                new TrustConfig.StaleTrustList("https://www.nrca-ds.de/st/TSL-XML.xml", "DE", now - 31 * day, 31),
                new TrustConfig.StaleTrustList("https://sr.example.sk/tl.xml", null, now - 20 * day, 20))));
        // No refresh information at all, only stale lists — the fallback lead-in.
        root.getChildren().add(bar(-1, false, List.of(
                new TrustConfig.StaleTrustList("https://tl.example.cz/tl.xml", "CZ", now - 95 * day, 95))));
        // A country whose list contributed nothing: the red warning, alone and beside the amber one.
        root.getChildren().add(bar(now - 3 * 3600 * 1000, false, List.of(), List.of("PT")));
        root.getChildren().add(bar(now - 3 * 3600 * 1000, false, List.of(
                new TrustConfig.StaleTrustList("https://tl.example.cz/tl.xml", "CZ", now - 95 * day, 95)),
                List.of("PT", "IE")));

        Scene scene = new Scene(root, 620, 320);
        write(scene.snapshot(null), new File(outDir, "bar-variants.png"), 0);
    }

    private static VBox bar(long refreshMs, boolean inProgress,
                            List<TrustConfig.StaleTrustList> stale) {
        return bar(refreshMs, inProgress, stale, List.of());
    }

    private static VBox bar(long refreshMs, boolean inProgress,
                            List<TrustConfig.StaleTrustList> stale, List<String> empty) {
        TrustListInfoBar b = new TrustListInfoBar();
        // set() od té doby, co se pruh překresluje na místě, rovnou naplní i trvalý uzel — pro
        // náhled tedy stačí ten, žádné zvláštní vykreslení navíc.
        b.set(refreshMs, inProgress, null, stale, empty);
        return b.node();
    }

    /**
     * The real main window. Its status line is written by the background Trusted-List load, so the
     * snapshot has to wait for that rather than fire once: polling until the line stops saying
     * "Načítám" is what makes the shot show the finished state.
     */
    private void showMainView(Stage stage) {
        MainView view = new MainView();
        Scene scene = new Scene(view.getRoot(), 980, 640);
        scene.getStylesheets().add(
                MainView.class.getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        Timeline poll = new Timeline(new KeyFrame(Duration.seconds(1), e -> { }));
        poll.setCycleCount(40);
        poll.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), e -> {
            if (statusText(scene).contains("Načítám")) return;
            poll.stop();
            try {
                // Only the toolbar matters here; the rest of the window is empty at startup.
                write(scene.snapshot(null), new File(outDir, "status-mainview.png"), 118);
                System.out.println("status: " + statusText(scene));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            Platform.exit();
        }));
        poll.setOnFinished(e -> Platform.exit());
        poll.play();
    }

    /** Concatenated text of every Text/TextField/Label in the scene's status area. */
    private static String statusText(Scene scene) {
        StringBuilder sb = new StringBuilder();
        collect(scene.getRoot(), sb);
        return sb.toString();
    }

    private static void collect(javafx.scene.Node n, StringBuilder sb) {
        if (n instanceof javafx.scene.text.Text t) sb.append(t.getText()).append(' ');
        if (n instanceof javafx.scene.control.TextField f) sb.append(f.getText()).append(' ');
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    /** Pixel copy into a BufferedImage — javafx-swing (SwingFXUtils) is not a dependency here. */
    private static void write(WritableImage img, File target, int cropHeight) throws Exception {
        int w = (int) img.getWidth();
        int h = cropHeight > 0 ? Math.min(cropHeight, (int) img.getHeight()) : (int) img.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(out, "png", target);
        System.out.println("napsáno: " + target.getAbsolutePath());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
