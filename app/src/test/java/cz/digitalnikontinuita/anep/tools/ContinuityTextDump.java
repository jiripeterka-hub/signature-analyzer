package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import cz.digitalnikontinuita.anep.ui.ContinuityView;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Vypíše text záložky <b>Digitální kontinuita</b> pro zadané soubory — po blocích.
 *
 * <p>Sonda {@link ContinuityConsistencyScan} hledá v témže textu vzorce, ale nález neukáže celý
 * blok a nad jedním konkrétním dokumentem se stejně čte snímek obrazovky. Tohle je ta chybějící
 * půlka: prostý výpis, ve kterém se dá porovnat znění před zásahem a po něm.
 *
 * <p>K tomu se u každého prvku vypíše, co jeho horizont drží
 * ({@link ContinuityHorizonProbe}) — bez toho se z textu nepozná, která větev ho složila.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.graphics \
 *        cz.digitalnikontinuita.anep.tools.ContinuityTextDump "cesta\k\souboru.pdf"
 * </pre>
 */
public final class ContinuityTextDump extends Application {

    @Override
    public void start(Stage stage) {
        List<String> files = getParameters().getRaw();
        if (files.isEmpty()) {
            System.out.println("usage: ContinuityTextDump <file>…");
            Platform.exit();
            return;
        }
        new Thread(() -> {
            try {
                run(files);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "continuity-dump").start();
    }

    private void run(List<String> args) throws Exception {
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        for (File file : expand(args)) {
            System.out.println("═══ " + file + " ═══");
            try {
                dump(file, settings, verifier);
            } catch (Exception e) {
                System.out.println("  (chyba: " + e + ")");
            }
        }
    }

    /**
     * Adresář znamená všechna PDF pod ním, {@code @seznam.txt} soubory vyjmenované v něm —
     * kvůli srovnávacím skenům nad sbírkou i nad ručně vybranou hrstkou.
     */
    private static List<File> expand(List<String> args) throws Exception {
        List<File> out = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("@")) {
                for (String line : java.nio.file.Files.readAllLines(
                        java.nio.file.Path.of(a.substring(1)),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) out.add(new File(line.trim()));
                }
                continue;
            }
            java.nio.file.Path p = java.nio.file.Path.of(a);
            if (java.nio.file.Files.isDirectory(p)) {
                try (var walk = java.nio.file.Files.walk(p)) {
                    walk.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().toLowerCase(
                                java.util.Locale.ROOT).endsWith(".pdf"))
                        .sorted()
                        .forEach(f -> out.add(f.toFile()));
                }
            } else {
                out.add(p.toFile());
            }
        }
        return out;
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
            System.out.println("  ── horizont: " + ContinuityHorizonProbe.describe(sig));
        }

        final Object lock = new Object();
        final boolean[] done = { false };
        final List<String> blocks = new ArrayList<>();
        Platform.runLater(() -> {
            try {
                ContinuityView cv = new ContinuityView();
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
                av.setContinuityView(cv);
                av.show(result);
                new Scene(cv.getRoot(), 980, 700);
                StringBuilder sb = new StringBuilder();
                collect(cv.getRoot(), sb);
                blocks.add(sb.toString());
            } catch (Exception e) {
                blocks.add("(chyba: " + e + ")");
            } finally {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        blocks.forEach(System.out::println);
    }

    /** Texty z celého podstromu — táž metoda jako v {@link ContinuityConsistencyScan}. */
    private static void collect(Node n, StringBuilder sb) {
        if (n instanceof Labeled l && l.getText() != null) sb.append(l.getText()).append('\n');
        if (n instanceof javafx.scene.text.TextFlow tf) {
            for (Node c : tf.getChildrenUnmodifiable()) {
                if (c instanceof javafx.scene.text.Text t) sb.append(t.getText());
                if (c instanceof Labeled l && l.getText() != null) sb.append(l.getText());
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
