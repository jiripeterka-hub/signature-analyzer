package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;

import java.io.File;

/**
 * Co brána {@code continuityObstacle()} řekne o každém prvku dokumentu, a z čeho k tomu došla.
 *
 * <p>Ta věta se čtenáři ukáže v záložce Digitální kontinuita jako důvod, proč se u prvku nedá
 * zachovávat ověřitelnost. Bez výpisu vstupů se nedá poznat, jestli je ten důvod pravdivý —
 * poslední větev brány je společná pro několik různých situací a řekne „chybí revokační
 * informace" i tam, kde žádné potřeba nejsou.
 *
 * <p>Sonda je v balíčku {@code ui}, protože brána je balíčkově viditelná.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.ContinuityObstacleProbe &lt;soubor&gt;</pre>
 */
public final class ContinuityObstacleProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ContinuityObstacleProbe <soubor>");
            return;
        }
        File file = new File(args[0]);
        AppSettings settings = AppSettings.load();
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var outcome = new ValidatorFactory(settings)
                .validate(new DocumentLoader().load(file), null, verifier, null);
        var result = new ReportMapper().map(file.getName(), "x", outcome, settings,
                java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());

        int n = 0;
        for (SignatureSummary sig : result.getSignatures()) {
            n++;
            System.out.println();
            System.out.printf("--- prvek č. %d : %s  %s/%s%n", n, sig.getFormat(),
                    sig.getIndication(), sig.getSubIndication());
            System.out.printf("      situace            : %s%n", sig.getDetectedSituations());
            System.out.printf("      důvody             : %s%n", sig.getDetectedReasons());
            System.out.printf("      rozhodný okamžik   : %s%n", sig.getBestSignatureTime());
            System.out.printf("      ověřitelné do      : %s%n", sig.getVerifiableUntil());
            System.out.printf("      lhůta uchování     : %s%n", sig.getAugmentationDeadline());
            System.out.printf("      uchování má smysl  : %s%n", sig.isPreservationWorthwhile());
            System.out.printf("      S33 detail         : %s%n", sig.getS33Detail());
            System.out.printf("      S33 co chybí       : %s%n", sig.getS33MissingDetail());
            System.out.printf("      kotva z úložiště   : %s (%s)%n",
                    sig.isTrustedViaCustomStore(), sig.getCustomTrustAnchorName());
            System.out.printf("      konec platnosti cert.: %s%n", sig.getCertNotAfter());
            sig.getAllTimestamps().forEach(t -> System.out.printf(
                    "      razítko %s passed=%s vydavatel=%s notAfter=%s%n",
                    t.time(), t.passed(), t.issuer(), t.notAfter()));
            System.out.printf("      zobrazený verdikt  : %s%n",
                    AdvisorView.displayedIndication(sig));
            System.out.printf("      >>> PŘEKÁŽKA       : %s%n",
                    AdvisorView.continuityObstacle(sig, java.util.Set.of()));
        }
    }
}
