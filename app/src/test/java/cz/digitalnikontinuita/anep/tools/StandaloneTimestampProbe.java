package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;

/**
 * Co AnEP řekne o samostatném časovém razítku: dílčí indikace DSS, zařazená příčina selhání,
 * situace a důvody.
 *
 * <p>Vznikla 25. 9. 2026 nad nálezem uživatele: samostatné razítko (.tst) předložené bez
 * orazítkovaných dat dostávalo důvod 31 se zněním „otisk nesouhlasí“, ačkoli DSS hlásí jen to,
 * že krytá data nenašlo.
 *
 * <pre>java -cp … StandaloneTimestampProbe &lt;soubor&gt; [&lt;orazítkovaná data&gt;]</pre>
 */
public final class StandaloneTimestampProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: StandaloneTimestampProbe <soubor> [<data>]");
            return;
        }
        AppSettings settings = AppSettings.load();
        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        var mapper = new ReportMapper();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        File f = new File(args[0]);
        File data = args.length > 1 ? new File(args[1]) : null;
        var doc = loader.load(f);
        var detached = data != null ? loader.load(data) : null;
        var outcome = factory.validate(doc, detached, verifier, null);
        ValidationResult result = mapper.map(f.getName(), null, outcome, settings);
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.println("id          = " + sig.getSignatureId());
            System.out.println("indication  = " + sig.getIndication()
                    + " / " + sig.getSubIndication());
            System.out.println("tsFailure   = " + sig.getStandaloneTimestampFailure());
            System.out.println("situations  = " + sig.getDetectedSituations());
            System.out.println("reasons     = " + sig.getDetectedReasons());
        }
    }
}
