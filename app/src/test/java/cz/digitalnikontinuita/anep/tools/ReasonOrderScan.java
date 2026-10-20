package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Podklad k otázce, zda řadit důvody tak, aby napřed stál ten, na kterém ověřování skončilo.
 *
 * <p>„Na kterém skončilo“ se tu měří, ne odhaduje: každý soubor se ověří dvakrát — přísně a se
 * všemi čtyřmi ústupky zapnutými. Důvod, který zůstane i po ústupcích, nespraví žádné nastavení,
 * a je to tedy on, co výsledek drží.
 *
 * <p>Na řádek jeden podpis s aspoň dvěma důvody, oddělovač tabulátor:
 * soubor, indikace přísně, pořadí na kartě, důvody po ústupcích, indikace po ústupcích.
 * Vznikla 25. 9. 2026 nad nálezem uživatele u „2result.pdf“ (bylo 22, 1, 4, 15; žádáno 4, 1, 15,
 * 22). Z jejího měření vzešla pevná tabulka {@code AdvisorView.RELAXABLE_REASONS}; tahle sonda
 * slouží k tomu, aby se dala znovu přeměřit.
 *
 * <pre>java -cp … ReasonOrderScan &lt;složka&gt;…</pre>
 */
public final class ReasonOrderScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json",
                    ".tst", ".zfo");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String a : args) roots.add(Path.of(a));

        AppSettings strict = AppSettings.load();
        relax(strict, false);
        AppSettings lenient = AppSettings.load();
        relax(lenient, true);

        var loader = new DocumentLoader();
        var mapper = new ReportMapper();
        var strictFactory = new ValidatorFactory(strict);
        var lenientFactory = new ValidatorFactory(lenient);
        CommonCertificateVerifier verifier =
                new TrustConfig(strict).buildLotlCertificateVerifier();

        int files = 0, errors = 0;
        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(p -> EXTENSIONS.stream().anyMatch(
                                e -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(e)))
                        .sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                try {
                    ValidationResult s = mapper.map(p.getFileName().toString(), null,
                            strictFactory.validate(loader.load(p.toFile()), null, verifier, null),
                            strict);
                    ValidationResult l = mapper.map(p.getFileName().toString(), null,
                            lenientFactory.validate(loader.load(p.toFile()), null, verifier, null),
                            lenient);
                    List<SignatureSummary> ss = s.getSignatures();
                    List<SignatureSummary> ls = l.getSignatures();
                    for (int i = 0; i < ss.size(); i++) {
                        SignatureSummary sig = ss.get(i);
                        if (sig.getDetectedReasons().size() < 2) continue;
                        SignatureSummary len = i < ls.size() ? ls.get(i) : null;
                        List<Integer> shown = AdvisorView.reasonsInDisplayOrder(sig);
                        System.out.println(root.relativize(p) + "\t"
                                + sig.getIndication() + "/" + sig.getSubIndication() + "\t"
                                + shown + "\t"
                                + (len != null ? len.getDetectedReasons() : "?") + "\t"
                                + (len != null ? len.getIndication() + "/" + len.getSubIndication()
                                               : "?"));
                    }
                } catch (Exception e) {
                    errors++;
                    System.err.println(p + "  !! " + e);
                }
            }
        }
        System.err.println("souborů: " + files + ", chyb: " + errors);
    }

    private static void relax(AppSettings s, boolean on) {
        s.setAcceptNewerOcsp(on);
        s.setAcceptExpiredRevocationSources(on);
        s.setIgnoreSha1Deprecated(on);
        s.setAcceptPdfNotEtsi(on);
    }
}
