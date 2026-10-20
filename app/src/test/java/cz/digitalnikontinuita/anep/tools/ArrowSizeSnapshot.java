package cz.digitalnikontinuita.anep.tools;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * Vykreslí záhlaví rozbalovacího bloku v několika velikostech šipky a uloží je jako PNG.
 *
 * <p>Velikost prvku se nedá vybrat z čísel v kódu — 14 px u „malého“ trojúhelníku U+25B8 a 14 px
 * u „plného“ U+25BA vykreslí znatelně jinak velkou značku, protože rozdíl je v kresbě znaku,
 * ne jen v písmu. Sonda proto staví tytéž řádky, jaké staví
 * {@code AdvisorView.collapsibleBlock}, a snímek bere ze skutečné scény JavaFX.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.ArrowSizeSnapshot \
 *       -Dexec.args="-out D:\out\sipky.png"
 * </pre>
 */
public final class ArrowSizeSnapshot extends Application {

    /** Jedna nabízená velikost: dvojice znaků, velikost písma a šířka sloupce se šipkou. */
    private record Variant(String key, String popis, String open, String shut,
                           int fontSize, int minWidth, boolean rotate, double polygon) {
        Variant(String key, String popis, String open, String shut, int fontSize, int minWidth) {
            this(key, popis, open, shut, fontSize, minWidth, false, 0);
        }
    }

    private static final Variant[] VARIANTS = {
            new Variant("A", "malý trojúhelník 12 px — nynější stav", "▾", "▸", 12, 14),
            new Variant("B", "malý trojúhelník 14 px", "▾", "▸", 14, 16),
            new Variant("C", "malý trojúhelník 16 px", "▾", "▸", 16, 18),
            new Variant("D", "malý trojúhelník 18 px", "▾", "▸", 18, 20),
            new Variant("E", "plný trojúhelník 11 px", "▼", "►", 11, 16),
            new Variant("F", "plný trojúhelník 13 px", "▼", "►", 13, 18),
            new Variant("G", "plný trojúhelník 15 px", "▼", "►", 15, 20),
            new Variant("H", "souměrný trojúhelník 11 px", "▼", "▶", 11, 16),
            new Variant("I", "souměrný trojúhelník 13 px", "▼", "▶", 13, 18),
            new Variant("J", "souměrný trojúhelník 15 px", "▼", "▶", 15, 20),
            // Zavřený stav je totéž ▼ otočené o čtvrt otáčky. Dvojice je tím souměrná bez
            // ohledu na to, jak která kresba pravostranného znaku v písmu vypadá.
            new Variant("K", "otočené ▼ 11 px", "▼", "▼", 11, 16, true, 0),
            new Variant("L", "otočené ▼ 13 px", "▼", "▼", 13, 18, true, 0),
            new Variant("M", "otočené ▼ 15 px", "▼", "▼", 15, 20, true, 0),
            // Kreslený tvar místo znaku: velikost je přesně to, co se zadá, a na písmu
            // nezávisí vůbec.
            new Variant("N", "kreslený trojúhelník, strana 9 px", null, null, 0, 16, false, 9),
            new Variant("O", "kreslený trojúhelník, strana 11 px", null, null, 0, 18, false, 11),
            new Variant("P", "kreslený trojúhelník, strana 13 px", null, null, 0, 20, false, 13),
    };

    /** Klíče variant, které se mají vykreslit; {@code null} znamená všechny. */
    private String only;

