package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.ExtendedView;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Kolik samostatných razítek ztratí v Rozšířeném přehledu poznámku „platné jen při ověřování
 * dle standardů“, když se certifikát jejich autority posoudí ke vzniku kryjícího razítka
 * ({@link ExtendedView#timestampCoverage}) místo k dnešku.
 *
 * <p>Vznikla 21. 11. 2026 nad nálezem uživatele u kontejneru ASiC-E se dvěma samostatnými
 * razítky. Prochází jen kontejnery ASiC; řádek na prvek-razítko, které obstálo:
 * {@code PRED}/{@code PO} říká, zda by poznámku mělo, {@code ZMENA} označí rozdíl.
 *
 * <pre>java -cp … CoveredTimestampScan &lt;složka&gt; [&lt;složka&gt; …]</pre>
 */
public final class CoveredTimestampScan {

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.load();
        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        var mapper = new ReportMapper();
        CommonCertificateVerifier verifier =
                new TrustConfig(settings).buildLotlCertificateVerifier();

        int files = 0, elements = 0, before = 0, after = 0, changed = 0, errors = 0;
        for (String root : args) {
            List<Path> list;
            try (Stream<Path> s = Files.walk(Path.of(root))) {
                list = s.filter(Files::isRegularFile).filter(CoveredTimestampScan::isAsic).toList();
            }
            for (Path p : list) {
                files++;
                File f = p.toFile();
                try {
                    var outcome = factory.validate(loader.load(f), null, verifier, null);
                    ValidationResult result = mapper.map(f.getName(), null, outcome, settings);
                    Map<String, Instant> cover = ExtendedView.timestampCoverage(
                            result.getSignatures(), result.getDiagnosticData());
                    Instant now = Instant.now();
                    for (SignatureSummary sig : result.getSignatures()) {
                        if (!"TimeStampToken".equals(sig.getFormat())) continue;
                        if (!"TOTAL_PASSED".equals(sig.getIndication())) continue;
                        Instant notAfter = sig.getCertNotAfter();
                        if (notAfter == null) continue;
                        elements++;
                        boolean b = notAfter.isBefore(now);
                        boolean a = notAfter.isBefore(cover.getOrDefault(sig.getSignatureId(), now));
                        if (b) before++;
                        if (a) after++;
                        if (a != b) changed++;
                        System.out.println((a != b ? "ZMENA " : "      ") + "PRED=" + b + " PO=" + a
                                + "  certDo=" + notAfter + "  kryto=" + cover.get(sig.getSignatureId())
                                + "  " + p);
                    }
                } catch (Throwable t) {
                    errors++;
                    System.out.println("CHYBA " + t.getClass().getSimpleName() + "  " + p);
                }
            }
        }
        System.out.println("SOUHRN souborů=" + files + " razítek=" + elements
                + " poznámka-před=" + before + " poznámka-po=" + after
                + " změn=" + changed + " chyb=" + errors);
        System.exit(0);
    }

    private static boolean isAsic(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".asice") || n.endsWith(".asics") || n.endsWith(".sce")
                || n.endsWith(".scs");
    }
}
