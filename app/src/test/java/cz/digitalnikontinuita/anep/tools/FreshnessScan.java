package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Counts how many signatures that currently report S33 would stop reporting it once revocation
 * data had to be fresh with respect to the decisive instant.
 *
 * <p>S33 promises that the missing revocation data can be embedded and the signature will stay
 * verifiable. Today the test behind it only asks whether the data covers the certificate's
 * validity period — not whether it is late enough to know about a revocation that happened at the
 * decisive instant. A CRL published two years before the signature was time-stamped covers the
 * certificate and says nothing about the moment that matters.
 *
 * <p>DSS itself decides nothing here: its bundled policy sets {@code RevocationFreshness} to
 * IGNORE and omits {@code RevocationFreshnessNextUpdate} altogether. So the rule cannot be
 * mirrored from the validator the way earlier S33 corrections were — it has to be chosen, and
 * these are the numbers to choose on.
 *
 * <p>Three candidate rules, evaluated side by side:
 * <ol>
 *   <li>{@code nextUpdate >= BST} — the list was the current one at that instant; falls back to
 *       rule 2 when the revocation carries no nextUpdate. Mirrors RevocationFreshnessNextUpdate.</li>
 *   <li>{@code thisUpdate >= BST} — issued no earlier than the decisive instant.</li>
 *   <li>{@code thisUpdate >= BST + grace} — waits out the publication delay, so a revocation
 *       reported just after signing is still caught. The grace period of the older ETSI model.</li>
 * </ol>
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.FreshnessScan [-grace 24h] &lt;folder&gt; [&lt;folder&gt;...]
 * </pre>
 */
public final class FreshnessScan {

    private static final List<String> EXTENSIONS =
            List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics", ".sce", ".scs", ".xml", ".json");

