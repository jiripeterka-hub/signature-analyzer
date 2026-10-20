package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.ExtendedView;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Prints the shape of a card in the Rozšířený přehled: the rows above the blocks, then every
 * block with its title, whether it opens expanded, and the labels inside it.
 *
 * <p>Written for the restructuring of 26. 8. 2026, when the card went from one run of rows to
 * a short head plus nine named blocks. Which blocks a given document actually gets depends on
 * what it carries — a seal has no visualisation, a B-level signature no time-stamps, a
 * standalone time-stamp neither — and that cannot be read off the source: the conditions sit in
 * nine separate builders. This walks real documents and reports what a reader would see.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.tools.ExtendedLayoutSmoke [-rows] &lt;file|folder&gt;…</pre>
 *
 * <p>{@code -rows} also prints each row's value, not just its label.
 */
public final class ExtendedLayoutSmoke extends Application {

    private boolean withValues;
    private boolean withStyle;
    private java.io.File evidenceRecord;

    @Override
    public void start(Stage stage) {
        List<String> args = new ArrayList<>(getParameters().getRaw());
        withValues = args.remove("-rows");
        withStyle = args.remove("-style");
        // Evidenční záznam je samostatný soubor předaný vedle dokumentu — jinak se blok
        // „Evidenční záznamy" nedá vidět vůbec.
        int er = args.indexOf("-er");
        if (er >= 0 && er + 1 < args.size()) {
            evidenceRecord = Path.of(args.remove(er + 1)).toFile();
            args.remove(er);
        }
        if (args.isEmpty()) {
            System.out.println("usage: ExtendedLayoutSmoke [-rows] [-er <záznam>] <file|folder>…");
            Platform.exit();
            return;
        }
        new Thread(() -> {
            try {
                report(args);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "extended-layout-smoke").start();
    }

    private void report(List<String> args) throws Exception {
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();

        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }

        for (Path path : files) {
            System.out.println("== " + path.getFileName());
            try {
                DSSDocument doc = loader.load(path.toFile());
                // Vizualizace se nebere z ověření, ale z PDF vedle něj — bez tohohle průchodu
                // by sonda blok „Vizualizace podpisu" nikdy neviděla, ať dokument nese cokoli.
                var source = cz.digitalnikontinuita.anep.core.PdfSource.of(path.toFile(), doc);
                var appearances = cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor
                        .extract(source, null);
                var tools = cz.digitalnikontinuita.anep.core.PdfSigningToolExtractor.extract(source, null);
                List<DSSDocument> records = evidenceRecord == null ? List.of()
                        : List.of(loader.load(evidenceRecord));
                var outcome = factory.validate(doc, (DSSDocument) null, verifier, null, records);
                // Osiřelé podpisy stejnou cestou jako MainView — jinak by sonda podpisy
                // adbe.x509.rsa_sha1 vůbec neviděla.
                var orphans = cz.digitalnikontinuita.anep.core.Pkcs1SignatureValidator.verifyAll(
                        cz.digitalnikontinuita.anep.core.PdfOrphanSignatureDetector.detect(source, null),
                        verifier, settings, outcome.reports().getDiagnosticData(),
                        outcome.reports().getSimpleReport());
                ValidationResult result = new ReportMapper().map(path.getFileName().toString(),
                        null, outcome, settings, appearances, orphans, false, tools);
                dump(result);
            } catch (Exception | StackOverflowError e) {
                System.out.println("   !! " + e);
            }
        }
        System.out.println();
        System.out.println("souborů: " + files.size());
    }

    /** Builds the view on the FX thread and walks the tree it produced. */
    private void dump(ValidationResult result) throws Exception {
        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                ExtendedView view = new ExtendedView();
                view.show(result);
                Parent root = view.getRoot();
                new javafx.scene.Scene(root, 900, 700);
                root.applyCss();
                root.layout();
                for (Node card : cards(root)) {
                    System.out.println("   -- karta");
                    walk(card, "      ", false);
                }
            } catch (Exception e) {
                System.out.println("   !! " + e);
                e.printStackTrace(System.out);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
    }

    /** The per-signature cards: VBoxes carrying the card border style. */
    private static List<Node> cards(Parent root) {
        List<Node> out = new ArrayList<>();
        collectCards(root, out);
        return out;
    }

    private static void collectCards(Node node, List<Node> out) {
        if (node instanceof VBox v && v.getStyle().contains("-fx-border-radius: 4")
                && v.getStyle().contains("-fx-border-color")) {
            out.add(v);
            return;
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collectCards(child, out);
        }
    }

    /**
     * Walks one card. A collapsible block is a VBox whose first child is the header HBox that
     * {@code AdvisorView.collapsibleBlock} builds — an arrow Label followed by the title.
     */
    private void walk(Node node, String indent, boolean insideBlock) {
        if (node instanceof VBox box && isBlock(box)) {
            HBox header = (HBox) box.getChildren().get(0);
            String arrow = ((Label) header.getChildren().get(0)).getText();
            String title = ((Label) header.getChildren().get(1)).getText();
            Node content = box.getChildren().get(1);
            System.out.println(indent + "[" + arrow + "] " + title
                    + (content.isVisible() ? "  (rozbalený)" : "  (zabalený)"));
            if (withStyle) reportHeaderStyle(header, content, indent + "    ");
            walk(content, indent + "    ", true);
            return;
        }
        if (node instanceof HBox row && !row.getChildren().isEmpty()
                && row.getChildren().get(0) instanceof Label lbl
                && lbl.getMinWidth() >= 80) {
            System.out.println(indent + (insideBlock ? "" : "řádek: ") + lbl.getText()
                    + (withValues ? "  " + value(row) : ""));
            return;
        }
        // Labeled se musí ptát PŘED Parent: Label je Parent taky, a sestup do jeho skinu vrací
        // zkrácený text z vykreslení ("…") místo toho, co v něm doopravdy stojí.
        if (node instanceof Labeled l) {
            if (l.getText() != null && !l.getText().isBlank()) {
                System.out.println(indent + "text: " + shorten(l.getText().trim()));
            }
            return;
        }
        if (node instanceof javafx.scene.image.ImageView view) {
            System.out.println(indent + "OBRÁZEK " + (int) view.getImage().getWidth() + "×"
                    + (int) view.getImage().getHeight());
            return;
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) walk(child, indent, insideBlock);
        } else if (node instanceof Text t && t.getText() != null && !t.getText().isBlank()) {
            System.out.println(indent + "text: " + shorten(t.getText().trim()));
        }
    }

