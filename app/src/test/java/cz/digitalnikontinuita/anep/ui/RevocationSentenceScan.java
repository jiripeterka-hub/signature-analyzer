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
import java.util.List;

/**
 * Kterým prvkům odstavec „Revokační informace" v Analýze připojoval větu o <b>jiném</b>, odmítnutém
 * údaji — a kterým ji připojí i po zúžení na neúspěšná ověření.
 *
 * <p>Věta má vysvětlovat, proč se stav k rozhodnému okamžiku zjistit nepodařilo. Tam, kde podpis
 * obstál, nevysvětluje nic: říká o využitém údaji, že nestačil, a popíše místo něj jiný. Tahle
 * sonda spočítá obojí, aby se dalo říct, kolika dokumentů se to týká a jestli o ni nepřijdou i ty,
 * kde na místě je.
 *
 * <pre>java -cp … cz.digitalnikontinuita.anep.ui.RevocationSentenceScan &lt;složka|soubor&gt;…</pre>
 */
public final class RevocationSentenceScan {

    private static final List<String> EXT = List.of(
            ".pdf", ".asice", ".asics", ".sce", ".scs", ".p7s", ".p7m", ".xml", ".zfo");

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

        int sigs = 0, oldFires = 0, newFires = 0, lost = 0;
        for (File f : files) {
            try {
                CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
                DSSDocument doc = new DocumentLoader().load(f);
                ValidationResult result = new ReportMapper().map(f.getName(), null,
                        new ValidatorFactory(settings).validate(doc, null, verifier, null),
                        settings);
                for (SignatureSummary sig : result.getSignatures()) {
                    ValidationCourse vc = sig.getValidationCourse();
                    if (vc == null || vc.signingCertRevocation() == null) continue;
                    sigs++;
                    ValidationCourse.RevocationFact bad = vc.signingCertRevocationUnusableFact();
                    java.time.Instant certUntil = sig.getCertNotAfter();
                    // Táž podmínka jako AdvisorView.contemporaryWithValidity().
                    boolean contemporary = bad != null
                            && (certUntil == null || bad.issued() == null
                                || !bad.issued().isAfter(certUntil));
                    boolean passed = "TOTAL_PASSED".equals(AdvisorView.displayedIndication(sig));
                    if (contemporary) oldFires++;
                    if (contemporary && !passed) newFires++;
                    if (contemporary && passed) {
                        lost++;
                        System.out.println("ZTRACENA\t" + f.getName() + "\tverdikt=platný"
                                + "\tvyužit: " + (vc.primaryRevocation() != null
                                        ? vc.primaryRevocation().label() : "?")
                                + "\todmítnutý: " + (bad.ocsp() ? "OCSP" : "CRL")
                                + " z " + bad.issued());
                    } else if (contemporary) {
                        System.out.println("ZUSTAVA\t" + f.getName() + "\tverdikt="
                                + AdvisorView.displayedIndication(sig));
                    }
                }
            } catch (Throwable t) {
                System.out.println("ERR\t" + f.getName() + "\t" + t);
            }
        }
        System.out.println("# prvků s využitým údajem: " + sigs);
        System.out.println("# věta o odmítnutém údaji — dřív: " + oldFires
                + ", nově: " + newFires + ", zmizela u: " + lost);
    }
}
