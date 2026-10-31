package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Vypíše text karty <b>Analýza</b> pro zadané soubory.
 *
 * <p>{@code -trust soubor.cer} přidá certifikát jako kotvu důvěry jen pro tenhle běh — bez
 * zápisu do vlastního úložiště uživatele. Napsáno 31. 10. 2026 kvůli podpisům od služby
 * Podepsat.online: jejich kartu je třeba vidět bez kořene služby (důvod 3) i s ním (platný).
 * Proklik „Vlastní důvěryhodné certifikáty“ v radě se ve výpisu stiskne a ohlásí řádkem
 * „(proklik: …)“ — okno se přitom neotevírá.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.graphics,javafx.base \
 *        cz.digitalnikontinuita.anep.tools.AdvisorTextDump [-trust kořen.cer] "cesta\k\souboru.pdf"
 * </pre>
 */
public final class AdvisorTextDump extends Application {

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        new Thread(() -> {
            try {
                run(args);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "advisor-dump").start();
    }

    private void run(List<String> args) throws Exception {
        List<File> files = new ArrayList<>();
        File trust = null;
        for (int i = 0; i < args.size(); i++) {
            if ("-trust".equals(args.get(i))) trust = new File(args.get(++i));
            else files.add(new File(args.get(i)));
        }
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        if (trust != null) {
            CommonTrustedCertificateSource extra = new CommonTrustedCertificateSource();
            extra.addCertificate(new CertificateToken((java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.FileInputStream(trust))));
            verifier.addTrustedCertSources(extra);
        }
        for (File file : files) {
            System.out.println("═══ " + file.getName() + " ═══");
            dump(file, settings, verifier);
        }
    }

    private void dump(File file, AppSettings settings, CommonCertificateVerifier verifier)
            throws Exception {
        DSSDocument doc = new DocumentLoader().load(file);
        var pdfSource = cz.digitalnikontinuita.anep.core.PdfSource.of(file);
        ValidationResult result = new ReportMapper().map(file.getName(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings,
                cz.digitalnikontinuita.anep.core.PdfSignatureAppearanceExtractor
                        .extract(pdfSource, null));
        for (var sig : result.getSignatures()) {
            System.out.println("  ── " + sig.getIndication() + " / " + sig.getSubIndication()
                    + " / " + sig.getLegalType() + " / důvody " + sig.getDetectedReasons());
        }

        final Object lock = new Object();
        final boolean[] done = { false };
        final StringBuilder sb = new StringBuilder();
        Platform.runLater(() -> {
            try {
                AdvisorView av = new AdvisorView();
                av.setAppSettings(settings);
                av.setSourceFile(file, null, null);
                var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
                av.setFixationService(new FixationService(ref));
                av.setRestampingService(new cz.digitalnikontinuita.anep.core.RestampingService());
                av.setAugmentationService(
                        new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
                av.setExtensionService(
                        new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
                av.setTimestampingService(
                        new cz.digitalnikontinuita.anep.core.TimestampingService(ref));
                av.setSettingsOpener(advanced -> { });
                // Proklik se ve výpisu stiskne (viz collect) a ohlásí se tu — tak je vidět, že
                // vede, kam má, aniž by se okno skutečně otevíralo.
                av.setTrustStoreOpener(() -> sb.append("(proklik: okno Vlastní důvěryhodné certifikáty)\n"));
                av.show(result);
                new Scene(av.getRoot(), 980, 700);
                collect(av.getRoot(), sb);
            } catch (Exception e) {
                sb.append("(chyba: ").append(e).append(")");
            } finally {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        System.out.println(sb);
    }

    private static void collect(Node n, StringBuilder sb) {
        if (n instanceof Labeled l && l.getText() != null) sb.append(l.getText()).append('\n');
        if (n instanceof TextInputControl t && t.getText() != null) sb.append(t.getText()).append('\n');
        if (n instanceof javafx.scene.text.TextFlow tf) {
            for (Node c : tf.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
                if (c instanceof javafx.scene.control.Hyperlink h) {
                    sb.append('[').append(h.getText()).append(']');
                    if (h.getText().equals("Vlastní důvěryhodné certifikáty")) h.fire();
                }
                else if (c instanceof Labeled l && l.getText() != null) sb.append(l.getText());
            }
            sb.append('\n');
            return;
        }
        if (n instanceof ScrollPane sp && sp.getContent() != null) collect(sp.getContent(), sb);
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collect(c, sb);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
