package cz.digitalnikontinuita.anep.tools;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Snímek <b>celého okna</b> aplikace, i s horní zónou pro vložení dokumentu a s pruhem záložek.
 *
 * <h2>Proč vedle {@link CardSnapshot}</h2>
 *
 * <p>{@code CardSnapshot} snímá obsah jedné záložky — to stačí, když se ladí text uvnitř karty.
 * Uživatelská příručka ale potřebuje ukázat i to, co je nad kartami: kam se dokument přetáhne,
 * kde je zelené tlačítko, jak vypadá řádek „K ověření byl předložen soubor“. To všechno drží
 * {@code MainView} a bez něj se nasnímat nedá.
 *
 * <h2>Reflexe</h2>
 *
 * <p>{@code acceptSigFile}, {@code analyzeIfReady} i příznak {@code lotlReady} jsou v
 * {@code MainView} soukromé — a mají být. Sonda je měřicí přístroj a platit za její pohodlí
 * zveřejněním vnitřností aplikace by bylo obráceně; táž úvaha stojí u
 * {@link ContinuityHorizonProbe}.
 *
 * <h2>Čekání</h2>
 *
 * <p>Dvě čekání, obě na text, ne na čas. Nejdřív na důvěryhodné seznamy ({@code lotlReady}) —
 * bez nich je tlačítko „ověřit“ zakázané a ověření by se nespustilo. Potom na dokončení ověření,
 * které se pozná podle řádku <i>„Hotovo:“</i> v okně. Pevná pauza by buď zdržovala, nebo občas
 * nestačila; obojí je v příručce vidět jako prázdné okno.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.WindowSnapshot \
 *       -Dexec.args="-out okno.png -tab Analýza -h 900 dokument.pdf"
 * </pre>
 */
public final class WindowSnapshot extends Application {

