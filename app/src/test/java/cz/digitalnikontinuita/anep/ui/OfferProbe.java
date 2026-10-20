package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.SignatureExtensionService;
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
import java.util.stream.Stream;

/**
 * Says which of the two actions the Analýza tab would offer for each file, and why.
 *
 * <p>The offer rules are a policy, not a detail: uchování is withheld where it cannot change what
 * a conformant validator concludes, and zafixování stands in for it there — but sits beside it in
 * expert mode. Those conditions live in the view and are otherwise only observable by clicking, so
 * this reproduces them from the same predicates the view uses.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.ui.OfferProbe &lt;file|folder&gt; [...]
 * </pre>
 */
public final class OfferProbe {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: OfferProbe <file|folder> [...]");
            return;
        }
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setAcceptPdfNotEtsi(false);

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

        System.out.printf("%-52s %-22s %-9s %-9s %-9s %s%n",
                "soubor", "formát", "razítko", "uchování", "zafix.", "zafix. v expertním");
        for (String a : args) {
            Path root = Path.of(a);
            List<Path> files;
            if (Files.isDirectory(root)) {
                try (Stream<Path> w = Files.walk(root)) {
                    files = w.filter(Files::isRegularFile).filter(OfferProbe::isCandidate)
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
                    List<SignatureSummary> sigs = result.getSignatures();
                    if (sigs.isEmpty()) continue;

                    // Mirrors the view: a signature with nothing anchoring it is offered a
                    // time-stamp; one that is anchored and whose missing revocation data can be
                    // obtained issued after that anchor is offered preservation; otherwise
                    // fixation. Two paths, the hand-built PDF one and DSS's own extension.
                    boolean worthPreserving = sigs.stream()
                            .anyMatch(SignatureSummary::isPreservationWorthwhile);
                    List<SignatureSummary> realSigs = sigs.stream()
                            .filter(s -> !"TimeStampToken".equals(s.getFormat()))
                            .toList();
                    boolean notAnchored = !realSigs.isEmpty()
                            && realSigs.stream().allMatch(s -> s.getBestSignatureTime() == null);
                    java.time.Instant now = java.time.Instant.now();
                    boolean certsStillValid = realSigs.stream().allMatch(s -> !s.isCertRevoked()
                            && s.getCertNotAfter() != null && s.getCertNotAfter().isAfter(now));
                    boolean preservablePdf = worthPreserving && (!notAnchored || certsStillValid)
                            && sigs.stream().anyMatch(s ->
                            AdvisorView.isPdfSignature(s.getFormat())
                            && !AdvisorView.isPdfNotEtsi(s.getFormat()));
                    boolean tooLateToRenew =
                            SignatureExtensionService.isTimestampOnlyContainer(result)
                            && AdvisorView.lastTimestampChainExpired(result);
                    boolean preservableExt = worthPreserving
                            && sigs.stream().noneMatch(s -> s.getDetectedSituations().contains(13))
                            && !tooLateToRenew
                            && (!notAnchored || certsStillValid)
                            && SignatureExtensionService.handles(result, p.toFile());
                    boolean preservable = preservablePdf || preservableExt;
                    // EN 319 122-1 requirement m): the time-stamp has to predate expiry/revocation
                    // of the signing certificate, so afterwards it is not offered at all.
                    boolean timestampable = !preservable && notAnchored && certsStillValid
                            && sigs.stream().noneMatch(s -> s.getDetectedSituations().contains(13));
                    String fmt = sigs.get(0).getFormat();
                    System.out.printf("%-52.52s %-22s %-9s %-9s %-9s %s%n",
                            p.getFileName(), fmt,
                            timestampable ? "ANO" : "ne",
                            preservable ? "ANO" : "ne",
                            (preservable || timestampable) ? "ne" : "ANO",
                            "ANO");
                } catch (Exception e) {
                    System.out.printf("%-52.52s  SELHALO: %s%n", p.getFileName(), e);
                }
            }
        }
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }
}
