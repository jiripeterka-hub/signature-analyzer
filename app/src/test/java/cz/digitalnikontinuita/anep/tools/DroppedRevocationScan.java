package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.DocumentLoader;
import cz.digitalnikontinuita.anep.core.ReportMapper;
import cz.digitalnikontinuita.anep.core.TrustConfig;
import cz.digitalnikontinuita.anep.core.ValidatorFactory;
import cz.digitalnikontinuita.anep.core.ValidatorFactory.ValidationOutcome;
import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.ValidationResult;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.RevocationWrapper;
import eu.europa.esig.dss.enumerations.CertificateStatus;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.Revocation;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.spi.x509.revocation.RevocationSource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Kolik revokačního materiálu AnEP při ověřování stáhne a zahodí — a mohlo by ho tedy vložit
 * do přerazítkovaného souboru.
 *
 * <p><b>Proč to měřit.</b> {@code RestampingService} vkládá do dokumentu všechno, co ověřování
 * vidělo, „with no usability filter of any kind". Jenže odpověď, kterou DSS odmítne jako
 * nekonzistentní — vydanou po expiraci certifikátu nebo vydavatelem, jemuž mezitím vypršel
 * certifikát — k certifikátu v {@code DiagnosticData} vůbec nepřipojí. Do přerazítkování se tak
 * nedostane a nikdo její bajty nedrží: {@code SkippedRevocation} si pamatuje jen status a data.
 * Otázka zní, jestli je toho dost na to, aby stálo za to ty bajty zachytit.
 *
 * <p><b>Jak to sonda dělá.</b> Obalí on-line zdroj CRL i OCSP odbočkou, která si každou odpověď
 * uloží, <em>než</em> na ni sáhnou filtry AnEP. Obalení musí proběhnout před prvním ověřením:
 * {@code ValidatorFactory} si zdroj při prvním běhu zapamatuje jako základ a od té doby ho každé
 * kolo obnovuje — takže odbočka se stane tím základem a přežije všechna kola.
 *
 * <p>Zahozený je pak ten záznam, jehož bajty se v {@code DiagnosticData} po ověření nenajdou.
 * Sonda je vypíše roztříděné podle důvodu (po expiraci / expirovaný vydavatel) a podle stavu,
 * protože ty dvě skupiny se posuzují různě: post-expirační REVOKED dokládá pořád totéž co v den
 * vydání, kdežto post-expirační GOOD může znamenat i „záznam už jsme smazali" (RFC 5280 §3.3).
 *
 * <p><b>Co ukázala.</b> Sbírka {@code D:\tmp}, 214 PDF, 10. 9. 2026, přísný režim. Materiál se
 * zahazuje u 52 souborů, ale na těch, kam přerazítkování vůbec dosáhne (situace 32 a k tomu 33
 * nebo 45), je jich jedenáct a získaly by 9× CRL GOOD, 1× OCSP GOOD a 2× OCSP UNKNOWN.
 * <b>Ani jeden záznam REVOKED</b> — a to není náhoda sbírky, nýbrž stavba věci: post-expirační
 * REVOKED dokládá pořád totéž co v den vydání, takže ho DSS i filtry AnEP propouštějí a do
 * dokumentu se vkládá už dnes. Zahazuje se právě to, co nic nedokládá.
 *
 * <p>U {@code kvalifikovany_po_revokaci s protokolem.pdf} by vložení přímo škodilo: dokument nese
 * OCSP odpověď REVOKED z 3. 12. 2022 (autoritativní, důvod 22), kdežto dnes stažený seznam téže
 * autority ten certifikát už nejmenuje a hlásí GOOD. Vložit ho by znamenalo přiložit k dokumentu
 * tvrzení, které jeho vlastní doklad vyvrací — a stojí to 854 kB.
 *
 * <p><b>Čeho se měření nedotklo.</b> Všechen zahozený materiál je od českých autorit. Právě proto
 * sbírka nemůže odpovědět na otázku, kvůli které sonda vznikla: jestli by vkládání pomohlo tam,
 * kde validátor typu CzechPOINTu revokační údaj sám nemá, tedy u zahraniční autority. K tomu je
 * potřeba zahraniční příklad, ne další kolo nad touhle sbírkou.
 *
 * <pre>
 *   java -cp ... cz.digitalnikontinuita.anep.tools.DroppedRevocationScan "D:\tmp" [-limit 50]
 * </pre>
 */
public final class DroppedRevocationScan {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd");

