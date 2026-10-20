package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.EvidenceRecordEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies a group evidence record against several documents at once: the first is the one being
 * validated, the rest ride along as detached contents so the record can account for them too.
 *
 * <p>The app deliberately does <em>not</em> offer this — one verification covers one document,
 * because a result mixing several files would leave the reader guessing which authentication
 * element belongs to which. Extra documents are checked separately in
 * {@code EvidenceRecordCoverageWindow}. This tool keeps the underlying
 * {@code ValidatorFactory.validate(doc, List, …)} path exercised and is the quickest way to see
 * the coverage counts move: run it with one document for the partial case and with all of them
 * for full coverage — "ověřeno 1, nepředloženo 2" becomes "ověřeno 3".
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 *
 * <pre>
 *   java -cp ... EvidenceRecordGroupSmoke &lt;evidence-record&gt; &lt;doc1&gt; [&lt;doc2&gt; ...]
 * </pre>
 */
public final class EvidenceRecordGroupSmoke {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: EvidenceRecordGroupSmoke <evidence-record> <doc1> [<doc2> ...]");
            return;
        }

        DocumentLoader loader = new DocumentLoader();
        DSSDocument er = loader.load(new File(args[0]));

        List<DSSDocument> docs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) docs.add(loader.load(new File(args[i])));

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trustConfig.buildOfflineCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);

        System.out.println("evidenční záznam : " + new File(args[0]).getName());
        System.out.println("dokumentů        : " + docs.size());
        for (int i = 1; i < args.length; i++) {
            System.out.println("    " + (i == 1 ? "[ověřovaný] " : "[jen ke kontrole] ")
                    + new File(args[i]).getName());
        }
        System.out.println();

        DSSDocument primary = docs.get(0);
        List<DSSDocument> rest = docs.subList(1, docs.size());
        ValidationOutcome outcome = factory.validate(primary, rest, verifier, null, List.of(er));
        ValidationResult result = new ReportMapper().map(
                new File(args[1]).getName(), null, outcome, settings);

        List<EvidenceRecordEntry> found = new ArrayList<>(result.getStandaloneEvidenceRecords());
        for (SignatureSummary sig : result.getSignatures()) {
            found.addAll(sig.getEvidenceRecords());
            // Printed so the decisive-instant rule can be checked against real data: the row in
            // Rozšířený přehled must show whichever of these two is earlier.
            System.out.println("podpis " + sig.getFormat()
                    + "  vlastní BST: " + sig.getBestSignatureTime()
                    + "  (platné razítko: " + sig.hasValidTimestamp() + ")"
                    + "  situace=" + sig.getDetectedSituations());
        }

        if (found.isEmpty()) {
            System.out.println("Žádný evidenční záznam se nenamapoval.");
            return;
        }
        for (EvidenceRecordEntry e : found) {
            System.out.println(e.type() + "  (" + e.origin() + ")");
            System.out.println("    kryje objektů   : " + e.digestMatchersTotal());
            System.out.println("    ověřeno         : " + e.digestMatchersIntact());
            System.out.println("    nepředloženo    : " + e.digestMatchersMissing());
            System.out.println("    nesouhlasí      : " + e.digestMatchersBroken());
            System.out.println("    poskytuje důkaz : " + e.providesProof()
                    + (e.partiallyChecked() ? "  (jen pro předložené)" : ""));
            System.out.println("    nejstarší důkaz : " + e.earliestProof());
            if (!e.coveredObjects().isEmpty()) {
                System.out.println("    kryté soubory   : " + String.join(", ", e.coveredObjects()));
            }
        }
    }
}
