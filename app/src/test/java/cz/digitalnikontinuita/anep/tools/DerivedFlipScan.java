package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;

import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Co druhý průchod udělal s verdikty prvků, o jejichž existenci svědčí krycí prvek.
 *
 * <p>Měří dopad pozdějšího kontrolního času ({@code ValidatorFactory.lateControlTime}): týž běh
 * s ním a bez něj ({@code -Danep.nolatecontrol=true}) se pak porovná řádek po řádku.
 *
 * <pre>
 *   mvn -o -q -pl app exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=cz.digitalnikontinuita.anep.tools.DerivedFlipScan \
 *       -Dexec.args="seznam.txt vystup.txt [-ustupky]"
 * </pre>
 */
public final class DerivedFlipScan {

    private DerivedFlipScan() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DerivedFlipScan <seznam.txt> <vystup.txt> [-ustupky]");
            return;
        }
        boolean lenient = args.length > 2 && "-ustupky".equals(args[2]);
        List<String> paths = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);

        AppSettings settings = AppSettings.load();
        if (lenient) {
            settings.setAcceptNewerOcsp(true);
            settings.setAcceptExpiredRevocationSources(true);
        }
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);

        int files = 0, derived = 0, flips = 0, late = 0;
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8))) {
            w.println("# ustupky=" + lenient
                    + "  pozdejsi-kontrolni-cas=" + !Boolean.getBoolean("anep.nolatecontrol"));
            for (String p : paths) {
                if (p == null || p.isBlank()) continue;
                File f = new File(p.trim());
                if (!f.isFile()) continue;
                files++;
                try {
                    ValidatorFactory.ValidationOutcome outcome =
                            factory.validate(new DocumentLoader().load(f), null, verifier, null);
                    var base = outcome.reports().getSimpleReport();
                    for (var e : outcome.derivedTimeResults().entrySet()) {
                        var d = e.getValue();
                        derived++;
                        boolean passed = "TOTAL_PASSED".equals(d.indication());
                        if (passed) flips++;
                        boolean moved = d.controlTime().after(d.when());
                        if (passed && moved) late++;
                        w.println(String.join("\t",
                                e.getKey().substring(0, 12),
                                String.valueOf(base.getIndication(e.getKey())),
                                d.indication() + "/" + d.subIndication(),
                                "when=" + d.when().toInstant(),
                                "control=" + d.controlTime().toInstant(),
                                f.getName()));
                    }
                } catch (Exception | StackOverflowError ex) {
                    w.println("CHYBA\t" + f.getName() + "\t" + ex);
                }
                w.flush();
                if (files % 25 == 0) System.out.println("### " + files + "/" + paths.size());
            }
            String sum = "souboru=" + files + ", prvku s druhym pruchodem=" + derived
                    + ", z toho kladnych=" + flips + " (s pozdejsim kontrolnim casem=" + late + ")";
            w.println("# " + sum);
            System.out.println("### " + sum);
        }
    }
}
