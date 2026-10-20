package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.CustomTrustStore;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;

/**
 * Prints a signature's own certificate chain — who trusts what, and where the chain stops — so the
 * question "would pinning the issuing CA do, or does it have to be the root?" can be answered by
 * measurement rather than by reading DSS's javadoc.
 *
 * <p>Point it at the same document three times, each with {@code APPDATA} aimed at a different
 * {@code trusted-certs} directory: empty, root only, issuing CA only. {@code -Dprobe.dumpcerts}
 * writes every certificate of the chain out as DER, which is how those directories get built.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class CommercialAnchorProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: CommercialAnchorProbe <file>");
            return;
        }
        if ("-bundle".equals(args[0])) {
            // Kontrola balíku: chybějící zdroj se jinak jen vypíše na stderr a tiše vypadne.
            var issuing = cz.digitalnikontinuita.anep.core.CzechCommercialCATrust.loadCommercialIssuingCAs();
            System.out.println("vydávající autority v balíku: " + issuing.size());
            for (var c : issuing) {
                System.out.println("   " + c.getSubject().getPrettyPrintRFC2253()
                        + "   " + c.getNotBefore() + " .. " + c.getNotAfter());
            }
            System.out.println("kořeny v balíku: "
                    + cz.digitalnikontinuita.anep.core.CzechCommercialCATrust.loadAllRoots().size());
            return;
        }
        Path file = Path.of(args[0]);

        AppSettings settings = AppSettings.load();
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        DSSDocument doc = new DocumentLoader().load(file.toFile());
        ValidationOutcome outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        SimpleReport simple = outcome.reports().getSimpleReport();
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        System.out.println();
        System.out.println("úložiště: " + CustomTrustStore.listCertificates().size() + " certifikátů");
        for (CustomTrustStore.Entry e : CustomTrustStore.listCertificates()) {
            System.out.println("   připnuto: " + e.certificate().getSubject().getPrettyPrintRFC2253());
        }

        String dumpDir = System.getProperty("probe.dumpcerts");
        for (String sigId : simple.getSignatureIdList()) {
            SignatureWrapper sw = dd.getSignatureById(sigId);
            System.out.printf("%npodpis %s%s   (%s)%n", simple.getIndication(sigId),
                    simple.getSubIndication(sigId) != null ? "/" + simple.getSubIndication(sigId) : "",
                    simple.getSignatureQualification(sigId));
            if (sw == null) continue;
            int i = 0;
            for (CertificateWrapper c : sw.getCertificateChain()) {
                System.out.printf("   %d. %-52s trusted=%-5b TL=%-5b  revokace=%d %s%n", ++i,
                        shorten(c.getReadableCertificateName()), c.isTrusted(),
                        c.isTrustedListReached(), c.getCertificateRevocationData().size(),
                        c.getCertificateRevocationData().isEmpty() ? "(nezjišťována)" : "");
                if (dumpDir != null && c.getBinaries() != null) {
                    java.nio.file.Files.write(Path.of(dumpDir,
                            i + "_" + c.getId().replaceAll("[^A-Za-z0-9-]", "_") + ".cer"),
                            c.getBinaries());
                }
            }
        }
    }

    private static String shorten(String name) {
        return name == null ? "?" : name.length() <= 52 ? name : name.substring(0, 49) + "...";
    }
}
