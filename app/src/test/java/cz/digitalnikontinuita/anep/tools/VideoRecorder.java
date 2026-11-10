package cz.digitalnikontinuita.anep.tools;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Instruktážní video: aplikace řízená scénářem, snímaná po jednotlivých snímcích.
 *
 * <h2>Proč ne záznam obrazovky</h2>
 *
 * <p>Záznam plochy (ffmpeg gdigrab) by zachytil i to, co na ploše být nemá, a tempo by záviselo
 * na tom, jak rychle počítač zrovna ověřuje. Tady se každý snímek pořídí ze scény JavaFX
 * ({@code snapshot}) a pošle surový do ffmpeg — čas videa je čas scénáře, ne hodin. Kurzor,
 * titulky a zvýraznění jsou vrstva nad aplikací v téže scéně; skutečný kurzor systému
 * se do snímku nedostane, a ani nemá. Čekání na ověření se ve videu zkrátí střihem.
 *
 * <p>Kliká se skutečně ({@code fire()}, výběr záložky), kurzor jen ukazuje kam. Dokument se
 * zóně podá přes {@code acceptSigFile} — přetažení z Průzkumníka se bez Dragboardu nasimulovat
 * nedá, video ho naznačí štítkem souboru v ruce kurzoru. Soukromé prvky {@code MainView} se
 * berou reflexí jako v {@link WindowSnapshot}.
 *
 * <p>Vzniklo 8. 11. 2026 jako zkušební video „ověření podepsaného PDF“. Sondu pouštět
 * s {@code APPDATA} na kopii — ověření zapisuje do seznamu naposledy ověřených souborů.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 --module-path &lt;javafx jary&gt; --add-modules javafx.controls \
 *        -cp ... cz.digitalnikontinuita.anep.tools.VideoRecorder \
 *        -ffmpeg ffmpeg.exe -out video.mp4 dokument.pdf
 * </pre>
 */
public final class VideoRecorder extends Application {

    private static final int FPS = 25;
    /** Logická velikost okna; snímek se kreslí ve {@link #SCALE}× větším rozlišení (1080p). */
    private static final int W = 1280, H = 720;
    private static final double SCALE = 1.5;

    private Object view;
    private Parent appRoot;
    private StackPane stack;
    private Pane overlay;
    private SVGPath cursor;
    private Circle ring;
    private Label caption;
    private Rectangle highlight;
    private HBox fileChip;
    private StackPane card;
    private Label cardTitle, cardSub;

    private double cx = W * 0.7, cy = H * 0.75;
    private OutputStream ffmpeg;
    private WritableImage img;
    private byte[] buf;
    private int frames;

