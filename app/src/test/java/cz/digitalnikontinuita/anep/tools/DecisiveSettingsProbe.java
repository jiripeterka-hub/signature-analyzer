package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;

/**
 * Validates a document under the strict settings and under the lenient ones, and prints beside each
 * verdict the relaxations the application would name as decisive.
 *
 * <p>A relaxation is only decisive if the signature fails without it. The application infers that
 * instead: it asks whether the setting is on and whether the document contains anything the setting
 * could bear on, which is not the same question and answers yes too often. Where the strict run
 * also passes, every name the lenient run prints is wrong.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class DecisiveSettingsProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: DecisiveSettingsProbe <file>…");
            return;
        }
        CommonCertificateVerifier verifier =
                new TrustConfig(AppSettings.load()).buildLotlCertificateVerifier();

        for (String arg : args) {
            Path file = Path.of(arg);
            System.out.println();
            System.out.println(file.getFileName());
            run("dle standardů", file, verifier, false);
            run("tolerantně", file, verifier, true);
        }
    }

    private static void run(String label, Path file, CommonCertificateVerifier verifier,
                            boolean lenient) throws Exception {
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(lenient);
        settings.setAcceptExpiredRevocationSources(lenient);
        settings.setIgnoreSha1Deprecated(lenient);
        settings.setAcceptPdfNotEtsi(lenient);

        DSSDocument doc = new DocumentLoader().load(file.toFile());
        ValidationResult result = new ReportMapper().map(file.getFileName().toString(), null,
                new ValidatorFactory(settings).validate(doc, null, verifier, null), settings);

        for (SignatureSummary sig : result.getSignatures()) {
            System.out.printf("   %-14s %-14s%s  označeno za rozhodující: %s%n",
                    label, sig.getIndication(),
                    sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "",
                    sig.getDecisiveSettings().isEmpty() ? "nic" : sig.getDecisiveSettings());
        }
    }
}
