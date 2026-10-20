package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationCourse;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Komu Zjednodušený přehled odebere tlačítko „Upozornění“.
 *
 * <p>Pokyn uživatele ze 16. 9. 2026: u podpisu <b>bez časového razítka, jehož certifikát už
 * expiroval</b>, se v Zjednodušeném přehledu upozornění vysázet nemá; v Rozšířeném přehledu
 * zůstává. Pravidlo je v {@code ResultView.noProofAndCertExpired}, jenže tlačítko nese čtyři
 * různá znění (situace 20, 21, 48 a 2) a z kódu není poznat, kterých se dotkne — proto tahle
 * sonda. Odpovídá na dvě otázky: kolika podpisům tlačítko zmizí a od které situace.
 *
 * <p>Vedle toho hlídá, že se pravidlo netrefilo do situace 2: tam důkaz existence je (rozhodný
 * okamžik určuje časové razítko), takže {@code hasValidTimestamp()} musí být pravda a tlačítko
 * má zůstat. Nenulový počet ve sloupci S2 je nález, ne výsledek.
 *
 * <p>Bez JavaFX: podmínka se čte z {@link SignatureSummary}, ne z hotové karty.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.SimpleWarningScan \
 *       -Dexec.args="-out D:\out\warn.txt D:\tmp"
 * </pre>
 */
public final class SimpleWarningScan {

    private SimpleWarningScan() { }

    /** Přípony, ve kterých sbírka nese podepsané dokumenty. */
    private static final List<String> EXT =
            List.of(".pdf", ".asice", ".asics", ".sce", ".scs", ".p7s", ".p7m", ".zfo");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        Path out = null;
        boolean all = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-all" -> all = true;
                case "-out" -> out = Path.of(args[++i]);
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty() || out == null) {
            System.out.println("usage: SimpleWarningScan [-all] -out <file> <folder>...");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(all);
        settings.setAcceptExpiredRevocationSources(all);
        settings.setIgnoreSha1Deprecated(all);
        settings.setAcceptPdfNotEtsi(all);

        CommonCertificateVerifier verifier;
        try {
            verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
            System.out.println("Duveryhodne seznamy: nacteny");
        } catch (Exception e) {
            verifier = new TrustConfig(settings).buildOfflineCertificateVerifier();
            System.out.println("Duveryhodne seznamy: NEDOSTUPNE");
        }
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();
        ReportMapper mapper = new ReportMapper();

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return EXT.stream().anyMatch(n::endsWith);
                    })
                    .sorted().forEach(files::add);
            }
        }
        System.out.println("### souboru: " + files.size());

        Files.createDirectories(out.toAbsolutePath().getParent());
        Map<Integer, int[]> perSituation = new LinkedHashMap<>();   // [celkem, skryto]
        for (int s : new int[] {20, 21, 48, 2}) perSituation.put(s, new int[2]);
        int sigs = 0, withWarning = 0, hidden = 0;

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# ustupky vsechny=" + all);
            int done = 0;
            for (Path p : files) {
                ValidationResult result;
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    result = mapper.map(p.getFileName().toString(), null,
                            factory.validate(doc, null, verifier, null), settings);
                } catch (Exception | StackOverflowError e) {
                    w.println("SKIP\t" + p + "\t" + e);
                    continue;
                }
                for (SignatureSummary sig : result.getSignatures()) {
                    sigs++;
                    int situation = warningSituation(sig);
                    if (situation == 0) continue;
                    withWarning++;
                    perSituation.get(situation)[0]++;
                    boolean skryto = noProofAndCertExpired(sig);
                    if (skryto) {
                        hidden++;
                        perSituation.get(situation)[1]++;
                        w.println("SKRYTO\tS" + situation + "\t" + sig.getIndication()
                                + "\tnotAfter=" + sig.getCertNotAfter() + "\t" + p);
                    } else {
                        w.println("zustava\tS" + situation + "\t" + sig.getIndication()
                                + "\trazitko=" + sig.hasValidTimestamp()
                                + "\tnotAfter=" + sig.getCertNotAfter() + "\t" + p);
                    }
                }
                if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
                w.flush();
            }
            StringBuilder sum = new StringBuilder("podpisu=" + sigs
                    + ", s upozornenim=" + withWarning + ", z toho skryto=" + hidden);
            for (var e : perSituation.entrySet()) {
                sum.append(", S").append(e.getKey()).append("=").append(e.getValue()[0])
                   .append("/skryto ").append(e.getValue()[1]);
            }
            w.println();
            w.println("# " + sum);
            System.out.println("### " + sum);
        }
    }

    /** Která ze čtyř situací by na kartě vysadila tlačítko; 0, když žádná. Pořadí jako v ResultView. */
    private static int warningSituation(SignatureSummary sig) {
        var s = sig.getDetectedSituations();
        if (s.contains(20)) return 20;
        if (s.contains(21)) return 21;
        if (s.contains(48)) return 48;
        if (s.contains(2))  return 2;
        return 0;
    }

    /** Opis pravidla z {@code ResultView.noProofAndCertExpired} — sonda do ui nevidí. */
    private static boolean noProofAndCertExpired(SignatureSummary sig) {
        if (sig.hasValidTimestamp() || sig.getCertNotAfter() == null) return false;
        ValidationCourse vc = sig.getValidationCourse();
        Instant reference = vc != null && vc.validationTime() != null
                ? vc.validationTime() : Instant.now();
        return sig.getCertNotAfter().isBefore(reference);
    }
}
