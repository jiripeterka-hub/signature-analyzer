package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.detailedreport.DetailedReport;
import eu.europa.esig.dss.detailedreport.jaxb.XmlBasicBuildingBlocks;
import eu.europa.esig.dss.detailedreport.jaxb.XmlConstraint;
import eu.europa.esig.dss.detailedreport.jaxb.XmlConstraintsConclusion;
import eu.europa.esig.dss.detailedreport.jaxb.XmlConstraintsConclusionWithControlTime;
import eu.europa.esig.dss.detailedreport.jaxb.XmlProofOfExistence;
import eu.europa.esig.dss.detailedreport.jaxb.XmlStatus;
import eu.europa.esig.dss.detailedreport.jaxb.XmlSubXCV;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.diagnostic.TrustServiceWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Validates one document twice — once holding SHA-1 to its expiry date, once tolerating it — and
 * prints, per time-stamp: what DSS concluded, which constraints failed, the sunset date and trust
 * services of every anchor in its chain, and whether the time-stamp ended up carrying the proof
 * of existence the signature rests on.
 *
 * <p>Supersedes {@link Sha1SunsetProbe}, which answered the narrower "do the sunset checks change"
 * question but shared one verifier across both runs and printed neither the trust-service history
 * nor the resulting proof of existence. Here each run builds its own verifier.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class SunsetPoeProbe {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: SunsetPoeProbe <file>");
            return;
        }
        Path file = Path.of(args[0]);

        run("SHA-1 dle standardu", file, false);
        run("SHA-1 tolerovana", file, true);
    }

    private static void run(String label, Path file, boolean tolerateSha1) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setIgnoreSha1Deprecated(tolerateSha1);
        // Vlastni overovatel pro kazdy beh: DSS si do nej zapisuje nalezene certifikaty.
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();

        DSSDocument doc = new DocumentLoader().load(file.toFile());
        ValidationOutcome outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        SimpleReport simple = outcome.reports().getSimpleReport();
        DetailedReport detailed = outcome.reports().getDetailedReport();
        DiagnosticData dd = outcome.reports().getDiagnosticData();
        String dump = System.getProperty("probe.dump");
        if (dump != null) {
            java.nio.file.Files.writeString(Path.of(dump, "detailed-" + (tolerateSha1 ? "tolerant" : "strict") + ".xml"),
                    outcome.reports().getXmlDetailedReport());
        }

        System.out.println();
        System.out.println("=".repeat(110));
        System.out.println(label + "   (ignoreSha1Deprecated=" + settings.isIgnoreSha1Deprecated() + ")");
        System.out.println("=".repeat(110));

        for (String sigId : simple.getSignatureIdList()) {
            System.out.printf("podpis %s: %s%s%n", sigId, simple.getIndication(sigId),
                    simple.getSubIndication(sigId) != null ? "/" + simple.getSubIndication(sigId) : "");
            System.out.printf("   kvalifikace           : %s%n", detailed.getSignatureQualification(sigId));
            System.out.printf("   best signature time   : %s%n", fmt(detailed.getBestSignatureTime(sigId)));
            XmlProofOfExistence poe = detailed.getBestProofOfExistence(sigId);
            System.out.printf("   dukaz existence z     : %s  (%s)%n",
                    poe != null ? fmt(poe.getTime()) : "-",
                    poe != null ? String.valueOf(poe.getTimestampId()) : "-");
            System.out.printf("   BASIC / LTV / ARCHIVE : %s / %s / %s%n",
                    ind(detailed.getBasicValidationIndication(sigId),
                        detailed.getBasicValidationSubIndication(sigId)),
                    ind(detailed.getLongTermValidationIndication(sigId),
                        detailed.getLongTermValidationSubIndication(sigId)),
                    ind(detailed.getArchiveDataValidationIndication(sigId),
                        detailed.getArchiveDataValidationSubIndication(sigId)));
            dumpControlTimes(detailed, sigId, "   ");
        }

        for (TimestampWrapper ts : dd.getTimestampList()) {
            CertificateWrapper tsa = ts.getSigningCertificate();
            System.out.printf("%nrazitko %s  %s  id=%s%n", fmt(ts.getProductionTime()), ts.getType(), ts.getId());
            System.out.printf("   TSA                   : %s%n",
                    tsa != null ? tsa.getReadableCertificateName() : "?");
            System.out.printf("   platnost certifikatu  : %s -> %s%n",
                    tsa != null ? fmt(tsa.getNotBefore()) : "?",
                    tsa != null ? fmt(tsa.getNotAfter()) : "?");
            System.out.printf("   otisk / podpis        : %s / %s%n",
                    ts.getDigestMatchers().isEmpty() ? "?" : ts.getDigestMatchers().get(0).getDigestMethod(),
                    ts.getDigestAlgorithm() + " + " + ts.getEncryptionAlgorithm());
            System.out.printf("   syrove priznaky       : imprint=%b sigIntact=%b sigValid=%b trustedChain=%b%n",
                    ts.isMessageImprintDataIntact(), ts.isSignatureIntact(),
                    ts.isSignatureValid(), ts.isTrustedChain());
            System.out.printf("   DSS zaver             : %s%n",
                    ind(simple.getIndication(ts.getId()), simple.getSubIndication(ts.getId())));
            System.out.printf("   BBB                   : %s%n",
                    ind(detailed.getBasicBuildingBlocksIndication(ts.getId()),
                        detailed.getBasicBuildingBlocksSubIndication(ts.getId())));
            System.out.printf("   zakladni overeni      : %s%n",
                    ind(detailed.getBasicTimestampValidationIndication(ts.getId()),
                        detailed.getBasicTimestampValidationSubIndication(ts.getId())));
            System.out.printf("   kvalifikace  vznik/POE: %s / %s%n",
                    detailed.getTimestampQualificationAtTstGenerationTime(ts.getId()),
                    detailed.getTimestampQualificationAtBestPoeTime(ts.getId()));
            dumpChain(ts);
            dumpControlTimes(detailed, ts.getId(), "   ");
            dumpFailures(detailed, ts.getId());
        }
    }

    private static void dumpChain(TimestampWrapper ts) {
        String certDir = System.getProperty("probe.dumpcerts");
        for (CertificateWrapper c : ts.getCertificateChain()) {
            if (certDir != null && c.getBinaries() != null) {
                try {
                    java.nio.file.Files.write(Path.of(certDir,
                            c.getId().replaceAll("[^A-Za-z0-9-]", "_") + ".cer"), c.getBinaries());
                } catch (Exception ignored) {
                    // sonda, ne nastroj
                }
            }
            System.out.printf("      %-46s trusted=%-5b start=%-19s sunset=%s%n",
                    shorten(c.getReadableCertificateName()), c.isTrusted(),
                    c.getTrustStartDate() == null ? "-" : fmt(c.getTrustStartDate()),
                    c.getTrustSunsetDate() == null ? "-" : fmt(c.getTrustSunsetDate()));
            for (TrustServiceWrapper svc : c.getTrustServices()) {
                System.out.printf("         sluzba %-8s %-18s %s -> %s   %s%n",
                        svc.getStatus() == null ? "?" : tail(svc.getStatus()),
                        shortType(svc.getType()),
                        fmt(svc.getStartDate()), svc.getEndDate() == null ? "(dosud)" : fmt(svc.getEndDate()),
                        svc.getServiceNames() == null || svc.getServiceNames().isEmpty()
                                ? "" : svc.getServiceNames().get(0));
            }
        }
    }

    /** Control time of every past-validation block DSS filled in for this token. */
    private static void dumpControlTimes(DetailedReport detailed, String tokenId, String indent) {
        XmlBasicBuildingBlocks bbb = detailed.getBasicBuildingBlockById(tokenId);
        if (bbb == null) return;
        printControlTime(indent, "PSV", bbb.getPSV());
        printControlTime(indent, "PCV", bbb.getPCV());
        printControlTime(indent, "VTS", bbb.getVTS());
        if (bbb.getVTS() != null && bbb.getVTS().getTrustAnchor() != null) {
            System.out.printf("%sVTS kotva             : %s%n", indent, bbb.getVTS().getTrustAnchor());
        }
    }

    private static void printControlTime(String indent, String name,
                                         XmlConstraintsConclusionWithControlTime block) {
        if (block == null) return;
        System.out.printf("%s%-3s control time      : %s   zaver %s%n", indent, name,
                fmt(block.getControlTime()),
                block.getConclusion() == null ? "?" : ind(block.getConclusion().getIndication(),
                        block.getConclusion().getSubIndication()));
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
        printAll("SAV", bbb.getSAV());
        printAll("CV", bbb.getCV());
        printAll("PSV", bbb.getPSV());
        printAll("PCV", bbb.getPCV());
        printAll("VTS", bbb.getVTS());
    }

    private static void printAll(String block, XmlConstraintsConclusion cc) {
        if (cc == null) return;
        for (XmlConstraint c : cc.getConstraint()) print(block, c);
    }

    private static void print(String block, XmlConstraint c) {
        if (c.getStatus() == XmlStatus.OK) return;
        System.out.printf("      %-7s %-11s %-28s %s%n", block, c.getStatus(),
                c.getName() != null ? c.getName().getKey() : "?",
                c.getError() != null ? c.getError().getValue()
                        : c.getWarning() != null ? c.getWarning().getValue() : "");
    }

    private static String ind(Object indication, Object sub) {
        return indication + (sub != null ? "/" + sub : "");
    }

    private static String fmt(Date d) {
        return d == null ? "-" : FMT.format(d);
    }

    private static String tail(String uri) {
        int i = uri.lastIndexOf('/');
        return i < 0 ? uri : uri.substring(i + 1);
    }

    private static String shortType(String type) {
        return type == null ? "?" : tail(type);
    }

    private static String shorten(String name) {
        return name == null ? "?" : name.length() <= 46 ? name : name.substring(0, 43) + "...";
    }
}
