package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.SignatureExtensionService;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Preserves a non-PDF signature — container, detached CAdES/XAdES/JAdES — and validates the result,
 * so the claim "uchování povýší podpis na LTA" is measured rather than assumed.
 *
 * <p>Prints the level and indication before and after. A file whose "after" is not LTA is a
 * failure worth seeing even when {@code extendDocument()} itself did not throw.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.ExtendProbe &lt;soubor s podpisem&gt; [podepsaný dokument]
 * </pre>
 */
public final class ExtendProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ExtendProbe <soubor s podpisem> [podepsaný dokument]");
            return;
        }
        File signatureFile = Path.of(args[0]).toFile();
        File detached = args.length > 1 ? Path.of(args[1]).toFile() : null;

        AppSettings settings = AppSettings.load();
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) {
            System.err.println("V nastavení není vybrána žádná TSA — uchování nelze provést.");
            return;
        }

        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
        }

        ValidationResult before = validate(signatureFile, detached, settings, verifier);
        System.out.printf("vstup    %,12d B   %s%n", signatureFile.length(), signatureFile.getName());
        if (detached != null) {
            System.out.printf("obsah    %,12d B   %s%n", detached.length(), detached.getName());
        }
        report("  před  ", before);
        System.out.println("  handles(): " + SignatureExtensionService.handles(before, signatureFile));

        SignatureExtensionService svc = new SignatureExtensionService(new AtomicReference<>(verifier));
        SignatureExtensionService.ExtensionResult res = svc.preserve(
                signatureFile, detached, before, profile,
                TimestampingService.resolveAlgorithm(profile.getHashAlgorithm()));

        byte[] bytes;
        try (InputStream is = res.document().openStream()) { bytes = is.readAllBytes(); }
        String name = signatureFile.getName();
        int dot = name.lastIndexOf('.');
        Path out = Path.of(System.getProperty("anep.probe.out", "."))
                .resolve((dot > 0 ? name.substring(0, dot) : name) + "_LTA"
                        + (dot > 0 ? name.substring(dot) : ""));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, bytes);
        System.out.printf("výstup   %,12d B   %s   (%+,d B)%n",
                (long) bytes.length, out.getFileName(), bytes.length - signatureFile.length());
        System.out.println("  razítko do: " + (res.tsaCertNotAfter() == null
                ? "nezjištěno" : res.tsaCertNotAfter()));

        report("  po    ", validate(out.toFile(), detached, settings, verifier));
    }

    private static ValidationResult validate(File signature, File detached, AppSettings settings,
                                             CommonCertificateVerifier verifier) {
        DocumentLoader loader = new DocumentLoader();
        DSSDocument sigDoc = loader.load(signature);
        DSSDocument content = detached == null ? null : loader.load(detached);
        ValidationOutcome outcome =
                new ValidatorFactory(settings).validate(sigDoc, content, verifier, null);
        return new ReportMapper().map(signature.getName(), null, outcome, settings);
    }

    private static void report(String label, ValidationResult result) {
        if (result.getSignatures().isEmpty()) {
            System.out.println(label + "žádný podpis");
        }
        for (SignatureSummary sig : result.getSignatures()) {
            System.out.printf("%s%-22s %-14s %-24s situace %s%n", label, sig.getFormat(),
                    sig.getIndication(), sig.getSubIndication() == null ? "" : sig.getSubIndication(),
                    sig.getDetectedSituations());
            if (sig.getS33Detail() != null) {
                System.out.println(label + "  S33 dostupné: " + sig.getS33Detail());
            }
            if (sig.getS33MissingDetail() != null) {
                System.out.println(label + "  S33 chybí:    " + sig.getS33MissingDetail());
            }
        }
        // For a time-stamp-only container the interesting facts are how many time-stamps there are
        // and whether the older ones now carry revocation data for their TSA certificate — that is
        // the difference between a renewed chain and two unrelated stamps.
        var dd = result.getDiagnosticData();
        if (dd == null) return;
        for (var ts : dd.getTimestampList()) {
            var cert = ts.getSigningCertificate();
            int revs = cert == null ? 0 : cert.getCertificateRevocationData().size();
            System.out.printf("%s  razítko %s  %s  revokace certifikátu TSA: %s%n",
                    label, ts.getProductionTime(), ts.getType(),
                    revs == 0 ? "žádná" : revs + "×");
        }
    }
}
