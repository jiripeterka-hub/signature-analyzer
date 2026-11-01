package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Je program oficiálním sestavením AnEP? Řádek do souhrnu podpisu kódu v prohlížeči kontejnerů.
 * <p>
 * Zavedeno 1. 11. 2026 s prvním podepsaným vydáním (1.1.0). Instalátor může člověk dostat
 * odjinud než ze stránky aplikace a podpis kódu sám řekne jen „platný, podepsal X“ — že X je
 * autor AnEP, musí poznat ten, kdo X zná.
 * <p>
 * <b>Rozhoduje otisk certifikátu, ne jméno.</b> Předmět certifikátu Certum pro podpis kódu nese
 * jen jméno, obec a stát, žádný jedinečný identifikátor, a „Jiří Peterka“ není v Česku jméno
 * jediného člověka. Shoda jména a vydávající autority je proto jen náznak („?“): může jít
 * o obnovený certifikát autora, který tato verze AnEP ještě nezná, nebo o jmenovce. Otisk
 * SHA-1 se v tom případě ukáže, aby se dal porovnat se stránkou aplikace — Windows ukazují
 * tentýž („Kryptografický otisk“).
 * <p>
 * Za AnEP se soubor „vydává“ podle jména ({@code AnEP.exe}, {@code AnEP-1.1.0.msi}). Je to
 * slabé, ale přesně to je případ podvrženého instalátoru; u ostatních programů řádek nevzniká.
 */
public final class OfficialBuild {

    private OfficialBuild() {}

    public static final String KEY = "Sestavení AnEP";

    /**
     * SHA-256 certifikátů, kterými autor AnEP podepisuje. Při obnovení certifikátu sem přidat
     * nový, starý nechat — starší vydání jím zůstávají podepsaná.
     */
    static final Set<String> KNOWN_CERTIFICATES = Set.of(
            // Certum Code Signing 2021 CA, sériové číslo 3A890D47EC0F7F1C7962BA88A7EBA781,
            // platný 1. 11. 2026 – 1. 11. 2027; SHA-1 3AD5E8DE839EC03878458C8E4D2ED4F72376E6DC.
            "13930456cefa0384b6376360866f06df3e355c5fa58a52c16acddbe909f0a73c");

    static final String AUTHOR = "Jiří Peterka";
    private static final String PAGE = "https://bajecnysvet.cz/anep/";
    private static final Pattern ANEP_NAME =
            Pattern.compile("(?i)anep([-_ .(].*)?\\.(exe|msi)");

    /**
     * Posouzení podepsaného programu.
     *
     * @param signer   certifikát podepisujícího, nebo {@code null}, když v podpisu chybí
     * @param verdict  celkový verdikt podpisu kódu („✓ …“, „✗ …“, „? …“)
     * @param fileName jméno souboru, jak ho prohlížeč ukazuje; smí být {@code null}
     * @return řádek souhrnu, nebo {@code null}, když se program AnEP netýká
     */
    public static String assess(X509CertificateHolder signer, String verdict, String fileName) {
        boolean valid = verdict != null && verdict.startsWith("✓");
        boolean invalid = verdict != null && verdict.startsWith("✗");
        if (signer != null && isKnown(signer)) {
            if (valid) {
                return "✓ oficiální sestavení — podepsal autor AnEP " + AUTHOR
                        + " certifikátem, který tato verze AnEP zná";
            }
            if (invalid) {
                return "✗ nejde o neporušené oficiální sestavení — soubor je podepsán certifikátem"
                        + " autora AnEP, ale podpis neobstál";
            }
            return "? soubor je podepsán certifikátem autora AnEP, platnost podpisu se ale"
                    + " nepodařilo ověřit";
        }
        if (signer != null && valid && looksLikeAuthor(signer)) {
            return "? podepsal " + AUTHOR + " certifikátem, který tato verze AnEP nezná — může jít"
                    + " o obnovený certifikát autora, nebo o jinou osobu stejného jména. Otisk"
                    + " certifikátu (SHA-1 " + sha1(signer) + ") porovnejte s otiskem na stránce "
                    + PAGE;
        }
        if (!claimsAnep(fileName)) return null;
        if (invalid) {
            return "✗ nejde o oficiální sestavení AnEP — soubor se jmenuje jako AnEP, ale jeho"
                    + " podpis neobstál";
        }
        if (signer == null || looksLikeAuthor(signer)) {
            return "? soubor se jmenuje jako AnEP, ale nepodařilo se ověřit, že ho podepsal"
                    + " autor AnEP";
        }
        return "✗ nejde o oficiální sestavení AnEP — soubor se jmenuje jako AnEP, ale podepsal"
                + " ho „" + name(signer.getSubject(), BCStyle.CN) + "“, ne autor AnEP";
    }

    /** Posouzení nepodepsaného programu; {@code null}, když se nevydává za AnEP. */
    public static String assessUnsigned(String fileName) {
        if (!claimsAnep(fileName)) return null;
        return "✗ nejde o oficiální sestavení AnEP — soubor se jmenuje jako AnEP, ale není"
                + " podepsaný; oficiální sestavení od verze 1.1.0 podpis kódu nesou";
    }

    static boolean claimsAnep(String fileName) {
        if (fileName == null) return false;
        String base = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        return ANEP_NAME.matcher(base.trim()).matches();
    }

    static boolean isKnown(X509CertificateHolder cert) {
        try {
            return KNOWN_CERTIFICATES.contains(hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        } catch (Exception e) {
            return false;
        }
    }

    /** Jméno a stát autora, vydala autorita Certum pro podpis kódu. Jen náznak, viz javadoc třídy. */
    static boolean looksLikeAuthor(X509CertificateHolder cert) {
        X500Name s = cert.getSubject();
        String issuer = name(cert.getIssuer(), BCStyle.CN);
        return AUTHOR.equals(name(s, BCStyle.CN)) && AUTHOR.equals(name(s, BCStyle.O))
                && "CZ".equalsIgnoreCase(name(s, BCStyle.C))
                && issuer != null && issuer.startsWith("Certum Code Signing");
    }

    private static String name(X500Name n, ASN1ObjectIdentifier type) {
        RDN[] rdns = n.getRDNs(type);
        return rdns.length == 0 ? null : IETFUtils.valueToString(rdns[0].getFirst().getValue());
    }

    private static String sha1(X509CertificateHolder cert) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(cert.getEncoded())).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "?";
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
