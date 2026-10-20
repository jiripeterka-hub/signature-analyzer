package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.enumerations.Indication;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Co nové pravidlo pro kontrolní čas druhého průchodu udělalo s verdikty.
 *
 * <p>Pouští se nad soubory, u kterých dřívější měření našlo prvek s rozhodným okamžikem
 * <b>před</b> jeho revokačním údajem — tedy přesně nad těmi, kterých se změna týká. U každého
 * takového prvku vypíše, ke kterému okamžiku se nově počítalo a co vyšlo; očekává se, že verdikt
 * zůstane týž, jaký dřívější měření naměřilo k oběma okamžikům.
 *
 * <pre>java -cp … ControlTimeEffectScan &lt;seznam-cest.txt&gt;</pre>
 */
public final class ControlTimeEffectScan {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ControlTimeEffectScan <seznam-cest.txt>");
            return;
        }
        List<String> paths = Files.readAllLines(Path.of(args[0]));
        int files = 0, shifted = 0, unchangedInstant = 0;

        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p.trim());
            if (!f.isFile()) continue;
            files++;
            try {
                AppSettings settings = AppSettings.load();
                var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
                var outcome = new ValidatorFactory(settings)
                        .validate(new DocumentLoader().load(f), null, verifier, null);
                var base = outcome.reports().getSimpleReport();

                for (var e : outcome.derivedTimeResults().entrySet()) {
                    var d = e.getValue();
                    boolean moved = !d.when().equals(d.controlTime());
                    if (moved) shifted++; else unchangedInstant++;
                    System.out.printf("%-52s %s  DSS=%s  druhý průchod=%s%s%n",
                            f.getName().length() > 52 ? f.getName().substring(0, 52) : f.getName(),
                            e.getKey().substring(0, 12),
                            base.getIndication(e.getKey()), d.indication(),
                            moved ? "  [posunuto z " + d.when().toInstant()
                                    + " na " + d.controlTime().toInstant() + "]" : "");
                    System.out.flush();
                }
            } catch (Exception ex) {
                System.out.println("CHYBA  " + f.getName() + " : " + ex);
            }
        }
        System.out.println();
        System.out.printf("HOTOVO: souborů %d, prvků s druhým průchodem %d,"
                        + " z toho s posunutým kontrolním časem %d%n",
                files, shifted + unchangedInstant, shifted);
        System.out.println("Indication.TOTAL_PASSED = " + Indication.TOTAL_PASSED.name());
    }
}
