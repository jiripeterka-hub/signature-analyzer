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
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows which action buttons the Analýza tab offers for a document, in both the ordinary and the
 * expert mode, so the preservation rule can be checked without clicking through the application.
 *
 * <p>Preservation is offered only where it produces something a reader will accept: the verdict is
 * already valid and situations 31, 33 and 34 hold, plus 32 outside expert mode. Where it is
 * withheld, fixation should appear in its place.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.PreserveOfferSmoke &lt;file&gt;…</pre>
 */
public final class PreserveOfferSmoke extends Application {

    @Override
    public void start(Stage stage) {
        List<String> files = getParameters().getRaw();
        if (files.isEmpty()) {
            System.out.println("usage: PreserveOfferSmoke <file>…");
            Platform.exit();
            return;
        }
        new Thread(() -> {
            try {
                report(files);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "offer-smoke").start();
    }

    /**
     * Rozvine adresáře na soubory. Sken je screening, ne důkaz: jeden verifier obsluhuje celou
     * dávku, takže se nálezy musí přeměřit jednotlivě — na to je -Danep.gatetrace.
     */
    private static List<String> expand(List<String> args) throws Exception {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            // @seznam.txt — cesta na řádek. Dávka o stovkách souborů se na příkazovou řádku
            // Windows nevejde a rozvinout adresář nejde, když je výběr výsledkem síta.
            if (a.startsWith("@")) {
                for (String line : java.nio.file.Files.readAllLines(
                        java.nio.file.Path.of(a.substring(1)))) {
                    String t = line.trim();
                    if (!t.isEmpty()) out.add(t);
                }
                continue;
            }
            File f = new File(a);
            if (f.isDirectory()) {
                try (var walk = java.nio.file.Files.walk(f.toPath())) {
                    walk.filter(java.nio.file.Files::isRegularFile)
                        .map(java.nio.file.Path::toString)
                        .filter(n -> {
                            String low = n.toLowerCase(java.util.Locale.ROOT);
                            return low.endsWith(".pdf") || low.endsWith(".asice")
                                    || low.endsWith(".asics");
                        })
                        .sorted()
                        .forEach(out::add);
                }
            } else {
                out.add(a);
            }
        }
        return out;
    }

    private void report(List<String> rawFiles) throws Exception {
        List<String> files = expand(rawFiles);
        List<String> suspects = new ArrayList<>();
        int done = 0;
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();

        for (String path : files) {
            File file = new File(path);
            // Nečitelný soubor sken přeskočí, neukončí. Jeden přejmenovaný adresář jinak shodil
            // celý běh na prvním souboru a výsledek „0 podezřelých z 221" vypadal jako nález,
            // přestože neproběhlo nic.
            DSSDocument doc;
            try {
                doc = loader.load(file);
            } catch (Exception e) {
                System.out.println();
                System.out.println(file.getName() + "   PŘESKOČENO: " + e);
                continue;
            }
            ValidationResult result;
            try {
                result = new ReportMapper().map(file.getName(), null,
                        factory.validate(doc, null, verifier, null), settings);
            } catch (Exception e) {
                System.out.println();
                System.out.println(file.getName() + "   PŘESKOČENO při ověření: " + e);
                continue;
            }
            System.out.println();
            System.out.println(file.getName());
            for (var sig : result.getSignatures()) {
                System.out.println("   " + sig.getIndication() + "  situace="
                        + sig.getDetectedSituations());
            }
            String ordinary = offeredButtons(result, file, verifier, false);
            System.out.printf("   %-14s %s%n", "běžný:", ordinary);
            System.out.printf("   %-14s %s%n", "expertní:",
                    offeredButtons(result, file, verifier, true));

            // Podezřelý je dokument, kde některý prvek obstál, a přesto se zachování nenabízí.
            // Přesně tak vypadal PAdES B-B kvalifikovaný PS 4096.pdf, než se opravila brána.
            boolean anyPassed = result.getSignatures().stream()
                    .anyMatch(sig -> "TOTAL_PASSED".equals(sig.getIndication()));
            if (anyPassed && !ordinary.contains("Zachovat")) {
                suspects.add(file.getName() + "   " + ordinary);
            }
            if (++done % 25 == 0) System.out.println("### hotovo " + done + " z " + files.size());
        }

        System.out.println();
        System.out.println("################ PODEZŘELÉ: prvek obstál, zachování se nenabízí");
        if (suspects.isEmpty()) {
            System.out.println("  žádný");
        } else {
            for (String s : suspects) System.out.println("  " + s);
        }
        System.out.println("################ celkem " + suspects.size() + " z " + files.size());
    }

    /** Names of the action buttons the view would show, as the reader would see them. */
    private String offeredButtons(ValidationResult result, File file,
                                  CommonCertificateVerifier verifier,
                                  boolean expert) throws Exception {
        AppSettings mode = AppSettings.load();
        mode.setExpertMode(expert);
        final List<String> visible = new ArrayList<>();
        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                AdvisorView view = new AdvisorView();
                view.setAppSettings(mode);
                view.setSourceFile(file, null, null);
                var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
                view.setFixationService(new FixationService(ref));
                // Bez téhle služby je canRestamp vždy false, takže dokument, jehož jedinou
                // cestou je přerazítkování (typicky PDF-NOT-ETSI), vyjde ze skenu jako
                // „obstál, a přesto se nic nenabízí" — nález přístroje, ne aplikace.
                view.setRestampingService(new cz.digitalnikontinuita.anep.core.RestampingService());
                view.setAugmentationService(
                        new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
                view.setExtensionService(
                        new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
                view.setTimestampingService(
                        new cz.digitalnikontinuita.anep.core.TimestampingService(ref));
                view.show(result);
                // Řádek s tlačítky bydlí od zavedení záložky Digitální kontinuita mimo kořen
                // Analýzy; bere se proto přímo od pohledu, ne hledáním ve stromu.
                collectButtons(view.getActionRow(), visible);
            } catch (Exception e) {
                visible.add("!! " + e);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        return visible.isEmpty() ? "(žádné tlačítko)" : String.join(", ", visible);
    }

    private static void collectButtons(Node node, List<String> out) {
        if (node instanceof Button b) {
            if (b.isVisible() && b.getText() != null && !b.getText().isBlank()) out.add(b.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collectButtons(child, out);
        } else if (node instanceof Region r) {
            for (Node child : r.getChildrenUnmodifiable()) collectButtons(child, out);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
