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
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Ověřuje, že blok „Digitální kontinuita" a řádek s tlačítky opravdu skončí v {@link
 * ContinuityView}.
 *
 * <p>Vzniklo s přesunem toho bloku z Analýzy do vlastní záložky. Že se to zkompiluje, neznamená,
 * že se to vykreslí na správné místo: obojí staví dál AdvisorView a předává to ven, takže
 * nezapojený {@code setContinuityView} by se projevil jen tichou prázdnou záložkou.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.ContinuityTabSmoke &lt;file&gt;…</pre>
 */
public final class ContinuityTabSmoke extends Application {

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        // Tolerantní režim je vlastní stav aplikace, ne varianta souboru: pod ním obstojí prvky,
        // které přísně neobstály, a tím se mění, kterou část záložky dostanou. Bez přepínače se
        // sonda k těm případům vůbec nedostane.
        boolean tolerant = args.contains("-t");
        List<String> files = args.stream().filter(a -> !a.startsWith("-")).toList();
        if (files.isEmpty()) {
            System.out.println("usage: ContinuityTabSmoke [-t] <file>…");
            Platform.exit();
            return;
        }
        new Thread(() -> {
            try {
                report(files, tolerant);
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
            Platform.exit();
        }, "continuity-smoke").start();
    }

    private void report(List<String> files, boolean tolerant) throws Exception {
        AppSettings settings = AppSettings.load();
        if (tolerant) {
            // Přesně ta čtveřice, kterou překlápí tlačítko „ověřit tolerantně" v MainView.
            settings.setAcceptNewerOcsp(true);
            settings.setAcceptExpiredRevocationSources(true);
            settings.setIgnoreSha1Deprecated(true);
            settings.setAcceptPdfNotEtsi(true);
            System.out.println("[sonda] tolerantní režim zapnut");
        }
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();

        for (String path : files) {
            File file = new File(path);
            DSSDocument doc = loader.load(file);
            ValidationResult result = new ReportMapper()
                    .map(file.getName(), null, factory.validate(doc, null, verifier, null), settings);
            // Shodu s PDF/A nastavuje MainView, ne ReportMapper — bez těchhle dvou řádků by
            // sonda tvrdila, že se v záložce o zachování archivní podoby nepíše, a byl by to
            // nález o sondě. Přístroj se ověřuje dřív než kód.
            var pdfSource = cz.digitalnikontinuita.anep.core.PdfSource.of(file);
            var pdfProfile = cz.digitalnikontinuita.anep.core.PdfProfileExtractor
                    .extract(pdfSource, null);
            result.setPdfProfile(pdfProfile);
            result.setPdfConformance(cz.digitalnikontinuita.anep.core.PdfAConformanceChecker
                    .check(pdfSource, pdfProfile));
            System.out.println("   [sonda] profil: "
                    + (pdfProfile == null ? "-" : pdfProfile.standards())
                    + "   vyhovuje: " + (result.getPdfConformance() == null ? "-"
                        : result.getPdfConformance().compliant()));
            System.out.println();
            System.out.println(file.getName() + "  (" + result.getSignatures().size()
                    + " autentizačních prvků)");
            inspect(result, file, verifier, settings);
        }
    }

    private void inspect(ValidationResult result, File file, CommonCertificateVerifier verifier,
                         AppSettings settings) throws Exception {
        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                AdvisorView advisor = new AdvisorView();
                ContinuityView continuity = new ContinuityView();
                advisor.setAppSettings(settings);
                advisor.setSourceFile(file, null, null);
                var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
                advisor.setFixationService(new FixationService(ref));
                // Bez téhle služby je canRestamp vždy false, takže dokument, jehož jedinou
                // cestou je přerazítkování (typicky PDF-NOT-ETSI), vyjde ze skenu jako
                // „obstál, a přesto se nic nenabízí" — nález přístroje, ne aplikace.
                advisor.setRestampingService(new cz.digitalnikontinuita.anep.core.RestampingService());
                advisor.setAugmentationService(new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
                advisor.setExtensionService(
                        new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
                advisor.setTimestampingService(new cz.digitalnikontinuita.anep.core.TimestampingService(ref));
                // Přesně to zapojení, které dělá MainView.
                continuity.setActionRow(advisor.getActionRow());
                advisor.setContinuityView(continuity);

                advisor.show(result);

                List<Node> tab = collect(continuity.getRoot(), new ArrayList<>());
                List<Node> rozbor = collect(advisor.getRoot(), new ArrayList<>());

                System.out.println("   tlačítka: " + buttons(tab));
                System.out.println("   blok zůstal v Analýze: "
                        + labels(rozbor).stream().anyMatch(s -> s.startsWith("Elektronický podpis")));
                System.out.println("   obsah záložky:");
                for (String s : labels(tab)) {
                    System.out.println("      · " + (s.length() > 400 ? s.substring(0, 400) + "…" : s));
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

    private static List<String> labels(List<Node> nodes) {
        List<String> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()
                    && !(n instanceof Hyperlink)) {
                out.add(l.getText());
            } else if (n instanceof javafx.scene.text.TextFlow flow) {
                // Odstavce s tučnými slovy a odrážky jsou TextFlow, ne Label — jejich text nese
                // řada uzlů Text. Dokud je sonda přeskakovala, nebyl výklad pojmu ani žádná
                // odrážka v jejím výstupu vidět a nešlo poznat, že se do nich vysadila data.
                StringBuilder sb = new StringBuilder();
                for (Node child : flow.getChildren()) {
                    if (child instanceof javafx.scene.text.Text t) sb.append(t.getText());
                    else if (child instanceof Hyperlink h) sb.append(h.getText());
                }
                if (!sb.toString().isBlank()) out.add(sb.toString());
            }
        }
        return out;
    }

    private static String buttons(List<Node> nodes) {
        List<String> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n instanceof Button b && b.isVisible() && b.getText() != null
                    && !b.getText().isBlank()) {
                out.add(b.getText());
            }
        }
        return out.isEmpty() ? "(žádné)" : String.join(", ", out);
    }

    private static List<Node> collect(Node node, List<Node> out) {
        out.add(node);
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, out);
        }
        if (node instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), out);
        }
        return out;
    }
}
