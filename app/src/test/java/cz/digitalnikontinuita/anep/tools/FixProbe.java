package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.FixationService;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.model.DSSDocument;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fixes a document into a time-stamped ASiC container and lists what ended up inside.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.FixProbe &lt;file&gt;
 * </pre>
 */
public final class FixProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: FixProbe <file> [oddělený podpis / evidenční záznam]");
            return;
        }
        AppSettings settings = AppSettings.load();
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) {
            System.err.println("V nastavení není vybrána žádná TSA.");
            return;
        }

        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
        }

        Path src = Path.of(args[0]);
        Path secondary = args.length > 1 ? Path.of(args[1]) : null;
        DocumentLoader loader = new DocumentLoader();
        // Same convention as MainView: the second file is the detached signature, the first the
        // content it signs, so the roles are the other way round from the argument order.
        DSSDocument sigDoc = secondary == null
                ? loader.load(src.toFile()) : loader.load(secondary.toFile());
        DSSDocument content = secondary == null ? null : loader.load(src.toFile());
        ValidationOutcome outcome =
                new ValidatorFactory(settings).validate(sigDoc, content, verifier, null);

        ValidationResult result = new ReportMapper().map(src.getFileName().toString(), null,
                outcome, settings, java.util.Map.of(), java.util.List.of(), secondary != null);
        FixationService svc = new FixationService(new AtomicReference<>(verifier));
        FixationService.FixationResult res = svc.fix(src.toFile(),
                secondary == null ? null : secondary.toFile(), result, profile,
                TimestampingService.resolveAlgorithm(profile.getHashAlgorithm()));

        byte[] bytes;
        try (InputStream is = res.container().openStream()) { bytes = is.readAllBytes(); }
        Path out = Path.of(System.getProperty("anep.probe.out", "."))
                .resolve(src.getFileName().toString().replaceAll("\\.[^.]+$", "") + "-zafixovano.asice");
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);

        System.out.printf("vstup     %,12d B   %s%n", Files.size(src), src.getFileName());
        System.out.printf("kontejner %,12d B   %s%n", (long) bytes.length, out.getFileName());
        System.out.printf("  položek %d, revokací %d, certifikátů %d%n",
                res.documents(), res.revocations(), res.certificates());
        System.out.println("  obsah:");
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                System.out.printf("      %-64s %,10d B%n", e.getName(), Math.max(e.getSize(), 0));
            }
        }
    }
}
