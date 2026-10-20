package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import cz.digitalnikontinuita.anep.ui.AdvisorView;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon;
import eu.europa.esig.dss.diagnostic.CertificateWrapper;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.diagnostic.TimestampWrapper;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dopad zkrácení data ověřitelnosti podle ETSI TS 119 312 — kolik prvků by přišlo o kolik času.
 *
 * <p>Vznikla 5. 10. 2026 k otázce z osnovy politiky uchovávání: ETSI TS 119 511, OVR-6.4-07
 * žádá, aby se očekávaná doba trvání důkazu zakládala na odhadu vhodnosti kryptografických
 * algoritmů. Horizont AnEP se dnes počítá z certifikátů a dostupnosti revokačních údajů.
 * Sonda pro každý prvek najde <b>nejnovější razítko, které ho kryje</b> (u podpisu bez razítka
 * podpis sám), z jeho algoritmů odvodí konec vhodnosti podle tabulky výchozí politiky DSS 6.5
 * a porovná ho s horizontem AnEP i s tolerantním čtením. Navíc vypíše souhrn autorit razítek
 * (klíč, konec certifikátu, konec vhodnosti) — z něj je vidět, kam by se posunulo datum po
 * úkonu u autority, kterou uživatel používá.
 *
 * <pre>java -cp … CryptoHorizonScan &lt;výstup.tsv&gt; &lt;složka&gt;…</pre>
 */
public final class CryptoHorizonScan {

