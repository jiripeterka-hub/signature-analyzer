package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.ContainerInspector;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import cz.digitalnikontinuita.anep.core.PdfAttachmentInspector;
import cz.digitalnikontinuita.anep.core.PdfSource;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidationPolicyFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.SignerDataWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.diagnostic.jaxb.*;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.Indication;
import eu.europa.esig.dss.enumerations.TimestampedObjectType;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.policy.ValidationPolicy;
import eu.europa.esig.dss.policy.ValidationPolicyFacade;
import eu.europa.esig.dss.simplereport.SimpleReport;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.executor.signature.DefaultSignatureProcessExecutor;
import eu.europa.esig.dss.validation.reports.Reports;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Měří, co by vloženým podepsaným prvkům dal <b>důkaz existence z obalu</b> (návrh
 * {@code docs/dukaz-existence-z-obalu-navrh.md}, 1. 11. 2026).
 *
 * <p>Pro každý obal (ASiC, CMS, ZFO, XAdES/JAdES obalující, PDF s přílohami) se ověří obal sám
 * a najdou se jeho časová razítka s indikací PASSED. Pro každý vložený prvek, který nese podpis
 * nebo razítko, se pak porovná závěr DSS <b>samostatně</b> a <b>s připsaným krycím razítkem
 * obalu</b> — týmž postupem jako {@code ValidatorFactory.recomputeWithCoveringTimestamp()}:
 * razítko (s razítky obalu, která drží jeho platnost, a s certifikáty a revokačními údaji obalu)
 * se dočasně připíše do diagnostických dat prvku, hodiny se neposouvají. Obojí ve dvou politikách:
 * přísné a se všemi čtyřmi ústupky.
 *
 * <p>Krytí prvku razítkem se určuje takto: otisk prvku mezi otisky podepsaných dat razítka nebo
 * podpisu, který razítko kryje (ASiC, CMS); u obalů s jediným podepsaným obsahem (CMS, ZFO, XML,
 * JSON), kde se prvek nachází <i>uvnitř</i> obsahu, každé razítko nad podpisem obalu („uvnitř“);
 * u příloh PDF razítka těch revizí, v nichž už příloha se stejným otiskem je.
 *
 * <p>Měří indikace DSS, ne celý výklad AnEP (odvozený čas z krycího podpisu uvnitř prvku se tu
 * nepočítá). Výstup obsahuje jména souborů — psát jen mimo repozitář.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.EnvelopePoeScan &lt;složka|soubor&gt;…
 * </pre>
 */
public final class EnvelopePoeScan {

    private static final Set<String> EXTENSIONS = Set.of(
            "asice", "asics", "sce", "scs", "p7m", "zfo", "xml", "json", "pdf");
    private static final long MAX_SIZE = 60L * 1024 * 1024;
    private static final int TIMEOUT_S = Integer.getInteger("timeout", 300);

    private static ValidatorFactory validatorFactory;
    private static CommonCertificateVerifier verifier;
    private static ValidationPolicy strict;
    private static ValidationPolicy relaxed;
    private static final Map<String, ValidationPolicy> singles = new LinkedHashMap<>();

