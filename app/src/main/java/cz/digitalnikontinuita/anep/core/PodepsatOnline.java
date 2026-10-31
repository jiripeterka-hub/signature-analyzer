package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zaručené elektronické podpisy od služby Podepsat.online (https://www.podepsat.online/).
 *
 * <p>Služba podepisuje PDF jednorázovým certifikátem (v předmětu {@code OU=One Time Signing}),
 * který vydává vlastní, nekvalifikovaná autorita provozovatele — {@code CN=SignMaster CA},
 * {@code O=Software602 a.s.}. Ta není v důvěryhodných seznamech EU, takže bez zásahu uživatele
 * končí ověření neznámou platností (důvod 3). Identita podepsaného je přitom jen deklarovaná:
 * uživatel ji vypíše do formuláře a služba ji „ověří“ zprávou na zadaný e-mail a kódem v SMS
 * na zadané číslo. Obojí zapisuje do položky /Reason podpisu, odkud ji karta Analýzy čte.
 *
 * <p>Kořen se proto přibaluje, ale <b>do úložiště se nevkládá sám</b> — jen tlačítkem v okně
 * Vlastní důvěryhodné certifikáty (pokyn uživatele 31. 10. 2026). Důvěřovat službě, která
 * identitu neověřuje, je rozhodnutí čtenáře, ne aplikace.
 *
 * <p>Certifikát kořene ({@code serial 0602}, platnost 8. 8. 2014 – 31. 12. 2037, self-signed,
 * {@code CA:TRUE, pathlen:1}) je stažený 31. 10. 2026 z adresy v AIA podpisového certifikátu
 * ({@code http://www.secustamp.eu/tf/ca/SignMasterCA.crt}); druhá adresa z AIA
 * ({@code www.602.eu}) jen přesměrovává. Podpisový certifikát vzorového dokumentu se proti němu
 * ověří ({@code openssl verify -no_check_time}) a jeho vlastní podpis rovněž.
 */
public final class PodepsatOnline {

    /** Odkaz na službu, jak ho uvádějí texty karty. */
    public static final String URL = "https://www.podepsat.online/";

    private static final String ROOT_RESOURCE = "/certs/podepsat-online/signmaster-ca.cer";

    /** SHA-256 přibaleného kořene; test jím hlídá, že se soubor v balíku nezměnil. */
    public static final String ROOT_SHA256 =
            "2fe2d03ef6d2d12a6e0d003a3a55b084336034d5b4a43938145323db1df486fa";

    /**
     * Znění, které služba zapisuje do /Reason: „Identita uživatele ověřena prostřednictvím emailu
     * … a telefonu …“. Naměřeno na vzorovém dokumentu služby z 31. 10. 2026. Mezery a tečka na
     * konci volně — služba může znění drobně upravit, a e-mail i číslo jsou to, oč tu jde.
     */
    private static final Pattern REASON = Pattern.compile(
            "ověřena\\s+prostřednictvím\\s+e-?mailu\\s+(\\S+?)\\s+a\\s+telefonu\\s+(\\S+?)\\.?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** E-mail a telefon, na které služba poslala ověřovací zprávy; kterýkoli může chybět. */
    public record Contact(String email, String phone) {}

    private PodepsatOnline() {
    }

    /**
     * Pochází podpis od služby Podepsat.online?
     *
     * <p>Rozhoduje vydavatel podpisového certifikátu — SignMaster CA od Software602 — a znění
     * /Reason, které služba píše. Sám vydavatel nestačí: SignMaster CA je autorita společnosti
     * Software602, ne jen této služby, a věta o ověření e-mailem a SMS by se o podpisu z jiné
     * její služby tvrdila bez podkladu.
     */
    public static boolean isServiceSignature(String issuerDn, String pdfReason) {
        return isServiceIssuer(issuerDn) && pdfReason != null && REASON.matcher(pdfReason).find();
    }

    /** Vydala certifikát autorita SignMaster CA společnosti Software602? */
    static boolean isServiceIssuer(String issuerDn) {
        if (issuerDn == null) return false;
        String dn = issuerDn.toLowerCase(java.util.Locale.ROOT);
        return dn.contains("cn=signmaster ca") && dn.contains("software602");
    }

    /** E-mail a telefon z /Reason, nebo {@code null}, když znění službě neodpovídá. */
    public static Contact contact(String pdfReason) {
        if (pdfReason == null) return null;
        Matcher m = REASON.matcher(pdfReason.strip());
        return m.find() ? new Contact(m.group(1), m.group(2)) : null;
    }

    /** Přibalený kořen služby, nebo {@code null}, nepodaří-li se ho načíst. */
    public static CertificateToken loadRoot() {
        try (java.io.InputStream in = PodepsatOnline.class.getResourceAsStream(ROOT_RESOURCE)) {
            if (in == null) {
                System.err.println("[PodepsatOnline] Bundled certificate resource not found: "
                        + ROOT_RESOURCE);
                return null;
            }
            X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            return new CertificateToken(x509);
        } catch (Exception e) {
            System.err.println("[PodepsatOnline] Cannot load bundled certificate: " + e.getMessage());
            return null;
        }
    }
}
