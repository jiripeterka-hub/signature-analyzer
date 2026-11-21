package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;

/**
 * Vidí DSS, že jedno samostatné razítko kryje druhé, a co z toho AnEP udělá?
 *
 * <p>Vznikla 21. 11. 2026 nad nálezem uživatele: kontejner ASiC-E se dvěma samostatnými
 * razítky (druhé přerazítkovává první za platnosti jeho certifikátu) — u prvního razítka
 * Rozšířený přehled tvrdil, že je platné „jen dle standardů“.
 *
 * <pre>java -cp … CoveringTimestampProbe &lt;soubor&gt;</pre>
 */
public final class CoveringTimestampProbe {

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        var mapper = new ReportMapper();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        File f = new File(args[0]);
        var outcome = factory.validate(loader.load(f), null, verifier, null);
        ValidationResult result = mapper.map(f.getName(), null, outcome, settings);
        DiagnosticData dd = result.getDiagnosticData();
        for (TimestampWrapper ts : dd.getTimestampList()) {
            System.out.println("TS " + ts.getId() + "  type=" + ts.getType()
                    + "  file=" + ts.getFilename()
                    + "  time=" + ts.getProductionTime().toInstant()
                    + "  cert=" + (ts.getSigningCertificate() == null ? null
                        : ts.getSigningCertificate().getCommonName() + " do "
                          + ts.getSigningCertificate().getNotAfter().toInstant()));
            System.out.println("   covers ts  = " + ts.getTimestampedTimestamps().stream()
                    .map(TimestampWrapper::getId).toList());
            System.out.println("   covers sig = " + ts.getTimestampedSignatures().size()
                    + "  signed data = " + ts.getTimestampedSignedData().size()
                    + "  refs = " + ts.getTimestampedObjects().stream()
                        .map(o -> o.getCategory() + ":" + o.getToken().getId()).toList());
            System.out.println("   simple     = " + outcome.reports().getSimpleReport()
                    .getIndication(ts.getId()) + " / "
                    + outcome.reports().getSimpleReport().getSubIndication(ts.getId()));
        }
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.println("ELEMENT " + sig.getSignatureId()
                    + "  ind=" + sig.getIndication() + "/" + sig.getSubIndication()
                    + "  certNotAfter=" + sig.getCertNotAfter()
                    + "  envelopeProof=" + sig.getEnvelopeProofTime()
                    + "  anchor=" + sig.isAnchorCert());
            System.out.println("   situations = " + sig.getDetectedSituations());
            System.out.println("   reasons    = " + sig.getDetectedReasons());
            System.out.println("   sunsetOnly = " + cz.digitalnikontinuita.anep.ui.AdvisorView
                    .shouldWarnSunsetOnly(sig, sig.getIndication()));
        }
        System.exit(0);
    }
}
