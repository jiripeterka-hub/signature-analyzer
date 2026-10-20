package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Measures what the revocation lists fetched for trust anchors actually cost a preserved document.
 *
 * <p>The question is whether embedding them is worth the bytes. Nothing in the standards asks for
 * it — a trust anchor's status is not established during validation, and EN 319 142-1 wants the
 * material that was used — but a certificate is only an anchor relative to the store it is judged
 * against, and in the European model that anchor is usually an issuing CA rather than a root. Judged
 * against a pinned root instead, the same CA is an ordinary intermediate whose revocation a
 * validator does require, and a preserved document without it has to go back to the network.
 *
 * <p>These lists are the ones {@code computeS33} downloads from a certificate's CDP because DSS
 * asked for no revocation for it, and they reach the document as {@code extraCrls}. The size probes
 * miss them entirely: {@code AugmentSizeProbe} passes an empty list where the application passes
 * these, so every growth figure measured so far excludes exactly this cost.
 *
 * <p>Prints one line per document that pays anything, each list with its issuer and entry count, and
 * a tally at the end. Reuses one {@code CertificateVerifier} across the corpus like
 * {@code SituationScan} does — that skews which revocation is found, but not what a fetched list
 * weighs, which is all this measures.
 *
 * <pre>java -cp ... cz.digitalnikontinuita.anep.tools.AnchorCrlCostProbe &lt;folder&gt; [...]</pre>
 */
public final class AnchorCrlCostProbe {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String a : args) roots.add(Path.of(a));
        if (roots.isEmpty()) {
            System.err.println("usage: AnchorCrlCostProbe <folder> [...]");
            return;
        }

        AppSettings settings = AppSettings.load();
        TrustConfig trustConfig = new TrustConfig(settings);
        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        ReportMapper reportMapper = new ReportMapper();
        DocumentLoader documentLoader = new DocumentLoader();
        CommonCertificateVerifier verifier = trustConfig.buildLotlCertificateVerifier();

        int files = 0, paying = 0;
        long totalBytes = 0;
        List<Long> perDocument = new ArrayList<>();
        Map<String, long[]> byIssuer = new LinkedHashMap<>();   // issuer → {count, bytes}

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile).filter(AnchorCrlCostProbe::isCandidate)
                        .sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                long docBytes = 0;
                List<String> lines = new ArrayList<>();
                try {
                    DSSDocument doc = documentLoader.load(p.toFile());
                    ValidationResult result = reportMapper.map(p.getFileName().toString(), null,
                            validatorFactory.validate(doc, null, verifier, null), settings);
                    for (SignatureSummary sig : result.getSignatures()) {
                        for (byte[] der : sig.getS33CrlBytes()) {
                            if (der == null || der.length == 0) continue;
                            docBytes += der.length;
                            lines.add("      " + describe(der, byIssuer));
                        }
                    }
                } catch (Exception | StackOverflowError e) {
                    continue;   // unreadable documents are another probe's business
                }
                if (docBytes == 0) continue;
                paying++;
                totalBytes += docBytes;
                perDocument.add(docBytes);
                System.out.printf("%,9d B  %s%n", docBytes, p.getFileName());
                lines.forEach(System.out::println);
            }
        }

        perDocument.sort(null);
        System.out.println();
        System.out.println("souborů: " + files + ", z toho platí za kotvy: " + paying);
        System.out.printf("celkem: %,d B%n", totalBytes);
        if (!perDocument.isEmpty()) {
            System.out.printf("na dokument — medián %,d B, průměr %,d B, maximum %,d B%n",
                    perDocument.get(perDocument.size() / 2), totalBytes / perDocument.size(),
                    perDocument.get(perDocument.size() - 1));
        }
        System.out.println("podle vydavatele seznamu:");
        byIssuer.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(e -> System.out.printf("  %,9d B  (%dx)  %s%n",
                        e.getValue()[1], e.getValue()[0], e.getKey()));
    }

    private static String describe(byte[] der, Map<String, long[]> byIssuer) {
        try {
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509")
                    .generateCRL(new ByteArrayInputStream(der));
            String issuer = crl.getIssuerX500Principal().getName();
            int entries = crl.getRevokedCertificates() == null
                    ? 0 : crl.getRevokedCertificates().size();
            long[] tally = byIssuer.computeIfAbsent(issuer, k -> new long[2]);
            tally[0]++;
            tally[1] += der.length;
            // Age and remaining validity, because a list fetched today can be months old and
            // already past its nextUpdate: a certification authority that revokes nothing has no
            // reason to publish often. What that is worth to a preserved document is a separate
            // question from what it costs, and it cannot be answered without these two dates.
            java.util.Date thisUpdate = crl.getThisUpdate();
            java.util.Date nextUpdate = crl.getNextUpdate();
            long ageDays = thisUpdate == null ? -1
                    : (System.currentTimeMillis() - thisUpdate.getTime()) / 86_400_000L;
            String expiry = nextUpdate == null ? "bez nextUpdate"
                    : nextUpdate.before(new java.util.Date())
                        ? "PROŠLÁ o " + ((System.currentTimeMillis() - nextUpdate.getTime())
                                / 86_400_000L) + " dní"
                        : "platí ještě " + ((nextUpdate.getTime() - System.currentTimeMillis())
                                / 86_400_000L) + " dní";
            return String.format("%,9d B  položek %5d  stáří %4d dní, %s  vydal %s",
                    der.length, entries, ageDays, expiry, issuer);
        } catch (Exception e) {
            byIssuer.computeIfAbsent("(nečitelná)", k -> new long[2])[1] += der.length;
            return String.format("%,9d B  (nečitelná)", der.length);
        }
    }

    private static boolean isCandidate(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
