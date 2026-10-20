package cz.digitalnikontinuita.anep.core;

import eu.europa.esig.dss.model.x509.CertificateToken;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Bundled certificates of the five Czech certification authorities that also issue
 * COMMERCIAL (non-qualified) certificates outside the EU Trusted Lists scope: I.CA,
 * PostSignum, eIdentity, SSSVD/NCA (Správa státních služeb vytvářejících důvěru — the
 * state's own National Certification Authority) and Komerční banka. Only QUALIFIED services
 * are part of EUTL/LOTL — commercial certificate lines sit entirely outside it, so DSS
 * has no built-in way to trust them.
 *
 * <p>Do {@link CustomTrustStore} se na jedno stisknutí vkládají <b>vydávající (mezilehlé)
 * autority</b>, ne kořeny (Nastavení → Vlastní důvěryhodné certifikáty → "Vložit certifikáty
 * komerčních autorit"). Kotva důvěry nemusí být self-signed: DSS ukončí řetěz u kteréhokoli
 * certifikátu, který má v důvěryhodných zdrojích, takže připnutá vydávající autorita stačí.
 * Změřeno 29. 8. 2026 na {@code PAdES LT zaruceny.pdf} — s připnutým kořenem i s připnutou
 * autoritou {@code I.CA Public CA/RSA 07/2015} vyjde týž závěr, jen řetěz končí o patro níž.</p>
 *
 * <p>Proč vydávající, a ne kořen. Za prvé záběr: kořen kryje všechno, co pod ním kdy vznikne,
 * kdežto vydávající autorita jen to, co vydala ona — to je vědomé rozhodnutí komu důvěřovat.
 * Za druhé přesnost hlášení: kořen připnutý nad kotvou, kterou nese důvěryhodný seznam, se
 * ověření vůbec neúčastní, a přesto o něm aplikace musela rozhodovat, jestli ho zmínit.</p>
 *
 * <p>Cenou je, že se u připnuté autority přestane zjišťovat revokace — kotva se na ni neptá —
 * a že při obměně vydávající autority je nutné seznam doplnit. Kořeny proto zůstávají v balíku
 * dál, ale jen jako <i>adjunct</i> materiál, díky kterému jde řetěz sestavit i tam, kde
 * podpisový certifikát nemá použitelné AIA (pozorováno u eIdentity CCAeID3.2).</p>
 *
 * <p>All certificates were downloaded from the CAs' own official repositories:</p>
 * <ul>
 *   <li>https://www.ica.cz/korenove-certifikaty</li>
 *   <li>https://www.postsignum.cz/certifikaty_autorit.html</li>
 *   <li>https://www.eidentity.cz/certifikaty-certifikacnich-autorit/</li>
 *   <li>https://narodni-ca.gov.cz/Dokumenty.aspx</li>
 *   <li>http://www.kb.cz/pki/ — cesta je z AIA a CDP samotného certifikátu
 *       ({@code rcakb3.crt}, {@code rcakb3.crl}), ne z odhadu</li>
 * </ul>
 */
public class CzechCommercialCATrust {

    private static final String[] ROOT_RESOURCES = {
        // I.CA (První certifikační autorita, a.s.)
        "/certs/ica/rca15-rsa.cer",   // I.CA Root CA/RSA (legacy)
        "/certs/ica/rca16-ecc.cer",   // I.CA Root CA/ECC 12/2016 (legacy)
        "/certs/ica/rca22-rsa.cer",   // I.CA Root CA/RSA 05/2022 (current)
        "/certs/ica/rca22-ecc.cer",   // I.CA Root CA/ECC 05/2022 (current)
        // PostSignum (Česká pošta, s.p.)
        "/certs/postsignum/qca-root.cer",    // PostSignum Root QCA
        "/certs/postsignum/qca2-root.cer",   // PostSignum Root QCA 2
        "/certs/postsignum/qca4-root.cer",   // PostSignum Root QCA 4
        "/certs/postsignum/root-eccr1.cer",  // PostSignum Root QCA ECC R1
        // eIdentity a.s.
        "/certs/eidentity/root2.cer", // ACAeID2 - Qualified Root Certificate (legacy)
        "/certs/eidentity/root3.cer", // ACAeID3 - Root Certificate (current)
        // SSSVD / Národní certifikační autorita (NCA) — commercial line via SubCA2
        "/certs/sssvd/root-rsa.cer",  // NCA Root CA/RSA 10/2023
        "/certs/sssvd/root-ecc.cer",  // NCA Root CA/ECC 10/2023
        // Komerční banka, a.s.
        "/certs/kb/root3-ca.cer",     // KB Root 3 CA
    };

    /**
     * Vydávající (mezilehlé) autority komerčních linek. Tohle je to, co se připíná jako kotva
     * důvěry, a zároveň to, co se každému ověření podává jako adjunct materiál — bez něj by
     * u certifikátů bez použitelného AIA nešel řetěz sestavit vůbec.
     *
     * <p><b>Seznam je nutné doplňovat.</b> Na rozdíl od kořene, který kryje všechno pod sebou,
     * musí být každá vydávající autorita uvedena jmenovitě — jakmile autorita nasadí novou,
     * podpisy pod ní se bez doplnění neověří. Zdroje jsou v odkazech u třídy výš.
     *
     * <p><b>Každá položka je ověřena podpisem</b> proti kořeni, který balík nese —
     * {@code openssl verify -no_check_time}, 29. 8. 2026. Kořeny samotné mají ověřený vlastní
     * podpis. U nové položky to udělej taky: bez toho je v balíku certifikát, o kterém se ví jen
     * to, že se odněkud vzal.
     */
    private static final String[] ISSUING_CA_RESOURCES = {
        // I.CA (První certifikační autorita, a.s.)
        // Standard CA 09/2009 je self-signed a vydávala koncové certifikáty přímo — je tedy
        // sama tou vydávající autoritou, nejde o kořen nad další vrstvou.
        "/certs/ica/standard-ca-09-2009.cer",     // 2009-09-01 .. 2019-09-01
        "/certs/ica/public-ca-rsa-07-2015.cer",   // 2015-07-08 .. 2025-07-05, pod I.CA Root CA/RSA
        "/certs/ica/public-ca-ecc-12-2016.cer",   // 2016-12-07 .. 2026-12-05
        "/certs/ica/public-ca-rsa-06-2022.cer",   // 2022-06-20 .. 2032-06-17
        "/certs/ica/public-ca-ecc-06-2022.cer",   // 2022-06-20 .. 2032-06-17
        // PostSignum (Česká pošta, s.p.)
        "/certs/postsignum/public-ca.cer",         // 2005-11-08 .. 2020-11-08, pod Root QCA
        "/certs/postsignum/public-ca2.cer",        // 2010-01-19 .. 2020-01-19, pod Root QCA 2
        "/certs/postsignum/public-ca3.cer",        // 2017-03-20 .. 2025-01-19, pod Root QCA 2
        "/certs/postsignum/public-ca4.cer",        // 2018-09-27 .. 2033-09-27, pod Root QCA 4
        "/certs/postsignum/public-ca5.cer",        // 2018-10-03 .. 2033-10-03, pod Root QCA 4
        "/certs/postsignum/public-ecc-r1-ca1.cer", // 2024-01-30 .. 2034-01-30, pod Root QCA ECC R1
        "/certs/postsignum/public-ecc-r1-ca2.cer", // 2024-01-30 .. 2034-01-30, pod Root QCA ECC R1
        // eIdentity a.s.
        "/certs/eidentity/cca2-1.cer",
        "/certs/eidentity/cca3-1.cer",
        "/certs/eidentity/cca3-2.cer",
        "/certs/eidentity/cca3-3.cer",
        // SSSVD / Národní certifikační autorita (NCA) — komerční linka přes SubCA2
        "/certs/sssvd/subca2-rsa.cer",
        "/certs/sssvd/subca2-ecc.cer",
        // Komerční banka, a.s. — vedle kvalifikovaných vydává i komerční, linkou Business CA
        "/certs/kb/business-ca-rsa.cer",          // 2022-04-01 .. 2032-04-01, pod KB Root 3 CA
    };

    private CzechCommercialCATrust() {
    }

    /**
     * Vydávající autority komerčních linek — to, co se připíná do vlastního úložiště jako kotva
     * důvěry a co se zároveň podává každému ověření jako adjunct materiál.
     */
    public static List<CertificateToken> loadCommercialIssuingCAs() {
        return loadResourceCerts(ISSUING_CA_RESOURCES);
    }

    /**
     * Kořeny týchž autorit. Do úložiště se už nevkládají (viz javadoc třídy); podávají se jen
     * jako adjunct materiál, aby šel řetěz sestavit i nad připnutou kotvou a bylo ho vidět celý.
     */
    public static List<CertificateToken> loadAllRoots() {
        return loadResourceCerts(ROOT_RESOURCES);
    }

    private static List<CertificateToken> loadResourceCerts(String[] resourcePaths) {
        List<CertificateToken> result = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (String path : resourcePaths) {
                try (java.io.InputStream in = CzechCommercialCATrust.class.getResourceAsStream(path)) {
                    if (in == null) {
                        System.err.println("[CzechCommercialCATrust] Bundled certificate resource not found: " + path);
                        continue;
                    }
                    X509Certificate x509 = (X509Certificate) cf.generateCertificate(in);
                    result.add(new CertificateToken(x509));
                } catch (Exception e) {
                    System.err.println("[CzechCommercialCATrust] Cannot load bundled certificate "
                            + path + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // CertificateFactory.getInstance("X.509") shouldn't fail — ignore defensively.
        }
        return result;
    }
}