    @Override
    public void start(Stage stage) {
        String out = "sipky.png", only = null;
        var args = getParameters().getRaw();
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "-out" -> out = args.get(++i);
                // Užší výběr, když se srovnává jen několik variant: -only A,B,L,O
                case "-only" -> only = args.get(++i);
                default -> { }
            }
        }
        final String outPath = out;
        this.only = only;
        Platform.runLater(() -> {
            try {
                render(outPath);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        });
    }

    private void render(String out) throws Exception {
        VBox page = new VBox(0);
        page.setPadding(new Insets(14));
        page.setStyle("-fx-background-color: #f4f4f4;");

        java.util.List<Variant> chosen = new java.util.ArrayList<>();
        for (Variant v : VARIANTS) {
            if (only == null || java.util.Arrays.asList(only.split(",")).contains(v.key())) {
                chosen.add(v);
            }
        }
        for (Variant v : chosen) {
            Label caption = new Label(v.key() + " — " + v.popis());
            caption.setStyle("-fx-text-fill: #777777; -fx-font-size: 11px;");
            caption.setPadding(new Insets(12, 0, 3, 0));

            HBox shut = header(v, false);
            HBox open = header(v, true);
            // Řádek obsahu pod otevřeným záhlavím — velikost šipky se posuzuje proti textu,
            // který blok nese, ne proti prázdné ploše.
            Label body = new Label("Podpis byl vytvořen kvalifikovaným prostředkem.");
            body.setStyle("-fx-font-size: 13px; -fx-text-fill: #111111;");
            body.setPadding(new Insets(4, 12, 6, 30));

            page.getChildren().addAll(caption, shut, open, body);
        }

        Scene scene = new Scene(page, 520, 100);
        page.applyCss();
        page.layout();
        page.resize(520, page.prefHeight(520));
        page.layout();

        // Naměřené výšky záhlaví: větší písmo šipky zvedá řádek, a to je cena, kterou volba
        // platí. Bez čísla by se o ní jen hádalo.
        for (int i = 0; i < chosen.size(); i++) {
            HBox shut = (HBox) page.getChildren().get(i * 4 + 1);
            javafx.scene.Node arrow = shut.getChildren().get(0);
            System.out.printf("%s  výška záhlaví %.1f px, šipka %.1f × %.1f px%n",
                    chosen.get(i).key(), shut.getHeight(),
                    arrow.getLayoutBounds().getWidth(), arrow.getLayoutBounds().getHeight());
        }

        write(page, out, 1);
        write(page, out.replace(".png", "-2x.png"), 2);
        System.out.println("scéna: " + (int) page.getWidth() + "×" + (int) page.getHeight());
    }

    /** Totéž složení jako v {@code AdvisorView.collapsibleBlock}: šipka, mezera 4, nadpis. */
    private static HBox header(Variant v, boolean expanded) {
        javafx.scene.layout.Region arrow;
        if (v.polygon() > 0) {
            double a = v.polygon();
            // Rovnostranný trojúhelník o straně a, špičkou dolů (otevřeno) nebo doprava.
            double h = a * Math.sqrt(3) / 2;
            javafx.scene.shape.Polygon tri = expanded
                    ? new javafx.scene.shape.Polygon(0, 0, a, 0, a / 2, h)
                    : new javafx.scene.shape.Polygon(0, 0, h, a / 2, 0, a);
            tri.setFill(javafx.scene.paint.Color.web("#555555"));
            javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(tri);
            holder.setMinWidth(v.minWidth());
            holder.setPrefWidth(v.minWidth());
            arrow = holder;
        } else {
            Label lbl = new Label(expanded ? v.open() : v.shut());
            lbl.setStyle("-fx-text-fill: #555555; -fx-font-size: " + v.fontSize() + "px;");
            lbl.setMinWidth(v.minWidth());
            if (v.rotate() && !expanded) lbl.setRotate(-90);
            arrow = lbl;
        }

        Label title = new Label("Průběh ověřování");
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: #111111; -fx-font-size: 13px;");

        HBox header = new HBox(4, arrow, title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 12, 6, 12));
        header.setStyle(expanded ? "-fx-background-color: #e4e4e4;" : "-fx-background-color: transparent;");
        return header;
    }

    private static void write(Region node, String out, int scale) throws Exception {
        SnapshotParameters sp = new SnapshotParameters();
        if (scale != 1) sp.setTransform(new Scale(scale, scale));
        WritableImage img = node.snapshot(sp, null);
        File png = new File(out);
        if (png.getParentFile() != null) png.getParentFile().mkdirs();
        ImageIO.write(toBufferedImage(img), "png", png);
        System.out.println("uloženo: " + png.getAbsolutePath()
                + "  " + (int) img.getWidth() + "×" + (int) img.getHeight());
    }

    /** Převod bez {@code SwingFXUtils} — modul {@code javafx.swing} v téhle sestavě není. */
    private static java.awt.image.BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.setRGB(x, y, reader.getArgb(x, y));
        }
        return out;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
