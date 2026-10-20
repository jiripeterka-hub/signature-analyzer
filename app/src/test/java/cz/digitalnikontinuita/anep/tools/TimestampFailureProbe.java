package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;

/**
 * Vypíše u každého razítka indikaci a ty kontroly DSS, které o ní rozhodly — a totéž po zapnutí
 * ústupků, aby bylo vidět, co z hlášených chyb je příčina a co jen průvodní jev.
 *
 * <p>Bez tohohle rozlišení se hlásí jako důvod to, co je v protokolu nahoře: u razítek z roku 2012
 * to bývá SHA-1, kdežto verdikt drží něco jiného.
 *
 * <p>Každý běh dostává vlastní {@code CertificateVerifier} — DSS si do něj zapisuje nalezené
 * certifikáty a druhý běh by byl tišeji schopnější než první.
 */
public final class TimestampFailureProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: TimestampFailureProbe <soubor>");
            return;
        }
        Path file = Path.of(args[0]);

        run(file, "přísně (výchozí)", false, false);
        run(file, "s ústupkem pro SHA-1", true, false);
        run(file, "s ústupkem pro SHA-1 i pro expirované zdroje revokací", true, true);
    }

    private static void run(Path file, String label, boolean sha1, boolean expiredRev)
            throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setIgnoreSha1Deprecated(sha1);
        settings.setAcceptExpiredRevocationSources(expiredRev);

        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        DSSDocument doc = new DocumentLoader().load(file.toFile());
        var outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        SimpleReport report = outcome.reports().getSimpleReport();

        System.out.println();
        System.out.println("===== " + label);
        for (String sigId : report.getSignatureIdList()) {
            System.out.printf("  podpis %s : %s / %s%n", sigId.substring(0, 10),
                    report.getIndication(sigId), report.getSubIndication(sigId));
            for (var ts : report.getSignatureTimestamps(sigId)) {
                System.out.printf("    razítko %s : %s / %s%n", ts.getId().substring(0, 10),
                        ts.getIndication(), ts.getSubIndication());
                ts.getAdESValidationDetails().getError()
                        .forEach(e -> System.out.println("        chyba: " + e.getKey()));
            }
        }

        var result = new cz.digitalnikontinuita.anep.core.ReportMapper().map(
                file.getFileName().toString(), "x", outcome, settings,
                java.util.Map.of(), java.util.List.of(), false, java.util.Map.of());
        for (var sig : result.getSignatures()) {
            System.out.printf("  → %s : situace %s, důvody %s%n",
                    sig.getSignerDisplayName(), sig.getDetectedSituations(),
                    sig.getDetectedReasons());
            sig.getAllTimestamps().forEach(t -> System.out.printf(
                    "      razítko %s (%s) passed=%s příčina=%s, notAfter=%s%n",
                    t.time(), t.tsKind(), t.passed(), t.failureCause(), t.notAfter()));
        }
    }
}
