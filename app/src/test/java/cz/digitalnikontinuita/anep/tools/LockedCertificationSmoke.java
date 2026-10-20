package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.RestampingService;
import cz.digitalnikontinuita.anep.core.SignatureExtensionService;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Upozornění před úkonem u certifikace „žádné změny“ (DocMDP P=1), ověřené skutečným stiskem.
 *
 * <p>Pro každý soubor postaví pohled se skutečnými službami, stiskne tlačítko úkonu ({@code fire()})
 * a vypíše, zda se objevilo upozornění a co v něm stojí. Upozornění se zavře tlačítkem „Zrušit“,
 * takže úkon sám neproběhne — jinak by se otevřel dialog pro uložení.
 *
 * <pre>
 *   ... LockedCertificationSmoke &lt;pdf s P=1&gt; &lt;pdf bez certifikace&gt;
 * </pre>
 */
public final class LockedCertificationSmoke extends Application {

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        new Thread(() -> {
            try {
                AppSettings settings = AppSettings.load();
                TrustConfig tc = new TrustConfig(settings);
                CommonCertificateVerifier verifier;
                try {
                    verifier = tc.buildLotlCertificateVerifier();
                } catch (Exception e) {
                    verifier = tc.buildOfflineCertificateVerifier();
                }
                for (String a : args) {
                    File f = new File(a);
                    ValidationResult result = new ReportMapper().map(f.getName(), null,
                            new ValidatorFactory(settings).validate(new DocumentLoader().load(f),
                                    null, verifier, null), settings);
                    System.out.println("=== " + f.getName().replaceAll(".*-", "*-"));
                    press(result, f, verifier, settings);
                }
            } catch (Throwable e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "locked-smoke").start();
    }

    private void press(ValidationResult result, File file, CommonCertificateVerifier verifier,
                       AppSettings settings) throws Exception {
        Object lock = new Object();
        boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                AdvisorView view = new AdvisorView();
                view.setAppSettings(settings);
                view.setSourceFile(file, null, null);
                var ref = new AtomicReference<>(verifier);
                view.setFixationService(new FixationService(ref));
                view.setRestampingService(new RestampingService());
                view.setAugmentationService(new AugmentationService(ref));
                view.setExtensionService(new SignatureExtensionService(ref));
                view.setTimestampingService(new TimestampingService(ref));
                view.show(result);

                List<Button> buttons = new ArrayList<>();
                collect(view.getActionRow(), buttons);
                Button action = buttons.stream()
                        .filter(b -> b.isVisible() && b.getText() != null
                                && (b.getText().startsWith("Přerazítk") || b.getText().startsWith("Uchov")
                                    || b.getText().startsWith("Prodlou")))
                        .findFirst().orElse(null);
                if (action == null) {
                    System.out.println("  tlačítko úkonu se nenabízí");
                } else {
                    System.out.println("  stisk: " + action.getText());
                    // Naplánováno předem: poběží uvnitř showAndWait() upozornění, které fire() otevře.
                    Platform.runLater(this::answerDialog);
                    action.fire();
                    System.out.println("  fire() vrátilo řízení");
                }
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
    }

    private void answerDialog() {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w.getScene() == null || !(w.getScene().getRoot() instanceof DialogPane pane)) continue;
            System.out.println("  UPOZORNĚNÍ: " + pane.getHeaderText());
            if (pane.getContent() instanceof Label l) System.out.println("  text: " + l.getText());
            for (ButtonType bt : pane.getButtonTypes()) {
                System.out.println("  tlačítko: " + bt.getText());
            }
            for (ButtonType bt : pane.getButtonTypes()) {
                if (bt.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) {
                    ((Button) pane.lookupButton(bt)).fire();
                    return;
                }
            }
        }
        // Žádné upozornění: úkon by teď pokračoval k uložení; v tom případě dialog pro uložení
        // zavře uživatel ručně. Pro soubor bez certifikace se sem čeká.
        System.out.println("  žádné upozornění");
    }

    private static void collect(Node node, List<Button> out) {
        if (node instanceof Button b) out.add(b);
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, out);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
