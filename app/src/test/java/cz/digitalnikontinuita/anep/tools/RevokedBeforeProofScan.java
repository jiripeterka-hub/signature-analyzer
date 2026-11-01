package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.OcspFirstCrlFallbackRevocationDataLoadingStrategy.SkippedRevocation;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.CertificateRevocationWrapper;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.enumerations.CertificateStatus;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Hledá podpisy, které AnEP vyhodnotí jako platné, ačkoli některý <b>známý</b> revokační údaj
 * uvádí odvolání certifikátu z řetězu <b>před</b> rozhodným okamžikem.
 *
 * <p>Vznikla 1. 11. 2026 při měření důkazu existence z obalu ({@code EnvelopePoeScan}): ASiC-S
 * s podpisem úrovně B vyšel s razítkem kontejneru přísně TOTAL_PASSED, ačkoli certifikát byl
 * odvolán týž den dopoledne. Odvolání dokládal jen CRL vydaný po expiraci, který přísná politika
 * DSS nepřijme, a závěr se opřel o vložený CRL z rána se stavem GOOD. Otázka zní, zda se totéž
 * stává i bez obalu — u vlastního razítka prvku a u odvozeného času z krycího podpisu.
 *
 * <p>Měří <b>závěr AnEP</b> ({@link ReportMapper}, tedy včetně odvozeného času a situací 20/21),
 * ne holou indikaci DSS. Za známé se berou revokační údaje v diagnostických datech (vložené
 * i stažené, přijaté i nepřijaté) a odpovědi OCSP, které AnEP stáhl, ale DSS nepředal
 * ({@code skippedRevocations}). Výstup obsahuje jména souborů — psát jen mimo repozitář.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.RevokedBeforeProofScan [-t] &lt;složka|soubor&gt;…
 * </pre>
 * {@code -t} zapne všechny čtyři ústupky.
 */
public final class RevokedBeforeProofScan {

    private static final List<String> EXTENSIONS = List.of(".pdf", ".p7s", ".p7m", ".asice",
            ".asics", ".sce", ".scs", ".xml", ".json", ".zfo");
    private static final int TIMEOUT_S = Integer.getInteger("timeout", 300);

    public static void main(String[] args) throws Exception {
        List<String> paths = new ArrayList<>(List.of(args));
        boolean tolerant = paths.remove("-t");
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(tolerant);
        settings.setAcceptExpiredRevocationSources(tolerant);
        settings.setIgnoreSha1Deprecated(tolerant);
        settings.setAcceptPdfNotEtsi(tolerant);
        settings.setUseCustomValidationTime(false);
        var verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        var factory = new ValidatorFactory(settings);
        var loader = new DocumentLoader();
        var mapper = new ReportMapper();

        List<Path> files = new ArrayList<>();
        for (String a : paths) {
            Path root = Path.of(a);
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).filter(RevokedBeforeProofScan::wanted)
                        .sorted().forEach(files::add);
                }
            } else if (Files.isRegularFile(root)) {
                files.add(root);
            }
        }
        System.out.println("souborů: " + files.size() + (tolerant ? " (s ústupky)" : " (přísně)"));

        Map<String, Integer> totals = new TreeMap<>();
        ExecutorService ex = Executors.newSingleThreadExecutor(daemon());
        for (Path p : files) {
            Future<List<String>> job = ex.submit(() -> {
                List<String> lines = new ArrayList<>();
                var outcome = factory.validate(loader.load(p.toFile()), null, verifier, null);
                ValidationResult result = mapper.map(p.getFileName().toString(), "x", outcome,
                        settings, Map.of(), List.of(), false, Map.of());
                DiagnosticData dd = outcome.reports().getDiagnosticData();
                for (SignatureSummary s : result.getSignatures()) {
                    SignatureWrapper sw = dd.getSignatureById(s.getSignatureId());
                    if (sw == null) continue;
                    lines.add("#SIG " + s.getIndication());
                    if (!"TOTAL_PASSED".equals(s.getIndication())) continue;
                    Instant bst = s.getBestSignatureTime();
                    if (bst == null) continue;
                    String hit = revokedBefore(sw, bst, outcome.skippedRevocations());
                    if (hit == null) continue;
                    var derived = outcome.derivedTimeResults().get(s.getSignatureId());
                    String via = derived != null && "TOTAL_PASSED".equals(derived.indication())
                            ? (derived.poeSupplied() ? "odvozený čas (připsané razítko)" : "odvozený čas")
                            : (sw.getTimestampList().isEmpty() ? "bez vlastního razítka" : "vlastní razítko");
                    lines.add("PLATNÝ PŘES ODVOLÁNÍ  " + p + "\n    podpis " + s.getSignatureId()
                            + "  BST " + bst + "  přes: " + via + "\n    " + hit
                            + "\n    situace " + s.getDetectedSituations()
                            + "  rozhodující nastavení " + s.getDecisiveSettings());
                }
                return lines;
            });
            try {
                for (String l : job.get(TIMEOUT_S, TimeUnit.SECONDS)) {
                    if (l.startsWith("#SIG ")) {
                        totals.merge("podpis: " + l.substring(5), 1, Integer::sum);
                    } else {
                        totals.merge("PLATNÝ PŘES ODVOLÁNÍ", 1, Integer::sum);
                        System.out.println(l);
                    }
                }
            } catch (TimeoutException te) {
                job.cancel(true);
                totals.merge("soubor: vypršel čas", 1, Integer::sum);
                System.out.println("TIMEOUT  " + p);
                ex.shutdownNow();
                ex = Executors.newSingleThreadExecutor(daemon());
            } catch (ExecutionException ee) {
                totals.merge("soubor: chyba", 1, Integer::sum);
            }
        }
        ex.shutdownNow();
        System.out.println("\n=== SOUHRN");
        totals.forEach((k, v) -> System.out.println(String.format("  %-40s %d", k, v)));
        System.exit(0);
    }

    /** Popis prvního známého odvolání před {@code bst} v neduvěryhodné části řetězu, jinak {@code null}. */
    private static String revokedBefore(SignatureWrapper sw, Instant bst, List<SkippedRevocation> skipped) {
        for (CertificateWrapper cw : sw.getCertificateChain()) {
            if (cw.isTrusted()) break;
            for (CertificateRevocationWrapper rev : cw.getCertificateRevocationData()) {
                if (rev.getStatus() != CertificateStatus.REVOKED || rev.getRevocationDate() == null) continue;
                if (!rev.getRevocationDate().toInstant().isBefore(bst)) continue;
                return rev.getRevocationType() + " vydaný " + rev.getThisUpdate() + " (" + rev.getOrigin()
                        + "): " + cw.getCertificateDN() + " odvolán " + rev.getRevocationDate()
                        + ", certifikát platil do " + cw.getNotAfter();
            }
            for (SkippedRevocation sr : skipped) {
                if (!cw.getId().equals(sr.certId()) || sr.status() != CertificateStatus.REVOKED
                        || sr.revocationDate() == null) continue;
                if (!sr.revocationDate().toInstant().isBefore(bst)) continue;
                return "OCSP nepředaná DSS, vydaná " + sr.thisUpdate() + ": " + cw.getCertificateDN()
                        + " odvolán " + sr.revocationDate();
            }
        }
        return null;
    }

    private static boolean wanted(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return EXTENSIONS.stream().anyMatch(n::endsWith);
    }

    private static ThreadFactory daemon() {
        return r -> { Thread t = new Thread(r); t.setDaemon(true); return t; };
    }
}
