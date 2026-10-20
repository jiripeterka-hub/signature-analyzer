package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Věta „Závěr…“ z Analýzy pro každý podpis ve sbírce, do souboru.
 *
 * <p>Závěr má šest větví a o tom, která padne, rozhodují podmínky rozeseté po
 * {@code conclusionText}. Opsat je do sondy znamená měřit něco jiného než aplikaci — proto se
 * tu staví <b>skutečná karta</b> a věta se z ní vytáhne, stejně jako to dělá
 * {@link AdvisorCourseSmoke} pro jeden soubor.
 *
 * <p>Pomalé (JavaFX i ověřování), zato věrné. Slouží k před/po srovnání, když se s některou
 * větví hýbe: {@code diff} dvou běhů ukáže přesně ty prvky, kterým se závěr změnil.
 *
 * <pre>java -cp … ConclusionScan -out D:\out\zavery.txt [-t] &lt;složka&gt;…</pre>
 */
public final class ConclusionScan extends Application {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    private static final List<Path> roots = new ArrayList<>();
    private static Path out;
    private static boolean tolerant;

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-out" -> out = Path.of(args[++i]);
                case "-t" -> tolerant = true;
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (out == null || roots.isEmpty()) {
            System.err.println("usage: ConclusionScan -out <soubor> [-t] <složka>…");
            System.exit(2);
        }
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(tolerant);
        settings.setAcceptExpiredRevocationSources(tolerant);
        settings.setIgnoreSha1Deprecated(tolerant);
        settings.setAcceptPdfNotEtsi(tolerant);

        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        var mapper = new ReportMapper();

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).filter(ConclusionScan::isCandidate)
                        .sorted().forEach(files::add);
            }
        }

        try (PrintStream ps = new PrintStream(Files.newOutputStream(out), true,
                StandardCharsets.UTF_8)) {
            for (Path p : files) {
                List<String[]> pairs = new ArrayList<>();
                try {
                    var doc = loader.load(p.toFile());
                    var outcome = factory.validate(doc, null, verifier, null);
                    ValidationResult result = mapper.map(p.getFileName().toString(), "x", outcome,
                            settings, java.util.Map.of(), java.util.List.of(), false,
                            java.util.Map.of());
                    AdvisorView view = new AdvisorView();
                    view.setAppSettings(settings);
                    view.setLenientVerifyCallback(() -> { });
                    view.setTimestampStandardsMode(settings.isTimestampStandardsMode());
                    stage.setScene(new Scene(view.getRoot(), 900, 700));
                    view.show(result);
                    collectPairs(view.getRoot(), pairs);
                } catch (Exception e) {
                    pairs.add(new String[] {"", "CHYBA: " + e});
                }
                if (pairs.isEmpty()) pairs.add(new String[] {"", "(bez závěru)"});
                for (String[] pair : pairs) {
                    ps.println(p.getFileName() + "\t" + pair[1]);
                    if (contradictsRevocation(pair[1], pair[0])) {
                        ps.println(p.getFileName() + "\tROZPOR\t" + pair[0]);
                    }
                }
            }
        }
        System.out.println("hotovo, souborů: " + files.size());
        Platform.exit();
        System.exit(0);
    }

    /**
     * Odporuje si Závěr s odstavcem o revokaci?
     *
     * <p>Hledání podřetězců v próze je křehké a v aplikačním kódu by nemělo co dělat; tady je
     * to ale <b>detektor</b>, ne chování, a přesně na tenhle rozpor uživatel narazil (15. 9. 2026):
     * odstavec hlásil využitou vloženou odpověď OCSP a Závěr pod ním „bez použitelného údaje“.
     * Změní-li se znění, přestane detektor hlásit — ať; pravidlo drží kód, ne tahle sonda.
     */
    private static boolean contradictsRevocation(String conclusion, String revocation) {
        if (!conclusion.contains("Bez použitelného údaje o stavu revokace")) return false;
        return revocation.contains("byla využita") || revocation.contains("byl využit")
                || revocation.contains("posloužil");
    }

    /**
     * Dvojice [odstavec o revokaci, závěr] — jedna na každou kartu, v pořadí dokumentu.
     *
     * <p>Párovat se musí, ne sbírat zvlášť. První podoba sondy porovnávala první odstavec
     * o revokaci s každým závěrem a nad {@code vice podpisu/priklady podpisu pro kurz media 4.pdf}
     * — čtyři podpisy, tři platné a jeden neznámý — ohlásila rozpor, který žádný nebyl:
     * větu o využité odpovědi vzala z karty prvního podpisu a závěr ze čtvrté.
     *
     * <p>Uvnitř karty stojí odstavec před závěrem, takže stačí číst v pořadí dokumentu a po každém
     * závěru rozepsaný odstavec zahodit: co přijde pak, už patří další kartě. Karta, která
     * odstavec vůbec nemá, tak dostane prázdný řetězec místo cizího.
     */
    private static void collectPairs(Parent parent, List<String[]> out) {
        List<String> pending = new ArrayList<>();
        walk(parent, pending, out);
    }

    private static void walk(Parent parent, List<String> pending, List<String[]> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            String text = textOf(n);
            if (text != null) {
                if (text.startsWith("Revokační informace.")) {
                    pending.clear();
                    pending.add(text);
                } else if (text.startsWith("Závěr.")) {
                    out.add(new String[] {pending.isEmpty() ? "" : pending.get(0), text});
                    pending.clear();
                }
            }
            if (n instanceof javafx.scene.control.ScrollPane sp
                    && sp.getContent() instanceof Parent p) {
                walk(p, pending, out);
            } else if (n instanceof Parent p) {
                walk(p, pending, out);
            }
        }
    }

    /** Text uzlu, ať už je to popisek nebo sázený odstavec; {@code null} u ostatních. */
    private static String textOf(Node n) {
        if (n instanceof Label l && l.getText() != null) {
            return l.getText().replace('\n', ' ');
        }
        if (n instanceof javafx.scene.text.TextFlow tf) {
            StringBuilder sb = new StringBuilder();
            for (Node run : tf.getChildrenUnmodifiable()) {
                if (run instanceof javafx.scene.text.Text tx) sb.append(tx.getText());
            }
            return sb.toString().replace('\n', ' ');
        }
        return null;
    }

    /** Věty začínající daným uvozením, z celého podstromu. */
    private static void collectPrefixed(Parent parent, String prefix, List<String> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof Label l && l.getText() != null
                    && l.getText().startsWith(prefix)) {
                out.add(l.getText().replace('\n', ' '));
            } else if (n instanceof javafx.scene.text.TextFlow tf) {
                StringBuilder sb = new StringBuilder();
                for (Node run : tf.getChildrenUnmodifiable()) {
                    if (run instanceof javafx.scene.text.Text tx) sb.append(tx.getText());
                }
                if (sb.toString().startsWith(prefix)) {
                    out.add(sb.toString().replace('\n', ' '));
                }
            } else if (n instanceof javafx.scene.control.ScrollPane sp
                    && sp.getContent() instanceof Parent p) {
                collectPrefixed(p, prefix, out);
            } else if (n instanceof Parent p) {
                collectPrefixed(p, prefix, out);
            }
        }
    }

    /** Věty začínající „Závěr.“ — jedna na každý autentizační prvek, v pořadí karet. */
    private static void collect(Parent parent, List<String> out) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof Label l && l.getText() != null && l.getText().startsWith("Závěr.")) {
                out.add(l.getText());
            } else if (n instanceof javafx.scene.text.TextFlow tf) {
                StringBuilder sb = new StringBuilder();
                for (Node run : tf.getChildrenUnmodifiable()) {
                    if (run instanceof javafx.scene.text.Text tx) sb.append(tx.getText());
                }
                if (sb.toString().startsWith("Závěr.")) out.add(sb.toString());
            } else if (n instanceof javafx.scene.control.ScrollPane sp
                    && sp.getContent() instanceof Parent p) {
                collect(p, out);
            } else if (n instanceof Parent p) {
                collect(p, out);
            }
        }
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
