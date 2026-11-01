package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import cz.digitalnikontinuita.anep.core.EnvelopeProof;
import cz.digitalnikontinuita.anep.core.PdfAttachmentInspector;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.model.InMemoryDocument;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Závěr <b>AnEP</b> pro každý podepsaný prvek obalu ve sbírce — samostatně a s důkazem existence
 * z obalu ({@link ValidatorFactory#withEnvelopeProof}, {@link ReportMapper}), tedy přesně to, co
 * uvidí karta po stisku „Ověřit v AnEP" v prohlížeči.
 *
 * <p>Doplňuje {@code EnvelopePoeScan}, který měřil holé indikace DSS před implementací
 * (1. 11. 2026): tenhle měří hotovou cestu, s výkladem situací. Hlásí změny závěru, situaci 57
 * a 20 a výjimky, které by v aplikaci shodily kartu. Výstup obsahuje jména souborů — psát jen
 * mimo repozitář.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.EnvelopeProofScan [-t] &lt;složka|soubor&gt;…
 * </pre>
 * {@code -t} zapne všechny čtyři ústupky.
 */
public final class EnvelopeProofScan {

    private static final Set<String> EXTENSIONS = Set.of(
            "asice", "asics", "sce", "scs", "p7m", "zfo", "xml", "json", "pdf");
    private static final long MAX_SIZE = 60L * 1024 * 1024;
    private static final int TIMEOUT_S = Integer.getInteger("timeout", 300);

    private static AppSettings settings;
    private static ValidatorFactory factory;
    private static eu.europa.esig.dss.spi.validation.CommonCertificateVerifier verifier;
    private static final Map<String, Integer> totals = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        List<String> paths = new ArrayList<>(List.of(args));
        boolean tolerant = paths.remove("-t");
        settings = AppSettings.load();
        settings.setAcceptNewerOcsp(tolerant);
        settings.setAcceptExpiredRevocationSources(tolerant);
        settings.setIgnoreSha1Deprecated(tolerant);
        settings.setAcceptPdfNotEtsi(tolerant);
        settings.setUseCustomValidationTime(false);
        verifier = new TrustConfig(settings).buildLotlCertificateVerifier();
        factory = new ValidatorFactory(settings);

        List<File> files = new ArrayList<>();
        for (String a : paths) {
            File f = new File(a);
            if (f.isDirectory()) {
                try (Stream<Path> walk = Files.walk(f.toPath())) {
                    walk.filter(Files::isRegularFile).filter(EnvelopeProofScan::wanted)
                        .sorted().forEach(p -> files.add(p.toFile()));
                }
            } else if (f.isFile()) {
                files.add(f);
            }
        }
        System.out.println("souborů: " + files.size() + (tolerant ? " (s ústupky)" : " (přísně)"));

        ExecutorService ex = Executors.newSingleThreadExecutor(daemon());
        for (File f : files) {
            Future<?> job = ex.submit(() -> scan(f));
            try {
                job.get(TIMEOUT_S, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                job.cancel(true);
                count("obal: vypršel čas");
                System.out.println("TIMEOUT  " + f);
                ex.shutdownNow();
                ex = Executors.newSingleThreadExecutor(daemon());
            } catch (ExecutionException ee) {
                count("obal: VÝJIMKA");
                System.out.println("VÝJIMKA  " + f + "  " + ee.getCause());
            }
        }
        ex.shutdownNow();
        System.out.println("\n=== SOUHRN");
        totals.forEach((k, v) -> System.out.println(String.format("  %-50s %d", k, v)));
        System.exit(0);
    }

    private static void scan(File file) {
        byte[] bytes;
        try { bytes = Files.readAllBytes(file.toPath()); } catch (Exception e) { return; }
        Kind kind = ContainerInspector.detectKind(bytes);
        Result tree;
        if (kind == Kind.PDF) {
            if (!PdfAttachmentInspector.hasAttachments(bytes)) return;
            tree = PdfAttachmentInspector.inspect(bytes, file.getName(), null);
        } else if (kind == Kind.ZIP || kind == Kind.CMS || kind == Kind.ZFO
                || kind == Kind.XML || kind == Kind.JSON) {
            if (kind == Kind.XML && !contains(bytes, "SignatureValue")) return;
            tree = ContainerInspector.inspect(bytes, file.getName());
        } else {
            return;
        }
        if (tree == null || tree.root() == null) return;
        List<Node> elements = new ArrayList<>();
        collect(tree.root(), kind, elements, new HashSet<>());
        for (Node el : elements) {
            try {
                element(file, bytes, kind, el);
            } catch (Exception e) {
                count("prvek: VÝJIMKA");
                System.out.println("VÝJIMKA  " + file + " :: " + el.getPath() + "  " + e);
                e.printStackTrace(System.out);
            }
        }
    }

    private static void element(File file, byte[] envelope, Kind kind, Node el) {
        byte[] data = el.getData();
        var outcome = factory.validate(new InMemoryDocument(data, el.getName()), null, verifier, null);
        if (outcome.reports().getSimpleReport().getSignatureIdList().isEmpty()) return;
        String before = verdict(outcome, el.getName());
        var withProof = factory.withEnvelopeProof(outcome,
                new EnvelopeProof.Source(envelope, file.getName(), el.getPath(), data), verifier);
        String after = verdict(withProof, el.getName());
        String change = before.equals(after) ? "beze změny" : "ZMĚNA";
        count(kind + ": " + change);
        if (after.contains("57")) count("situace 57");
        if (after.contains("57") && after.contains(" 20")) count("situace 57 spolu s 20");
        if (!before.equals(after)) {
            System.out.println("ZMĚNA    " + file + " :: " + el.getPath()
                    + "\n    samostatně  " + before + "\n    s obalem    " + after);
        }
    }

    /** Indikace a situace všech podpisů, jak je vydá ReportMapper. */
    private static String verdict(ValidatorFactory.ValidationOutcome outcome, String name) {
        ValidationResult result = new ReportMapper().map(name, "x", outcome, settings, Map.of(),
                List.of(), false, Map.of());
        StringBuilder sb = new StringBuilder();
        for (SignatureSummary s : result.getSignatures()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s.getIndication());
            if (s.getSubIndication() != null) sb.append('/').append(s.getSubIndication());
            sb.append(" sit ").append(s.getDetectedSituations().toString()
                    .replace("[", "").replace("]", "").replace(",", ""));
        }
        return sb.toString();
    }

    private static void collect(Node n, Kind envKind, List<Node> out, Set<String> seen) {
        for (Node c : n.getChildren()) {
            if (!c.isDirectory() && c.getData() != null && c.getData().length > 0
                    && !(envKind == Kind.ZIP && c.getPath() != null
                         && c.getPath().toUpperCase().startsWith("META-INF/"))
                    && signedCandidate(c.getData())
                    && seen.add(Base64.getEncoder().encodeToString(
                            eu.europa.esig.dss.spi.DSSUtils.digest(
                                    eu.europa.esig.dss.enumerations.DigestAlgorithm.SHA256, c.getData())))) {
                out.add(c);
            }
            collect(c, envKind, out, seen);
        }
    }

    private static boolean signedCandidate(byte[] d) {
        Kind k = ContainerInspector.detectKind(d);
        switch (k) {
            case PDF: return contains(d, "/ByteRange");
            case CMS: return !containsTstInfo(d);
            case ZFO: return true;
            case XML: return contains(d, "SignatureValue");
            case JSON: return contains(d, "\"signature\"") && contains(d, "\"protected\"");
            case ZIP:
                try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(d))) {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        String n = e.getName().toLowerCase();
                        if (n.startsWith("meta-inf/") && (n.contains("signature")
                                || n.contains("timestamp") || n.contains("evidencerecord"))) return true;
                    }
                } catch (Exception ignored) { }
                return false;
            default: return false;
        }
    }

    /** id-ct-TSTInfo v prvních bajtech: je to časové razítko obalu, ne jeho obsah. */
    private static boolean containsTstInfo(byte[] d) {
        byte[] oid = {0x06, 0x0B, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01,
                0x09, 0x10, 0x01, 0x04};
        outer:
        for (int i = 0, n = Math.min(d.length - oid.length, 512); i <= n; i++) {
            for (int j = 0; j < oid.length; j++) if (d[i + j] != oid[j]) continue outer;
            return true;
        }
        return false;
    }

    private static boolean wanted(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(n.substring(dot + 1))) return false;
        try { return Files.size(p) <= MAX_SIZE; } catch (Exception e) { return false; }
    }

    private static boolean contains(byte[] d, String s) {
        return new String(d, StandardCharsets.ISO_8859_1).contains(s);
    }

    private static synchronized void count(String key) {
        totals.merge(key, 1, Integer::sum);
    }

    private static ThreadFactory daemon() {
        return r -> { Thread t = new Thread(r); t.setDaemon(true); return t; };
    }
}
