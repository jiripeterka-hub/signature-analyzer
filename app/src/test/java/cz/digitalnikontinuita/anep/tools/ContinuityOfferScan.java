package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Stage;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kolik dokumentů se překlopí do nabídky, když se povolí ústupky — měřeno tlačítkem, ne bránou.
 *
 * <p>Otázka, na kterou vznikl: nabídka „Zachovat ověřitelnost“ dnes visí na verdiktu
 * ({@code displayedIndication == TOTAL_PASSED} ve třech branách v {@code AdvisorView.show()}),
 * a ten je funkcí právě zapnuté validační politiky. Týž soubor tedy nabídku dostane, nebo
 * nedostane, podle zaškrtávátek v nastavení. Návrh je nabídnout úkon i tam, kde AnEP platnost
 * sám nepotvrdí, ale tolerantní čtení ano — a rozsah toho návrhu je přesně rozdíl mezi během
 * s ústupky a bez nich.
 *
 * <p><b>Proč přes {@code show()} a ne přes vlastní kopii podmínek.</b> Bran je přes deset
 * a rozprostřených; replika by měřila mou představu o nich. Sonda proto staví skutečný pohled
 * se skutečnými službami a čte, která tlačítka jsou vidět — totéž, co uvidí uživatel. Vzato
 * z {@code PreserveOfferSmoke}, které tohle dělá pro jeden soubor; tady jde o dávku a hlavně
 * o to, aby validaci i pohled řídilo <b>jedno</b> nastavení. {@code PreserveOfferSmoke} si uvnitř
 * volá {@code AppSettings.load()} znovu, takže ústupky zadané na příkazové řádce by se do pohledu
 * nedostaly.
 *
 * <p><b>Sken je síto, ne důkaz.</b> Celou dávku obsluhuje jeden {@code CertificateVerifier},
 * takže se nálezy musí přeměřit jednotlivě — na to je {@code -Danep.gatetrace}. Rozhodující
 * ústupky se navíc bez druhého průchodu validace jen odhadují ({@code computeDecisiveSettings}),
 * takže situace 34 může chybět i tam, kde ústupek ve skutečnosti nerozhodl; na odpověď
 * „nabídne se, nebo ne“ to nemá vliv, na to, <i>kterou</i> ze tří cest brána vybere, ano.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.ContinuityOfferScan \
 *       -Dexec.args="-strict -out D:\tmp\out\strict.txt D:\tmp"
 * </pre>
 */
public final class ContinuityOfferScan extends Application {