    public static void main(String[] args) {
        // Totéž, co dělá Main před spuštěním JavaFX. MainView si ve vlákně pro důvěryhodné
        // seznamy nastavuje kontextový classloader na AppClassLoader.get(), a ten je bez tohohle
        // řádku null — DSS pak nenajde implementaci IUtils a ověření se nikdy nespustí.
        cz.digitalnikontinuita.anep.AppClassLoader.set(
                Thread.currentThread().getContextClassLoader());
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        String out = null, tab = null, file = null;
        int width = 980, height = 640;
        boolean trim = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "-out" -> out = args.get(++i);
                case "-tab" -> tab = args.get(++i);
                case "-w" -> width = Integer.parseInt(args.get(++i));
                case "-h" -> height = Integer.parseInt(args.get(++i));
                // Okno má pevnou výšku, kdežto obsah bývá kratší; s -trim se dole odřízne
                // jednolitá plocha, aby v příručce nezela prázdnota.
                case "-trim" -> trim = true;
                default -> file = args.get(i);
            }
        }
        if (out == null) {
            System.out.println("usage: WindowSnapshot -out <png> [-tab <název>] [-w n] [-h n]"
                    + " [-trim] [<soubor>]");
            Platform.exit();
            return;
        }
        final String fOut = out, fTab = tab, fFile = file;
        final int fw = width, fh = height;
        final boolean fTrim = trim;
        new Thread(() -> {
            try {
                run(fOut, fTab, fFile, fw, fh, fTrim);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "window-snapshot").start();
    }

    private void run(String out, String tab, String file, int width, int height, boolean trim)
            throws Exception {
        Object[] view = new Object[1];
        Parent[] root = new Parent[1];
        onFx(() -> {
            try {
                Class<?> cls = Class.forName("cz.digitalnikontinuita.anep.ui.MainView");
                Object mv = cls.getDeclaredConstructor().newInstance();
                Method getRoot = cls.getMethod("getRoot");
                Parent r = (Parent) getRoot.invoke(mv);
                Scene scene = new Scene(r, width, height);
                var css = getClass().getResource("/css/app.css");
                scene.getStylesheets().add(css != null ? css.toExternalForm() : "data:text/css,");
                view[0] = mv;
                root[0] = r;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Čeká se vždycky, i bez souboru: úvodní obrazovka do té doby hlásí „Načítám důvěryhodné
        // seznamy EU…“ a snímek by ukazoval stav, který za pár vteřin zmizí.
        System.out.println("čekám na důvěryhodné seznamy…");
        await(() -> flag(view[0], "lotlReady"), 180);
        if (file != null) {
            onFx(() -> invoke(view[0], "acceptSigFile", File.class, new File(file)));
            onFx(() -> invoke(view[0], "analyzeIfReady"));
            System.out.println("ověřuji…");
            await(() -> text(root[0]).contains("Hotovo:"), 300);
        }
        if (tab != null) {
            final String want = tab;
            onFx(() -> {
                TabPane pane = findTabPane(root[0]);
                if (pane == null) return;
                for (Tab t : pane.getTabs()) {
                    if (t.getText() != null && t.getText().startsWith(want)) {
                        pane.getSelectionModel().select(t);
                        return;
                    }
                }
                System.out.println("záložka nenalezena: " + want + "  (jsou: "
                        + pane.getTabs().stream().map(Tab::getText).toList() + ")");
            });
        }

        // Několik průchodů frontou FX vlákna, než se snímá: hodnoty v přehledech si dopočítávají
        // výšku přes vnořená Platform.runLater a snímek pořízený hned je zastihne rozevřené.
        // Totéž zjistil CardSnapshot a stálo to jeden nepoužitelný obrázek.
        for (int i = 0; i < 6; i++) {
            onFx(() -> { root[0].applyCss(); root[0].layout(); });
        }

        WritableImage[] img = new WritableImage[1];
        onFx(() -> img[0] = root[0].snapshot(new SnapshotParameters(), null));
        File png = new File(out);
        if (png.getParentFile() != null) png.getParentFile().mkdirs();
        java.awt.image.BufferedImage bi = toBufferedImage(img[0], trim);
        ImageIO.write(bi, "png", png);
        System.out.println("uloženo: " + png.getAbsolutePath()
                + "  " + bi.getWidth() + "×" + bi.getHeight());
    }

    // ── pomůcky ─────────────────────────────────────────────────────────────────────────────

    private static void onFx(Runnable r) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] err = new Throwable[1];
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err[0] = t;
            } finally {
                done.countDown();
            }
        });
        done.await();
        if (err[0] != null) err[0].printStackTrace(System.out);
    }

    /** Čeká na podmínku vyhodnocovanou na FX vlákně; {@code seconds} je strop, ne odhad. */
    private static void await(java.util.function.BooleanSupplier cond, int seconds)
            throws Exception {
        for (int i = 0; i < seconds * 4; i++) {
            boolean[] ok = new boolean[1];
            onFx(() -> ok[0] = cond.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(250);
        }
        System.out.println("POZOR: čekání vypršelo, snímek bude z rozdělaného stavu");
    }

    private static boolean flag(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(target);
        } catch (Exception e) {
            return true;   // neznámé pole nesmí zaseknout čekání navždy
        }
    }

    private static void invoke(Object target, String name, Class<?> argType, Object arg) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, argType);
            m.setAccessible(true);
            m.invoke(target, arg);
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }

    private static void invoke(Object target, String name) {
        try {
            Method m = target.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(target);
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }

    /** Všechen text v okně, na jednu hromadu — slouží jen k poznání, že ověření doběhlo. */
    private static String text(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled l && l.getText() != null) sb.append(l.getText()).append('\n');
        if (node instanceof Text t && t.getText() != null) sb.append(t.getText()).append('\n');
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    private static TabPane findTabPane(Node node) {
        if (node instanceof TabPane tp) return tp;
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                TabPane found = findTabPane(c);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Převod bez {@code SwingFXUtils} — modul {@code javafx.swing} v téhle sestavě není.
     * S {@code trim} se odspodu odřízne plocha, ve které je každý řádek pixelů stejný.
     */
    private static java.awt.image.BufferedImage toBufferedImage(WritableImage img, boolean trim) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        PixelReader pr = img.getPixelReader();
        int cut = h;
        if (trim) {
            while (cut > 1 && uniformRow(pr, w, cut - 1)) cut--;
            cut = Math.min(h, cut + 8);            // pár pixelů okraje, ať to nekončí natěsno
        }
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                w, cut, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < cut; y++) {
            for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        }
        return bi;
    }

    private static boolean uniformRow(PixelReader pr, int w, int y) {
        int first = pr.getArgb(0, y);
        for (int x = 1; x < w; x++) {
            if (pr.getArgb(x, y) != first) return false;
        }
        return true;
    }
}
