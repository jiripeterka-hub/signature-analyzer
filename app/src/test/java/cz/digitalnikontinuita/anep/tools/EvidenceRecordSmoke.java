package cz.digitalnikontinuita.anep.tools;

import eu.europa.esig.dss.enumerations.EvidenceRecordTypeEnum;
import eu.europa.esig.dss.evidencerecord.common.validation.DefaultEvidenceRecordValidator;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.ReferenceValidation;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.validation.analyzer.evidencerecord.EvidenceRecordAnalyzer;
import eu.europa.esig.dss.spi.validation.analyzer.evidencerecord.EvidenceRecordAnalyzerFactory;
import eu.europa.esig.dss.spi.x509.evidencerecord.EvidenceRecord;
import eu.europa.esig.dss.spi.x509.tsp.TimestampToken;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Headless probe over a folder of evidence records: for every file it asks DSS whether the
 * bytes are an evidence record at all, and for those that are, prints what DSS managed to
 * parse out — format, hash-tree shape, embedded time-stamps, covered data objects.
 *
 * <p>Exists to answer the first question of the evidence-record work: do the two DSS modules
 * actually load via {@code ServiceLoader}, and do they cope with real-world records rather
 * than only with DSS's own test vectors. It deliberately does no trust processing — a record
 * whose time-stamps chain to an untrusted TSA still parses, and that is what is being checked
 * here. Verdicts come later, from the normal validation pipeline.
 *
 * <p>Run it with a plain {@code java -cp}, not through {@code exec:java}: the exec plugin's
 * isolated classloader makes {@code XPathFactory.newInstance()} fail, which kills DSS's
 * {@code DomUtils} before any evidence record is read. From {@code app/}:
 *
 * <pre>
 *   mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
 *   java -cp "$(cat cp.txt);target/classes;target/test-classes" \
 *        cz.digitalnikontinuita.anep.tools.EvidenceRecordSmoke "D:\path\to\corpus"
 * </pre>
 */
public final class EvidenceRecordSmoke {

    private static final SimpleDateFormat TS_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: EvidenceRecordSmoke <folder> [<folder>...]");
            return;
        }

        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            Path root = Path.of(arg);
            if (!Files.isDirectory(root)) {
                System.err.println("not a folder, skipped: " + root);
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        files.sort(null);

        int recognised = 0;
        int parsed = 0;
        List<String> failures = new ArrayList<>();

        for (Path file : files) {
            DSSDocument doc = new FileDocument(new File(file.toString()));

            boolean isEr;
            try {
                isEr = EvidenceRecordAnalyzerFactory.isSupportedDocument(doc);
            } catch (Exception e) {
                // A malformed or unrelated file is expected to land here; it is not a failure
                // of the smoke test, only a "no" answer to "is this an evidence record".
                isEr = false;
            }
            if (!isEr) continue;

            recognised++;
            String label = file.getFileName().toString();
            try {
                EvidenceRecordAnalyzer analyzer = EvidenceRecordAnalyzerFactory.fromDocument(doc);
                analyzer.setCertificateVerifier(new CommonCertificateVerifier());
                EvidenceRecord er = analyzer.getEvidenceRecord();

                System.out.println(label);
                System.out.println("    formát       : " + describe(er.getEvidenceRecordType())
                        + "   otisk 1. skupiny: " + er.getOriginalDigestAlgorithm());

                List<TimestampToken> timestamps = er.getTimestamps();
                System.out.println("    razítek      : " + timestamps.size());
                for (TimestampToken ts : timestamps) {
                    System.out.println("        " + TS_FMT.format(ts.getGenerationTime())
                            + "  " + ts.getTimeStampType()
                            + "  " + issuerOf(ts));
                }

                // Without detached content every reference is reported as unmatched; the count
                // of references is still the useful signal here (how wide the record reaches).
                List<ReferenceValidation> refs = er.getReferenceValidation();
                System.out.println("    krytých obj. : " + refs.size());

                List<String> structure = er.getStructureValidationResult();
                if (!structure.isEmpty()) {
                    System.out.println("    struktura    : " + String.join("; ", structure));
                }
                System.out.println();
                parsed++;

            } catch (Exception e) {
                System.out.println(label);
                System.out.println("    !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.out.println();
                failures.add(label + " — " + e.getClass().getSimpleName());
            }
        }

        System.out.println("------------------------------------------------------------");
        System.out.println("souborů celkem      : " + files.size());
        System.out.println("rozpoznáno jako ER  : " + recognised);
        System.out.println("z toho zpracováno   : " + parsed);
        if (!failures.isEmpty()) {
            System.out.println("selhalo:");
            failures.forEach(f -> System.out.println("    " + f));
        }
    }

    private static String describe(EvidenceRecordTypeEnum type) {
        if (type == null) return "neznámý";
        return switch (type) {
            case XML_EVIDENCE_RECORD -> "XMLERS (RFC 6283)";
            case ASN1_EVIDENCE_RECORD -> "ERS (RFC 4998)";
        };
    }

    private static String issuerOf(TimestampToken ts) {
        if (ts.getCandidatesForSigningCertificate() == null) return "";
        var signer = ts.getCandidatesForSigningCertificate().getTheBestCandidate();
        if (signer == null || signer.getCertificateToken() == null) return "";
        return signer.getCertificateToken().getSubject().getPrettyPrintRFC2253();
    }
}
