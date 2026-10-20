package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.XmlTrustService;
import eu.europa.esig.dss.enumerations.TimestampType;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Counts the documents whose earliest proof of existence rests on a time-stamping service that the
 * Trusted List has since withdrawn.
 *
 * <p>Written for one concrete disagreement: a re-stamped ISDS message that AnEP passes and the
 * online DSS demo does not. The demo declines the 2014 signature time-stamp because the qualified
 * time-stamping service behind it was withdrawn in 2016, falls back to the 2018 archive time-stamp,
 * and by then the signing certificate had expired. The document is fine; what changed is the
 * service's present status. The question this answers is how much of a corpus stands on that
 * footing — and, of those, how many would actually change verdict if the earliest time-stamp were
 * discarded, which is the number that matters.
 *
 * <p>Two conditions, both read from the Trusted List entry of the time-stamp's own TSA certificate:
 * the service was in a positive state when the time-stamp was made, and it is not in one now.
 *
 * <p>Run with a plain {@code java -cp} — see {@link EvidenceRecordSmoke} for why.
 * {@code -ext} narrows the walk (default {@code zfo}); {@code -v} lists every affected file.
 */
public final class WithdrawnTsaScan {

    /** Statuses that count as "the service was operating properly" — pre- and post-eIDAS alike. */
    private static final List<String> POSITIVE = List.of(
            "granted", "accredited", "undersupervision", "supervisionincessation");

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        List<String> extensions = new ArrayList<>(List.of(".zfo"));
        boolean verbose = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-v" -> verbose = true;
                case "-ext" -> {
                    extensions.clear();
                    for (String e : args[++i].split(",")) {
                        String x = e.trim().toLowerCase(Locale.ROOT);
                        extensions.add(x.startsWith(".") ? x : "." + x);
                    }
                }
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: WithdrawnTsaScan [-v] [-ext zfo,p7s] <folder>…");
            return;
        }

        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setAcceptPdfNotEtsi(false);
        TrustConfig trustConfig = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trustConfig.buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        ReportMapper mapper = new ReportMapper();
        DocumentLoader loader = new DocumentLoader();
        Date now = new Date();

        int files = 0, signatures = 0, withEarliest = 0, onWithdrawn = 0, wouldFlip = 0, failed = 0;
        List<String> affected = new ArrayList<>();
        List<String> flipping = new ArrayList<>();

        for (Path root : roots) {
            List<Path> candidates;
            try (Stream<Path> walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.toString().toLowerCase(Locale.ROOT);
                            return extensions.stream().anyMatch(n::endsWith);
                        })
                        .sorted().toList();
            }
            for (Path p : candidates) {
                files++;
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    ValidationOutcome outcome = factory.validate(doc, null, verifier, null);
                    mapper.map(p.getFileName().toString(), null, outcome, settings);
                    var dd = outcome.reports().getDiagnosticData();

                    for (SignatureWrapper sig : dd.getSignatures()) {
                        signatures++;
                        TimestampWrapper earliest = earliestSignatureTimestamp(sig);
                        if (earliest == null) continue;
                        withEarliest++;

                        CertificateWrapper tsa = earliest.getSigningCertificate();
                        if (tsa == null) continue;
                        Date made = earliest.getProductionTime();
                        if (!wasPositiveThenAndNotNow(tsa, made, now)) continue;
                        onWithdrawn++;
                        affected.add(p.getFileName() + "  razítko " + made + "  TSA " + tsa.getCommonName());

                        // Would it change the verdict? Only if the next proof of existence lands
                        // after the signing certificate expired — that is what turns a passed
                        // archival validation into OUT_OF_BOUNDS.
                        CertificateWrapper signer = sig.getSigningCertificate();
                        Date fallback = nextTimestampAfter(sig, made);
                        if (signer != null && signer.getNotAfter() != null
                                && (fallback == null || fallback.after(signer.getNotAfter()))) {
                            wouldFlip++;
                            flipping.add(p.getFileName() + "  cert do " + signer.getNotAfter()
                                    + ", další POE " + (fallback == null ? "žádné" : fallback));
                        }
                    }
                } catch (Exception | StackOverflowError e) {
                    failed++;
                    if (verbose) System.out.println("!! " + p.getFileName() + "  " + e);
                }
            }
        }

        if (verbose) {
            System.out.println("--- stojí na stažené službě:");
            affected.forEach(a -> System.out.println("   " + a));
        }
        System.out.println("--- změnily by verdikt, kdyby se to razítko zahodilo:");
        flipping.forEach(a -> System.out.println("   " + a));
        System.out.println("-".repeat(100));
        System.out.println("souborů: " + files + ", podpisů: " + signatures
                + ", z toho s podpisovým razítkem: " + withEarliest
                + "\nnejstarší razítko ze služby dnes stažené: " + onWithdrawn
                + "\nz toho by bez něj verdikt spadl: " + wouldFlip
                + "\nnepodařilo se ověřit: " + failed);
    }

    private static TimestampWrapper earliestSignatureTimestamp(SignatureWrapper sig) {
        TimestampWrapper best = null;
        for (TimestampWrapper ts : sig.getTimestampList()) {
            if (ts.getType() != TimestampType.SIGNATURE_TIMESTAMP) continue;
            if (ts.getProductionTime() == null) continue;
            if (best == null || ts.getProductionTime().before(best.getProductionTime())) best = ts;
        }
        return best;
    }

    /** Production time of the next time-stamp of any kind after {@code after} — the next POE. */
    private static Date nextTimestampAfter(SignatureWrapper sig, Date after) {
        Date next = null;
        for (TimestampWrapper ts : sig.getTimestampList()) {
            Date t = ts.getProductionTime();
            if (t == null || !t.after(after)) continue;
            if (next == null || t.before(next)) next = t;
        }
        return next;
    }

    /**
     * True when the TSA's time-stamping service was in a positive state when the time-stamp was
     * made and is in none now — the shape that makes a document's verdict depend on when it is
     * asked about rather than on what it contains.
     */
    private static boolean wasPositiveThenAndNotNow(CertificateWrapper tsa, Date made, Date now) {
        boolean positiveThen = false;
        boolean positiveNow = false;
        boolean anyTimestampService = false;
        for (var tsp : tsa.getTrustServiceProviders()) {
            for (XmlTrustService svc : tsp.getTrustServices()) {
                String type = tail(svc.getServiceType());
                // Only the time-stamping service matters; the same TSA certificate is often listed
                // under a certificate-issuing service too, and that one may well still be granted.
                if (!"QTST".equalsIgnoreCase(type) && !"TSA/QTST".equalsIgnoreCase(type)) continue;
                anyTimestampService = true;
                boolean positive = POSITIVE.contains(tail(svc.getStatus()).toLowerCase(Locale.ROOT));
                if (positive && covers(svc, made)) positiveThen = true;
                if (positive && covers(svc, now)) positiveNow = true;
            }
        }
        return anyTimestampService && positiveThen && !positiveNow;
    }

    private static boolean covers(XmlTrustService svc, Date when) {
        Date from = svc.getStartDate();
        Date to = svc.getEndDate();
        return (from == null || !when.before(from)) && (to == null || when.before(to));
    }

    private static String tail(String uri) {
        return uri == null ? "" : uri.substring(uri.lastIndexOf('/') + 1);
    }
}
