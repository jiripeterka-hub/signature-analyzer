package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Proč se u prvku neuplatnil důkaz existence odvozený z prvku, který ho kryje (situace 54)?
 *
 * <p>Vypíše, co rozhoduje: rozsahy podepsaných bajtů, krytí, razítka a jejich indikace, výsledek
 * {@link ReportMapper#coveringProof} a závěr druhého průchodu z
 * {@code ValidatorFactory.derivedTimeResults()}.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.core.CoveringProofProbe &lt;soubor&gt;</pre>
 */
public final class CoveringProofProbe {

    private CoveringProofProbe() { }

    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        AppSettings settings = AppSettings.load();
        if (args.length > 1 && "-ustupky".equals(args[1])) {
            settings.setAcceptNewerOcsp(true);
            System.out.println("# ustupek: revokacni informace vydane po expiraci = ANO");
        }
        TrustConfig trust = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trust.buildLotlCertificateVerifier();
            System.out.println("# duveryhodne seznamy: nacteny");
        } catch (Exception e) {
            verifier = trust.buildOfflineCertificateVerifier();
            System.out.println("# duveryhodne seznamy: NEDOSTUPNE");
        }

        DSSDocument doc = new DocumentLoader().load(f);
        ValidatorFactory.ValidationOutcome outcome =
                new ValidatorFactory(settings).validate(doc, null, verifier, null);
        DiagnosticData dd = outcome.reports().getDiagnosticData();
        SimpleReport now = outcome.reports().getSimpleReport();
        List<SignatureWrapper> sigs = dd.getSignatures();

        System.out.println("# prvku: " + sigs.size());
        for (SignatureWrapper sw : sigs) {
            System.out.println();
            System.out.println("== " + sw.getId()
                    + "  " + now.getIndication(sw.getId())
                    + "/" + now.getSubIndication(sw.getId()));
            System.out.println("   format=" + sw.getSignatureFormat()
                    + "  intact=" + sw.isSignatureIntact()
                    + "  cas=" + sw.getClaimedSigningTime());
            System.out.println("   rozsah=" + Arrays.toString(range(sw)));
            for (TimestampWrapper ts : sw.getTimestampList()) {
                System.out.println("   razitko " + ts.getId() + " typ=" + ts.getType()
                        + " cas=" + ts.getProductionTime()
                        + " indikace=" + now.getIndication(ts.getId())
                        + "/" + now.getSubIndication(ts.getId()));
            }
            System.out.println("   vlastni nejstarsi platne razitko="
                    + ReportMapper.earliestValidTimestampTime(sw, now));
            for (SignatureWrapper other : sigs) {
                if (other.getId().equals(sw.getId())) continue;
                System.out.println("   kryje jej " + other.getId() + "? "
                        + ReportMapper.coversElement(other, sw)
                        + "  (jeho rozsah=" + Arrays.toString(range(other))
                        + ", intact=" + other.isSignatureIntact()
                        + ", jeho nejstarsi platne razitko="
                        + ReportMapper.earliestValidTimestampTime(other, now) + ")");
            }
            System.out.println("   coveringProof=" + ReportMapper.coveringProof(sw, sigs, now));
            var sc = sw.getSigningCertificate();
            if (sc != null) {
                System.out.println("   cert=" + sc.getNotBefore() + " .. " + sc.getNotAfter()
                        + "  revokacnich udaju=" + sc.getCertificateRevocationData().size()
                        + "  retez=" + sc.getCertificateChain().size());
                for (var r : sc.getCertificateRevocationData()) {
                    var ri = r.getSigningCertificate();
                    System.out.println("      revokace " + r.getOrigin() + " thisUpdate="
                            + r.getThisUpdate() + " stav=" + r.getStatus()
                            + "  vydavatel udaje=" + (ri == null ? "?" : ri.getNotBefore()
                              + " .. " + ri.getNotAfter()));
                }
            }
            print("chyby(1. pruchod)", now.getAdESValidationErrors(sw.getId()));
            var derived = outcome.derivedTimeResults().get(sw.getId());
            if (derived != null && derived.reports() != null) {
                print("chyby(2. pruchod)", derived.reports().getSimpleReport()
                        .getAdESValidationErrors(sw.getId()));
            }
            System.out.println("   druhy pruchod=" + (derived == null ? "neprobehl"
                    : derived.indication() + "/" + derived.subIndication()
                      + "  when=" + derived.when() + "  controlTime=" + derived.controlTime()));
        }
    }

    private static void print(String what, List<eu.europa.esig.dss.jaxb.object.Message> msgs) {
        System.out.println("   " + what + ":");
        for (var m : msgs) System.out.println("      " + m.getKey() + " — " + m.getValue());
    }

    private static long[] range(SignatureWrapper sw) {
        var rev = sw.getPDFRevision();
        return rev == null ? null : rev.getSignatureByteRange().stream()
                .mapToLong(java.math.BigInteger::longValue).toArray();
    }
}
