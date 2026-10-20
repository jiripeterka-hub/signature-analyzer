package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import eu.europa.esig.dss.model.tsl.TrustProperties;
import eu.europa.esig.dss.model.tsl.TrustServiceStatusAndInformationExtensions;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CertificateSource;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.tsl.function.GrantedOrRecognizedAtNationalLevelTrustAnchorPeriodPredicate;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Counts the ServiceSupplyPoints — the OCSP and CRL endpoints a Trusted List publishes for a
 * service — reachable through the compact trust-state cache versus through a fresh parse of the
 * same XML. DSS reads them via {@code getAlternativeOCSPUrls} / {@code getAlternativeCRLUrls} when
 * a certificate's own AIA and CDP do not yield usable revocation data.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class SupplyPointProbe {

    private static final String EU_LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml";

    public static void main(String[] args) throws Exception {
        TrustedListsCertificateSource cached = null;
        CommonCertificateVerifier verifier =
                new TrustConfig(AppSettings.load()).buildLotlCertificateVerifier();
        for (CertificateSource src : verifier.getTrustedCertSources().getSources()) {
            if (src instanceof TrustedListsCertificateSource t) cached = t;
        }
        TrustedListsCertificateSource fresh = reparsed();

        report("kompaktní cache", cached);
        report("čerstvý rozbor", fresh);
        System.out.println();
        diff(cached, fresh);
        String needle = args.length > 0 ? args[0] : "Actalis";
        dumpMatching("kompaktní cache", cached, needle);
        dumpMatching("čerstvý rozbor", fresh, needle);
    }

    private static void report(String label, TrustedListsCertificateSource src) {
        if (src == null) { System.out.println(label + ": zdroj nenalezen"); return; }
        int certs = 0, withSupplyPoints = 0, ocspUrls = 0, crlUrls = 0, totalPoints = 0;
        int totalProps = 0, totalEntries = 0, maxProps = 0;
        String worst = null;
        String sample = null;
        for (CertificateToken cert : src.getCertificates()) {
            certs++;
            int here = 0;
            int props = src.getTrustServices(cert).size();
            totalProps += props;
            if (props > maxProps) { maxProps = props; worst = cert.getSubject().getRFC2253(); }
            for (TrustProperties tp : src.getTrustServices(cert)) {
                for (TrustServiceStatusAndInformationExtensions ignored : tp.getTrustService()) {
                    totalEntries++;
                }
                for (TrustServiceStatusAndInformationExtensions svc : tp.getTrustService()) {
                    List<String> points = svc.getServiceSupplyPoints();
                    if (points != null) here += points.size();
                }
            }
            if (here > 0) {
                withSupplyPoints++;
                totalPoints += here;
                if (sample == null) sample = cert.getSubject().getRFC2253();
            }
            ocspUrls += src.getAlternativeOCSPUrls(cert).size();
            crlUrls  += src.getAlternativeCRLUrls(cert).size();
        }
        System.out.printf("%-18s certifikátů %d, se ServiceSupplyPoints %d (celkem bodů %d), "
                        + "alternativních OCSP URL %d, CRL URL %d%n",
                label, certs, withSupplyPoints, totalPoints, ocspUrls, crlUrls);
        System.out.printf("                   TrustProperties celkem %d, záznamů služeb %d, "
                        + "nejvíc na jeden certifikát %d%n", totalProps, totalEntries, maxProps);
        if (worst != null) System.out.println("                   nejvíc u: " + worst);
    }

    /**
     * Compares the two sources certificate by certificate and prints the first differences found,
     * so the one that matters does not have to be guessed at.
     */
    private static void diff(TrustedListsCertificateSource a, TrustedListsCertificateSource b) {
        Map<String, String> left = fingerprints(a);
        Map<String, String> right = fingerprints(b);
        int onlyLeft = 0, onlyRight = 0, differing = 0, shown = 0;
        for (Map.Entry<String, String> e : left.entrySet()) {
            String other = right.get(e.getKey());
            if (other == null) { onlyLeft++; continue; }
            if (other.equals(e.getValue())) continue;
            differing++;
            if (shown++ < 8) {
                System.out.println("ROZDÍL " + e.getKey());
                System.out.println("   cache:  " + e.getValue());
                System.out.println("   čerstvý:" + other);
            }
        }
        for (String key : right.keySet()) if (!left.containsKey(key)) onlyRight++;
        System.out.printf("porovnání: jen v cache %d, jen v čerstvém %d, liší se %d%n",
                onlyLeft, onlyRight, differing);
    }

    /** Certificate subject to everything about it validation could read from the source. */
    private static Map<String, String> fingerprints(TrustedListsCertificateSource src) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (CertificateToken cert : src.getCertificates()) {
            StringBuilder sb = new StringBuilder();
            sb.append("trusted=").append(src.isTrusted(cert))
              .append(" trustTime=").append(src.getTrustTime(cert))
              .append(" ocsp=").append(src.getAlternativeOCSPUrls(cert))
              .append(" crl=").append(src.getAlternativeCRLUrls(cert));
            List<String> services = new java.util.ArrayList<>();
            for (TrustProperties tp : src.getTrustServices(cert)) {
                for (TrustServiceStatusAndInformationExtensions svc : tp.getTrustService()) {
                    services.add(svc.getType() + "/" + svc.getStatus() + "/" + svc.getStartDate()
                            + "/" + svc.getEndDate() + "/" + svc.getAdditionalServiceInfoUris());
                }
            }
            java.util.Collections.sort(services);
            sb.append(" services=").append(services);
            out.put(cert.getDSSIdAsString() + " " + cert.getSubject().getRFC2253(), sb.toString());
        }
        return out;
    }

    /** Everything the source holds for certificates whose subject contains {@code needle}. */
    private static void dumpMatching(String label, TrustedListsCertificateSource src, String needle) {
        System.out.println("--- " + label + " — " + needle);
        for (CertificateToken cert : src.getCertificates()) {
            String dn = cert.getSubject().getRFC2253();
            if (!dn.contains(needle)) continue;
            System.out.println("  " + dn);
            System.out.println("    trusted=" + src.isTrusted(cert)
                    + "  trustTime=" + src.getTrustTime(cert));
            for (TrustProperties tp : src.getTrustServices(cert)) {
                System.out.println("    TL=" + (tp.getTLInfo() != null ? tp.getTLInfo().getUrl() : null)
                        + "  LOTL=" + (tp.getLOTLInfo() != null ? tp.getLOTLInfo().getUrl() : null)
                        + "  territory=" + (tp.getTrustServiceProvider() != null
                            ? tp.getTrustServiceProvider().getTerritory() : null));
                for (TrustServiceStatusAndInformationExtensions svc : tp.getTrustService()) {
                    System.out.println("      type=" + svc.getType());
                    System.out.println("      status=" + svc.getStatus()
                            + "  from=" + svc.getStartDate() + "  to=" + svc.getEndDate());
                    System.out.println("      asi=" + svc.getAdditionalServiceInfoUris()
                            + "  supply=" + svc.getServiceSupplyPoints());
                    System.out.println("      qualifiers=" + (svc.getConditionsForQualifiers() == null
                            ? null : svc.getConditionsForQualifiers().stream()
                                .map(q -> String.valueOf(q.getQualifiers())).toList()));
                }
            }
        }
    }

    private static TrustedListsCertificateSource reparsed() throws Exception {
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
}
