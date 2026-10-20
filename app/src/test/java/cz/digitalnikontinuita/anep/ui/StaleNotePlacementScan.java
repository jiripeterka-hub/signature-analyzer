package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationCourse;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Má poznámka situace 51 kam splynout? Sonda k úvaze „vepsat ji do odstavce Revokační informace“
 * ve výkladu průběhu.
 *
 * <p>Ten odstavec totiž nestojí vždycky: {@link AdvisorView#revocationItem} má větev
 * {@code OMITTED} pro prvky se situací 3 nebo 39 — tedy právě pro expirovaný certifikát bez
 * razítka, což je případ, kterým celá otázka začala. Sonda proto u každého prvku se situací 51
 * spočítá, do které větve spadne a jaký má verdikt.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.StaleNotePlacementScan &lt;složka|soubor&gt;…</pre>
 */
public final class StaleNotePlacementScan {

    private static final List<String> EXT = List.of(
            ".pdf", ".asice", ".asics", ".sce", ".scs", ".p7s", ".p7m", ".xml", ".zfo");

    private StaleNotePlacementScan() { }

    public static void main(String[] args) throws Exception {
        List<File> files = new ArrayList<>();
        for (String a : args) {
            File f = new File(a);
            if (!f.isDirectory()) { files.add(f); continue; }
            try (var s = Files.walk(f.toPath(), 5)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> EXT.stream().anyMatch(e -> p.toString().toLowerCase().endsWith(e)))
                 .sorted().forEach(p -> files.add(p.toFile()));
            }
        }
        System.out.println("# souborů: " + files.size());

        AppSettings settings = AppSettings.load();
        TrustConfig trust = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trust.buildLotlCertificateVerifier();
            System.out.println("# důvěryhodné seznamy: načteny");
        } catch (Exception e) {
            verifier = trust.buildOfflineCertificateVerifier();
            System.out.println("# důvěryhodné seznamy: NEDOSTUPNÉ");
        }

        Map<AdvisorView.RevocationItem, Integer> tally = new EnumMap<>(AdvisorView.RevocationItem.class);
        int sigs = 0, with51 = 0, done = 0;
        for (File f : files) {
            try {
                DSSDocument doc = new DocumentLoader().load(f);
                ValidationResult result = new ReportMapper().map(f.getName(), null,
                        new ValidatorFactory(settings).validate(doc, null, verifier, null),
                        settings);
                for (SignatureSummary sig : result.getSignatures()) {
                    sigs++;
                    if (!sig.getDetectedSituations().contains(51)) continue;
                    with51++;
                    ValidationCourse vc = sig.getValidationCourse();
                    boolean passed = "TOTAL_PASSED".equals(sig.getIndication());
                    boolean failed = "TOTAL_FAILED".equals(sig.getIndication());
                    AdvisorView.RevocationItem item =
                            AdvisorView.revocationItem(sig, vc, passed, failed);
                    tally.merge(item, 1, Integer::sum);
                    System.out.println(sig.getIndication() + "\t" + item
                            + "\tsituace=" + sig.getDetectedSituations()
                            + "\tdůvody=" + sig.getDetectedReasons()
                            + "\t" + f.getName());
                    if (System.getProperty("anep.notetext") != null) {
                        System.out.println("    poznamka: " + sig.getStaleRevocationDetail());
                    }
                }
            } catch (Exception | StackOverflowError e) {
                System.out.println("SKIP\t" + f.getName() + "\t" + e);
            }
            if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
        }
        System.out.println("### podpisů=" + sigs + ", se situací 51=" + with51 + ", větve=" + tally);
    }
}
