package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;

/**
 * Jeden dokument, jeden režim, jeden proces — a vlastní verifier.
 *
 * <p>Napsáno proto, že {@code RevocationProbe} pouští čtyři režimy za sebou nad jedním
 * verifierem: co si první běh stáhne, má druhý v cache, takže se z něj nedá číst, co by se
 * stalo, kdyby uživatel ověřoval v tom režimu rovnou. Tahle sonda dělá právě jedno ověření;
 * porovnávat režimy znamená pustit ji dvakrát.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.SingleModeProbe &lt;soubor&gt; [newer] [expired] [sha1] [pdfnotetsi]
 * </pre>
 */
public final class SingleModeProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: SingleModeProbe <file> [newer] [expired] [sha1] [pdfnotetsi]");
            return;
        }
        java.io.File file = new java.io.File(args[0]);
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setIgnoreSha1Deprecated(false);
        settings.setAcceptPdfNotEtsi(false);
        StringBuilder mode = new StringBuilder("přísně");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "newer"       -> { settings.setAcceptNewerOcsp(true); mode.append(" +newer"); }
                case "expired"     -> { settings.setAcceptExpiredRevocationSources(true); mode.append(" +expired"); }
                case "sha1"        -> { settings.setIgnoreSha1Deprecated(true); mode.append(" +sha1"); }
                case "pdfnotetsi"  -> { settings.setAcceptPdfNotEtsi(true); mode.append(" +pdfnotetsi"); }
                default -> System.err.println("neznámý přepínač: " + args[i]);
            }
        }

        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var doc = new DocumentLoader().load(file);
        ValidationOutcome outcome = new ValidatorFactory(settings).validate(doc, null, verifier, null);
        ValidationResult result = new ReportMapper().map(file.getName(), null, outcome, settings);
        DiagnosticData dd = outcome.reports().getDiagnosticData();

        System.out.println();
        System.out.println("### " + file.getName() + "   [" + mode + "]");
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.println("  " + sig.getIndication()
                    + (sig.getSubIndication() != null ? "/" + sig.getSubIndication() : "")
                    + "   situace=" + sig.getDetectedSituations()
                    + "   důvody=" + sig.getDetectedReasons());
        }
        System.out.println("  přeskočené revokace: " + outcome.skippedRevocations().size());
        for (var sr : outcome.skippedRevocations()) {
            System.out.println("    přeskočeno: status=" + sr.status()
                    + " thisUpdate=" + sr.thisUpdate() + " revokace=" + sr.revocationDate());
        }
        for (SignatureWrapper sw : dd.getSignatures()) {
            for (CertificateWrapper c : sw.getCertificateChain()) {
                if (c.isTrusted()) break;
                System.out.println("  certifikát " + c.getReadableCertificateName()
                        + "   notAfter=" + c.getNotAfter());
                for (CertificateRevocationWrapper rev : c.getCertificateRevocationData()) {
                    System.out.println("    " + rev.getRevocationType()
                            + (rev.isInternalRevocationOrigin() ? " vložená" : " stažená")
                            + "  status=" + rev.getStatus()
                            + "  thisUpdate=" + rev.getThisUpdate()
                            + "  eco=" + rev.getExpiredCertsOnCRL());
                }
                if (c.getCertificateRevocationData().isEmpty()) {
                    System.out.println("    (žádná revokační data)");
                }
            }
        }
    }
}