    private static final Map<String, Integer> totals = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: EnvelopePoeScan <folder|file>…");
            return;
        }
        AppSettings settings = AppSettings.load();
        settings.setAcceptNewerOcsp(false);
        settings.setAcceptExpiredRevocationSources(false);
        settings.setIgnoreSha1Deprecated(false);
        settings.setAcceptPdfNotEtsi(false);
        settings.setUseCustomValidationTime(false);
        strict = orDefault(ValidationPolicyFactory.build(settings));
        AppSettings loose = AppSettings.load();
        loose.setAcceptNewerOcsp(true);
        loose.setAcceptExpiredRevocationSources(true);
        loose.setIgnoreSha1Deprecated(true);
        loose.setAcceptPdfNotEtsi(true);
        loose.setUseCustomValidationTime(false);
        relaxed = orDefault(ValidationPolicyFactory.build(loose));
        for (String name : List.of("novější OCSP", "post-expirační zdroje", "SHA-1", "PDF-NOT-ETSI")) {
            AppSettings one = AppSettings.load();
            one.setAcceptNewerOcsp(name.equals("novější OCSP"));
            one.setAcceptExpiredRevocationSources(name.equals("post-expirační zdroje"));
            one.setIgnoreSha1Deprecated(name.equals("SHA-1"));
            one.setAcceptPdfNotEtsi(name.equals("PDF-NOT-ETSI"));
            one.setUseCustomValidationTime(false);
            singles.put(name, orDefault(ValidationPolicyFactory.build(one)));
        }

        TrustConfig trust = new TrustConfig(settings);
        try { verifier = trust.buildLotlCertificateVerifier(); }
        catch (Exception e) { verifier = trust.buildOfflineCertificateVerifier(); }
        validatorFactory = new ValidatorFactory(settings);

        List<File> files = new ArrayList<>();
        for (String a : args) {
            File f = new File(a);
            if (f.isDirectory()) {
                try (Stream<Path> walk = Files.walk(f.toPath())) {
                    walk.filter(Files::isRegularFile).filter(EnvelopePoeScan::wanted)
                        .sorted().forEach(p -> files.add(p.toFile()));
                }
            } else if (f.isFile()) {
                files.add(f);
            }
        }
        System.out.println("souborů ke zkoumání: " + files.size());

        ExecutorService ex = Executors.newSingleThreadExecutor(daemon());
        for (File f : files) {
            Future<?> job = ex.submit(() -> scanEnvelope(f));
            try {
                job.get(TIMEOUT_S, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                job.cancel(true);
                count("obal: vypršel čas");
                System.out.println("TIMEOUT  " + f);
                ex.shutdownNow();
                ex = Executors.newSingleThreadExecutor(daemon());
            } catch (ExecutionException ee) {
                count("obal: chyba");
                System.out.println("CHYBA    " + f + "  " + ee.getCause());
            }
        }
        ex.shutdownNow();
        System.out.println("\n=== SOUHRN");
        totals.forEach((k, v) -> System.out.println(String.format("  %-60s %d", k, v)));
        System.exit(0);
    }

    private static boolean wanted(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(n.substring(dot + 1))) return false;
        try { return Files.size(p) <= MAX_SIZE; } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ obal

    private static void scanEnvelope(File file) {
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
        Set<String> seen = new HashSet<>();
        collect(tree.root(), kind, elements, seen);
        if (elements.isEmpty()) return;
        count("obal s podepsaným prvkem: " + kind);

        Reports env;
        try {
            env = validatorFactory.validate(new InMemoryDocument(bytes, file.getName()), null,
                    verifier, null).reports();
        } catch (Exception e) {
            count("obal: DSS ho neověří");
            System.out.println("OBAL?    " + file + "  " + e);
            return;
        }
        Reports envRelaxed = recompute(env, relaxed, env.getDiagnosticDataJaxb().getValidationDate());
        DiagnosticData dd = env.getDiagnosticData();
        if (dd.getTimestampList().isEmpty()) {
            count("obal bez razítka: " + kind);
            System.out.println("BEZ TS   " + file + "  (" + elements.size() + " prvků)");
            return;
        }

        for (Node el : elements) {
            try {
                scanElement(file, bytes, kind, env, envRelaxed, el);
            } catch (Exception e) {
                count("prvek: chyba");
                System.out.println("  CHYBA  " + el.getPath() + "  " + e);
            }
        }
    }

    private static void collect(Node n, Kind envKind, List<Node> out, Set<String> seen) {
        for (Node c : n.getChildren()) {
            if (!c.isDirectory() && c.getData() != null && c.getData().length > 0
                    && !(envKind == Kind.ZIP && c.getPath() != null
                         && c.getPath().toUpperCase().startsWith("META-INF/"))
                    && signedCandidate(c.getData())
                    && seen.add(hex(DSSUtils.digest(DigestAlgorithm.SHA256, c.getData())))) {
                out.add(c);
            }
            collect(c, envKind, out, seen);
        }
    }

    private static boolean signedCandidate(byte[] d) {
        Kind k = ContainerInspector.detectKind(d);
        switch (k) {
            case PDF: return contains(d, "/ByteRange");
            // Časové razítko obalu samo (signers/1/ts1, EncapsulatedTimeStamp) prvkem obalu
            // není — kryje podpis, ne obsah. Pozná se podle typu obsahu id-ct-TSTInfo.
            case CMS: return !containsBytes(d, TST_INFO_OID);
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

    // ------------------------------------------------------------------ prvek

    private static void scanElement(File file, byte[] envBytes, Kind kind, Reports env,
                                    Reports envRelaxed, Node el) {
        byte[] data = el.getData();
        Reports own = validatorFactory.validate(new InMemoryDocument(data, el.getName()), null,
                verifier, null).reports();
        XmlDiagnosticData odd = own.getDiagnosticDataJaxb();
        if (odd.getSignatures().isEmpty() && odd.getUsedTimestamps().isEmpty()) {
            count("prvek: nic k ověření");
            return;
        }
        Date at = odd.getValidationDate();

        Cover cS = cover(env, kind, envBytes, data);
        Cover cR = cover(envRelaxed, kind, envBytes, data);

        String s0 = verdict(recompute(own, strict, at));
        String s1 = verdict(recompute(own, relaxed, at));
        String p0 = cS == null ? s0 : verdict(injected(own, env, cS, strict, at));
        String p1 = cR == null ? s1 : verdict(injected(own, envRelaxed, cR, relaxed, at));

        String c0 = change(s0, p0), c1 = change(s1, p1);
        count("prvek: " + kind + " — přísně: " + c0);
        count("prvek: " + kind + " — s ústupky: " + c1);
        if (cS == null && cR == null) count("prvek: krycí razítko obalu nenalezeno");

        System.out.println(String.format("%-11s %s :: %s", tag(c0, c1), file, el.getPath()));
        System.out.println("    krytí přísně:    " + (cS == null ? "—" : cS));
        System.out.println("    krytí s ústupky: " + (cR == null ? "—" : cR));
        System.out.println("    přísně     samostatně " + s0 + "   s důkazem " + p0);
        System.out.println("    s ústupky  samostatně " + s1 + "   s důkazem " + p1);

        // Který ústupek sám stačí, aby důkaz z obalu změnil závěr?
        if (!indications(p1).equals(indications(p0)) && !indications(p1).equals(indications(s1))) {
            List<String> enough = new ArrayList<>();
            for (Map.Entry<String, ValidationPolicy> e : singles.entrySet()) {
                Reports envOne = recompute(env, e.getValue(), env.getDiagnosticDataJaxb().getValidationDate());
                Cover c = cover(envOne, kind, envBytes, data);
                if (c == null) continue;
                String v = verdict(injected(own, envOne, c, e.getValue(), at));
                if (indications(v).equals(indications(p1))) enough.add(e.getKey());
            }
            String key = enough.isEmpty() ? "jen kombinace" : String.join(" | ", enough);
            count("rozhodující ústupek: " + key);
            System.out.println("    stačí ústupek:   " + key);
        }
    }

    private static String tag(String c0, String c1) {
        if (c0.startsWith("ZÁVĚR") || c1.startsWith("ZÁVĚR")) return "ZÁVĚR";
        if (c0.startsWith("POD") || c1.startsWith("POD")) return "POD-IND";
        return "BEZE ZMĚNY";
    }

    private static String change(String before, String after) {
        if (before.equals(after)) return "beze změny";
        if (indications(before).equals(indications(after))) return "POD-INDIKACE";
        return "ZÁVĚR";
    }

    private static String indications(String verdict) {
        return verdict.replaceAll("/[A-Z_]+", "");
    }

    /** Indikace podpisů (a razítek, nemá-li prvek podpis) v pořadí diagnostických dat. */
    private static String verdict(Reports r) {
        if (r == null) return "(přepočet selhal)";
        SimpleReport sr = r.getSimpleReport();
        XmlDiagnosticData dd = r.getDiagnosticDataJaxb();
        StringBuilder sb = new StringBuilder("[");
        if (!dd.getSignatures().isEmpty()) {
            for (XmlSignature s : dd.getSignatures()) part(sb, sr, s.getId());
        } else {
            for (XmlTimestamp t : dd.getUsedTimestamps()) part(sb, sr, t.getId());
        }
        return sb.append(']').toString();
    }

    private static void part(StringBuilder sb, SimpleReport sr, String id) {
        if (sb.length() > 1) sb.append(' ');
        Indication i = sr.getIndication(id);
        sb.append(i);
        if (sr.getSubIndication(id) != null) sb.append('/').append(sr.getSubIndication(id));
    }

    // ------------------------------------------------------------------ krytí

    record Cover(XmlTimestamp ts, Instant when, String how) {
        @Override public String toString() { return when + " (" + ts.getType() + ", " + how + ")"; }
    }

    private static Cover cover(Reports env, Kind kind, byte[] envBytes, byte[] data) {
        if (env == null) return null;
        DiagnosticData dd = env.getDiagnosticData();
        SimpleReport sr = env.getSimpleReport();
        Map<String, XmlTimestamp> jaxb = new HashMap<>();
        for (XmlTimestamp t : env.getDiagnosticDataJaxb().getUsedTimestamps()) jaxb.put(t.getId(), t);

        Cover best = null;
        for (TimestampWrapper ts : dd.getTimestampList()) {
            if (sr.getIndication(ts.getId()) != Indication.PASSED) continue;
            String how = null;
            for (SignerDataWrapper sd : ts.getTimestampedSignedData()) {
                if (matches(sd.getDigestAlgoAndValue(), data)) { how = "otisk podepsaných dat"; break; }
            }
            if (how == null) {
                for (SignatureWrapper s : ts.getTimestampedSignatures()) {
                    for (XmlDigestMatcher m : s.getDigestMatchers()) {
                        if (matches(m, data)) { how = "otisk v podpisu"; break; }
                    }
                    if (how != null) break;
                    if (kind == Kind.CMS || kind == Kind.ZFO || kind == Kind.XML || kind == Kind.JSON) {
                        how = "uvnitř podepsaného obsahu";
                        break;
                    }
                    if (kind == Kind.PDF && pdfRevisionHolds(envBytes, s.getPDFRevision() == null ? null
                            : s.getPDFRevision().getSignatureByteRange(), data)) {
                        how = "revize PDF s přílohou";
                        break;
                    }
                }
            }
            if (how == null && kind == Kind.PDF && ts.getPDFRevision() != null
                    && pdfRevisionHolds(envBytes, ts.getPDFRevision().getSignatureByteRange(), data)) {
                how = "revize PDF s přílohou (DocTimeStamp)";
            }
            if (how == null) continue;
            Instant when = ts.getProductionTime().toInstant();
            if (best == null || when.isBefore(best.when())) best = new Cover(jaxb.get(ts.getId()), when, how);
        }
        return best;
    }

    private static boolean matches(XmlDigestAlgoAndValue dv, byte[] data) {
        if (dv == null || dv.getDigestMethod() == null || dv.getDigestValue() == null) return false;
        try {
            return Arrays.equals(DSSUtils.digest(dv.getDigestMethod(), data), dv.getDigestValue());
        } catch (Exception e) {
            return false;
        }
    }

    /** Je v revizi PDF končící ByteRange podpisu příloha se stejným obsahem? */
    private static boolean pdfRevisionHolds(byte[] pdf, List<BigInteger> range, byte[] data) {
        if (range == null || range.size() != 4) return false;
        long end = range.get(2).longValue() + range.get(3).longValue();
        if (end <= 0 || end > pdf.length) return false;
        byte[] rev = Arrays.copyOf(pdf, (int) end);
        for (PdfAttachmentInspector.Embedded e : PdfAttachmentInspector.embeddedFiles(PdfSource.of(rev), null)) {
            if (Arrays.equals(e.data(), data)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ připsání

    private static Reports injected(Reports own, Reports env, Cover cover, ValidationPolicy policy, Date at) {
        XmlDiagnosticData odd = own.getDiagnosticDataJaxb();
        XmlDiagnosticData edd = env.getDiagnosticDataJaxb();
        Set<String> ids = new HashSet<>();
        odd.getUsedTimestamps().forEach(t -> ids.add(t.getId()));
        if (ids.contains(cover.ts().getId())) return null;

        List<XmlCertificate> addedCerts = new ArrayList<>();
        List<XmlRevocation> addedRevs = new ArrayList<>();
        List<XmlTimestamp> addedTs = new ArrayList<>();
        List<XmlFoundTimestamp> addedFound = new ArrayList<>();
        List<XmlTimestampedObject> addedObjects = new ArrayList<>();
        List<XmlSignature> touched = new ArrayList<>();
        Set<String> certIds = new HashSet<>();
        odd.getUsedCertificates().forEach(c -> certIds.add(c.getId()));
        Set<String> revIds = new HashSet<>();
        odd.getUsedRevocations().forEach(r -> revIds.add(r.getId()));
        List<XmlTimestamp> ownTimestamps = new ArrayList<>(odd.getUsedTimestamps());
        try {
            for (XmlCertificate c : edd.getUsedCertificates()) if (certIds.add(c.getId())) addedCerts.add(c);
            for (XmlRevocation r : edd.getUsedRevocations()) if (revIds.add(r.getId())) addedRevs.add(r);
            // Razítka obalu všechna: archivní drží v platnosti to krycí.
            for (XmlTimestamp t : edd.getUsedTimestamps()) if (ids.add(t.getId())) addedTs.add(t);
            odd.getUsedCertificates().addAll(addedCerts);
            odd.getUsedRevocations().addAll(addedRevs);
            odd.getUsedTimestamps().addAll(addedTs);
            for (XmlSignature s : odd.getSignatures()) {
                XmlFoundTimestamp ft = new XmlFoundTimestamp();
                ft.setTimestamp(cover.ts());
                s.getFoundTimestamps().add(ft);
                addedFound.add(ft);
                touched.add(s);
                XmlTimestampedObject o = new XmlTimestampedObject();
                o.setCategory(TimestampedObjectType.SIGNATURE);
                o.setToken(s);
                addedObjects.add(o);
            }
            for (XmlTimestamp t : ownTimestamps) {
                XmlTimestampedObject o = new XmlTimestampedObject();
                o.setCategory(TimestampedObjectType.TIMESTAMP);
                o.setToken(t);
                addedObjects.add(o);
            }
            cover.ts().getTimestampedObjects().addAll(addedObjects);
            return recompute(own, policy, at);
        } finally {
            cover.ts().getTimestampedObjects().removeAll(addedObjects);
            for (int i = 0; i < touched.size(); i++) touched.get(i).getFoundTimestamps().remove(addedFound.get(i));
            odd.getUsedTimestamps().removeAll(addedTs);
            odd.getUsedRevocations().removeAll(addedRevs);
            odd.getUsedCertificates().removeAll(addedCerts);
        }
    }

    private static Reports recompute(Reports r, ValidationPolicy policy, Date when) {
        try {
            DefaultSignatureProcessExecutor ex = new DefaultSignatureProcessExecutor();
            ex.setDiagnosticData(r.getDiagnosticDataJaxb());
            ex.setValidationPolicy(policy);
            ex.setCurrentTime(when);
            return ex.execute();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ drobnosti

    private static ValidationPolicy orDefault(ValidationPolicy p) throws Exception {
        if (p != null) return p;
        try (var is = ValidationPolicyFacade.class.getResourceAsStream("/policy/constraint.xml")) {
            return ValidationPolicyFacade.newFacade().getValidationPolicy(is);
        }
    }

    /** DER kódování OID 1.2.840.113549.1.9.16.1.4 (id-ct-TSTInfo). */
    private static final byte[] TST_INFO_OID = {0x06, 0x0B, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xF7, 0x0D, 0x01, 0x09, 0x10, 0x01, 0x04};

    private static boolean containsBytes(byte[] d, byte[] needle) {
        outer:
        for (int i = 0, n = Math.min(d.length - needle.length, 512); i <= n; i++) {
            for (int j = 0; j < needle.length; j++) if (d[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static boolean contains(byte[] d, String s) {
        return new String(d, StandardCharsets.ISO_8859_1).contains(s);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static synchronized void count(String key) {
        totals.merge(key, 1, Integer::sum);
    }

    private static ThreadFactory daemon() {
        return r -> { Thread t = new Thread(r); t.setDaemon(true); return t; };
    }
}