    public static void main(String[] args) {
        cz.digitalnikontinuita.anep.AppClassLoader.set(
                Thread.currentThread().getContextClassLoader());
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        String out = null, ff = null, file = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "-out" -> out = args.get(++i);
                case "-ffmpeg" -> ff = args.get(++i);
                default -> file = args.get(i);
            }
        }
        final String fOut = out, fFf = ff, fFile = file;
        new Thread(() -> {
            try {
                record(fOut, fFf, fFile);
            } catch (Throwable e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "video-recorder").start();
    }

    // ── scénář ──────────────────────────────────────────────────────────────────────────────

    private void record(String out, String ff, String file) throws Exception {
        onFx(this::buildScene);
        System.out.println("čekám na důvěryhodné seznamy…");
        await(() -> flag(view, "lotlReady"), 240);

        Process p = new ProcessBuilder(ff, "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "bgra",
                "-s", (int) (W * SCALE) + "x" + (int) (H * SCALE), "-r", String.valueOf(FPS),
                "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", out)
                .redirectError(new File(out + ".ffmpeg.log"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        ffmpeg = new java.io.BufferedOutputStream(p.getOutputStream(), 1 << 22);

        Node sigZone = (Node) field(view, "sigZone");
        Button validate = (Button) field(view, "validateButton");
        TabPane tabs = (TabPane) field(view, "tabPane");
        String name = new File(file).getName();

        // 1. Úvodní karta
        showCard("AnEP — Analyzátor elektronických podpisů", "Ukázka: ověření podepsaného PDF");
        hold(3.0);
        fadeCard(0.6);

        // 2. Okno připravené
        say("Po spuštění si AnEP načte důvěryhodné seznamy EU. Pak je připraven k ověření.");
        hold(3.5);

        // 3. Vložení dokumentu
        say("Podepsaný dokument přetáhněte do horní zóny — nebo na ni klikněte a vyberte ho.");
        highlightOn(sigZone);
        hold(1.2);
        onFx(() -> { fileLabel(name); fileChip.setVisible(true); });
        Bounds z = sceneBounds(sigZone);
        moveTo(z.getMinX() + z.getWidth() * 0.45, z.getCenterY(), 2.0);
        onFx(() -> fileChip.setVisible(false));
        onFx(() -> invoke(view, "acceptSigFile", File.class, new File(file)));
        settle();
        click(0.35);
        highlightOff();
        hold(2.0);

        // 4. Ověření
        say("Zelené tlačítko spustí ověření.");
        highlightOn(validate);
        Bounds vb = sceneBounds(validate);
        moveTo(vb.getCenterX(), vb.getCenterY(), 1.4);
        onFx(() -> handler(validate.getOnMouseEntered()));
        hold(0.6);
        onFx(validate::arm);
        click(0.2);
        onFx(() -> { validate.disarm(); validate.fire(); });
        highlightOff();
        // Ověření se střihem: snímky jdou, dokud běží (nejvýš ~2 s videa), zbytek čekání
        // se vystřihne. Titulek se přepne hned, jak je výsledek na kartě.
        say("Ověřuji…");
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n(2.0) && !done(); i++) frame();
        // await vyhodnocuje na FX vlákně — done() by tam čekalo samo na sebe.
        await(() -> text(appRoot).contains("Hotovo:"), 300);
        System.out.println("ověření: " + (System.currentTimeMillis() - t0) + " ms");
        onFx(() -> handler(validate.getOnMouseExited()));
        Tab summary = (Tab) field(view, "summaryTab");
        onFx(() -> tabs.getSelectionModel().select(summary));
        settle();

        // 5. Zjednodušený přehled
        say("Zjednodušený přehled: celkový výsledek ověření a hlavní údaje o podpisu.");
        moveTo(W * 0.62, H * 0.55, 1.0);
        hold(3.5);
        Node status = onFxGet(() -> labelled(summary.getContent(), "Stav platnosti"));
        if (status != null) {
            Node row = onFxGet(status::getParent);
            say("Stav platnosti je vidět na první pohled — tady je podpis platný.");
            highlightOn(row);
            Bounds rb = sceneBounds(row);
            moveTo(rb.getMinX() + rb.getWidth() * 0.3, rb.getCenterY() + 4, 1.0);
            hold(3.5);
            highlightOff();
        }

        // 6. Analýza
        say("Karta Analýza vykládá výsledek česky — co bylo ověřeno a co z toho plyne.");
        Tab advisor = onFxGet(() -> tabs.getTabs().stream()
                .filter(t -> "Analýza".equals(t.getText())).findFirst().orElse(null));
        Node header = onFxGet(() -> tabHeader(tabs, "Analýza"));
        if (header != null) {
            Bounds hb = sceneBounds(header);
            moveTo(hb.getCenterX(), hb.getCenterY(), 1.3);
            click(0.3);
        }
        onFx(() -> tabs.getSelectionModel().select(advisor));
        settle();
        moveTo(W - 22, H * 0.45, 0.8);
        hold(4.5);
        ScrollPane asp = onFxGet(() -> findScroll(advisor.getContent()));
        openBlock(asp, advisor, "Možné právní účinky",
                "Podrobnosti jsou v rozbalovacích blocích — třeba možné právní účinky podpisu.", 7.0);
        openBlock(asp, advisor, "Průběh ověřování",
                "Průběh ověřování ukazuje, jak ověření proběhlo a o co se výsledek opírá.", 8.0);

        // 7. Závěr
        say(null);
        showCard("AnEP", "Návod a ke stažení: bajecnysvet.cz/anep");
        hold(3.5);

        ffmpeg.close();
        int rc = p.waitFor();
        System.out.println("hotovo: " + frames + " snímků (" + frames / FPS + " s), ffmpeg=" + rc
                + " → " + new File(out).getAbsolutePath());
    }

    private boolean done() {
        try {
            return onFxGet(() -> text(appRoot).contains("Hotovo:"));
        } catch (InterruptedException e) {
            return true;
        }
    }

    /**
     * Otevře rozbalovací blok karty tak, jak by to udělal čtenář: kurzor na záhlaví, klik
     * (obsluha záhlaví v {@code AdvisorView.collapsibleBlock} událost nečte), a pak karta
     * dojede, aby byl blok vidět od nadpisu.
     */
    private void openBlock(ScrollPane sp, Tab tab, String titlePrefix, String text, double read)
            throws Exception {
        Node title = onFxGet(() -> labelled(tab.getContent(), titlePrefix));
        if (title == null) {
            System.out.println("blok nenalezen: " + titlePrefix);
            return;
        }
        Node header = onFxGet(title::getParent);
        say(text);
        if (sp != null) scrollToNode(sp, header, 1.2);
        Bounds hb = sceneBounds(title);
        moveTo(hb.getMinX() + Math.min(hb.getWidth() * 0.5, 120), hb.getCenterY(), 1.2);
        onFx(() -> handler(((javafx.scene.layout.HBox) header).getOnMouseEntered()));
        hold(0.4);
        click(0.3);
        onFx(() -> handler(((javafx.scene.layout.HBox) header).getOnMouseClicked()));
        settle();
        hold(1.0);
        if (sp != null) scrollToNode(sp, header, 1.4);
        // Odložit k pravému okraji: text Analýzy jde přes celou šířku a kurzor nad ním ruší.
        moveTo(W - 22, H * 0.45, 0.8);
        hold(read);
    }

    /** Dojede kartou tak, aby uzel stál kousek pod horním okrajem výřezu. */
    private void scrollToNode(ScrollPane sp, Node node, double sec) throws Exception {
        double[] v = onFxGet(() -> {
            Node content = sp.getContent();
            Bounds inContent = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double ch = content.getBoundsInLocal().getHeight();
            double vh = sp.getViewportBounds().getHeight();
            double target = ch <= vh ? 0 : Math.max(0, Math.min(1, (inContent.getMinY() - 8) / (ch - vh)));
            return new double[]{sp.getVvalue(), target};
        });
        if (Math.abs(v[0] - v[1]) > 0.002) scroll(sp, v[0], v[1], sec);
    }

    /** První Label, jehož text začíná daným slovem — pro řádky a záhlaví bloků. */
    private static Node labelled(Node node, String prefix) {
        if (node instanceof Labeled l && l.getText() != null && l.getText().startsWith(prefix)
                && l.isVisible()) return node;
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Node f = labelled(c, prefix);
                if (f != null) return f;
            }
        }
        return null;
    }

    // ── scéna ───────────────────────────────────────────────────────────────────────────────

    private void buildScene() {
        try {
            Class<?> cls = Class.forName("cz.digitalnikontinuita.anep.ui.MainView");
            view = cls.getDeclaredConstructor().newInstance();
            appRoot = (Parent) cls.getMethod("getRoot").invoke(view);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        overlay = new Pane();
        overlay.setMouseTransparent(true);
        overlay.setPrefSize(W, H);

        highlight = new Rectangle();
        highlight.setFill(Color.TRANSPARENT);
        highlight.setStroke(Color.web("#f28c00"));
        highlight.setStrokeWidth(3.5);
        highlight.setArcWidth(14);
        highlight.setArcHeight(14);
        highlight.setVisible(false);

        ring = new Circle(0, Color.TRANSPARENT);
        ring.setStroke(Color.web("#f28c00"));
        ring.setStrokeWidth(3);
        ring.setVisible(false);

        Label icon = new Label("PDF");
        icon.setStyle("-fx-background-color: #c62828; -fx-text-fill: white; -fx-font-weight: bold;"
                + " -fx-font-size: 11px; -fx-padding: 3 5 3 5; -fx-background-radius: 3;");
        Label fname = new Label();
        fname.setStyle("-fx-font-size: 13px; -fx-text-fill: #222;");
        fileChip = new HBox(8, icon, fname);
        fileChip.setAlignment(Pos.CENTER_LEFT);
        fileChip.setStyle("-fx-background-color: rgba(255,255,255,0.95); -fx-padding: 6 10 6 8;"
                + " -fx-background-radius: 5; -fx-border-color: #9e9e9e; -fx-border-radius: 5;");
        fileChip.setEffect(new DropShadow(8, Color.rgb(0, 0, 0, 0.35)));
        fileChip.setVisible(false);
        fileChip.setOpacity(0.92);

        cursor = new SVGPath();
        cursor.setContent("M0,0 L0,19 L4.8,14.6 L8.2,22.2 L11.2,20.9 L7.9,13.5 L14.2,13.5 Z");
        cursor.setFill(Color.WHITE);
        cursor.setStroke(Color.BLACK);
        cursor.setStrokeWidth(1.3);
        cursor.setScaleX(1.25);
        cursor.setScaleY(1.25);
        cursor.setEffect(new DropShadow(4, 1, 2, Color.rgb(0, 0, 0, 0.4)));

        caption = new Label();
        caption.setWrapText(true);
        caption.setTextAlignment(TextAlignment.CENTER);
        caption.setAlignment(Pos.CENTER);
        caption.setMaxWidth(W * 0.78);
        caption.setStyle("-fx-background-color: rgba(20,24,32,0.86); -fx-text-fill: white;"
                + " -fx-font-size: 21px; -fx-padding: 10 22 12 22; -fx-background-radius: 8;");
        caption.setVisible(false);

        cardTitle = new Label();
        cardTitle.setStyle("-fx-font-size: 38px; -fx-font-weight: bold; -fx-text-fill: white;");
        cardSub = new Label();
        cardSub.setStyle("-fx-font-size: 24px; -fx-text-fill: #d6e4f0;");
        VBox cardBox = new VBox(18, cardTitle, cardSub);
        cardBox.setAlignment(Pos.CENTER);
        card = new StackPane(cardBox);
        card.setStyle("-fx-background-color: linear-gradient(to bottom right, #1d3557, #274c77);");
        card.setPrefSize(W, H);
        card.setVisible(false);

        overlay.getChildren().addAll(highlight, ring, fileChip, cursor);
        StackPane capLayer = new StackPane(caption);
        capLayer.setMouseTransparent(true);
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 26, 0));
        stack = new StackPane(appRoot, overlay, capLayer, card);
        Scene scene = new Scene(stack, W, H);
        var css = getClass().getResource("/css/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        img = new WritableImage((int) (W * SCALE), (int) (H * SCALE));
        buf = new byte[(int) (W * SCALE) * (int) (H * SCALE) * 4];
        placeCursor();
    }

    private void fileLabel(String name) {
        ((Label) fileChip.getChildren().get(1)).setText(name);
    }

    private void placeCursor() {
        // SVGPath se škáluje kolem středu; hrot šipky má zůstat přesně v (cx, cy).
        Bounds b = cursor.getLayoutBounds();
        double s = cursor.getScaleX();
        cursor.setLayoutX(cx + (s - 1) * b.getCenterX());
        cursor.setLayoutY(cy + (s - 1) * b.getCenterY());
        fileChip.setLayoutX(cx + 14);
        fileChip.setLayoutY(cy + 18);
    }

    // ── kroky scénáře, každý vydá své snímky ───────────────────────────────────────────────

    private void hold(double sec) throws Exception {
        for (int i = 0, n = n(sec); i < n; i++) frame();
    }

    private void moveTo(double x, double y, double sec) throws Exception {
        double x0 = cx, y0 = cy;
        int n = n(sec);
        for (int i = 1; i <= n; i++) {
            double t = ease((double) i / n);
            // Mírný oblouk, ať pohyb nevypadá jako po pravítku.
            double arc = Math.sin(Math.PI * t) * Math.min(60, Math.hypot(x - x0, y - y0) * 0.12);
            cx = x0 + (x - x0) * t;
            cy = y0 + (y - y0) * t - arc;
            onFx(this::placeCursor);
            frame();
        }
    }

    private void click(double sec) throws Exception {
        int n = n(sec);
        onFx(() -> { ring.setCenterX(cx); ring.setCenterY(cy); ring.setVisible(true); });
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            onFx(() -> { ring.setRadius(6 + 22 * t); ring.setOpacity(1 - t); });
            frame();
        }
        onFx(() -> ring.setVisible(false));
    }

    private void scroll(ScrollPane sp, double from, double to, double sec) throws Exception {
        int n = n(sec);
        for (int i = 1; i <= n; i++) {
            double t = ease((double) i / n);
            onFx(() -> sp.setVvalue(from + (to - from) * t));
            frame();
        }
    }

    private void say(String text) throws Exception {
        onFx(() -> {
            caption.setText(text == null ? "" : text);
            caption.setVisible(text != null);
        });
    }

    private void highlightOn(Node node) throws Exception {
        onFx(() -> {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            highlight.setX(b.getMinX() - 5);
            highlight.setY(b.getMinY() - 5);
            highlight.setWidth(b.getWidth() + 10);
            highlight.setHeight(b.getHeight() + 10);
            highlight.setVisible(true);
        });
    }

    private void highlightOff() throws Exception {
        onFx(() -> highlight.setVisible(false));
    }

    private void showCard(String title, String sub) throws Exception {
        onFx(() -> {
            cardTitle.setText(title);
            cardSub.setText(sub);
            card.setOpacity(1);
            card.setVisible(true);
        });
    }

    private void fadeCard(double sec) throws Exception {
        int n = n(sec);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            onFx(() -> card.setOpacity(1 - t));
            frame();
        }
        onFx(() -> card.setVisible(false));
    }

    /** Pár průchodů frontou FX, aby se karta po změně dopočítala dřív, než ji snímek zastihne. */
    private void settle() throws Exception {
        for (int i = 0; i < 6; i++) onFx(() -> { stack.applyCss(); stack.layout(); });
    }

    private void frame() throws Exception {
        onFx(() -> {
            stack.applyCss();
            stack.layout();
            SnapshotParameters sp = new SnapshotParameters();
            sp.setTransform(Transform.scale(SCALE, SCALE));
            sp.setFill(Color.WHITE);
            stack.snapshot(sp, img);
            img.getPixelReader().getPixels(0, 0, (int) img.getWidth(), (int) img.getHeight(),
                    PixelFormat.getByteBgraPreInstance(), buf, 0, (int) img.getWidth() * 4);
        });
        ffmpeg.write(buf);
        frames++;
    }

    private static int n(double sec) {
        return Math.max(1, (int) Math.round(sec * FPS));
    }

    private static double ease(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    // ── pomůcky ─────────────────────────────────────────────────────────────────────────────

    private Bounds sceneBounds(Node node) throws Exception {
        return onFxGet(() -> node.localToScene(node.getBoundsInLocal()));
    }

    private static void handler(javafx.event.EventHandler<? super javafx.scene.input.MouseEvent> h) {
        // Obsluha najetí v MainView událost nečte, jen přepne styl tlačítka.
        if (h != null) {
            try { h.handle(null); } catch (RuntimeException ignored) { }
        }
    }

    private static Node tabHeader(TabPane tabs, String text) {
        for (Node n : tabs.lookupAll(".tab")) {
            List<String> texts = new ArrayList<>();
            collectTexts(n, texts);
            if (texts.contains(text)) return n;
        }
        return null;
    }

    private static void collectTexts(Node node, List<String> out) {
        if (node instanceof Labeled l && l.getText() != null) out.add(l.getText());
        if (node instanceof Text t && t.getText() != null) out.add(t.getText());
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collectTexts(c, out);
        }
    }

    private static ScrollPane findScroll(Node node) {
        if (node instanceof ScrollPane sp) return sp;
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                ScrollPane f = findScroll(c);
                if (f != null) return f;
            }
        }
        return null;
    }

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

    private static <T> T onFxGet(java.util.function.Supplier<T> s) throws InterruptedException {
        Object[] box = new Object[1];
        onFx(() -> box[0] = s.get());
        @SuppressWarnings("unchecked") T t = (T) box[0];
        return t;
    }

    private static void await(java.util.function.BooleanSupplier cond, int seconds)
            throws Exception {
        for (int i = 0; i < seconds * 4; i++) {
            boolean[] ok = new boolean[1];
            onFx(() -> ok[0] = cond.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(250);
        }
        System.out.println("POZOR: čekání vypršelo");
    }

    private static boolean flag(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(target);
        } catch (Exception e) {
            return true;
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(name, e);
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

    private static String text(Node node) {
        List<String> all = new ArrayList<>();
        collectTexts(node, all);
        return String.join("\n", all);
    }
}
