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
import eu.europa.esig.dss.detailedreport.jaxb.XmlXCV;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TrustServiceWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.tsl.function.GrantedOrRecognizedAtNationalLevelTrustAnchorPeriodPredicate;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

/**
 * Prints, side by side, why one document validates differently against the compact trust-state
 * cache than against the same Trusted List XML parsed afresh — down to the individual constraints
 * DSS marked as failed, plus what the signing certificate carries in the extensions that decide
 * which trust service it may be matched against.
 *
 * <p>Written for {@code D:\tmp\emailovy certifikat\dokument signed.pdf}, whose signing certificate
 * bears only clientAuth and emailProtection in its extended key usage.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class ChainFailureProbe {

    private static final String EU_LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ChainFailureProbe <file>");
            return;
        }
        Path file = Path.of(args[0]);

        AppSettings settings = AppSettings.load();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();

        // Both verifiers are assembled identically and differ only in the trust source, so the
        // production verifier's own wiring — its AIA loader above all — cannot colour the result.
        CommonCertificateVerifier production = new TrustConfig(settings).buildLotlCertificateVerifier();
        TrustedListsCertificateSource cachedSource = null;
        for (var src : production.getTrustedCertSources().getSources()) {
            if (src instanceof TrustedListsCertificateSource t) cachedSource = t;
        }
        CommonCertificateVerifier cached = wrap(cachedSource);
        CommonCertificateVerifier fresh = wrap(reparsedSource());

        // Revocation is fetched over the network, so whichever run goes first pays for any
        // transient failure and warms whatever the next one reuses. Reversing the order tells a
        // difference in the trust state apart from a difference in the weather.
        boolean reversed = args.length > 1 && args[1].equals("-reverse");
        if (reversed) {
            run("z čerstvého rozboru (první)", factory, loader, file, fresh, false);
            run("z kompaktní cache (druhý)", factory, loader, file, cached, false);
        } else {
            run("z kompaktní cache (první)", factory, loader, file, cached, true);
            run("z čerstvého rozboru (druhý)", factory, loader, file, fresh, false);
        }
    }

    private static void run(String label, ValidatorFactory factory, DocumentLoader loader,
                            Path file, CommonCertificateVerifier verifier, boolean dumpCert)
            throws Exception {
        DSSDocument doc = loader.load(file.toFile());
        ValidationOutcome outcome = factory.validate(doc, null, verifier, null);
        SimpleReport simple = outcome.reports().getSimpleReport();
        DetailedReport detailed = outcome.reports().getDetailedReport();
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        System.out.println("=".repeat(100));
        System.out.println(label);
        System.out.println("=".repeat(100));

        for (String id : simple.getSignatureIdList()) {
            System.out.printf("%npodpis %s: %s%s%n", id, simple.getIndication(id),
                    simple.getSubIndication(id) != null ? "/" + simple.getSubIndication(id) : "");

            SignatureWrapper sw = dd.getSignatureById(id);
            CertificateWrapper signer = sw != null ? sw.getSigningCertificate() : null;
            if (dumpCert && signer != null) {
                System.out.println("  podpisový certifikát: " + signer.getReadableCertificateName());
                System.out.println("  extended key usage:   " + signer.getExtendedKeyUsages());
                System.out.println("  key usage:            " + signer.getKeyUsages());
                System.out.println("  QC compliance:        " + signer.isQcCompliance());
                System.out.println("  v TL dosažen:         " + signer.isTrustedListReached());
                System.out.println("  řetěz:");
                for (CertificateWrapper c : sw.getCertificateChain()) {
                    System.out.printf("     %-60s trusted=%b sunset=%s%n",
                            trim(c.getReadableCertificateName()), c.isTrusted(),
                            c.getTrustSunsetDate());
                    for (TrustServiceWrapper svc : c.getTrustServices()) {
                        System.out.printf("        služba %-24s %-18s ASI=%s%n",
                                tail(svc.getType()), tail(svc.getStatus()),
                                svc.getAdditionalServiceInfos());
                    }
                }
            }
            if (signer != null) {
                System.out.println("  revokační data pro podpisový certifikát: "
                        + signer.getCertificateRevocationData().size());
                for (var rev : signer.getCertificateRevocationData()) {
                    System.out.printf("     %-6s %-10s thisUpdate=%s  vydavatel=%s%n",
                            rev.getRevocationType(), rev.getOrigin(), rev.getThisUpdate(),
                            rev.getSigningCertificate() != null
                                ? trim(rev.getSigningCertificate().getReadableCertificateName()) : "?");
                }
                for (CertificateWrapper c : sw.getCertificateChain()) {
                    System.out.printf("     CA %-45s revokací=%d  alternativní URL z TL=%s%n",
                            trim(c.getReadableCertificateName()),
                            c.getCertificateRevocationData().size(),
                            c.getCRLDistributionPoints());
                }
            }
            dumpFailedConstraints(detailed, id);
            if (sw != null && sw.getSigningCertificate() != null) {
                dumpFailedConstraints(detailed, sw.getSigningCertificate().getId());
            }
        }
    }

    private static void dumpFailedConstraints(DetailedReport detailed, String tokenId) {
        XmlBasicBuildingBlocks bbb = detailed.getBasicBuildingBlockById(tokenId);
        if (bbb == null) return;
        XmlXCV xcv = bbb.getXCV();
        if (xcv == null) return;
        System.out.println("  XCV " + tokenId + ": " + xcv.getConclusion().getIndication()
                + (xcv.getConclusion().getSubIndication() != null
                    ? "/" + xcv.getConclusion().getSubIndication() : ""));
        for (XmlConstraint c : xcv.getConstraint()) {
            if (c.getStatus() == XmlStatus.OK) continue;
            System.out.printf("     %-10s %-22s %s%n", c.getStatus(),
                    c.getName() != null ? c.getName().getKey() : "?",
                    c.getError() != null ? c.getError().getValue() : "");
        }
        for (XmlSubXCV sub : xcv.getSubXCV()) {
            for (XmlConstraint c : sub.getConstraint()) {
                if (c.getStatus() == XmlStatus.OK) continue;
                System.out.printf("     sub %-6s %-22s %s%n", c.getStatus(),
                        c.getName() != null ? c.getName().getKey() : "?",
                        c.getError() != null ? c.getError().getValue() : "");
            }
            if (sub.getCRS() == null) continue;
            System.out.println("     CRS: " + sub.getCRS().getConclusion().getIndication()
                    + "/" + sub.getCRS().getConclusion().getSubIndication());
            for (XmlConstraint c : sub.getCRS().getConstraint()) {
                System.out.printf("       CRS %-8s %-22s %s%n", c.getStatus(),
                        c.getName() != null ? c.getName().getKey() : "?",
                        c.getError() != null ? c.getError().getValue() : "");
            }
            for (var rac : sub.getCRS().getRAC()) {
                System.out.println("       RAC " + rac.getId() + " "
                        + rac.getConclusion().getIndication() + "/"
                        + rac.getConclusion().getSubIndication());
                for (XmlConstraint c : rac.getConstraint()) {
                    if (c.getStatus() == XmlStatus.OK) continue;
                    System.out.printf("         RAC %-8s %-22s %s%n", c.getStatus(),
                            c.getName() != null ? c.getName().getKey() : "?",
                            c.getError() != null ? c.getError().getValue() : "");
                }
            }
        }
    }

    /** A verifier assembled the same way for either trust source. */
    private static CommonCertificateVerifier wrap(TrustedListsCertificateSource src) {
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        verifier.setTrustedCertSources(src);
        verifier.setOcspSource(new OnlineOCSPSource());
        verifier.setCrlSource(new OnlineCRLSource());
        verifier.setAIASource(new DefaultAIASource(new CommonsDataLoader()));
        return verifier;
    }

    private static TrustedListsCertificateSource reparsedSource() throws Exception {
        File cacheDir = TrustConfig.tlCacheDir();
        FileCacheDataLoader cacheOnly = new FileCacheDataLoader();
        cacheOnly.setCacheExpirationTime(Long.MAX_VALUE);
        cacheOnly.setDataLoader(new IgnoreDataLoader());
        cacheOnly.setFileCacheDirectory(cacheDir);

        LOTLSource lotl = new LOTLSource();
        lotl.setUrl(EU_LOTL_URL);
        lotl.setPivotSupport(true);
        lotl.setCertificateSource(signingCerts(cacheOnly));
        lotl.setTrustAnchorValidityPredicate(
                new GrantedOrRecognizedAtNationalLevelTrustAnchorPeriodPredicate());

        TrustedListsCertificateSource src = new TrustedListsCertificateSource();
        TLValidationJob job = new TLValidationJob();
        job.setOfflineDataLoader(cacheOnly);
        job.setOnlineDataLoader(cacheOnly);
        job.setTrustedListCertificateSource(src);
        job.setListOfTrustedListSources(lotl);
        job.onlineRefresh();

        return src;
    }

    private static CommonTrustedCertificateSource signingCerts(FileCacheDataLoader loader) throws Exception {
        CommonTrustedCertificateSource out = new CommonTrustedCertificateSource();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(loader.get(EU_LOTL_URL)));
        NodeList nodes = doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "X509Certificate");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < nodes.getLength(); i++) {
            byte[] der = Base64.getDecoder().decode(nodes.item(i).getTextContent().replaceAll("\\s+", ""));
            out.addCertificate(new CertificateToken(
                    (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der))));
        }
        return out;
    }

    private static String trim(String s) {
        if (s == null) return "?";
        return s.length() > 58 ? s.substring(0, 57) + "…" : s;
    }

    private static String tail(String uri) {
        return uri == null ? "" : uri.substring(uri.lastIndexOf('/') + 1);
    }
}
