package cz.digitalnikontinuita.anep.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.InputStream;

/**
 * Okno „O aplikaci“ — co AnEP je, k čemu slouží, kdo za ním stojí a pod čím se šíří.
 *
 * <h2>Proč vzniklo</h2>
 *
 * <p>Věta o tom, že aplikace slouží výuce a že výsledky jsou bez záruky, stála dlouho jen
 * v patičce tištěného protokolu. Kdo si protokol nevytiskl, nedozvěděl se to nikde — a u nástroje,
 * který odpovídá na otázku „platí tenhle podpis?“, je to sdělení, které má být po ruce vždycky,
 * ne jen na papíře.
 *
 * <p>Druhý důvod je licenční. AnEP se šíří pod EUPL a ta stojí na tom, že je určitelný
 * poskytovatel licence. Aplikace o své licenci ani o svém autorovi dosud nikde neříkala nic;
 * bylo to jen v souborech vedle ní, kam se běžný čtenář nedívá.
 *
 * <h2>Co se odkud bere</h2>
 *
 * <p>Verze se čte z manifestu ({@code Implementation-Version}), ne z konstanty v kódu: číslo
 * v kódu se dřív nebo později rozejde s tím, co je v POMu, a nikdo si toho nevšimne. Když se
 * aplikace spustí z přeložených tříd a ne z archivu, manifest není a verze se neukáže — to je
 * v pořádku, je to stav při vývoji.
 */
final class AboutDialog {

    private AboutDialog() { }

    private static final String TITLE = "O aplikaci";
    /** Šířka obsahu. Okno se jí drží; bez toho si scéna vzala šířku podle nejdelšího
     *  odkazu, protože Hyperlink se nezalamuje, a okno vyšlo dvakrát širší, než mělo. */
    private static final double WIDTH = 500;
    private static final String NAME = "AnEP — Analyzátor elektronických podpisů";
    private static final String DSS_URL =
            "https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/";
    private static final String VERAPDF_URL = "https://verapdf.org/";
    private static final String EUPL_URL =
            "https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12";
    /** Stránka, ze které aplikace pochází — u licence EUPL je určitelný původ součást věci. */
    private static final String SOURCE_URL = "https://bajecnysvet.cz/anep";

    static void show(Window owner) {
        Stage stage = new Stage();
        stage.setTitle(TITLE);
        // Ikonu okna nenastavujeme: Main hlídá seznam oken a každému, které si ji
        // nenastavilo samo, ji doplní. Dělat to tady by tu úvahu jen zdvojilo.
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }

        VBox content = buildContent();

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(WIDTH);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: #ffffff;");

