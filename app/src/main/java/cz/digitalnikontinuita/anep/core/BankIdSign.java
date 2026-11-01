package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Zaručené elektronické podpisy od služby BankID Sign (Bankovní identita, a.s.).
 *
 * <p>Služba podepisuje PDF certifikátem, který vydává její vlastní, nekvalifikovaná autorita
 * {@code CN=BankID Document CA} pod kořenem {@code CN=BankID Document Root CA} — obě s
 * {@code O=Bankovni identita, a.s.}. Ani jedna není v důvěryhodných seznamech EU, takže bez
 * zásahu uživatele končí ověření neznámou platností (důvod 3). Na rozdíl od Podepsat.online
 * služba identitu ověřuje: podepisující se přihlásí bankovní identitou své banky a ta stojí
 * v položce O předmětu podpisového certifikátu. Vedle ní je tam identifikátor osoby
 * ({@link #PERSON_ID_OID}), který přiděluje BankID zvlášť pro každou banku.
 *
 * <p>Kořen se přibaluje, ale <b>do úložiště se nevkládá sám</b> — jen tlačítkem v okně
 * Vlastní důvěryhodné certifikáty (pokyn uživatele 1. 11. 2026), stejně jako u
 * {@link PodepsatOnline}.
 *
 * <p>Certifikát kořene (platnost 7. 10. 2021 – 2. 10. 2041, self-signed, {@code CA:TRUE}) je
 * vytažený 1. 11. 2026 přímo z podpisů služby: CMS ho nese spolu s vydávající autoritou
 * {@code BankID Document CA} (platnost do 9. 1. 2029). Ve třech podpisech z 31. 10. 2026
 * (přes dvě různé banky) je týž, a řetěz kořen → vydávající autorita → podpisový certifikát se
 * ověří ({@code openssl verify -no_check_time}).
 */
public final class BankIdSign {

    /** Identifikátor osoby u konkrétní banky — v aplikaci „ID osoby“. */
    public static final String PERSON_ID_OID = "1.3.6.1.4.1.58356.3";

    private static final String ROOT_RESOURCE = "/certs/bankid/document-root-ca.cer";

    /** SHA-256 přibaleného kořene; test jím hlídá, že se soubor v balíku nezměnil. */
    public static final String ROOT_SHA256 =
            "685999875324c8cf16dbc814600f9581e8a17796d8463207d793b743cf051655";

    private BankIdSign() {
    }

    /**
     * Pochází podpis od služby BankID Sign? Rozhoduje vydavatel podpisového certifikátu —
     * autorita {@code BankID Document CA} společnosti Bankovní identita. Znění /Reason
     * („Podpis Bank iD“) se nevyžaduje: autorita slouží jen podpisům dokumentů téže služby.
     */
    public static boolean isServiceSignature(String issuerDn) {
        if (issuerDn == null) return false;
        String dn = issuerDn.toLowerCase(java.util.Locale.ROOT);
        return dn.contains("cn=bankid document ca") && dn.contains("bankovni identita");
    }

    /** Přibalený kořen služby, nebo {@code null}, nepodaří-li se ho načíst. */
    public static CertificateToken loadRoot() {
        try (java.io.InputStream in = BankIdSign.class.getResourceAsStream(ROOT_RESOURCE)) {
            if (in == null) {
                System.err.println("[BankIdSign] Bundled certificate resource not found: "
                        + ROOT_RESOURCE);
                return null;
            }
            X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            return new CertificateToken(x509);
        } catch (Exception e) {
            System.err.println("[BankIdSign] Cannot load bundled certificate: " + e.getMessage());
            return null;
        }
    }
}
