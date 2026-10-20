package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;

/**
 * Ověří dokument s důvěryhodnými seznamy, ale <b>bez zdrojů revokačních dat</b> — tím se vyrobí
 * situace, kdy se o certifikátu razítkovací autority nedá zjistit, zda nebyl odvolán.
 *
 * <p>Slouží k jedinému: ukázat, že větev {@code NO_REVOCATION} v pojmenování příčin důvodu 21
 * opravdu nastane a s jakou dílčí indikací. Ve sbírce se neukázala ani jednou, takže by jinak
 * zůstala nezměřená.
 *
 * <p>Zdroje se odebírají <b>před prvním</b> voláním {@code validate()}: ValidatorFactory si při
 * něm zapamatuje výchozí stav verifieru a před každým dalším během ho obnovuje. Kdo je odebere
 * až potom, dostane je zpátky.
 */
public final class NoRevocationProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: NoRevocationProbe <soubor>");
            return;
        }
        File file = new File(args[0]);

        run(file, "s revokačními zdroji", false);
        run(file, "bez revokačních zdrojů", true);
    }

    private static void run(File file, String label, boolean strip) throws Exception {
        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier ccv = new TrustConfig(settings).buildLotlCertificateVerifier();
        if (strip) {
            ccv.setCrlSource(null);
            ccv.setOcspSource(null);
            ccv.setAIASource(null);
        }
        var outcome = new ValidatorFactory(settings)
                .validate(new DocumentLoader().load(file), null, ccv, null);
        var simple = outcome.reports().getSimpleReport();
        var result = new ReportMapper().map(file.getName(), "x", outcome, settings,
                java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

        System.out.println();
        System.out.println("===== " + label);
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.printf("  podpis %s / %s, důvody %s%n", sig.getIndication(),
                    sig.getSubIndication(), sig.getDetectedReasons());
            for (var ts : sig.getAllTimestamps()) {
                System.out.printf("      razítko %s passed=%s příčina=%s%n",
                        ts.time(), ts.passed(), ts.failureCause());
            }
        }
        for (String tsId : simple.getSignatureIdList()) {
            simple.getSignatureTimestamps(tsId).forEach(t -> System.out.printf(
                    "      DSS %s : %s / %s%n", t.getId().substring(0, 10),
                    t.getIndication(), t.getSubIndication()));
        }
    }
}
