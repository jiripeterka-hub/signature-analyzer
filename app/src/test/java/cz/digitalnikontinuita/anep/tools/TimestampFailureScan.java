package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Kolikrát a s jakou příčinou padne důvod 21 napříč zadanými soubory.
 *
 * <p>Zajímá především, jak často má prvek <b>právě jednu</b> příčinu: jen tehdy důvod 21 ukáže
 * pojmenovanou větu, jinak zůstane u obecného znění. Kdyby jedna příčina byla vzácná, nová věta
 * by se skoro neukázala a práce by byla zbytečná.
 *
 * <p>Každý soubor dostává vlastní {@code CertificateVerifier} — sdílený se během skenu učí
 * a pozdější soubory pak vycházejí jinak než v izolaci.
 *
 * <pre>java -cp … TimestampFailureScan &lt;seznam-cest.txt&gt;</pre>
 */
public final class TimestampFailureScan {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: TimestampFailureScan <seznam-cest.txt>");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]));
        TreeMap<String, Integer> byCause = new TreeMap<>();
        int files = 0, withReason21 = 0, single = 0, multiple = 0, unnamed = 0;

        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p.trim());
            if (!f.isFile()) {
                System.out.println("CHYBÍ  " + p);
                continue;
            }
            files++;
            try {
                AppSettings settings = AppSettings.load();
                var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
                var outcome = new ValidatorFactory(settings)
                        .validate(new DocumentLoader().load(f), null, verifier, null);
                var result = new ReportMapper().map(f.getName(), "x", outcome, settings,
                        java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

                for (SignatureSummary sig : result.getSignatures()) {
                    if (!sig.getDetectedReasons().contains(21)) continue;
                    withReason21++;
                    Set<SignatureSummary.TimestampFailure> causes = new LinkedHashSet<>();
                    for (var ts : sig.getAllTimestamps()) {
                        if (!ts.passed() && ts.failureCause() != null) causes.add(ts.failureCause());
                    }
                    if (causes.isEmpty()) unnamed++;
                    else if (causes.size() == 1) single++;
                    else multiple++;
                    causes.forEach(c -> byCause.merge(c.name(), 1, Integer::sum));
                    System.out.printf("%-60s %s%n", f.getName(), causes);
                }
            } catch (Exception e) {
                System.out.println("CHYBA  " + f.getName() + " : " + e);
            }
        }
        System.out.println();
        System.out.printf("souborů %d, prvků s důvodem 21: %d (jedna příčina %d, více %d, "
                + "nepojmenováno %d)%n", files, withReason21, single, multiple, unnamed);
        byCause.forEach((k, v) -> System.out.printf("   %-20s %d%n", k, v));
    }
}