        Button close = new Button("Zavřít");
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 16, 12, 16));

        VBox root = new VBox(scroll, buttons);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #ffffff;");

        Scene scene = new Scene(root);
        // Písmo aplikace, ne výchozí písmo JavaFX: styly se přebírají z okna, které dialog
        // otevřelo, stejně jako to dělá OfficePeekDialog.
        if (owner instanceof Stage ownerStage && ownerStage.getScene() != null) {
            scene.getStylesheets().addAll(ownerStage.getScene().getStylesheets());
        }
        stage.setScene(scene);
        // Okno se srovná na obsah. Strop je pojistka pro případ, že by se texty
        // rozrostly; do té doby se posuvník neukáže vůbec.
        stage.sizeToScene();
        stage.setMaxHeight(620);
        stage.showAndWait();
    }

    /**
     * Obsah okna. Oddělené od {@link #show}, aby se dal vysadit a přečíst bez otevírání
     * modálního okna — formulace jsou tu to hlavní a na ty se musí dát podívat. Táž úvaha
     * jako u {@code OfficePeekDialog}.
     */
    static VBox buildContent() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(16));
        content.setStyle("-fx-background-color: #ffffff;");
        content.setMaxWidth(WIDTH);

        content.getChildren().add(header());

        // Účel a záruka stojí spolu v jednom zvýrazněném bloku hned pod hlavičkou. Patří
        // k sobě — obojí říká, co od aplikace čekat — a jako jeden blok se čtou najednou
        // místo aby je od sebe oddělil nadpis. Je to jediné sdělení v tomhle okně, které
        // může čtenáře uchránit od chybného závěru; ostatní jsou údaje.
        content.getChildren().add(purposeBlock());

        content.getChildren().add(sectionLabel("Autor"));
        content.getChildren().add(row("Jiří Peterka",
                link("jiri@peterka.cz", "mailto:jiri@peterka.cz")));

        content.getChildren().add(sectionLabel("Zdroj"));
        content.getChildren().add(new HBox(link(SOURCE_URL, SOURCE_URL)));

        content.getChildren().add(sectionLabel("Licence"));
        content.getChildren().add(paragraph(
                "Evropská unijní veřejná licence (EUPL) verze 1.2; české znění má"
                + " rovnocennou právní sílu."));
        content.getChildren().add(row("Úplné znění", link("EUPL 1.2", EUPL_URL)));
        licenceFolder(content);

        content.getChildren().add(sectionLabel("Použité knihovny"));
        content.getChildren().add(paragraph(
                "Ověřování obstarává knihovna DSS Evropské komise, referenční implementace"
                + " norem ETSI. Shodu dokumentu s formátem PDF/A posuzuje veraPDF, referenční"
                + " validátor téhož formátu. Vedle nich přes sto dalších knihoven"
                + " s otevřeným kódem."));
        content.getChildren().add(row("DSS", link("Digital Signature Services", DSS_URL)));
        content.getChildren().add(row("veraPDF", link("veraPDF", VERAPDF_URL)));

        return content;
    }

    /** Ikona, jméno a verze. */
    private static HBox header() {
        HBox box = new HBox(12);
        box.setAlignment(Pos.CENTER_LEFT);
        try (InputStream in = AboutDialog.class.getResourceAsStream("/app-icon.png")) {
            if (in != null) {
                ImageView icon = new ImageView(new Image(in, 48, 48, true, true));
                box.getChildren().add(icon);
            }
        } catch (Exception ignored) {
            // Bez ikony se okno obejde; jméno a verze jsou to podstatné.
        }
        Label name = new Label(NAME);
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #111111;");
        name.setWrapText(true);

        VBox text = new VBox(2, name);
        String version = version();
        if (version != null) {
            Label v = new Label("verze " + version);
            v.setStyle("-fx-text-fill: #555555;");
            text.getChildren().add(v);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        box.getChildren().add(text);
        return box;
    }

    /**
     * Odkaz na adresář s licenčními texty, ale jen když tam opravdu je.
     *
     * <p>V zabalené aplikaci leží {@code licenses\} vedle spustitelného souboru; při spuštění
     * z Mavenu neexistuje. Nabízet tlačítko, které nic neotevře, je horší než ho nenabídnout.
     */
    private static void licenceFolder(VBox content) {
        File dir = licensesDir();
        if (dir == null) return;
        Button open = new Button("Licence použitých knihoven…");
        open.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().open(dir);
            } catch (Exception ignored) {
                // Nejde-li otevřít, cesta zůstává v popisku vedle.
            }
        });
        Tooltip.install(open, new Tooltip(dir.getAbsolutePath()));
        content.getChildren().add(new HBox(open));
    }

    /** {@code licenses\} vedle aplikace, nebo {@code null}, když tam není. */
    private static File licensesDir() {
        try {
            File jar = new File(AboutDialog.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // V zabalené podobě: <AnEP>\app\signature-analyzer.jar → <AnEP>\licenses
            File dir = jar.getParentFile();
            for (int up = 0; up < 3 && dir != null; up++, dir = dir.getParentFile()) {
                File candidate = new File(dir, "licenses");
                if (candidate.isDirectory()) return candidate;
            }
        } catch (Exception ignored) {
            // Spuštěno jinak, než čekáme; složka se prostě nenabídne.
        }
        return null;
    }

    /**
     * Verze z manifestu archivu, ze kterého tahle třída pochází; {@code null} při spuštění
     * z přeložených tříd.
     *
     * <p>Manifest se čte přímo z archivu, ne přes {@code Package.getImplementationVersion()}.
     * To druhé vrací verzi z definice balíčku, a ta se bere z toho, odkud se balíček načetl
     * jako první — při smíšené třídní cestě (archiv i přeložené třídy, což je případ sond)
     * to může být místo bez manifestu a verze pak chybí, ačkoli v archivu je. Změřeno.
     */
    static String version() {
        try {
            java.net.URL src = AboutDialog.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            File f = new File(src.toURI());
            if (!f.isFile()) return null;                 // přeložené třídy, ne archiv
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(f)) {
                java.util.jar.Manifest mf = jar.getManifest();
                if (mf == null) return null;
                String v = mf.getMainAttributes().getValue("Implementation-Version");
                return v == null || v.isBlank() ? null : v;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Stavební prvky ───────────────────────────────────────────────────────────────────────

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #003189;"
                + " -fx-padding: 4 0 0 0;");
        return l;
    }

    private static Label paragraph(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #333333;");
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    /**
     * K čemu aplikace je a co od ní nečekat — okrově, jako poznámky jinde v aplikaci.
     *
     * <p>Účel není zvýrazněn tučně a vyloučení záruky ano: obojí tučně by nezvýraznilo nic.
     */
    private static VBox purposeBlock() {
        Label purpose = new Label("Aplikace slouží výhradně potřebám výuky a seznamování se"
                + " s problematikou elektronických podpisů.");
        purpose.setStyle("-fx-text-fill: #7a4a00;");
        purpose.setWrapText(true);

        Label warranty = new Label("Výsledek ověření i jakýkoli další výstup aplikace AnEP"
                + " jsou poskytovány bez jakékoli záruky a nenahrazují ověření provedené"
                + " k tomu určeným nástrojem či službou.");
        warranty.setStyle("-fx-text-fill: #7a4a00; -fx-font-weight: bold;");
        warranty.setWrapText(true);

        VBox box = new VBox(6, purpose, warranty);
        box.setStyle("-fx-background-color: #fff8e8; -fx-padding: 8 10 8 10;");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static HBox row(String label, Region value) {
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: #111111;");
        l.setMinWidth(110);
        l.setPrefWidth(110);
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox row = new HBox(6, l, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Hyperlink link(String label, String url) {
        Hyperlink h = new Hyperlink(label);
        h.setStyle("-fx-text-fill: #0a5ca8; -fx-padding: 0; -fx-border-width: 0;");
        Tooltip.install(h, new Tooltip(url));
        h.setOnAction(e -> {
            try {
                if (url.startsWith("mailto:")) {
                    java.awt.Desktop.getDesktop().mail(new java.net.URI(url));
                } else {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                }
            } catch (Exception ignored) {
                // Bez prohlížeče nebo poštovního programu zůstává adresa v bublině.
            }
        });
        return h;
    }
}
