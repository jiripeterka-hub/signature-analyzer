package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.AugmentationService;
import cz.digitalnikontinuita.anep.core.TimestampingService;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the whole preservation chain — augmentation followed by an archive time-stamp — the same
 * way the Uchování button does, and repeats it on its own output.
 *
 * <p>Measuring augmentation alone was not enough: preservation also writes a time-stamp revision,
 * and a document preserved twice grew by twice the size of a CRL that was already inside it. Only
 * running both steps in sequence, twice, shows whether the growth is gone.
 *
 * <p>Calls a real TSA, so it needs the TSA profile configured in the application settings.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.PreserveProbe &lt;file&gt; [kol]
 * </pre>
 */
public final class PreserveProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: PreserveProbe <file> [počet kol]");
            return;
        }
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        AppSettings settings = AppSettings.load();
        TsaProfile profile = settings.getSelectedTsa();
        if (profile == null) {
            System.err.println("V nastavení není vybrána žádná TSA — uchování nelze provést.");
            return;
        }
        System.out.println("TSA: " + profile.getName());

        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Trusted Lists: loaded");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Trusted Lists: UNAVAILABLE");
        }

        AugmentationService augSvc = new AugmentationService(new AtomicReference<>(verifier));
        TimestampingService tsaSvc = new TimestampingService(new AtomicReference<>(verifier));
        DigestAlgorithm alg = TimestampingService.resolveAlgorithm(profile.getHashAlgorithm());

        Path out = Path.of(System.getProperty("anep.probe.out", "."));
        Files.createDirectories(out);
        File current = Path.of(args[0]).toFile();
        long previous = Files.size(current.toPath());
        System.out.printf("vstup                 %,12d B%n", previous);

        // -fromB reproduces what the Analýza button now does for a signature nothing anchors:
        // stamp, then embed, then stamp again — two time-stamps in one round, so that the level
        // reaches LTA rather than stopping at T.
        boolean fromBaseline = args.length > 2 && "-fromB".equals(args[2]);

        for (int round = 1; round <= rounds; round++) {
            DSSDocument input = new eu.europa.esig.dss.model.FileDocument(current);
            if (fromBaseline && round == 1) {
                input = tsaSvc.addTimestamp(input, null, profile, alg).doc();
                System.out.println("   (první razítko na úroveň T)");
            }
            // The CRLs situation 33 downloaded from CDP, exactly as AdvisorView hands them over.
            // Passing an empty list here made the probe skip the material DSS itself never
            // fetches — the chains of time-stamps already in the document — and the output looked
            // as though preservation had failed to embed them.
            DSSDocument augmented = augSvc.augment(input, null, s33Crls(input, settings, verifier));
            DSSDocument stamped = tsaSvc.addTimestamp(augmented, null, profile, alg).doc();
            byte[] bytes;
            try (InputStream is = stamped.openStream()) { bytes = is.readAllBytes(); }
            File dest = out.resolve("kolo" + round + ".pdf").toFile();
            Files.write(dest.toPath(), bytes);
            System.out.printf("%d. uchování           %,12d B   přírůstek %,12d B%n",
                    round, (long) bytes.length, bytes.length - previous);
            previous = bytes.length;
            current = dest;
        }
    }

    /**
     * Revocation lists situation 33 fetched from the certificates' distribution points.
     *
     * <p>DSS collects revocation only for what it validates, which leaves out the chains of
     * time-stamps already inside the document — and those are exactly what ETSI EN 319 142-1
     * clause 6.3 requirement x) says has to be embedded before a new document time-stamp. The
     * application gets them from the validation it has just run; so does this.
     */
    private static List<byte[]> s33Crls(DSSDocument input, AppSettings settings,
                                        CommonCertificateVerifier verifier) {
        try {
            var outcome = new cz.digitalnikontinuita.anep.core.ValidatorFactory(settings)
                    .validate(input, null, verifier, null);
            var result = new cz.digitalnikontinuita.anep.core.ReportMapper()
                    .map("probe.pdf", null, outcome, settings);
            List<byte[]> crls = new java.util.ArrayList<>();
            for (var sig : result.getSignatures()) {
                if (sig.getDetectedSituations().contains(33)) crls.addAll(sig.getS33CrlBytes());
            }
            // Mirrors AdvisorView: the lists validation leaned on go in too, or the probe would
            // measure a preservation the application no longer performs.
            crls.addAll(cz.digitalnikontinuita.anep.core.ReliedUponRevocation.crlBytes(
                    outcome.reports().getDiagnosticData()));
            System.out.println("   (extraCrls: " + crls.size() + " ks)");
            return crls;
        } catch (Exception e) {
            System.out.println("   (S33 CRL se nepodařilo získat: " + e + ")");
            return List.of();
        }
    }
}
