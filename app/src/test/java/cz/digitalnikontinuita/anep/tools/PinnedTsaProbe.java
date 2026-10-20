package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ověřuje dokument podruhé s certifikáty razítkovacích autorit připnutými jako kotvy důvěry —
 * tedy přesně tím, co doposud radil důvod 21 („vložte certifikát TSA do vlastního úložiště").
 *
 * <p>Rada dává smysl jen tam, kde autoritu seznamy neznají. Tahle sonda měří, co udělá u razítka,
 * jehož autorita v seznamech je a jemuž skončila platnost certifikátu.
 */
public final class PinnedTsaProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: PinnedTsaProbe <soubor>");
            return;
        }
        Path file = Path.of(args[0]);

        Map<String, byte[]> tsaCerts = new LinkedHashMap<>();
        report(file, null, false, "bez připnutí", tsaCerts);
        System.out.println();
        System.out.println("certifikáty razítkovacích autorit k připnutí: " + tsaCerts.keySet());
        report(file, tsaCerts, false, "s připnutými certifikáty TSA", new LinkedHashMap<>());
        report(file, null, true, "jen s ústupkem k SHA-1", new LinkedHashMap<>());
        report(file, tsaCerts, true, "připnuté TSA + ústupek k SHA-1", new LinkedHashMap<>());
    }

    private static void report(Path file, Map<String, byte[]> pin, boolean sha1, String label,
                               Map<String, byte[]> collect) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setIgnoreSha1Deprecated(sha1);
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();
        if (pin != null && !pin.isEmpty()) {
            CommonTrustedCertificateSource pinned = new CommonTrustedCertificateSource();
            pin.values().forEach(der -> pinned.addCertificate(DSSUtils.loadCertificate(der)));
            var sources = new java.util.ArrayList<>(verifier.getTrustedCertSources().getSources());
            sources.add(pinned);
            verifier.setTrustedCertSources(
                    sources.toArray(new eu.europa.esig.dss.spi.x509.CertificateSource[0]));
        }

        DSSDocument doc = new DocumentLoader().load(file.toFile());
        var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        SimpleReport report = outcome.reports().getSimpleReport();
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        System.out.println();
        System.out.println("===== " + label);
        for (String sigId : report.getSignatureIdList()) {
            System.out.printf("  podpis %s : %s / %s%n", sigId.substring(0, 10),
                    report.getIndication(sigId), report.getSubIndication(sigId));
            var sw = dd.getSignatureById(sigId);
            if (sw == null) continue;
            for (TimestampWrapper ts : sw.getTimestampList()) {
                System.out.printf("    razítko %s : %s / %s%n", ts.getId().substring(0, 10),
                        report.getIndication(ts.getId()), report.getSubIndication(ts.getId()));
                CertificateWrapper c = ts.getSigningCertificate();
                if (c != null && c.getBinaries() != null) {
                    collect.put(c.getReadableCertificateName(), c.getBinaries());
                }
            }
        }
    }
}
