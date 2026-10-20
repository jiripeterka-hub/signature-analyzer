package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.EvidenceRecordWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.enumerations.TimestampType;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prints, for every document in a folder, exactly which timestamp lists the evidence-record
 * time-stamps end up in.
 *
 * <p>The evidence-record work assumed {@code EVIDENCE_RECORD_TIMESTAMP} entries leak into
 * {@code SignatureWrapper.getTimestampList()} and {@code DiagnosticData.getTimestampList()},
 * which would put roughly twenty-five call sites in ReportMapper at risk of silently changing
 * behaviour. Assumptions about where a library files its data are cheap to make and expensive
 * to be wrong about, so this measures it instead: three counts per signature, side by side.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 */
public final class EvidenceRecordTimestampProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: EvidenceRecordTimestampProbe <folder>");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trustConfig.buildOfflineCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();

        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(args[0]))) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }

        int withEr = 0;
        int erTsInSignatureList = 0;
        int erTsInDiagnosticList = 0;

        for (Path p : files) {
            DSSDocument doc;
            ValidationOutcome outcome;
            try {
                doc = loader.load(new File(p.toString()));
                outcome = factory.validate(doc, List.<DSSDocument>of(), verifier, null, List.of());
            } catch (Exception e) {
                continue;
            }
            DiagnosticData dd = outcome.reports().getDiagnosticData();
            List<EvidenceRecordWrapper> records = dd.getEvidenceRecords();
            if (records.isEmpty()) continue;
            withEr++;

            long ddEr = dd.getTimestampList().stream()
                    .filter(t -> TimestampType.EVIDENCE_RECORD_TIMESTAMP == t.getType()).count();
            long ddAll = dd.getTimestampList().size();
            long ddNonEr = dd.getNonEvidenceRecordTimestamps().size();
            if (ddEr > 0) erTsInDiagnosticList++;

            System.out.println(p.getFileName());
            System.out.println("    evidenčních záznamů : " + records.size()
                    + "   razítek v nich: " + records.stream()
                            .mapToInt(r -> r.getTimestampList().size()).sum());
            System.out.println("    dd.getTimestampList(): " + ddAll
                    + "  z toho EVIDENCE_RECORD_TIMESTAMP: " + ddEr
                    + "   getNonEvidenceRecordTimestamps(): " + ddNonEr);

            for (SignatureWrapper sw : dd.getSignatures()) {
                long swEr = sw.getTimestampList().stream()
                        .filter(t -> TimestampType.EVIDENCE_RECORD_TIMESTAMP == t.getType()).count();
                if (swEr > 0) erTsInSignatureList++;
                String types = sw.getTimestampList().stream()
                        .map(TimestampWrapper::getType).map(Enum::name)
                        .collect(Collectors.joining(", "));
                System.out.println("    podpis " + shortId(sw.getId())
                        + "  sw.getTimestampList(): " + sw.getTimestampList().size()
                        + "  z toho ER: " + swEr
                        + (types.isEmpty() ? "" : "   [" + types + "]"));
            }
            System.out.println();
        }

        System.out.println("------------------------------------------------------------");
        System.out.println("dokumentů s evidenčním záznamem                      : " + withEr);
        System.out.println("z nich má ER razítko v dd.getTimestampList()         : " + erTsInDiagnosticList);
        System.out.println("podpisů, které mají ER razítko v sw.getTimestampList(): " + erTsInSignatureList);
    }

    private static String shortId(String id) {
        return id != null && id.length() > 10 ? id.substring(0, 10) : String.valueOf(id);
    }
}