    public static void main(String[] args) throws Exception {
        Duration grace = Duration.ofHours(24);
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("-grace".equals(args[i]) && i + 1 < args.length) {
                String v = args[++i].toLowerCase();
                grace = v.endsWith("h") ? Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)))
                        : Duration.ofMinutes(Long.parseLong(v));
            } else {
                roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: FreshnessScan [-grace 24h] <folder> [<folder>...]");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        TrustConfig trustConfig = new TrustConfig(settings);
        ValidatorFactory validatorFactory = new ValidatorFactory(settings);
        ReportMapper reportMapper = new ReportMapper();
        DocumentLoader documentLoader = new DocumentLoader();

        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Trusted Lists: loaded");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Trusted Lists: UNAVAILABLE (" + e + ")");
        }
        System.out.println("grace = " + grace);

        int files = 0, sigs = 0, withS33 = 0, noBst = 0, failed = 0;
        int fail1 = 0, fail2 = 0, fail3 = 0, failChain2 = 0;
        List<String> ex1 = new ArrayList<>(), ex2 = new ArrayList<>(), ex3 = new ArrayList<>();
        List<Long> gapsDays = new ArrayList<>();

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile).filter(FreshnessScan::isCandidate)
                        .sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                try {
                    DSSDocument doc = documentLoader.load(p.toFile());
                    ValidationOutcome outcome = validatorFactory.validate(doc, null, verifier, null);
                    ValidationResult result = reportMapper.map(
                            p.getFileName().toString(), null, outcome, settings);
                    DiagnosticData dd = outcome.reports().getDiagnosticData();

                    for (SignatureSummary sig : result.getSignatures()) {
                        sigs++;
                        if (!sig.getDetectedSituations().contains(33)) continue;
                        withS33++;
                        Instant bst = sig.getBestSignatureTime();
                        if (bst == null) { noBst++; continue; }
                        SignatureWrapper sw = dd.getSignatureById(sig.getSignatureId());
                        if (sw == null) continue;

                        // Only the end-entity certificate is judged. CA certificates belong to a
                        // different cadence entirely: a root publishes the CRL covering its
                        // intermediates perhaps once a year, so such a CRL is routinely older than
                        // any given signature without that saying anything about its quality.
                        // Holding them to the signature's decisive instant measures the CA's
                        // publication schedule, not the freshness of the evidence.
                        CertificateWrapper signing = sw.getSigningCertificate();
                        if (signing == null) continue;
                        Verdict v = judge(List.of(signing), bst, grace);
                        Verdict vAll = judge(sw.getCertificateChain(), bst, grace);
                        String label = rel(root, p) + "  BST=" + bst
                                + (v.worstGapDays >= 0 ? "  starší o " + v.worstGapDays + " dní" : "");
                        if (!v.rule1) { fail1++; ex1.add(label); }
                        if (!v.rule2) { fail2++; ex2.add(label); }
                        if (!v.rule3) { fail3++; ex3.add(label); }
                        if (!vAll.rule2) failChain2++;
                        if (v.worstGapDays > 0) gapsDays.add(v.worstGapDays);
                    }
                } catch (Exception | StackOverflowError e) {
                    failed++;
                }
            }
        }

        System.out.println();
        System.out.println("Souborů: " + files + ", podpisů: " + sigs
                + ", z toho s S33: " + withS33 + ", bez BST: " + noBst + ", chyb: " + failed);
        int base = withS33 - noBst;
        System.out.println("Posuzováno (S33 a známý BST): " + base);
        System.out.println();
        report("1) nextUpdate >= BST         ", fail1, base, ex1);
        report("2) thisUpdate >= BST         ", fail2, base, ex2);
        report("3) thisUpdate >= BST + grace ", fail3, base, ex3);
        System.out.println();
        System.out.println("Pro srovnání — pravidlo 2 uplatněné na celý řetězec včetně"
                + " mezilehlých CA: " + failChain2 + " z " + base
                + " (CA mají vlastní kadenci vydávání, proto se do rozhodnutí nepočítají)");

        if (!gapsDays.isEmpty()) {
            gapsDays.sort(Comparator.naturalOrder());
            System.out.println();
            System.out.println("Jak moc jsou zaostalá data stará (dny před BST), u "
                    + gapsDays.size() + " podpisů:");
            System.out.println("   min=" + gapsDays.get(0)
                    + "  medián=" + gapsDays.get(gapsDays.size() / 2)
                    + "  max=" + gapsDays.get(gapsDays.size() - 1));
        }
    }

    private static void report(String name, int fail, int base, List<String> examples) {
        String pct = base > 0 ? String.format(" (%.1f %%)", 100.0 * fail / base) : "";
        System.out.println(name + " ztratí S33: " + fail + " z " + base + pct);
        examples.stream().distinct().limit(8).forEach(e -> System.out.println("        " + e));
    }

    private record Verdict(boolean rule1, boolean rule2, boolean rule3, long worstGapDays) {}

    /**
     * Applies the three rules across every certificate S33 would have to cover. A certificate
     * passes a rule when at least one of its revocation entries does — that is the entry
     * augmentation would embed. Certificates with no revocation data at all are skipped: their
     * absence is a different problem, already covered by the existing S33 test.
     */
    private static Verdict judge(SignatureWrapper sw, Instant bst, Duration grace) {
        return judge(sw.getCertificateChain(), bst, grace);
    }

    /**
     * Only the signing certificate's chain is judged against the signature's decisive instant.
     *
     * <p>A time-stamp's own TSA certificate has a different control time — the proof of existence
     * that covers it, i.e. the next time-stamp in the chain, or the present moment for the last
     * one. Measuring it against the signature's BST compares against the wrong instant and
     * inflates the count; the first run of this tool did exactly that and produced a median
     * staleness of 441 days that was largely an artefact.
     */
    private static Verdict judge(List<CertificateWrapper> certs, Instant bst, Duration grace) {
        boolean r1 = true, r2 = true, r3 = true;
        long worstGap = -1;
        List<CertificateWrapper> chain = new ArrayList<>(certs);

        for (CertificateWrapper c : chain) {
            if (c.isTrusted() || c.isSelfSigned() || c.isIdPkixOcspNoCheck()) continue;
            List<CertificateRevocationWrapper> revs = c.getCertificateRevocationData();
            if (revs.isEmpty()) continue;
            boolean any1 = false, any2 = false, any3 = false;
            long bestGap = Long.MAX_VALUE;
            for (CertificateRevocationWrapper r : revs) {
                Instant tu = r.getThisUpdate() != null ? r.getThisUpdate().toInstant() : null;
                Instant nu = r.getNextUpdate() != null ? r.getNextUpdate().toInstant() : null;
                if (tu == null) { any1 = any2 = any3 = true; break; }   // cannot judge — do not blame
                boolean p2 = !tu.isBefore(bst);
                boolean p1 = nu != null ? !nu.isBefore(bst) : p2;
                boolean p3 = !tu.isBefore(bst.plus(grace));
                any1 |= p1; any2 |= p2; any3 |= p3;
                if (!p2) bestGap = Math.min(bestGap, Duration.between(tu, bst).toDays());
            }
            r1 &= any1; r2 &= any2; r3 &= any3;
            if (!any2 && bestGap != Long.MAX_VALUE) worstGap = Math.max(worstGap, bestGap);
        }
        return new Verdict(r1, r2, r3, worstGap);
    }

    private static boolean isCandidate(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }

    private static String rel(Path root, Path p) {
        try { return root.relativize(p).toString(); } catch (Exception e) { return p.toString(); }
    }
}
