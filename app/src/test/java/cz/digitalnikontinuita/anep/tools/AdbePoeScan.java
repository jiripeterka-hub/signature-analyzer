package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.enumerations.RevocationOrigin;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Jednořádkový výpis přes sbírku: drží lhůtu vložený revokační údaj bez důkazu existence?
 *
 * <p>Sesterská sonda k {@link AdbePoeProbe}, jen přes seznam souborů a stručně. Pouští se dvakrát
 * — jednou s běžnými třídami, jednou s upravenou {@code ReportMapper} vloženou na začátek
 * classpath — a výstupy se porovnají. Řádek, který se mezi běhy liší, je dokument, u kterého
 * pravidlo o důkazu existence rozhoduje o tom, co uživatel čte.
 *
 * <p>Seznam je textový soubor, jedna cesta na řádek, bez BOM.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "app/target/test-classes;app/target/classes;$(cat cp.txt)" \
 *        cz.digitalnikontinuita.anep.tools.AdbePoeScan seznam.txt
 * </pre>
 */
public final class AdbePoeScan {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: AdbePoeScan <seznam souborů>");
            return;
        }
        List<String> cesty = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();

        AppSettings settings = AppSettings.load();
        TrustConfig trust = new TrustConfig(settings);
        java.time.Instant ted = java.time.Instant.now();

        for (String cesta : cesty) {
            File file = new File(cesta);
            String jmeno = file.getName();
            try {
                CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
                DSSDocument doc = new DocumentLoader().load(file);
                ValidatorFactory.ValidationOutcome outcome =
                        new ValidatorFactory(settings).validate(doc, null, verifier, null);
                DiagnosticData dd = outcome.reports().getDiagnosticData();
                ValidationResult result =
                        new ReportMapper().map(jmeno, null, outcome, settings);
                Set<String> poe = poeSet(dd);

                int i = 0;
                for (SignatureSummary sig : result.getSignatures()) {
                    i++;
                    var ur = sig.getUnprovenRevocationDeadline();
                    java.time.Instant kdy = ur != null ? ur.when() : null;
                    String budoucnost = kdy == null ? "—" : (kdy.isAfter(ted) ? "BUDOUCÍ" : "uplynulá");
                    boolean adbe = maAdbe(dd);
                    System.out.println(jmeno + " #" + i
                            + " | " + sig.getIndication()
                            + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                            + " | " + sig.getFormat()
                            + " | adbe=" + adbe
                            + " | poe=" + poe.size()
                            + " | unproven=" + kdy + " (" + budoucnost + ")"
                            + " | " + ContinuityHorizonProbe.describe(sig).split("\\s+\\|")[0]);
                }
            } catch (Exception e) {
                System.out.println(jmeno + " | CHYBA: " + e);
            }
        }
    }

    /** Nese aspoň jeden revokační údaj toho podpisu nositele adbe-revocationInfoArchival? */
    private static boolean maAdbe(DiagnosticData dd) {
        for (var sw : dd.getSignatures()) {
            if (!sw.foundRevocations()
                    .getRelatedRevocationsByOrigin(RevocationOrigin.ADBE_REVOCATION_INFO_ARCHIVAL)
                    .isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> poeSet(DiagnosticData dd) throws Exception {
        var m = ReportMapper.class.getDeclaredMethod("revocationsWithPoe", DiagnosticData.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(null, dd);
    }
}
