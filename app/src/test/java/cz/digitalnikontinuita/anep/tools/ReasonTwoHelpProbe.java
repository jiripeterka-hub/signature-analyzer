package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Path;

/**
 * Co uvidí čtenář u důvodů 2 a 6: text oken, která od nich vedou — „Podrobněji …“ u popisu
 * problému (co je špatně) a „Podrobněji …“ v radě pro zkušenější uživatele (kde vzít CRL
 * z vhodné doby).
 *
 * <p>Napsáno kvůli tomu druhému: podklad pro ně staví {@code buildCrlSourceHelp} z certifikátů,
 * které si poznamenaly {@code buildNewerRevocationDetail} a {@code buildExpiredResponderDetail},
 * a jestli v něm opravdu něco je, se nedá odvodit ze čtení kódu — jen změřit na dokumentu, který
 * některý z těch důvodů vyvolá.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.ReasonTwoHelpProbe "D:\path\file.pdf" ...
 * </pre>
 */
public final class ReasonTwoHelpProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ReasonTwoHelpProbe <file> [<file>...]");
            return;
        }

        TrustConfig trustConfig = new TrustConfig(AppSettings.load());
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Trusted Lists: loaded");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Trusted Lists: UNAVAILABLE (" + e + ")");
        }

        for (String arg : args) {
            Path p = Path.of(arg);
            System.out.println();
            System.out.println("############ " + p.getFileName());
            AppSettings settings = AppSettings.load();
            settings.setAcceptNewerOcsp(false);
            settings.setAcceptExpiredRevocationSources(false);
            try {
                DSSDocument doc = new DocumentLoader().load(p.toFile());
                ValidationOutcome outcome = new ValidatorFactory(settings)
                        .validate(doc, null, verifier, null);
                ValidationResult result = new ReportMapper()
                        .map(p.getFileName().toString(), null, outcome, settings);
                for (SignatureSummary sig : result.getSignatures()) {
                    System.out.println("  " + sig.getIndication()
                            + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                            + "   situace=" + sig.getDetectedSituations()
                            + "   důvody=" + sig.getDetectedReasons());
                    boolean two = sig.getDetectedReasons().contains(2);
                    boolean six = sig.getDetectedReasons().contains(6);
                    if (!two && !six) continue;
                    if (two) {
                        System.out.println("  ── okno u popisu problému (důvod 2) ──");
                        System.out.println(indent(sig.getNewerRevocationDetail()));
                    }
                    if (six) {
                        System.out.println("  ── okno u popisu problému (důvod 6) ──");
                        System.out.println(indent(sig.getExpiredResponderDetail()));
                    }
                    System.out.println("  ── okno u rady (kde vzít CRL) ──");
                    System.out.println(indent(sig.getCrlSourceHelp()));
                }
            } catch (Exception e) {
                System.out.println("  CHYBA: " + e);
            }
        }
    }

    private static String indent(String s) {
        if (s == null) return "    (null)";
        return "    " + s.replace("\n", "\n    ");
    }
}
