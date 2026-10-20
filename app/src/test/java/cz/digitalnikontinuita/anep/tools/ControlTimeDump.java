package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints the control-time that DSS arrives at, from its own detailed report.
 *
 * <p>ETSI EN 319 102-1 does not treat revocation freshness as a gate the data either passes or
 * fails. Stale revocation information instead moves the control-time back towards its own
 * issuance, and the question becomes whether a proof of existence for the signature predates that
 * moment. The ETSI validation report shows the proofs of existence but never prints the
 * control-time; DSS's detailed report does, in its past-validation and control-time-sliding
 * blocks. This reads it out so the mechanism can be observed rather than assumed.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.ControlTimeDump &lt;file&gt; [-tags]
 * </pre>
 */
public final class ControlTimeDump {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ControlTimeDump <file> [-tags]");
            return;
        }
        boolean listTags = args.length > 1 && "-tags".equals(args[1]);

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
        }
        DSSDocument doc = new DocumentLoader().load(Path.of(args[0]).toFile());
        ValidationOutcome outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        String xml = outcome.reports().getXmlDetailedReport();

        if (listTags) {
            TreeSet<String> tags = new TreeSet<>();
            Matcher m = Pattern.compile("<([A-Za-z][A-Za-z0-9:._-]*)").matcher(xml);
            while (m.find()) tags.add(m.group(1));
            System.out.println("prvky v protokolu: " + tags);
            return;
        }

        System.out.println("=== řádky nesoucí control-time nebo minulé ověření ===");
        int printed = 0;
        for (String line : xml.split("\n")) {
            String t = line.trim();
            String lower = t.toLowerCase();
            if (lower.contains("controltime") || lower.contains("pastsignature")
                    || lower.contains("pastcertificate") || lower.contains("<psv")
                    || lower.contains("<pcv") || lower.contains("<cts")
                    || lower.contains("bestsignaturetime") || lower.contains("proofofexistence")) {
                System.out.println("  " + t);
                printed++;
            }
        }
        if (printed == 0) System.out.println("  (nic nenalezeno — spusť s -tags a podívej se na názvy prvků)");
    }
}
