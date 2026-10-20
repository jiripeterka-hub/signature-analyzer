package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;

/**
 * Proč vyjde jedno razítko kvalifikované a druhé ne, ačkoli stojí na témž certifikátu téže
 * autority.
 *
 * <p>U každého razítka vypíše čas vzniku, kvalifikovanost podle jednoduchého protokolu DSS
 * i podle podrobného (k okamžiku vzniku a k nejnižšímu důkazu existence), certifikát autority
 * a služby z důvěryhodného seznamu, pod kterými ten certifikát vede. Vznikla 25. 9. 2026 nad
 * dokumentem se čtyřmi razítky PostSignum TSU 2, z nichž druhé vycházelo jako nekvalifikované.
 *
 * <pre>java -cp … TimestampQualificationProbe &lt;soubor&gt;</pre>
 */
public final class TimestampQualificationProbe {

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setIgnoreSha1Deprecated(false);
        settings.setAcceptPdfNotEtsi(false);
        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        var outcome = factory.validate(loader.load(new File(args[0])), null, verifier, null);
        SimpleReport sr = outcome.reports().getSimpleReport();
        var dr = outcome.reports().getDetailedReport();
        var dd = outcome.reports().getDiagnosticData();
        int n = 0;
        for (TimestampWrapper ts : dd.getTimestampList()) {
            n++;
            System.out.println("=== razítko " + n + "  " + ts.getId());
            System.out.println("  vzniklo        : " + ts.getProductionTime().toInstant()
                    + "   typ " + ts.getType());
            System.out.println("  indikace       : " + sr.getIndication(ts.getId())
                    + " / " + sr.getSubIndication(ts.getId()));
            System.out.println("  kvalif. (SR)   : " + sr.getTimestampQualification(ts.getId()));
            System.out.println("  kvalif. vznik  : "
                    + dr.getTimestampQualificationAtTstGenerationTime(ts.getId()));
            System.out.println("  kvalif. bestPoE: "
                    + dr.getTimestampQualificationAtBestPoeTime(ts.getId()));
            CertificateWrapper tsa = ts.getSigningCertificate();
            if (tsa == null) {
                System.out.println("  (bez certifikátu autority)");
                continue;
            }
            System.out.println("  certifikát     : " + tsa.getCommonName()
                    + "  sn " + tsa.getSerialNumber()
                    + "  " + tsa.getNotBefore().toInstant() + " – " + tsa.getNotAfter().toInstant()
                    + "  trusted=" + tsa.isTrusted());
            for (var tsp : tsa.getTrustServiceProviders()) {
                for (var svc : tsp.getTrustServices()) {
                    System.out.println("    služba " + tail(svc.getServiceType())
                            + " / " + tail(svc.getStatus())
                            + "  od " + inst(svc.getStartDate()) + " do " + inst(svc.getEndDate())
                            + "  „" + svc.getServiceNames() + "“");
                }
            }
            var tq = dr.getTimestampQualificationAtTstGenerationTime(ts.getId());
            var block = dr.getXmlTimestampById(ts.getId());
            if (block != null && block.getValidationTimestampQualification() != null) {
                for (var c : block.getValidationTimestampQualification().getConclusion()
                        .getErrors()) {
                    System.out.println("  chyba kval.    : " + c.getKey() + " — " + c.getValue());
                }
                for (var c : block.getValidationTimestampQualification().getConclusion()
                        .getWarnings()) {
                    System.out.println("  výstraha kval. : " + c.getKey() + " — " + c.getValue());
                }
            }
        }
    }

    private static Object inst(java.util.Date d) {
        return d == null ? "—" : d.toInstant();
    }

    private static String tail(String uri) {
        if (uri == null) return "";
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }
}
