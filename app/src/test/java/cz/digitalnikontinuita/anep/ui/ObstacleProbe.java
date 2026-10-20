package cz.digitalnikontinuita.anep.ui;

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

import java.io.File;
import java.util.Set;

/**
 * Projde dokument celou ověřovací cestou a vypíše, co o každém prvku řekne
 * {@link AdvisorView#continuityObstacle} — vedle seznamu zjištěných situací.
 *
 * <p>Sonda vznikla k situaci 41 (evidenční záznam nad podpisem, který nenese vlastní revokační
 * údaje). Ta se dá vyrobit v testu ze stavitele, ale jestli ji ověřovací cesta na skutečném
 * souboru opravdu nastaví a jestli se překážka napíše čitelně, ukáže až tohle.
 *
 * <p>Spouští se ručně. Dvojice „dokument + evidenční záznam" se předá jako {@code dokument=zaznam},
 * protože právě tak k záznamu přijde i uživatel — jako k druhému souboru, ne jako k něčemu
 * uvnitř dokumentu:
 * {@code java -cp "target/classes;target/test-classes;$(cat cp.txt)"
 * cz.digitalnikontinuita.anep.ui.ObstacleProbe <soubor>[=<záznam>] [další…]}
 */
public final class ObstacleProbe {

    private ObstacleProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("použití: ObstacleProbe <soubor> [další…]");
            return;
        }
        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        ReportMapper reportMapper = new ReportMapper();
        DocumentLoader documentLoader = new DocumentLoader();

        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Důvěryhodné seznamy: načteny");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Důvěryhodné seznamy: NEDOSTUPNÉ (" + e + ") — offline běh");
        }

        for (String path : args) {
            int split = path.indexOf('=');
            File file = new File(split < 0 ? path : path.substring(0, split));
            File record = split < 0 ? null : new File(path.substring(split + 1));
            System.out.println("=== " + file.getName()
                    + (record != null ? "  + " + record.getName() : ""));
            try {
                DSSDocument doc = documentLoader.load(file);
                java.util.List<DSSDocument> records = record == null
                        ? java.util.List.of()
                        : java.util.List.of(documentLoader.load(record));
                ValidationOutcome outcome =
                        validatorFactory.validate(doc, (DSSDocument) null, verifier, null, records);
                ValidationResult result =
                        reportMapper.map(file.getName(), null, outcome, settings);

                if (result.getSignatures().isEmpty()) {
                    System.out.println("  (žádný podpis)");
                }
                // Co záznam podle DSS kryje. Situace 37–41 se počítají jen pro záznam, který kryje
                // právě tenhle podpis (getCoveredSignatures), a u záznamu nad celým souborem to
                // nemusí platit — pak se nespočítá žádná z nich a nabídku stahuje jen přítomnost
                // záznamu. Tohle je ta otázka, kterou seznam situací sám nezodpoví.
                var dd = outcome.reports().getDiagnosticData();
                for (var er : dd.getEvidenceRecords()) {
                    System.out.println("  [záznam] " + er.getId()
                            + "  krytých podpisů=" + er.getCoveredSignatures().size()
                            + "  krytých objektů=" + er.getDigestMatchers().size()
                            + "  struktura=" + er.isStructuralValidationValid());
                }
                for (SignatureSummary sig : result.getSignatures()) {
                    System.out.println("  " + sig.getSignatureId() + "  " + sig.getFormat()
                            + "  " + sig.getIndication());
                    System.out.println("    situace  = " + sig.getDetectedSituations());
                    System.out.println("    záznamů  = " + sig.getEvidenceRecords().size());
                    String obstacle = AdvisorView.continuityObstacle(sig, Set.of());
                    System.out.println("    překážka = "
                            + (obstacle == null ? "(žádná — prvek je dosažitelný)" : obstacle));
                }
            } catch (Exception e) {
                System.out.println("  CHYBA: " + e);
            }
        }
    }
}
