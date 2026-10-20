package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.util.List;

/**
 * Runs the real validation pipeline over the given files and prints the headline sentence the
 * summary tab would show, which is the one place a reader is told what kind of thing they opened.
 *
 * <p>Exists because that sentence is assembled from two sources that can disagree: DSS, which
 * sees a data message as an ordinary CAdES signature, and the ISDS flag, which is set from the
 * bytes. Feeding it a ZFO alongside a plain P7M is the only way to confirm each still gets the
 * wording meant for it.
 *
 * <p>Runs offline — the verdicts are irrelevant here, only the wording is. Use a plain
 * {@code java -cp}; no JavaFX toolkit is needed, nothing is rendered.
 */
public final class IsdsHeadlineProbe {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: IsdsHeadlineProbe <soubor>…");
            return;
        }
        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trustConfig.buildOfflineCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        ReportMapper mapper = new ReportMapper();
        DocumentLoader loader = new DocumentLoader();

        for (String path : args) {
            File file = new File(path);
            try {
                DSSDocument doc = loader.load(file);
                ValidationOutcome outcome =
                        factory.validate(doc, List.<DSSDocument>of(), verifier, null, List.of());
                ValidationResult result = mapper.map(file.getName(), null, outcome, settings);
                result.setIsdsArtefact(ContainerInspector.isdsArtefact(file));

                System.out.println(file.getName());
                System.out.println("   " + ResultView.authCountLabel(result));
                for (SignatureSummary sig : result.getSignatures()) {
                    System.out.println("      " + sig.getFormat() + "  " + sig.getIndication()
                            + (sig.getSubIndication() != null ? " / " + sig.getSubIndication() : ""));
                }
            } catch (Exception e) {
                System.out.println(file.getName() + "   CHYBA: " + e);
            }
        }
    }
}
