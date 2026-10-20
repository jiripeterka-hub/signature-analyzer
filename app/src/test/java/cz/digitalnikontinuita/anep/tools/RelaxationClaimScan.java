package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Kolikrát AnEP tvrdí „bez nich by ověření dopadlo stejně“, ačkoli by nedopadlo.
 *
 * <p>Sonda projde každý soubor cestou, kterou jde {@code MainView}: ověří jej se všemi ústupky,
 * změří {@code RelaxationMeasurement.measure()} a výsledek předá {@code ReportMapper}. Pak týž
 * soubor ověří jednou přísně. Sporný je prvek, který je v aplikaci kladný, jeho výčet
 * rozhodujících ústupků je prázdný — blok „Průběh ověřování“ o něm tedy napíše, že se ústupky
 * neprojevily a bez nich by ověření dopadlo stejně — a přitom přísný běh ten prvek neuzná.
 *
 * <p>Přísný běh se dělá vždy, i když měření uvnitř neproběhlo; právě rozdíl mezi „naměřeno nic“
 * a „neměřeno“ je to, na co se sonda ptá.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.RelaxationClaimScan [-v] &lt;složka|soubor&gt;…</pre>
 */
public final class RelaxationClaimScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        boolean verbose = false;
        for (String a : args) {
            if ("-v".equals(a)) verbose = true;
            else roots.add(Path.of(a));
        }
        if (roots.isEmpty()) {
            System.err.println("usage: RelaxationClaimScan [-v] <folder|file> [...]");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setUseCustomValidationTime(false);
        DocumentLoader loader = new DocumentLoader();
        CommonCertificateVerifier verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        ReportMapper mapper = new ReportMapper();

        int files = 0, positives = 0, claims = 0, wrong = 0, failed = 0;
        // Co by na témže souboru vyšlo bez měření — tedy cesta, kterou šel ReportMapper do
        // 19. 9. 2026 všude kromě MainView, a kterou jde dodnes, když měření selhalo.
        int oldClaims = 0, oldWrong = 0;
        // Jak často nese verdikt ustupek NEWER_OCSP, aniž by v dokumentu byl post-expirační
        // revokační údaj — tedy kolikrát odrážka o něm popisuje jinou vadu, než jaká tam je.
        int newerOcspNoPostExpiry = 0;
        List<String> offenders = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (Path root : roots) {
            List<Path> candidates;
            if (Files.isRegularFile(root)) {
                candidates = List.of(root);
            } else {
                try (Stream<Path> walk = Files.walk(root)) {
                    candidates = walk.filter(Files::isRegularFile)
                            .filter(RelaxationClaimScan::isCandidate)
                            .sorted().toList();
                }
            }
            for (Path p : candidates) {
                files++;
                if (files % 100 == 0) {
                    System.out.println("... " + files + " souboru, "
                            + (System.currentTimeMillis() - t0) / 1000 + " s");
                }
                try {
                    DSSDocument doc = loader.load(p.toFile());

                    for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) {
                        r.setEnabled(settings, true);
                    }
                    ValidationOutcome lenient = new ValidatorFactory(settings)
                            .validate(doc, (DSSDocument) null, verifier, null, List.of());
                    if (acceptedIn(lenient.reports().getSimpleReport()).isEmpty()) continue;

                    Map<String, ReportMapper.RequiredRelaxations> measured =
                            cz.digitalnikontinuita.anep.core.RelaxationMeasurement.measure(
                                    settings, lenient, doc, null, verifier, null, List.of());
                    ValidationResult result = mapper.map(p.getFileName().toString(), null,
                            lenient, settings, Map.of(), List.of(), false, Map.of(), measured);

                    for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) {
                        r.setEnabled(settings, false);
                    }
                    Set<String> strict = acceptedIn(new ValidatorFactory(settings)
                            .validate(doc, (DSSDocument) null, verifier, null, List.of())
                            .reports().getSimpleReport());

                    // Odvození čte zapnuté tolerance z nastavení, takže se musí vrátit zpátky —
                    // jinak by „bez měření“ neměřilo odvození, nýbrž přísný běh.
                    for (ReportMapper.Relaxation r : ReportMapper.Relaxation.values()) {
                        r.setEnabled(settings, true);
                    }
                    ValidationResult old = mapper.map(p.getFileName().toString(), null,
                            lenient, settings, Map.of(), List.of(), false, Map.of(), null);
                    for (int i = 0; i < old.getSignatures().size(); i++) {
                        SignatureSummary o = old.getSignatures().get(i);
                        if (!"TOTAL_PASSED".equals(o.getIndication())) continue;
                        if (!o.getDecisiveSettings().isEmpty()) continue;
                        oldClaims++;
                        if (!strict.contains(o.getSignatureId())) oldWrong++;
                    }
                    for (SignatureSummary sig : result.getSignatures()) {
                        if (!"TOTAL_PASSED".equals(sig.getIndication())) continue;
                        positives++;
                        if (sig.getDecisiveSettings().contains(
                                ReportMapper.Relaxation.NEWER_OCSP.settingName())
                                && (sig.getValidationCourse() == null
                                    || !sig.getValidationCourse().postExpiryRevocationPresent())) {
                            newerOcspNoPostExpiry++;
                            System.out.println("ODRAZKA-MIMO " + p + "  [" + sig.getSignatureId()
                                    + "]");
                        }
                        if (!sig.getDecisiveSettings().isEmpty()) continue;
                        claims++;
                        if (!strict.contains(sig.getSignatureId())) {
                            wrong++;
                            offenders.add(p + "  [" + sig.getSignatureId() + "]"
                                    + "  format=" + sig.getFormat()
                                    + "  zmereno=" + sig.isDecisiveSettingsMeasured());
                            System.out.println("NEPRAVDA " + p + "  [" + sig.getSignatureId()
                                    + "]  format=" + sig.getFormat()
                                    + "  zmereno=" + sig.isDecisiveSettingsMeasured());
                        }
                    }
                    if (verbose) {
                        System.out.println(p + "  prisne=" + strict + "  nameřeno=" + measured);
                    }
                } catch (Exception | StackOverflowError e) {
                    failed++;
                    if (verbose) System.out.println(p + "  CHYBA " + e);
                }
            }
        }

        System.out.println("---");
        System.out.println("souboru=" + files
                + "  kladnych prvku=" + positives
                + "  z toho s prazdnym vyctem (tvrdi ,dopadlo by stejne')=" + claims
                + "  z toho nepravdivych=" + wrong
                + "  neoverenych souboru=" + failed
                + "  | bez mereni: tvrdi=" + oldClaims + " nepravdivych=" + oldWrong
                + "  | NEWER_OCSP bez post-expiracniho udaje=" + newerOcspNoPostExpiry
                + "  cas=" + (System.currentTimeMillis() - t0) / 1000 + " s");
        offenders.forEach(s -> System.out.println("  " + s));
    }

    private static Set<String> acceptedIn(SimpleReport report) {
        Set<String> accepted = new LinkedHashSet<>();
        for (String id : report.getSignatureIdList()) {
            if (report.getIndication(id) == Indication.TOTAL_PASSED) accepted.add(id);
        }
        for (String id : report.getTimestampIdList()) {
            if (report.getIndication(id) == Indication.PASSED) accepted.add(id);
        }
        return accepted;
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
