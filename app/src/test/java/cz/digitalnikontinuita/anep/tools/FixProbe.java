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
 *   java -cp ... cz.digitalnikontinuita.anep.tools.FixProbe -envelope &lt;obal&gt; &lt;prvek&gt;
 * </pre>
 *
 * <p>{@code -envelope} (1. 11. 2026) vyjme prvek z obalu jako tlačítko „Ověřit v AnEP",
 * ověří ho s důkazem z obalu a obal předá balíčku. Pozor: razítko se bere od zdroje vybraného
 * v nastavení, tedy skutečně.
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

        cz.digitalnikontinuita.anep.core.EnvelopeProof.Source envelope = null;
        if ("-envelope".equals(args[0])) {
            Path container = Path.of(args[1]);
            byte[] envelopeBytes = Files.readAllBytes(container);
            byte[] element = EnvelopeProofSmoke.extract(envelopeBytes,
                    container.getFileName().toString(), args[2]);
            Path tmp = Files.createTempDirectory("anep-prvek-")
                    .resolve(Path.of(args[2]).getFileName().toString());
            Files.write(tmp, element);
            envelope = new cz.digitalnikontinuita.anep.core.EnvelopeProof.Source(envelopeBytes,
                    container.getFileName().toString(), args[2], element);
            args = new String[] { tmp.toString() };
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
        if (envelope != null) {
            outcome = new ValidatorFactory(settings).withEnvelopeProof(outcome, envelope, verifier);
        }

        ValidationResult result = new ReportMapper().map(src.getFileName().toString(), null,
                outcome, settings, java.util.Map.of(), java.util.List.of(), secondary != null);
        FixationService svc = new FixationService(new AtomicReference<>(verifier));
        FixationService.FixationResult res = svc.fix(src.toFile(),
                secondary == null ? null : secondary.toFile(), result, profile,
                TimestampingService.resolveAlgorithm(profile.getHashAlgorithm()), envelope);

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
        System.out.println("  obalů " + res.envelopes());
        System.out.println("  obsah:");
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry e;
            String popis = null;
            while ((e = zis.getNextEntry()) != null) {
                System.out.printf("      %-64s %,10d B%n", e.getName(), Math.max(e.getSize(), 0));
                if ("POPIS.txt".equals(e.getName())) {
                    popis = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            if (envelope != null && popis != null) System.out.println(popis);
        }
    }
}
