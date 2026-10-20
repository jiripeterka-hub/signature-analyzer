package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import eu.europa.esig.dss.model.tsl.CertificateTrustTime;
import eu.europa.esig.dss.model.tsl.TrustProperties;
import eu.europa.esig.dss.model.tsl.TrustServiceStatusAndInformationExtensions;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CertificateSource;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the trust state AnEP actually validates against and reports, per certificate, what the
 * Trusted Lists say about the services it was issued under and what trust time DSS derives.
 *
 * <p>Answers three questions the reports cannot: whether services in a non-positive state
 * (typically {@code withdrawn} since the 2016 eIDAS transition) are present in the loaded state at
 * all, whether any certificate is a trust anchor solely on the strength of such a service, and
 * whether the sunset date — the field DSS's own trust-anchor checks read — is ever populated.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class TrustSunsetProbe {

    /** Statuses under which a service counts as operating properly, pre- and post-eIDAS alike. */
    private static final List<String> POSITIVE = List.of(
            "granted", "accredited", "undersupervision", "supervisionincessation");

    private static final SimpleDateFormat DF = new SimpleDateFormat("yyyy-MM-dd");

    public static void main(String[] args) throws Exception {
        boolean verbose = args.length > 0 && args[0].equals("-v");

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trustConfig.buildLotlCertificateVerifier();

        TrustedListsCertificateSource tl = null;
        for (CertificateSource src : verifier.getTrustedCertSources().getSources()) {
            if (src instanceof TrustedListsCertificateSource t) tl = t;
        }
        if (tl == null) { System.out.println("no TrustedListsCertificateSource"); return; }

        Date now = new Date();
        int certs = 0, withServices = 0, withSunset = 0;
        int qtstAny = 0, qtstPositiveNow = 0, qtstWasPositiveNotNow = 0, qtstNeverPositive = 0;
        int neverPositiveAnyType = 0;
        Map<String, Integer> statusesNow = new TreeMap<>();
        List<String> sunsetSamples = new ArrayList<>();
        List<String> withdrawnQtst = new ArrayList<>();
        List<String> neverPositiveSamples = new ArrayList<>();

        for (CertificateToken cert : tl.getCertificates()) {
            certs++;
            List<TrustProperties> props = tl.getTrustServices(cert);
            if (props == null || props.isEmpty()) continue;
            withServices++;

            boolean qtst = false, qtstPosNow = false, qtstPosEver = false;
            boolean anyPosNow = false, anyPosEver = false;
            Date qtstWithdrawnSince = null;

            for (TrustProperties tp : props) {
                for (TrustServiceStatusAndInformationExtensions svc : tp.getTrustService()) {
                    String type = tail(svc.getType());
                    String status = tail(svc.getStatus()).toLowerCase(Locale.ROOT);
                    boolean positive = POSITIVE.contains(status);
                    boolean coversNow = covers(svc.getStartDate(), svc.getEndDate(), now);
                    if (coversNow) statusesNow.merge(status, 1, Integer::sum);
                    if (positive) {
                        anyPosEver = true;
                        if (coversNow) anyPosNow = true;
                    }
                    boolean isQtst = "QTST".equalsIgnoreCase(type) || "TSA/QTST".equalsIgnoreCase(type);
                    if (!isQtst) continue;
                    qtst = true;
                    if (positive) {
                        qtstPosEver = true;
                        if (coversNow) qtstPosNow = true;
                    } else if (coversNow) {
                        qtstWithdrawnSince = svc.getStartDate();
                    }
                }
            }

            if (qtst) {
                qtstAny++;
                if (qtstPosNow) qtstPositiveNow++;
                else if (qtstPosEver) {
                    qtstWasPositiveNotNow++;
                    if (withdrawnQtst.size() < 25) {
                        withdrawnQtst.add(String.format("%-60s  stažena %s  trustTime=%s",
                                shortName(cert),
                                qtstWithdrawnSince == null ? "?" : DF.format(qtstWithdrawnSince),
                                describe(tl.getTrustTime(cert))));
                    }
                } else qtstNeverPositive++;
            }

            if (!anyPosEver) {
                neverPositiveAnyType++;
                if (neverPositiveSamples.size() < 15) {
                    neverPositiveSamples.add(String.format("%-60s  trustTime=%s  isTrusted=%b",
                            shortName(cert), describe(tl.getTrustTime(cert)), tl.isTrusted(cert)));
                }
            } else if (!anyPosNow && verbose && neverPositiveSamples.size() < 15) {
                neverPositiveSamples.add("(kdysi kladná) " + shortName(cert));
            }

            CertificateTrustTime tt = tl.getTrustTime(cert);
            if (tt != null && tt.getEndDate() != null) {
                withSunset++;
                if (sunsetSamples.size() < 10) {
                    sunsetSamples.add(shortName(cert) + "  " + describe(tt));
                }
            }
        }

        System.out.println("=".repeat(100));
        System.out.println("certifikátů v důvěryhodném seznamu: " + certs
                + ", z toho se službami: " + withServices);
        System.out.println("s neprázdným sunset date (trust time end): " + withSunset);
        System.out.println();
        System.out.println("certifikáty pod službou typu QTST: " + qtstAny);
        System.out.println("   dnes kladný status:               " + qtstPositiveNow);
        System.out.println("   kdysi kladný, dnes už ne:         " + qtstWasPositiveNotNow);
        System.out.println("   nikdy kladný:                     " + qtstNeverPositive);
        System.out.println();
        System.out.println("certifikáty, jejichž žádná služba nikdy neměla kladný status: "
                + neverPositiveAnyType);
        System.out.println();
        System.out.println("statusy služeb platné k dnešku (počet výskytů):");
        statusesNow.forEach((k, v) -> System.out.printf("   %-30s %d%n", k, v));

        if (!withdrawnQtst.isEmpty()) {
            System.out.println();
            System.out.println("ukázka razítkovacích služeb se staženým statusem:");
            withdrawnQtst.forEach(s -> System.out.println("   " + s));
        }
        if (!neverPositiveSamples.isEmpty()) {
            System.out.println();
            System.out.println("ukázka certifikátů bez kladného statusu:");
            neverPositiveSamples.forEach(s -> System.out.println("   " + s));
        }
        if (!sunsetSamples.isEmpty()) {
            System.out.println();
            System.out.println("ukázka nastavených sunset dat:");
            sunsetSamples.forEach(s -> System.out.println("   " + s));
        }
        System.out.println("=".repeat(100));
    }

    private static String describe(CertificateTrustTime tt) {
        if (tt == null) return "null";
        return "[trusted=" + tt.isTrusted()
                + ", od=" + (tt.getStartDate() == null ? "-" : DF.format(tt.getStartDate()))
                + ", do=" + (tt.getEndDate() == null ? "-" : DF.format(tt.getEndDate())) + "]";
    }

    private static boolean covers(Date from, Date to, Date when) {
        return (from == null || !when.before(from)) && (to == null || when.before(to));
    }

    private static String shortName(CertificateToken cert) {
        String dn = cert.getSubject().getRFC2253();
        Map<String, String> parts = new LinkedHashMap<>();
        for (String piece : dn.split(",(?=[A-Za-z0-9.]+=)")) {
            int eq = piece.indexOf('=');
            if (eq > 0) parts.putIfAbsent(piece.substring(0, eq).trim(), piece.substring(eq + 1).trim());
        }
        String cn = parts.getOrDefault("CN", dn);
        String c = parts.getOrDefault("C", "??");
        return c + " " + (cn.length() > 55 ? cn.substring(0, 55) : cn);
    }

    private static String tail(String uri) {
        return uri == null ? "" : uri.substring(uri.lastIndexOf('/') + 1);
    }
}