    /** Jedna odpověď tak, jak ji odbočka viděla. */
    private record Rec(String kind, String certSubject, Date certNotAfter,
                       Date thisUpdate, CertificateStatus status, Date issuerNotAfter,
                       String digest, int size) {

        /** Proč by ji standardní validátor nepoužil — nebo null, když by ji použil. */
        String duvod() {
            boolean postExpiry = certNotAfter != null && thisUpdate != null
                    && thisUpdate.after(certNotAfter);
            boolean deadIssuer = issuerNotAfter != null && issuerNotAfter.before(new Date());
            if (postExpiry && deadIssuer) return "po expiraci + expirovaný vydavatel";
            if (postExpiry) return "po expiraci";
            if (deadIssuer) return "expirovaný vydavatel";
            return null;
        }
    }

    /** Zdroj, který pustí dotaz dál a odpověď si opíše. */
    private record Tee<R extends Revocation>(String kind, RevocationSource<R> delegate,
                                             List<Rec> sink) implements RevocationSource<R> {

        @Override
        public RevocationToken<R> getRevocationToken(CertificateToken cert, CertificateToken issuer) {
            RevocationToken<R> token;
            try {
                token = delegate.getRevocationToken(cert, issuer);
            } catch (Exception e) {
                return null;
            }
            if (token != null) {
                CertificateToken respIssuer = token.getIssuerCertificateToken();
                byte[] der = null;
                try {
                    der = token.getEncoded();
                } catch (Exception ignored) {
                    // Sonda smí přijít o jeden záznam; přerušit kvůli tomu celý sken by bylo horší.
                }
                sink.add(new Rec(kind, shortDn(cert), cert.getNotAfter(),
                        token.getThisUpdate(), token.getStatus(),
                        respIssuer != null ? respIssuer.getNotAfter() : null,
                        der != null ? sha256(der) : "?", der != null ? der.length : 0));
            }
            return token;
        }
    }

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        int limit = Integer.MAX_VALUE;
        for (int i = 0; i < args.length; i++) {
            if ("-limit".equals(args[i]) && i + 1 < args.length) {
                limit = Integer.parseInt(args[++i]);
                continue;
            }
            Path p = Path.of(args[i]);
            if (Files.isDirectory(p)) {
                try (var walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT)
                                    .endsWith(".pdf"))
                            .forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        if (files.isEmpty()) {
            System.err.println("usage: DroppedRevocationScan <složka|soubor>... [-limit N]");
            return;
        }
        if (files.size() > limit) files = files.subList(0, limit);

        TrustConfig trustConfig = new TrustConfig(AppSettings.load());
        CommonCertificateVerifier verifier;
        try {
            verifier = trustConfig.buildLotlCertificateVerifier();
            System.out.println("Důvěryhodné seznamy: načteny");
        } catch (Exception e) {
            verifier = trustConfig.buildOfflineCertificateVerifier();
            System.out.println("Důvěryhodné seznamy: NEDOSTUPNÉ (" + e + ")");
        }

        // Odbočka se musí nasadit teď, před prvním ověřením: ValidatorFactory si zdroj zapamatuje
        // jako základ a od té doby ho každé kolo obnovuje z paměti.
        List<Rec> sink = new ArrayList<>();
        verifier.setOcspSource(new Tee<>("OCSP", verifier.getOcspSource(), sink));
        verifier.setCrlSource(new Tee<>("CRL", verifier.getCrlSource(), sink));

        int souboru = 0;
        int souboruSeZahozenym = 0;
        int souboruSeZahozenymAS45 = 0;
        int selhalo = 0;
        TreeMap<String, Integer> podleDuvodu = new TreeMap<>();
        TreeMap<String, Integer> podleStavu = new TreeMap<>();
        long bajtu = 0;
        Set<String> jedinecne = new HashSet<>();
        long bajtuJedinecnych = 0;

        System.out.println("Souborů ke skenu: " + files.size());
        System.out.println();

        for (Path file : files) {
            souboru++;
            sink.clear();

            AppSettings settings = AppSettings.load();
            settings.setAcceptNewerOcsp(false);
            settings.setAcceptExpiredRevocationSources(false);
            settings.setAcceptPdfNotEtsi(false);
            settings.setUseCustomValidationTime(false);

            List<Rec> zahozene;
            String situace;
            String verdikt;
            try {
                DSSDocument doc = new DocumentLoader().load(file.toFile());
                ValidationOutcome outcome = new ValidatorFactory(settings)
                        .validate(doc, null, verifier, null);
                ValidationResult result = new ReportMapper()
                        .map(file.getFileName().toString(), null, outcome, settings);
                DiagnosticData dd = outcome.reports().getDiagnosticData();

                // Co by přerazítkování vložilo i bez odbočky: materiál z DiagnosticData a k tomu
                // CRL, které si stáhla chůze pro situace 33 a 45. Bez toho druhého by sonda za
                // ztrátu vydávala i seznamy, které se do souboru dostanou tak jako tak — a právě
                // tou cestou se tam post-expirační CRL dnes dostávají.
                Set<String> jizVkladane = new HashSet<>();
                for (RevocationWrapper rev : dd.getAllRevocationData()) {
                    byte[] bin = rev.getBinaries();
                    if (bin != null) jizVkladane.add(sha256(bin));
                }
                for (SignatureSummary sig : result.getSignatures()) {
                    for (byte[] der : sig.getS33CrlBytes()) jizVkladane.add(sha256(der));
                    for (byte[] der : sig.getS45CrlBytes()) jizVkladane.add(sha256(der));
                }

                Set<String> videne = new HashSet<>();
                zahozene = new ArrayList<>();
                for (Rec rec : sink) {
                    if (!videne.add(rec.digest())) continue;              // táž odpověď podruhé
                    if (jizVkladane.contains(rec.digest())) continue;     // do souboru se dostane
                    zahozene.add(rec);
                }

                Set<Integer> vsechny = new java.util.TreeSet<>();
                StringBuilder ind = new StringBuilder();
                for (SignatureSummary sig : result.getSignatures()) {
                    vsechny.addAll(sig.getDetectedSituations());
                    if (ind.length() > 0) ind.append(",");
                    ind.append(sig.getIndication());
                }
                situace = (vsechny.contains(45) ? "S45 " : "")
                        + (vsechny.contains(33) ? "S33 " : "")
                        + (vsechny.contains(31) ? "S31 " : "")
                        + (vsechny.contains(32) ? "S32 " : "");
                verdikt = ind.toString();
            } catch (Exception e) {
                selhalo++;
                System.out.println("  SELHALO  " + file.getFileName() + "  (" + e + ")");
                continue;
            }

            if (zahozene.isEmpty()) continue;

            souboruSeZahozenym++;
            if (situace.contains("S45")) souboruSeZahozenymAS45++;

            System.out.println(file.getFileName());
            System.out.println("    " + verdikt + "   " + situace);
            for (Rec rec : zahozene) {
                String duvod = rec.duvod();
                podleDuvodu.merge(duvod == null ? "jiné" : duvod, 1, Integer::sum);
                podleStavu.merge(rec.kind() + " " + rec.status(), 1, Integer::sum);
                bajtu += rec.size();
                if (jedinecne.add(rec.digest())) bajtuJedinecnych += rec.size();
                System.out.println("    zahozeno " + rec.kind()
                        + "  " + rec.status()
                        + "  thisUpdate=" + fmt(rec.thisUpdate())
                        + "  certDo=" + fmt(rec.certNotAfter())
                        + "  vydavatelDo=" + fmt(rec.issuerNotAfter())
                        + "  " + rec.size() + " B"
                        + "  [" + (duvod == null ? "jiné" : duvod) + "]"
                        + "  " + rec.certSubject());
            }
        }

        System.out.println();
        System.out.println("═══════════════ souhrn ═══════════════");
        System.out.println("souborů ověřeno:                " + (souboru - selhalo));
        System.out.println("souborů selhalo:                " + selhalo);
        System.out.println("souborů se zahozeným materiálem: " + souboruSeZahozenym);
        System.out.println("   z toho se situací 45:         " + souboruSeZahozenymAS45);
        System.out.println("zahozených záznamů celkem:      "
                + podleDuvodu.values().stream().mapToInt(Integer::intValue).sum());
        System.out.println("jejich objem (se opakováním):   " + (bajtu / 1024) + " kB");
        System.out.println("jedinečných záznamů:            " + jedinecne.size()
                + "  (" + (bajtuJedinecnych / 1024) + " kB)");
        System.out.println("podle důvodu:");
        podleDuvodu.forEach((k, v) -> System.out.println("   " + v + "×  " + k));
        System.out.println("podle druhu a stavu:");
        podleStavu.forEach((k, v) -> System.out.println("   " + v + "×  " + k));
    }

    private static String fmt(Date d) {
        return d == null ? "—" : FMT.format(d);
    }

    private static String shortDn(CertificateToken cert) {
        String dn = cert.getSubject().getRFC2253();
        int cn = dn.indexOf("CN=");
        if (cn < 0) return dn.length() > 50 ? dn.substring(0, 50) : dn;
        int end = dn.indexOf(',', cn);
        return end < 0 ? dn.substring(cn) : dn.substring(cn, end);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DroppedRevocationScan() {}
}
