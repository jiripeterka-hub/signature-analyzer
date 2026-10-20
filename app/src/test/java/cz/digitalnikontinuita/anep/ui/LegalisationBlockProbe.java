package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector;
import cz.digitalnikontinuita.anep.core.LegalisationLinkLog;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * Vypíše blok „Odkazované dokumenty z eLegalizace“ tak, jak ho uvidí čtenář v Rozšířeném přehledu.
 *
 * <p>Blok se staví ze dvou věcí, které spolu v běžném běhu nesouvisí: z odkazu přečteného
 * z přílohy a ze záznamu o kontrole, kterou uživatel provedl v samostatném okně. Sonda proto
 * dělá totéž co aplikace — přečte odkaz, předloží zadaný soubor ke kontrole a teprve pak postaví
 * kartu. Bez druhého souboru vypíše blok ve stavu „dosud nebyl předložen“.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.LegalisationBlockProbe \
 *       -Dexec.args="D:\...\11000_….pdf D:\...\dokument.pdf"
 * </pre>
 */
public final class LegalisationBlockProbe extends Application {

    private static final String TITLE = "Odkazované dokumenty z eLegalizace";

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        if (args.isEmpty()) {
            System.out.println("usage: LegalisationBlockProbe <dokument.pdf> [<odkazovaný soubor> …]");
            Platform.exit();
            return;
        }
        new Thread(() -> {
            try {
                run(args);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "legalisation-block-probe").start();
    }

    private void run(List<String> args) throws Exception {
        File source = new File(args.get(0));
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();

        LegalisationLinkInspector.Link link = LegalisationLinkInspector.read(source, null);
        System.out.println("odkaz v " + source.getName() + ": "
                + (link == null ? "žádný" : link.digests().size() + " otisků, typ " + link.type()));

        DSSDocument doc = new DocumentLoader().load(source);
        ValidationResult result = new ReportMapper().map(source.getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings);

        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                ExtendedView view = new ExtendedView();
                view.setFileInfo(source, null);
                view.setLegalisationLink(link);
                view.show(result);
                Parent root = view.getRoot();
                new Scene(root, 960, 700);
                root.applyCss();
                root.layout();
                System.out.println();
                System.out.println("### pořadí bloků na kartě");
                vypisNadpisy(root);
                System.out.println();
                System.out.println("### před kontrolou");
                if (!vypisBlok(root)) {
                    System.out.println("Blok „" + TITLE + "“ na kartě není.");
                }
                hotovaKarta[0] = root;
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) { while (!done[0]) lock.wait(); }

        // Kontroly až teď, nad hotovou kartou — právě to je stav, kvůli kterému blok poslouchá
        // záznam: okno se otevírá za výsledkem a čtenář v něm soubor předkládá dodatečně.
        // Řetěz jde tak, jak by ho udělal uživatel: druhý argument se předkládá k prvnímu,
        // třetí k druhému a tak dál.
        File at = source;
        LegalisationLinkInspector.Link atLink = link;
        for (int i = 1; i < args.size() && atLink != null; i++) {
            File candidate = new File(args.get(i));
            LegalisationLinkInspector.Check check = LegalisationLinkInspector.verify(atLink, candidate);
            LegalisationLinkInspector.Link next = LegalisationLinkInspector.read(candidate, null);
            LegalisationLinkLog.record(at, atLink, candidate, check, next);
            System.out.println("  kontrola " + candidate.getName() + " → " + check.outcome());
            at = candidate;
            atLink = next;
        }
        final boolean[] done2 = { false };
        // Dvě kola fronty: první doručí překreslení, které si záznam objednal, druhé počká,
        // až doběhne.
        Platform.runLater(() -> Platform.runLater(() -> {
            System.out.println();
            System.out.println(args.size() < 2 ? "### bez předloženého dokumentu" : "### po kontrole");
            if (hotovaKarta[0] == null || !vypisBlok(hotovaKarta[0])) {
                System.out.println("Blok „" + TITLE + "“ na kartě není.");
            }
            System.out.println();
            System.out.println("### Zjednodušený přehled");
            ResultView rv = new ResultView();
            rv.setFileInfo(source, null);
            rv.setLegalisationLink(link);
            rv.show(result);
            new Scene(rv.getRoot(), 960, 700);
            rv.getRoot().applyCss();
            rv.getRoot().layout();
            vypisVety(rv.getRoot());
            synchronized (lock) { done2[0] = true; lock.notifyAll(); }
        }));
        synchronized (lock) { while (!done2[0]) lock.wait(); }
    }

    private final Parent[] hotovaKarta = new Parent[1];

    /** Najde záhlaví bloku a vypíše řádky, které za ním následují až k dalšímu bloku. */
    private static boolean vypisBlok(Node node) {
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (child instanceof javafx.scene.layout.VBox box && jeBlok(box)) {
                    System.out.println("=== " + TITLE);
                    for (Node n : box.getChildrenUnmodifiable()) {
                        vypisRadky(n, "   ");
                    }
                    return true;
                }
                if (vypisBlok(child)) return true;
            }
        }
        return false;
    }

    /** Věty z rámečku o odkazech ve Zjednodušeném přehledu. */
    private static void vypisVety(Node node) {
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (child instanceof Label l && l.getText() != null
                        && (l.getText().contains("(eLegalizace)") || l.getText().startsWith("Otisky předloženého")
                                || l.getText().startsWith("Tento dokument zatím"))) {
                    System.out.println("   " + l.getText());
                }
                vypisVety(child);
            }
        }
    }

    /** Nadpisy všech rozbalovacích bloků v pořadí, v jakém na kartě stojí. */
    private static void vypisNadpisy(Node node) {
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (child instanceof javafx.scene.layout.VBox box && !box.getChildren().isEmpty()
                        && box.getChildren().get(0) instanceof HBox header) {
                    for (Node n : header.getChildrenUnmodifiable()) {
                        if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()
                                && l.getStyle().contains("bold")) {
                            System.out.println("   • " + l.getText());
                        }
                    }
                }
                vypisNadpisy(child);
            }
        }
    }

    private static boolean jeBlok(javafx.scene.layout.VBox box) {
        if (box.getChildren().isEmpty()) return false;
        if (!(box.getChildren().get(0) instanceof HBox header)) return false;
        for (Node n : header.getChildrenUnmodifiable()) {
            if (n instanceof Label l && TITLE.equals(l.getText())) return true;
        }
        return false;
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static void vypisRadky(Node node, String odsazeni) {
        if (node instanceof HBox hbox && hbox.getChildren().size() == 2
                && hbox.getChildren().get(0) instanceof Label lbl) {
            String hodnota = hbox.getChildren().get(1) instanceof TextArea ta ? ta.getText()
                    : hbox.getChildren().get(1) instanceof Label l2 ? l2.getText() : "";
            System.out.println(odsazeni + lbl.getText() + "  " + hodnota);
            return;
        }
        if (node instanceof javafx.scene.control.Separator) {
            System.out.println(odsazeni + "---");
            return;
        }
        if (node instanceof Parent p) {
            for (Node n : p.getChildrenUnmodifiable()) vypisRadky(n, odsazeni);
        }
    }
}