    private static final List<String> DEFAULT_EXT = List.of(".pdf");

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
        }, "offer-scan").start();
    }

    private void run(List<String> args) throws Exception {
        List<Path> roots = new ArrayList<>();
        List<String> lists = new ArrayList<>();
        List<String> ext = DEFAULT_EXT;
        Path out = null;
        boolean strict = false, newerOcsp = false, expiredRev = false, sha1 = false,
                pdfNotEtsi = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "-strict" -> strict = true;
                case "-all" -> { newerOcsp = true; expiredRev = true; sha1 = true; pdfNotEtsi = true; }
                case "-newerOcsp" -> newerOcsp = true;
                case "-expiredRev" -> expiredRev = true;
                case "-sha1" -> sha1 = true;
                case "-pdfNotEtsi" -> pdfNotEtsi = true;
                case "-out" -> out = Path.of(args.get(++i));
                case "-ext" -> {
                    List<String> list = new ArrayList<>();
                    for (String s : args.get(++i).split(",")) {
                        String e = s.trim().toLowerCase(Locale.ROOT);
                        list.add(e.startsWith(".") ? e : "." + e);
                    }
                    ext = list;
                }
                // Seznam se odkládá stranou a neprochází Path.of: "@C:\..." není platná
                // windowsová cesta (dvojtečka na třetí pozici) a rozebrání skončí výjimkou.
                default -> {
                    String v = args.get(i);
                    if (v.startsWith("@")) lists.add(v.substring(1));
                    else roots.add(Path.of(v));
                }
            }
        }
        if ((roots.isEmpty() && lists.isEmpty()) || out == null) {
            System.out.println("usage: ContinuityOfferScan -out <file> [-strict|-all|-sha1|...]"
                    + " [-ext pdf] <folder>...");
            return;
        }

        AppSettings settings = AppSettings.load();
        if (strict) {
            settings.setAcceptNewerOcsp(false);
            settings.setAcceptExpiredRevocationSources(false);
            settings.setIgnoreSha1Deprecated(false);
            settings.setAcceptPdfNotEtsi(false);
        }
        if (newerOcsp) settings.setAcceptNewerOcsp(true);
        if (expiredRev) settings.setAcceptExpiredRevocationSources(true);
        if (sha1) settings.setIgnoreSha1Deprecated(true);
        if (pdfNotEtsi) settings.setAcceptPdfNotEtsi(true);
        settings.setExpertMode(false);

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# ustupky: newerOcsp=" + settings.isAcceptNewerOcsp()
                    + " expiredRev=" + settings.isAcceptExpiredRevocationSources()
                    + " sha1=" + settings.isIgnoreSha1Deprecated()
                    + " pdfNotEtsi=" + settings.isAcceptPdfNotEtsi());
            w.println("# tsa=" + (settings.getSelectedTsa() != null
                    ? settings.getSelectedTsa().getName() : "ZADNA"));

            TrustConfig trustConfig = new TrustConfig(settings);
            CommonCertificateVerifier verifier;
            try {
                verifier = trustConfig.buildLotlCertificateVerifier();
                w.println("# duveryhodne seznamy: nacteny");
            } catch (Exception e) {
                verifier = trustConfig.buildOfflineCertificateVerifier();
                w.println("# duveryhodne seznamy: NEDOSTUPNE (" + e + ")");
            }
            ValidatorFactory factory = new ValidatorFactory(settings);
            DocumentLoader loader = new DocumentLoader();
            ReportMapper mapper = new ReportMapper();

            List<Path> files = new ArrayList<>();
            // @seznam.txt — cesta na řádek. Výběr, který je výsledkem síta, se do adresáře nedá
            // vyjádřit a na příkazovou řádku Windows se nevejde. Vzato z PreserveOfferSmoke.
            for (String listFile : lists) {
                for (String line : Files.readAllLines(Path.of(listFile))) {
                    String t = line.trim();
                    if (!t.isEmpty()) files.add(Path.of(t));
                }
            }
            for (Path root : roots) {
                try (var walk = Files.walk(root)) {
                    final List<String> fext = ext;
                    walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return fext.stream().anyMatch(n::endsWith);
                        })
                        .sorted()
                        .forEach(files::add);
                }
            }
            System.out.println("### souboru: " + files.size());

            int done = 0, offered = 0, skipped = 0;
            for (Path p : files) {
                File f = p.toFile();
                String rel;
                try {
                    rel = roots.isEmpty() ? p.toString()
                            : roots.get(0).relativize(p).toString();
                } catch (IllegalArgumentException e) {
                    rel = p.toString();      // ze seznamu @, kořen s ní nemá nic společného
                }
                ValidationResult result;
                try {
                    DSSDocument doc = loader.load(f);
                    result = mapper.map(f.getName(), null,
                            factory.validate(doc, null, verifier, null), settings);
                } catch (Exception | StackOverflowError e) {
                    skipped++;
                    w.println("SKIP\t" + rel + "\t" + e);
                    w.flush();
                    continue;
                }
                String buttons = buttons(result, f, verifier, settings);
                // Slovesa tří variant, jak je pojmenovává AdvisorView.ContinuityMode. Stálo tu
                // "Zachovat", což je jméno, které tlačítko nese naposledy před rozdělením úkonu
                // na uchování/prodloužení/přerazítkování — souhrn proto hlásil nabidka=0 nad
                // sbírkou, kde nabídku dostalo 31 dokumentů.
                boolean keep = buttons.contains("Uchovat") || buttons.contains("Prodloužit")
                        || buttons.contains("Přerazítkovat");
                if (keep) offered++;
                w.println((keep ? "ANO\t" : "NE \t") + rel + "\ttlacitka=" + buttons);
                for (SignatureSummary sig : result.getSignatures()) {
                    w.println("\t\t" + AdvisorView.displayedIndication(sig)
                            + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                            + "\t" + sig.getFormat()
                            + "\tsituace=" + sig.getDetectedSituations()
                            + "\tduvody=" + sig.getDetectedReasons()
                            + "\tustupky=" + sig.getDecisiveSettings()
                            + "\tjinovalidatori=" + AdvisorView.shouldOfferOtherValidators(sig)
                            + "\tvlastniKotva=" + sig.isTrustedViaCustomStore()
                            + "\tudajKPodpisCertu="
                            + (sig.getValidationCourse() != null
                                    && (sig.getValidationCourse().signingCertRevocation() != null
                                        || sig.getValidationCourse()
                                                .signingCertRevocationUnusableFact() != null)));
                }
                w.flush();
                if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
            }
            w.println();
            w.println("# souboru=" + files.size() + " nabidka=" + offered
                    + " bez nabidky=" + (files.size() - offered - skipped)
                    + " preskoceno=" + skipped);
            System.out.println("### hotovo: nabidka=" + offered + " z " + files.size()
                    + " (preskoceno " + skipped + ")");
        }
    }

    /** Tlačítka, která by pohled ukázal — postavená skutečným {@code show()}, ne odhadem. */
    private String buttons(ValidationResult result, File file, CommonCertificateVerifier verifier,
                           AppSettings settings) throws Exception {
        final List<String> visible = new ArrayList<>();
        final Object lock = new Object();
        final boolean[] done = { false };
        Platform.runLater(() -> {
            try {
                AdvisorView view = new AdvisorView();
                view.setAppSettings(settings);
                view.setSourceFile(file, null, null);
                var ref = new java.util.concurrent.atomic.AtomicReference<>(verifier);
                view.setFixationService(new FixationService(ref));
                view.setRestampingService(new cz.digitalnikontinuita.anep.core.RestampingService());
                view.setAugmentationService(
                        new cz.digitalnikontinuita.anep.core.AugmentationService(ref));
                view.setExtensionService(
                        new cz.digitalnikontinuita.anep.core.SignatureExtensionService(ref));
                view.setTimestampingService(
                        new cz.digitalnikontinuita.anep.core.TimestampingService(ref));
                view.show(result);
                collect(view.getActionRow(), visible);
            } catch (Exception e) {
                visible.add("!! " + e);
            }
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        return visible.isEmpty() ? "(zadne)" : String.join(" + ", visible);
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Button b) {
            if (b.isVisible() && b.getText() != null && !b.getText().isBlank()) out.add(b.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, out);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
