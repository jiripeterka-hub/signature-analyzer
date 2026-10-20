package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Odkud vzít „SHA1“ k časovému razítku, které neobstálo.
 *
 * <p>Uživatel chce v závorce u řádku „platnost razítka se nepodařilo ověřit\" vidět vedle
 * rozhodující příčiny i zastaralý otisk, i když o verdiktu nerozhoduje. Sonda vypíše všechno,
 * co o algoritmech razítka víme, a vedle toho klíče hlášení DSS — ať je vidět, které pole tu
 * informaci opravdu nese a kdy se rozcházejí.
 *
 * <pre>java -cp … TimestampAlgoProbe &lt;složka nebo soubor&gt;…</pre>
 */
public final class TimestampAlgoProbe {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setAcceptPdfNotEtsi(false);

        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path root = Path.of(a);
            if (Files.isRegularFile(root)) {
                files.add(root);
            } else {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).filter(TimestampAlgoProbe::isCandidate)
                            .sorted().forEach(files::add);
                }
            }
        }

        for (Path p : files) {
            try {
                var doc = loader.load(p.toFile());
                var outcome = factory.validate(doc, null, verifier, null);
                SimpleReport sr = outcome.reports().getSimpleReport();
                var dd = outcome.reports().getDiagnosticData();
                boolean printedName = false;
                for (TimestampWrapper ts : dd.getTimestampList()) {
                    boolean ok = Indication.PASSED.equals(sr.getIndication(ts.getId()));
                    if (ok) continue;
                    if (!printedName) {
                        System.out.println("=== " + p.getFileName());
                        printedName = true;
                    }
                    List<String> keys = new ArrayList<>();
                    sr.getAdESValidationErrors(ts.getId()).forEach(m -> keys.add(m.getKey()));
                    sr.getAdESValidationWarnings(ts.getId()).forEach(m -> keys.add(m.getKey()));
                    System.out.println("  razítko " + ts.getProductionTime());
                    System.out.println("     dilci indikace : " + sr.getSubIndication(ts.getId()));
                    System.out.println("     podpis tokenu  : " + ts.getDigestAlgorithm()
                            + " / " + ts.getEncryptionAlgorithm()
                            + " / " + ts.getKeyLengthUsedToSignThisToken() + " b");
                    var imprint = ts.getMessageImprint();
                    System.out.println("     message imprint: "
                            + (imprint == null ? "?" : imprint.getDigestMethod()));
                    var cert = ts.getSigningCertificate();
                    System.out.println("     cert autority  : "
                            + (cert == null ? "?" : cert.getDigestAlgorithm()
                                    + " / " + cert.getEncryptionAlgorithm()
                                    + " / " + cert.getKeyLengthUsedToSignThisToken() + " b"));
                    System.out.println("     klice DSS      : " + keys);
                }
            } catch (Exception e) {
                System.out.println("  CHYBA " + p.getFileName() + ": " + e);
            }
        }
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
