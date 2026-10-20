package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.EvidenceRecordDetector;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.EvidenceRecordWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.reports.Reports;

import java.io.File;
import java.util.List;

/**
 * Runs AnEP's real validation pipeline over a document once without and once with a detached
 * evidence record, and prints both verdicts side by side.
 *
 * <p>The comparison is the point. An evidence record earns its keep by supplying a proof of
 * existence, so the question worth answering is not "does DSS parse it" — {@link
 * EvidenceRecordSmoke} already answers that — but "does handing it to the validator change the
 * verdict, and does the record show up in the diagnostic data where the report mapper will look
 * for it". A run where both columns read the same means the wiring did nothing.
 *
 * <p>Note that this runs against {@code buildOfflineCertificateVerifier()}, so no Trusted List
 * is loaded and the signature's own chain will usually come back INDETERMINATE regardless of
 * the evidence record. Read the two columns for what differs between them, not for the verdict.
 *
 * <p>Run with a plain {@code java -cp} rather than {@code exec:java} — see {@link
 * EvidenceRecordSmoke} for why, and for the command that builds the classpath file.
 *
 * <pre>
 *   java -cp "$(cat cp.txt);target/classes;target/test-classes" \
 *        cz.digitalnikontinuita.anep.tools.EvidenceRecordPairSmoke &lt;document&gt; &lt;evidence-record&gt;
 * </pre>
 */
public final class EvidenceRecordPairSmoke {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: EvidenceRecordPairSmoke <document> <evidence-record>");
            return;
        }
        File docFile = new File(args[0]);
        File erFile = new File(args[1]);

        DocumentLoader loader = new DocumentLoader();
        DSSDocument doc = loader.load(docFile);
        DSSDocument er = loader.load(erFile);

        System.out.println("dokument        : " + docFile.getName());
        System.out.println("evidenční zázn. : " + erFile.getName());
        System.out.println("rozpoznán jako  : "
                + EvidenceRecordDetector.describe(EvidenceRecordDetector.typeOf(er)));
        System.out.println();

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trustConfig.buildOfflineCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);

        System.out.println("=== bez evidenčního záznamu ===");
        report(factory.validate(doc, List.<DSSDocument>of(), verifier, null, List.of()));

        System.out.println();
        System.out.println("=== s evidenčním záznamem ===");
        report(factory.validate(doc, List.<DSSDocument>of(), verifier, null, List.of(er)));
    }

    private static void report(ValidationOutcome outcome) {
        Reports reports = outcome.reports();
        SimpleReport sr = reports.getSimpleReport();
        DiagnosticData dd = reports.getDiagnosticData();

        for (String id : sr.getSignatureIdList()) {
            System.out.println("  podpis " + id);
            System.out.println("      indikace   : " + sr.getIndication(id)
                    + (sr.getSubIndication(id) != null ? " / " + sr.getSubIndication(id) : ""));
            System.out.println("      kvalifikace: " + sr.getSignatureQualification(id));
        }

        List<EvidenceRecordWrapper> records = dd.getEvidenceRecords();
        System.out.println("  evidenčních záznamů v DiagnosticData: " + records.size());
        for (EvidenceRecordWrapper er : records) {
            System.out.println("      " + er.getEvidenceRecordType()
                    + "  původ=" + er.getOrigin()
                    + "  vnořený=" + er.isEmbedded()
                    + "  struktura ok=" + er.isStructuralValidationValid());
            long intact = er.getDigestMatchers().stream()
                    .filter(m -> m.isDataFound() && m.isDataIntact()).count();
            System.out.println("      otisků " + intact + "/" + er.getDigestMatchers().size()
                    + " souhlasí, krytých podpisů " + er.getCoveredSignatures().size());
            for (var m : er.getDigestMatchers()) {
                System.out.println("        matcher typ=" + m.getType()
                        + "  found=" + m.isDataFound() + "  intact=" + m.isDataIntact()
                        + (m.getDocumentName() != null ? "  jméno=" + m.getDocumentName() : ""));
            }
            for (TimestampWrapper ts : er.getTimestampList()) {
                System.out.println("      razítko " + ts.getProductionTime()
                        + "  typ=" + ts.getType()
                        + "  platné=" + ts.isSignatureValid());
            }
        }
    }
}
