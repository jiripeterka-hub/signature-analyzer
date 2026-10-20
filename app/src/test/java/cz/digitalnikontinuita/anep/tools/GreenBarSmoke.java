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
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Prints the parenthetical the status bar of the Rozšířený přehled shows for each signature — the
 * reasons a verdict rests on beyond a plain standards-conformant check.
 *
 * <p>There are four of them and they combine, so reading the code is not enough to know what a
 * reader will actually see; this walks real documents and reports it.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.GreenBarSmoke &lt;file|folder&gt;…</pre>
 */
public final class GreenBarSmoke extends Application {

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        if (args.isEmpty()) {
            System.out.println("usage: GreenBarSmoke <file|folder>…");
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
        }, "greenbar-smoke").start();
    }

    private void report(List<String> rawArgs) throws Exception {
        List<String> args = new ArrayList<>(rawArgs);
        AppSettings settings = AppSettings.load();
        boolean showAll = args.remove("-all");
        if (args.remove("-sha1")) settings.setIgnoreSha1Deprecated(true);
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();

        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .sorted().forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }

        int withReason = 0;
        for (Path path : files) {
            try {
                DSSDocument doc = loader.load(path.toFile());
                ValidationResult result = new ReportMapper().map(path.getFileName().toString(),
                        null, factory.validate(doc, null, verifier, null), settings);
                String bar = statusBarText(result);
                if (bar.contains("(díky") || showAll) {
                    if (bar.contains("(díky")) withReason++;
                    System.out.printf("%-58s %s%n", trim(path.getFileName().toString()), bar);
                }
            } catch (Exception | StackOverflowError e) {
                System.out.println("!! " + path.getFileName() + "  " + e);
            }
        }
        System.out.println();
        System.out.println("souborů: " + files.size() + ", s uvedeným důvodem: " + withReason);
    }

    /** Concatenated text of every status bar the view builds for this result. */
    private String statusBarText(ValidationResult result) throws Exception {
        final StringBuilder out = new StringBuilder();
        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                ExtendedView view = new ExtendedView();
                view.show(result);
                // Attached to a scene and laid out on purpose: the status bar lives in a TextFlow
                // inside a ScrollPane, and those children do not exist until a skin has been built.
                javafx.scene.Parent root = view.getRoot();
                new javafx.scene.Scene(root, 900, 700);
                root.applyCss();
                root.layout();
                List<String> texts = new ArrayList<>();
                collectText(root, texts);
                for (String t : texts) {
                    if (t.startsWith("  (díky") || t.equals("platný") || t.equals("platná")) {
                        out.append(t.trim()).append(' ');
                    }
                }
            } catch (Exception e) {
                out.append("!! ").append(e);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        return out.toString().trim();
    }

    private static void collectText(Node node, List<String> out) {
        if (node instanceof Text t && t.getText() != null) out.add(t.getText());
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collectText(child, out);
        }
    }

    private static String trim(String s) {
        return s.length() <= 56 ? s : s.substring(0, 55) + "…";
    }

    public static void main(String[] args) {
        launch(args);
    }
}
