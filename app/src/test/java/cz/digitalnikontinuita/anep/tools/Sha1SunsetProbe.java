package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.detailedreport.DetailedReport;
import eu.europa.esig.dss.detailedreport.jaxb.XmlBasicBuildingBlocks;
import eu.europa.esig.dss.detailedreport.jaxb.XmlConstraint;
import eu.europa.esig.dss.detailedreport.jaxb.XmlStatus;
import eu.europa.esig.dss.detailedreport.jaxb.XmlSubXCV;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;

/**
 * Validates one document twice — once holding SHA-1 to its expiry date, once tolerating it — and
 * prints, per time-stamp, what DSS concluded and which constraints it failed, together with the
 * sunset date of the trust anchor its chain reaches.
 *
 * <p>Written to settle whether tolerating SHA-1 also lets a time-stamp past its anchor's sunset
 * through, or whether SHA-1 was simply the first of two independent reasons to refuse it.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class Sha1SunsetProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Sha1SunsetProbe <file>");
            return;
        }
        Path file = Path.of(args[0]);

        AppSettings settings = AppSettings.load();
        settings.setIgnoreSha1Deprecated(false);
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();

        run("SHA-1 dle standardů", file, verifier, settings, false);
        run("SHA-1 tolerována", file, verifier, settings, true);
    }

    private static void run(String label, Path file, CommonCertificateVerifier verifier,
                            AppSettings settings, boolean tolerateSha1) throws Exception {
        settings.setIgnoreSha1Deprecated(tolerateSha1);
        DSSDocument doc = new DocumentLoader().load(file.toFile());
        ValidationOutcome outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        SimpleReport simple = outcome.reports().getSimpleReport();
        DetailedReport detailed = outcome.reports().getDetailedReport();
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println(label + "   (ignoreSha1Deprecated=" + settings.isIgnoreSha1Deprecated() + ")");
        System.out.println("=".repeat(100));
        for (String sigId : simple.getSignatureIdList()) {
            System.out.printf("podpis %s%n", simple.getIndication(sigId)
                    + (simple.getSubIndication(sigId) != null
                        ? "/" + simple.getSubIndication(sigId) : ""));
        }
        for (TimestampWrapper ts : dd.getTimestampList()) {
            CertificateWrapper tsa = ts.getSigningCertificate();
            System.out.printf("%nrazítko %s  %s%n", ts.getProductionTime(), ts.getType());
            System.out.printf("   TSA: %s%n", tsa != null ? tsa.getReadableCertificateName() : "?");
            System.out.printf("   otisk razítka: %s%n",
                    ts.getDigestMatchers().isEmpty() ? "?"
                            : ts.getDigestMatchers().get(0).getDigestMethod());
            if (tsa != null) {
                System.out.printf("   sunset kotvy v řetězu: %s%n", sunsetOf(ts));
            }
            System.out.printf("   DSS: %s%s%n", simple.getIndication(ts.getId()),
                    simple.getSubIndication(ts.getId()) != null
                            ? "/" + simple.getSubIndication(ts.getId()) : "");
            dumpFailures(detailed, ts.getId());
        }
    }

    private static String sunsetOf(TimestampWrapper ts) {
        StringBuilder sb = new StringBuilder();
        for (CertificateWrapper c : ts.getCertificateChain()) {
            sb.append("\n      ").append(String.format("%-52s trusted=%-5b sunset=%s",
                    c.getReadableCertificateName(), c.isTrusted(),
                    c.getTrustSunsetDate() == null ? "-" : c.getTrustSunsetDate()));
        }
        return sb.toString();
    }

    private static void dumpFailures(DetailedReport detailed, String tokenId) {
        XmlBasicBuildingBlocks bbb = detailed.getBasicBuildingBlockById(tokenId);
        if (bbb == null) return;
        if (bbb.getXCV() != null) {
            for (XmlConstraint c : bbb.getXCV().getConstraint()) print("XCV", c);
            for (XmlSubXCV sub : bbb.getXCV().getSubXCV()) {
                for (XmlConstraint c : sub.getConstraint()) print("subXCV", c);
            }
        }
        if (bbb.getSAV() != null) {
            for (XmlConstraint c : bbb.getSAV().getConstraint()) print("SAV", c);
        }
        if (bbb.getCV() != null) {
            for (XmlConstraint c : bbb.getCV().getConstraint()) print("CV", c);
        }
    }

    private static void print(String block, XmlConstraint c) {
        if (c.getStatus() == XmlStatus.OK) return;
        System.out.printf("      %-7s %-9s %-24s %s%n", block, c.getStatus(),
                c.getName() != null ? c.getName().getKey() : "?",
                c.getError() != null ? c.getError().getValue() : "");
    }
}