    private static final List<String> EXT = List.of(".pdf", ".p7s", ".p7m", ".asice", ".asics",
            ".sce", ".scs", ".xml", ".json", ".zfo");

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<Path> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            try (var walk = Files.walk(Path.of(args[i]))) {
                walk.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return EXT.stream().anyMatch(n::endsWith);
                }).sorted().forEach(files::add);
            }
        }
        AppSettings settings = AppSettings.load();
        TrustConfig trust = new TrustConfig(settings);
        CommonCertificateVerifier verifier = trust.buildLotlCertificateVerifier();
        ValidatorFactory factory = new ValidatorFactory(settings);
        DocumentLoader loader = new DocumentLoader();
        ReportMapper mapper = new ReportMapper();
        ContinuityHorizon.Run run = new ContinuityHorizon.Run(true, List.of(), null);
        Instant now = Instant.now();

        // Autorita razítek → řádek souhrnu. Klíčem je certifikát, ne jméno: táž autorita
        // vystřídá klíče různé délky.
        Map<String, String> tsaSummary = new TreeMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("soubor\tprvek\tdruh\tverdikt\tanep\tanepDo\ttolerant\ttolerantDo"
                    + "\tkryje\talgoritmy\tkryptoDo\tzkraceniAnepDni\tzkraceniTolerantDni");
            System.out.println("### souboru: " + files.size());
            int done = 0;
            for (Path p : files) {
                ValidationResult result;
                DiagnosticData dd;
                try {
                    DSSDocument doc = loader.load(p.toFile());
                    var outcome = factory.validate(doc, null, verifier, null);
                    dd = outcome.reports().getDiagnosticData();
                    result = mapper.map(p.getFileName().toString(), null, outcome, settings);
                } catch (Exception | StackOverflowError e) {
                    w.println(p + "\t-\tSKIP\t" + e);
                    if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
                    continue;
                }
                for (TimestampWrapper ts : dd.getTimestampList()) {
                    // Obě hašovací funkce razítka: podpisu tokenu (volí autorita) a otisku
                    // orazítkovaných dat (volí žadatel). Doplněno 5. 10. 2026 — hledaly se
                    // příklady, kdy se liší, a otisky SHA-1.
                    DigestAlgorithm imprintAlg = ts.getMessageImprint() != null
                            ? ts.getMessageImprint().getDigestMethod() : null;
                    String pair = ts.getDigestAlgorithm() + " / " + imprintAlg;
                    counts.merge("razitko podpis/otisk " + pair, 1, Integer::sum);
                    if (ts.getDigestAlgorithm() != imprintAlg
                            || imprintAlg == DigestAlgorithm.SHA1) {
                        CertificateWrapper ic = ts.getSigningCertificate();
                        w.println("#RAZITKO\t" + p + "\t" + ts.getType()
                                + "\t" + (ts.getProductionTime() != null
                                        ? ts.getProductionTime().toInstant() : "-")
                                + "\t" + (ic != null ? ic.getCommonName() : "?")
                                + "\tpodpis=" + ts.getDigestAlgorithm()
                                + "\totisk=" + imprintAlg);
                    }
                    CertificateWrapper c = ts.getSigningCertificate();
                    if (c == null) continue;
                    Instant until = tokenUntil(ts.getDigestAlgorithm(), ts.getEncryptionAlgorithm(),
                            ts.getKeyLengthUsedToSignThisToken(),
                            ts.getMessageImprint() != null
                                    ? ts.getMessageImprint().getDigestMethod() : null);
                    tsaSummary.putIfAbsent(c.getId(), c.getCommonName()
                            + "\t" + ts.getEncryptionAlgorithm() + "-"
                            + ts.getKeyLengthUsedToSignThisToken()
                            + "\t" + ts.getDigestAlgorithm()
                            + "\tcertDo=" + day(c.getNotAfter() != null
                                    ? c.getNotAfter().toInstant() : null)
                            + "\tkryptoDo=" + day(until));
                }
                for (SignatureSummary sig : result.getSignatures()) {
                    String verdict = AdvisorView.displayedIndication(sig);
                    ContinuityHorizon.Horizon anep = ContinuityHorizon.anep(sig, verdict, run);
                    ContinuityHorizon.Horizon tol = ContinuityHorizon.tolerant(sig, verdict);
                    String id = sig.getSignatureId();
                    TimestampWrapper cover = newestCover(dd, id);
                    Instant crypto;
                    String algos;
                    String coverDesc;
                    if (cover != null) {
                        crypto = tokenUntil(cover.getDigestAlgorithm(),
                                cover.getEncryptionAlgorithm(),
                                cover.getKeyLengthUsedToSignThisToken(),
                                cover.getMessageImprint() != null
                                        ? cover.getMessageImprint().getDigestMethod() : null);
                        algos = cover.getEncryptionAlgorithm() + "-"
                                + cover.getKeyLengthUsedToSignThisToken() + "/"
                                + cover.getDigestAlgorithm();
                        coverDesc = "razitko " + day(cover.getProductionTime().toInstant());
                    } else {
                        SignatureWrapper sw = dd.getSignatureById(id);
                        if (sw == null) {
                            crypto = null;
                            algos = "?";
                            coverDesc = "nic";
                        } else {
                            crypto = tokenUntil(sw.getDigestAlgorithm(),
                                    sw.getEncryptionAlgorithm(),
                                    sw.getKeyLengthUsedToSignThisToken(), null);
                            algos = sw.getEncryptionAlgorithm() + "-"
                                    + sw.getKeyLengthUsedToSignThisToken() + "/"
                                    + sw.getDigestAlgorithm();
                            coverDesc = "podpis sam";
                        }
                    }
                    long cutAnep = cut(anep, crypto);
                    long cutTol = cut(tol, crypto);
                    w.println(p + "\t" + id + "\t" + sig.getFormat() + "\t" + verdict
                            + "\t" + anep.kind() + "\t" + day(anep.when())
                            + "\t" + tol.kind() + "\t" + day(tol.when())
                            + "\t" + coverDesc + "\t" + algos + "\t" + day(crypto)
                            + "\t" + cutAnep + "\t" + cutTol);
                    counts.merge("prvku", 1, Integer::sum);
                    counts.merge("anep " + bucket(anep, crypto, now), 1, Integer::sum);
                    counts.merge("tolerant " + bucket(tol, crypto, now), 1, Integer::sum);
                }
                w.flush();
                if (++done % 25 == 0) System.out.println("### " + done + "/" + files.size());
            }
            w.println();
            w.println("# autority razitek (certifikat, klic, otisk, konec certifikatu, konec vhodnosti)");
            tsaSummary.values().stream().sorted().forEach(s -> w.println("#TSA\t" + s));
            w.println();
            counts.forEach((k, v) -> w.println("#POCET\t" + k + "\t" + v));
        }
        System.out.println("### hotovo");
    }

    /**
     * Nejnovější razítko, které prvek kryje. U podpisu razítka nad ním (podpisová, archivní,
     * dokumentová); u razítka pozdější razítka, která ho zahrnují. Nekryje-li nic, u razítka se
     * vrátí ono samo — jeho vlastní algoritmy jsou to, co drží.
     */
    private static TimestampWrapper newestCover(DiagnosticData dd, String id) {
        List<TimestampWrapper> covering = new ArrayList<>();
        SignatureWrapper sw = dd.getSignatureById(id);
        if (sw != null) {
            covering.addAll(sw.getTimestampList());
        } else {
            TimestampWrapper self = dd.getTimestampById(id);
            if (self == null) return null;
            covering.add(self);
            for (TimestampWrapper t : dd.getTimestampList()) {
                if (t.getTimestampedTimestamps().stream().anyMatch(x -> x.getId().equals(id))) {
                    covering.add(t);
                }
            }
        }
        return covering.stream().max(Comparator.comparing(TimestampWrapper::getProductionTime))
                .orElse(null);
    }

    /**
     * Konec vhodnosti tokenu: nejdřívější z konců pro algoritmus otisku podpisu tokenu,
     * pro podpisový algoritmus s danou délkou klíče a pro otisk razítkovaných dat.
     * {@code null} = bez omezení.
     */
    static Instant tokenUntil(DigestAlgorithm digest, EncryptionAlgorithm enc, String keyLength,
                              DigestAlgorithm imprint) {
        Instant a = digestUntil(digest);
        Instant b = keyUntil(enc, keyLength);
        Instant c = digestUntil(imprint);
        Instant min = null;
        for (Instant x : new Instant[] { a, b, c }) {
            if (x != null && (min == null || x.isBefore(min))) min = x;
        }
        return min;
    }

    // Tabulka výchozí politiky DSS 6.5 (policy/constraint.xml, AlgoExpirationDate) —
    // táž, kterou cituje politika ověřování v oddílu 10.
    private static Instant digestUntil(DigestAlgorithm d) {
        if (d == null) return null;
        return switch (d) {
            case MD5 -> date("2004-08-01");
            case SHA1 -> date("2012-08-01");
            case SHA224 -> date("2029-01-01");
            case RIPEMD160 -> date("2014-08-01");
            case WHIRLPOOL -> date("2020-12-01");
            default -> null;
        };
    }

    private static Instant keyUntil(EncryptionAlgorithm enc, String keyLength) {
        if (enc == null) return null;
        int len;
        try {
            len = Integer.parseInt(keyLength);
        } catch (Exception e) {
            return null;
        }
        String name = enc.name();
        if (name.equals("RSA") || name.equals("RSASSA_PSS")) {
            if (len < 1024) return date("2010-08-01");
            if (len < 1900) return date("2019-10-01");
            if (len < 3000) return date("2027-01-01");
            return name.equals("RSA") ? date("2035-01-01") : null;
        }
        if (name.equals("DSA")) {
            if (len < 1900) return date("2015-12-01");
            if (len < 3000) return date("2027-01-01");
            return null;
        }
        if (name.equals("ECDSA") || name.equals("PLAIN_ECDSA")) {
            if (len < 224) return date("2012-08-01");
            if (len < 256) return date("2021-10-01");
            return null;
        }
        return null;
    }

    private static long cut(ContinuityHorizon.Horizon h, Instant crypto) {
        if (crypto == null || h.when() == null) return 0;
        if (!crypto.isBefore(h.when())) return 0;
        return ChronoUnit.DAYS.between(crypto, h.when());
    }

    /** Do které skupiny prvek patří: zda se horizont zkrátí, a zda by tím už byl za dneškem. */
    private static String bucket(ContinuityHorizon.Horizon h, Instant crypto, Instant now) {
        if (h.when() == null) {
            return crypto == null ? h.kind() + " bez data, krypto bez omezeni"
                    : h.kind() + " bez data, krypto do " + day(crypto);
        }
        if (crypto == null || !crypto.isBefore(h.when())) return "beze zmeny";
        return crypto.isBefore(now) ? "zkraceno do minulosti" : "zkraceno na " + day(crypto);
    }

    private static Instant date(String s) {
        return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String day(Instant t) {
        return t == null ? "-" : t.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