    /**
     * Písmo nadpisu a podklad záhlaví — v klidu, pod myší a po skutečném stisku.
     *
     * <p>Obsluhy se volají tak, jak by je zavolala myš: {@code handle(null)} stačí, protože
     * ani jedna se událostí nezabývá, a jde přesně o tu cestu, kterou vezme kliknutí. Kdyby se
     * jen četl styl nastavený při stavbě, neřeklo by to nic o tom, co se stane po rozbalení.
     */
    private static void reportHeaderStyle(HBox header, Node content, String indent) {
        Label title = (Label) header.getChildren().get(1);
        System.out.println(indent + "písmo nadpisu: " + fontSize(title)
                + ", obsah řádků: " + contentFontSize(content));
        System.out.println(indent + "podklad v klidu:  " + background(header));
        header.getOnMouseEntered().handle(null);
        System.out.println(indent + "podklad pod myší: " + background(header));
        header.getOnMouseExited().handle(null);
        header.getOnMouseClicked().handle(null);
        System.out.println(indent + "po stisku:        " + background(header)
                + (content.isVisible() ? "  (rozbalilo se)" : "  (sbalilo se)"));
        header.getOnMouseExited().handle(null);
        System.out.println(indent + "po odchodu myši:  " + background(header));
        // Vrátit do výchozího stavu, ať výpis níž nelže o tom, co uvidí čtenář.
        header.getOnMouseClicked().handle(null);
        header.getOnMouseExited().handle(null);
    }

    private static String fontSize(Label label) {
        String style = label.getStyle();
        int i = style.indexOf("-fx-font-size:");
        return i < 0 ? "(zděděno z .root = 13px)"
                : style.substring(i + 14, style.indexOf(";", i)).trim();
    }

    /** Velikost písma prvního řádku uvnitř bloku, se kterou se má nadpis srovnat. */
    private static String contentFontSize(Node content) {
        if (content instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (child instanceof HBox row && !row.getChildren().isEmpty()
                        && row.getChildren().get(0) instanceof Label lbl
                        && lbl.getMinWidth() >= 80) {
                    return fontSize(lbl);
                }
            }
        }
        return "?";
    }

    private static String background(HBox header) {
        String style = header.getStyle();
        int i = style.indexOf("-fx-background-color:");
        return i < 0 ? "(nenastaveno)" : style.substring(i + 21, style.indexOf(";", i)).trim();
    }

    private static boolean isBlock(VBox box) {
        if (box.getChildren().size() != 2) return false;
        if (!(box.getChildren().get(0) instanceof HBox header)) return false;
        if (header.getChildren().size() != 2) return false;
        if (!(header.getChildren().get(0) instanceof Label arrow)) return false;
        return "▾".equals(arrow.getText()) || "▸".equals(arrow.getText());
    }

    private static String value(HBox row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < row.getChildren().size(); i++) {
            String t = text(row.getChildren().get(i));
            if (!t.isBlank()) sb.append(sb.length() > 0 ? "  " : "").append(t.trim());
        }
        String s = sb.toString().replace("\n", " ");
        return s.length() <= 240 ? s : s.substring(0, 239) + "…";
    }

    private static String shorten(String s) {
        String one = s.replace("\n", " ");
        return one.length() <= 90 ? one : one.substring(0, 89) + "…";
    }

    private static String text(Node node) {
        if (node instanceof Labeled l) return l.getText() == null ? "" : l.getText();
        if (node instanceof TextInputControl t) return t.getText() == null ? "" : t.getText();
        if (node instanceof Text t) return t.getText() == null ? "" : t.getText();
        if (node instanceof TextFlow f) {
            StringBuilder sb = new StringBuilder();
            for (Node c : f.getChildrenUnmodifiable()) sb.append(text(c));
            return sb.toString();
        }
        if (node instanceof Parent p) {
            StringBuilder sb = new StringBuilder();
            for (Node c : p.getChildrenUnmodifiable()) sb.append(text(c));
            return sb.toString();
        }
        return "";
    }

    public static void main(String[] args) {
        launch(args);
    }
}
