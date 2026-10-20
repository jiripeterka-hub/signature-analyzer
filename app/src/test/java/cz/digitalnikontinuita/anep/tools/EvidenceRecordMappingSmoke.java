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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the full pipeline over a folder and prints the {@link EvidenceRecordEntry} objects the
 * report mapper produced — both those attached to a signature and those standing alone.
 *
 * <p>Checks the half of the mapping that no verdict exercises: whether covered object names,
 * digest-matcher counts, renewal kinds and the earliest-proof time survive the trip out of
 * DiagnosticData intact, and whether every record lands in exactly one place rather than
 * being listed twice or dropped.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class EvidenceRecordMappingSmoke {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: EvidenceRecordMappingSmoke <folder>");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trustConfig.buildOfflineCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        ReportMapper mapper = new ReportMapper();
        DocumentLoader loader = new DocumentLoader();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(args[0]))) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }

        int docsWithRecords = 0, attached = 0, standalone = 0, proving = 0, schemaIssues = 0;

        for (Path p : files) {
            ValidationResult result;
            ValidationOutcome outcome;
            try {
                DSSDocument doc = loader.load(new File(p.toString()));
                outcome = factory.validate(doc, List.<DSSDocument>of(), verifier, null, List.of());
                result = mapper.map(p.getFileName().toString(), null, outcome, settings);
            } catch (Exception e) {
                continue;
            }

            // DSS's own warnings on the evidence-record token, printed verbatim: situation S38 is
            // read straight off them, so this is where a false positive would have to be caught.
            try {
                var sr = outcome.reports().getSimpleReport();
                for (var er : outcome.reports().getDiagnosticData().getEvidenceRecords()) {
                    for (var m : sr.getAdESValidationWarnings(er.getId())) {
                        System.out.println("  [DSS warn] " + p.getFileName()
                                + "  " + m.getKey() + " — " + m.getValue());
                    }
                }
            } catch (Exception ignored) { }

            List<EvidenceRecordEntry> loose = result.getStandaloneEvidenceRecords();
            boolean any = !loose.isEmpty()
                    || result.getSignatures().stream().anyMatch(s -> !s.getEvidenceRecords().isEmpty());
            if (!any) continue;
            docsWithRecords++;

            System.out.println(p.getFileName());
            for (SignatureSummary sig : result.getSignatures()) {
                for (EvidenceRecordEntry er : sig.getEvidenceRecords()) {
                    attached++;
                    print("  u podpisu " + shortId(sig.getSignatureId()), er);
                    if (er.providesProof()) proving++;
                    if (!er.structureValid()) schemaIssues++;
                }
            }
            for (EvidenceRecordEntry er : loose) {
                standalone++;
                print("  samostatný", er);
                if (er.providesProof()) proving++;
                if (!er.structureValid()) schemaIssues++;
            }
            System.out.println();
        }

        System.out.println("------------------------------------------------------------");
        System.out.println("dokumentů se záznamem     : " + docsWithRecords);
        System.out.println("záznamů u podpisu         : " + attached);
        System.out.println("záznamů samostatných      : " + standalone);
        System.out.println("z toho poskytuje důkaz    : " + proving);
        System.out.println("se schématickou výhradou  : " + schemaIssues);
    }

    private static void print(String where, EvidenceRecordEntry er) {
        System.out.println(where + "  " + er.type()
                + (er.origin() != null ? "  (" + er.origin() + ")" : "")
                + (er.filename() != null ? "  soubor=" + er.filename() : ""));
        System.out.println("      verdikt      : " + er.indication()
                + (er.subIndication() != null ? " / " + er.subIndication() : ""));
        System.out.println("      otisky       : " + er.digestMatchersIntact() + "/"
                + er.digestMatchersTotal()
                + "   poskytuje důkaz: " + er.providesProof()
                + "   nejstarší důkaz: " + er.earliestProof());
        if (!er.coveredObjects().isEmpty()) {
            System.out.println("      kryje        : " + String.join(", ", er.coveredObjects()));
        }
        for (var ts : er.timestamps()) {
            System.out.println("      razítko " + ts.time() + "  druh=" + ts.tsKind()
                    + "  platné=" + ts.passed()
                    + (ts.issuer() != null ? "  " + ts.issuer() : ""));
        }
        if (!er.structureValid()) {
            System.out.println("      schéma       : " + String.join("; ", er.structureMessages()));
        }
    }

    private static String shortId(String id) {
        return id != null && id.length() > 10 ? id.substring(0, 10) : String.valueOf(id);
    }
}
