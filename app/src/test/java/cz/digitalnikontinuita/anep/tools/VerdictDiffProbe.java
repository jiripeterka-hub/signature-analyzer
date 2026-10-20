package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;

import java.io.File;

/**
 * Postaví vedle sebe, co o každém prvku říká knihovna DSS a co z toho udělá AnEP — a k tomu
 * zjištěné situace a důvody.
 *
 * <p>Na to se ptá čtenář, kterému se závěr aplikace rozchází se záložkou „Protokoly DSS“: obojí
 * je na téže kartě a bez tohohle výpisu se dá jen hádat, která větev rozdíl udělala.
 */
public final class VerdictDiffProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: VerdictDiffProbe <soubor>");
            return;
        }
        File file = new File(args[0]);
        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings)
                .validate(new DocumentLoader().load(file), null, verifier, null);
        var simple = outcome.reports().getSimpleReport();
        var result = new ReportMapper().map(file.getName(), "x", outcome, settings,
                java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

        System.out.println();
        int n = 0;
        for (SignatureSummary sig : result.getSignatures()) {
            n++;
            String id = sig.getSignatureId();
            System.out.printf("--- prvek č. %d  (%s)%n", n, id.substring(0, 12));
            System.out.printf("      DSS   : %s / %s%n",
                    simple.getIndication(id), simple.getSubIndication(id));
            System.out.printf("      AnEP  : %s / %s%n",
                    sig.getIndication(), sig.getSubIndication());
            System.out.printf("      formát: %s, podepsal: %s%n",
                    sig.getFormat(), sig.getSignerDisplayName());
            System.out.printf("      situace %s, důvody %s%n",
                    sig.getDetectedSituations(), sig.getDetectedReasons());
            System.out.printf("      rozhodný okamžik: %s%n", sig.getBestSignatureTime());
            var derived = outcome.derivedTimeResults().get(id);
            if (derived != null) {
                System.out.printf("      druhý průchod: zobrazený okamžik %s, počítáno k %s → %s%s%n",
                        derived.when().toInstant(), derived.controlTime().toInstant(),
                        derived.indication(),
                        derived.when().equals(derived.controlTime()) ? "" : "   [okamžiky se liší]");
            }
            if (sig.getCoveringProofDetail() != null) {
                System.out.println("      krytí (S54): " + sig.getCoveringProofDetail());
            }
            for (var ts : sig.getAllTimestamps()) {
                System.out.printf("      razítko %s (%s) passed=%s příčina=%s notAfter=%s%n",
                        ts.time(), ts.tsKind(), ts.passed(), ts.failureCause(), ts.notAfter());
            }
        }
    }
}
