package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.PdfEmptySignatureFieldDetector;
import cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor;
import cz.digitalnikontinuita.anep.core.PdfSource;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * Ptá se textem, ne obrázkem: postaví všechny tři záložky a vypíše, jestli v nich je věta
 * o viditelnosti vizualizace a věta o prázdných podpisových polích.
 *
 * <p>Snímek na tuhle otázku odpovídá špatně — blok bývá zabalený, karta je vysoká tisíce pixelů
 * a hledat v ní větu očima znamená ji přehlédnout. Text prochází {@code PrintUtil.buildFo()},
 * tedy touž cestou, kterou jde protokol; co najde on, uvidí i čtenář.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.ui.AppearanceCaveatProbe \
 *       -Dexec.args="D:\cesta\k\souboru.pdf ..."
 * </pre>
 */
public final class AppearanceCaveatProbe extends Application {

    @Override
    public void start(Stage stage) {
        List<String> files = getParameters().getRaw();
        new Thread(() -> {
            for (String f : files) {
                try {
                    report(f);
                } catch (Exception e) {
                    System.out.println(f + " → " + e);
                }
            }
            Platform.exit();
        }, "caveat-probe").start();
    }

    private void report(String file) throws Exception {
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        DSSDocument doc = new DocumentLoader().load(new File(file));
        PdfSource src = PdfSource.of(new File(file));
        ValidationResult result = new ReportMapper().map(new File(file).getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings,
                PdfSignatureAppearanceExtractor.extract(src, null));
        result.setEmptySignatureFields(PdfEmptySignatureFieldDetector.detect(src, null));

        System.out.println("### " + new File(file).getName());
        System.out.println("    hlavička: " + ResultView.authCountLabel(result));
        for (var sig : result.getSignatures()) {
            var vis = sig.getAppearanceVisibility();
            System.out.println("    prvek " + sig.getSignatureId()
                    + "  vzhled=" + (sig.getSignatureAppearancePng() != null)
                    + "  viditelnost=" + (vis == null ? "—"
                        : "vidět " + vis.visible() + " / tiskne " + vis.printed()));
        }

        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                for (String tab : new String[] { "zjednoduseny", "analyza", "rozsireny" }) {
                    VBox root = switch (tab) {
                        case "zjednoduseny" -> {
                            ResultView v = new ResultView();
                            v.show(result);
                            yield contentOf(v.getRoot());
                        }
                        case "analyza" -> {
                            AdvisorView v = new AdvisorView();
                            v.show(result);
                            yield contentOf(v.getRoot());
                        }
                        default -> {
                            ExtendedView v = new ExtendedView();
                            v.show(result);
                            yield contentOf(v.getRoot());
                        }
                    };
                    new Scene(root, 980, 100);
                    root.applyCss();
                    root.layout();
                    String fo = PrintUtil.buildFo(root, new File(file), null, "sonda", null, null);
                    System.out.println("    " + String.format("%-13s", tab)
                            + " nadpis vizualizace=" + fo.contains("Vizualizace")
                            + "  obrázek=" + fo.contains("<fo:external-graphic")
                            + "  věta o viditelnosti=" + (fo.contains("Tato konkrétní vizualizace")
                                || fo.contains("bez vizualizace"))
                            + "  věta o prázdném poli=" + fo.contains("prázdné podpisové pole")
                            + "  nadpis bloku polí=" + (fo.contains("Prázdné podpisové pole")
                                || fo.contains("Prázdná podpisová pole")));
                }
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) { while (!done[0]) lock.wait(); }
        System.out.println();
    }

    private static VBox contentOf(javafx.scene.Node root) {
        if (root instanceof VBox box) {
            for (javafx.scene.Node n : box.getChildren()) {
                if (n instanceof javafx.scene.control.ScrollPane sp
                        && sp.getContent() instanceof VBox inner) {
                    return inner;
                }
            }
            return box;
        }
        return new VBox();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
