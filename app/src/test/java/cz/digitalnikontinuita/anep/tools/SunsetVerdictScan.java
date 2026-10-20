package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.tsl.function.GrantedTrustAnchorPeriodPredicate;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Validates a corpus twice — once against the production trust state, once against the same cached
 * Trusted Lists re-parsed without a trust anchor validity predicate, the way the application read
 * them before it honoured sunset dates — and lists every signature whose DSS indication differs.
 *
 * <p>The two runs share everything except the trust source, so a difference can only come from a
 * trust anchor whose Trusted List entry has ceased to hold a positive status.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class SunsetVerdictScan {

    private static final String EU_LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml";

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        List<String> extensions = new ArrayList<>(
                List.of(".pdf", ".zfo", ".p7s", ".asice", ".asics", ".sce", ".xml"));
        boolean verbose = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-v" -> verbose = true;
                case "-ext" -> {
                    extensions.clear();
                    for (String e : args[++i].split(",")) {
                        String x = e.trim().toLowerCase(Locale.ROOT);
                        extensions.add(x.startsWith(".") ? x : "." + x);
                    }
                }
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: SunsetVerdictScan [-v] [-ext pdf,zfo] <folder>…");
            return;
        }

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        // The production trust state against the same cached XML re-parsed the way the
        // application read it before trust anchors carried a sunset date.
        CommonCertificateVerifier withSunset = trustConfig.buildLotlCertificateVerifier();
        CommonCertificateVerifier withoutSunset = reparsedVerifier(false);

        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();

        int files = 0, signatures = 0, changed = 0, failed = 0;
        Map<String, Integer> transitions = new TreeMap<>();
        List<String> details = new ArrayList<>();

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.toString().toLowerCase(Locale.ROOT);
                            return extensions.stream().anyMatch(n::endsWith);
                        })
                        .sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                try {
                    Map<String, String> before = indications(factory
                            .validate(loader.load(p.toFile()), null, withoutSunset, null)
                            .reports().getSimpleReport());
                    Map<String, String> after = indications(factory
                            .validate(loader.load(p.toFile()), null, withSunset, null)
                            .reports().getSimpleReport());

                    for (Map.Entry<String, String> e : before.entrySet()) {
                        signatures++;
                        String now = after.get(e.getKey());
                        if (now == null || now.equals(e.getValue())) continue;
                        changed++;
                        transitions.merge(e.getValue() + "  →  " + now, 1, Integer::sum);
                        details.add(String.format("%-50s %s  →  %s",
                                trim(p.getFileName().toString(), 50), e.getValue(), now));
                    }
                } catch (Exception | StackOverflowError e) {
                    failed++;
                    if (verbose) System.out.println("!! " + p.getFileName() + "  " + e);
                }
            }
        }

        System.out.println("=".repeat(100));
        System.out.println("souborů: " + files + ", podpisů: " + signatures
                + ", nepodařilo se ověřit: " + failed);
        System.out.println();
        System.out.println("podpisů, kterým sunset změnil výsledek: " + changed);
        transitions.forEach((k, v) -> System.out.printf("   %-60s %d%n", k, v));
        details.forEach(d -> System.out.println("   " + d));
        System.out.println("=".repeat(100));
    }

    private static Map<String, String> indications(SimpleReport report) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String id : report.getSignatureIdList()) {
            Indication ind = report.getIndication(id);
            out.put(id, ind + (report.getSubIndication(id) != null
                    ? "/" + report.getSubIndication(id) : ""));
        }
        return out;
    }

    /**
     * A verifier over the same cached Trusted List XML as the production one, parsed afresh so the
     * compact binary cache plays no part; {@code sunset} decides whether trust anchors get an end
     * of trust derived from the service status.
     */
    private static CommonCertificateVerifier reparsedVerifier(boolean sunset) throws Exception {
        File cacheDir = TrustConfig.tlCacheDir();
        FileCacheDataLoader cacheOnly = new FileCacheDataLoader();
        cacheOnly.setCacheExpirationTime(Long.MAX_VALUE);
        cacheOnly.setDataLoader(new IgnoreDataLoader());
        cacheOnly.setFileCacheDirectory(cacheDir);

        LOTLSource lotl = new LOTLSource();
        lotl.setUrl(EU_LOTL_URL);
        lotl.setPivotSupport(true);
        lotl.setCertificateSource(signingCerts(cacheOnly));
        if (sunset) lotl.setTrustAnchorValidityPredicate(new GrantedTrustAnchorPeriodPredicate());

        TrustedListsCertificateSource src = new TrustedListsCertificateSource();
        TLValidationJob job = new TLValidationJob();
        job.setOfflineDataLoader(cacheOnly);
        job.setOnlineDataLoader(cacheOnly);
        job.setTrustedListCertificateSource(src);
        job.setListOfTrustedListSources(lotl);
        job.onlineRefresh();

        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        verifier.setTrustedCertSources(src);
        verifier.setOcspSource(new OnlineOCSPSource());
        verifier.setCrlSource(new OnlineCRLSource());
        verifier.setAIASource(new DefaultAIASource(
                new eu.europa.esig.dss.service.http.commons.CommonsDataLoader()));
        return verifier;
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

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

}
