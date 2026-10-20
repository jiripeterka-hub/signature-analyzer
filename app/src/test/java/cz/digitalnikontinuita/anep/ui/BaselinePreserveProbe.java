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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Whether the Analýza tab would offer uchování for a level-B signature, and which part of the gate
 * decides it.
 *
 * <p>Preservation from B is the case the gate stopped admitting when it began asking for situations
 * 31 and 32 — neither of which a signature with no time-stamp can satisfy, since both describe a
 * chain of proofs of existence that does not exist yet. The predicate lives in the view and is
 * otherwise only observable by clicking, so this calls the same one and prints its inputs beside
 * its answer. Must stay in package {@code ui}: {@code preservationSituationsMet} is package-private.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.ui.BaselinePreserveProbe &lt;file|folder&gt; [...]
 * </pre>
 */
public final class BaselinePreserveProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: BaselinePreserveProbe <file|folder> [...]");
            return;
        }
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setAcceptPdfNotEtsi(false);
        settings.setIgnoreSha1Deprecated(false);

        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
        }
        ValidatorFactory factory = new ValidatorFactory(settings);
        ReportMapper mapper = new ReportMapper();
        DocumentLoader loader = new DocumentLoader();

        for (String a : args) {
            Path root = Path.of(a);
            List<Path> files;
            if (Files.isDirectory(root)) {
                try (Stream<Path> w = Files.walk(root)) {
                    files = w.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                                    .endsWith(".pdf"))
                            .sorted().toList();
                }
            } else {
                files = List.of(root);
            }
            for (Path p : files) {
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    ValidationOutcome outcome = factory.validate(doc, null, verifier, null);
                    ValidationResult result = mapper.map(
                            p.getFileName().toString(), null, outcome, settings);
                    // Same test the view applies: the newest time-stamp's certificate must be a
                    // trust anchor, or the standard's leniency about its expiry does not apply.
                    java.util.Set<String> lastTsTrusted = new java.util.HashSet<>();
                    var dd = outcome.reports().getDiagnosticData();
                    for (var sw : dd.getSignatures()) {
                        eu.europa.esig.dss.diagnostic.TimestampWrapper newest = null;
                        for (var ts : sw.getTimestampList()) {
                            if (ts.getProductionTime() == null) continue;
                            if (newest == null
                                    || ts.getProductionTime().after(newest.getProductionTime())) {
                                newest = ts;
                            }
                        }
                        if (newest == null) continue;
                        var tsu = newest.getSigningCertificate();
                        if (tsu != null && tsu.isTrusted()) lastTsTrusted.add(sw.getId());
                    }
                    for (SignatureSummary sig : result.getSignatures()) {
                        // The second half of the offer, and for a level-B document the decisive
                        // half: EN 319 122-1 clause 6.3 requirement m) allows no time-stamp once
                        // the signing certificate has expired or been revoked, so preservation
                        // from B cannot start there however complete the revocation picture is.
                        boolean certLive = !sig.isCertRevoked() && sig.getCertNotAfter() != null
                                && sig.getCertNotAfter().isAfter(java.time.Instant.now());
                        boolean gate = AdvisorView.preservationSituationsMet(sig, true);
                        List<Integer> s = sig.getDetectedSituations();
                        // Prodloužení: preservation's gate minus 32, plus "the sunset has not come".
                        boolean prolong = "TOTAL_PASSED".equals(sig.getIndication())
                                && lastTsTrusted.contains(sig.getSignatureId())
                                && !s.contains(32) && !s.contains(43)
                                && s.contains(31) && s.contains(34)
                                && (s.contains(33) || s.contains(45));
                        System.out.printf("%-46.46s %-18s raz=%d  S=%-26.26s  "
                                        + "cert %s  uchování %s  prodloužení %s%n",
                                p.getFileName(), sig.getFormat(),
                                sig.getAllTimestamps().size(), s.toString(),
                                certLive ? "platí " : "vypršel",
                                gate && (!sig.getAllTimestamps().isEmpty() || certLive)
                                        ? "ANO" : "ne ",
                                prolong ? "ANO" : "ne");
                    }
                } catch (Exception e) {
                    System.out.printf("%-56.56s  SELHALO: %s%n", p.getFileName(), e);
                }
            }
        }
    }

    private BaselinePreserveProbe() { }
}
