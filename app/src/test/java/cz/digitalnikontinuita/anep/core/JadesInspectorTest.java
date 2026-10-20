package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozbor podpisu JAdES v prohlížeči kontejnerů.
 *
 * <p>Dokud tenhle rozbor nebyl, ukázal prohlížeč u souboru JAdES <b>jeden prvek</b> — „dokument
 * JSON“ — a podepsaný dokument v něm zůstal jako řádek base64, ke kterému se uživatel nedostal.
 * Právě vyjmutí obsahu je tu to hlavní, takže první a nejdůležitější tvrzení je bajtová shoda
 * vyjmutého payloadu se vstupem.
 *
 * <p>Přípravky se skládají ručně z řetězců, ne podepisovačem DSS. Není to zkratka: sbírky mají
 * jen podpisy vyrobené jedním nástrojem v jedné podobě (obecná serializace, {@code b64} výchozí),
 * kdežto formát jich připouští víc — zploštělou serializaci, nekódovaný payload dle RFC 7797,
 * oddělená data přes {@code sigD}. Co se neumí vyrobit, to se neumí ani změřit, a tyhle podoby
 * by jinak nikdo nezkusil až do chvíle, kdy je někdo pošle.
 *
 * <p>Nic z toho neověřuje <b>platnost</b> podpisu — ta je věcí rozboru, ne prohlížeče. Proto tu
 * není ani jeden skutečný podpisový výpočet a hodnoty podpisů jsou libovolné bajty.
 */
class JadesInspectorTest {

    /** Minimální PDF — {@code detectKind} pozná typ podle značky na začátku, ne podle přípony. */
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes(StandardCharsets.UTF_8);

    private static byte[] certificate;

    @BeforeAll
    static void certificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Principal subject = new X500Principal("CN=Zkusebni vydavatel, C=CZ");
        long now = System.currentTimeMillis();
        certificate = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(7),
                new Date(now - 86_400_000L), new Date(now + 86_400_000L), subject, pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()))
                .getEncoded();
    }

    // ---------------------------------------------------------------- vložený obsah

    @Test
    @DisplayName("Obalující podpis vydá podepsaný dokument bajt po bajtu")
    void envelopingYieldsPayload() {
        Result result = inspect(enveloping(PDF), "podpis.json");

        assertEquals(Kind.JSON, result.kind());
        assertEquals("podpis JAdES s vloženým obsahem (JSON)", result.typeLabel());
        Node payload = find(result.root(), "dokument.pdf");
        assertNotNull(payload, "podepsaný obsah musí být samostatným prvkem stromu");
        assertArrayEquals(PDF, payload.getData());
        assertTrue(result.summary().get("Podepsaný obsah").contains("dokument PDF"),
                "souhrn má říct, co je uvnitř");
    }

    @Test
    @DisplayName("Prvek s obsahem je označen jako krytý podpisem — okno na něm otevře")
    void payloadIsMarkedAsCovered() {
        Node payload = find(inspect(enveloping(PDF), "podpis.json").root(), "dokument.pdf");

        assertNotNull(payload);
        // Na těchhle dvou údajích stojí výběr prvního prvku v ContainerView: role začínající
        // na „podep“ a krytí začínající na „ano“. Bez nich by se okno otevřelo na hlavičce.
        assertTrue(payload.getInfo().get("Role").startsWith("podep"));
        assertTrue(payload.getInfo().get("Kryto podpisem").startsWith("ano"));
        // Krytí se tu nedopočítává, tak ho ani nesmí tvrdit značkou, kterou ASiC vyhrazuje
        // pro ověřený otisk.
        assertTrue(!payload.getInfo().get("Kryto podpisem").contains("✔"));
    }

    @Test
    @DisplayName("Vyjmutý dokument se nepojmenovává po podpisovém souboru")
    void payloadIsNotNamedAfterTheSignatureFile() {
        // Podpis JAdES se jmenuje nezávisle na tom, co nese, takže z jeho názvu se jméno
        // dokumentu odvodit nedá — vymyšlené jméno by čtenář vzal za skutečné.
        Node payload = find(inspect(enveloping(PDF), "JAdES B-LTA enveloping.json").root(),
                "dokument.pdf");
        assertNotNull(payload, "bez jiné opory dostane dokument neutrální jméno");

        // Výjimka: když podpisový soubor vznikl přilepením vlastní přípony k názvu dokumentu,
        // jméno v něm opravdu je a sundáním přípony se vrátí.
        Node named = find(inspect(enveloping(PDF), "smlouva.pdf.json").root(), "smlouva.pdf");
        assertNotNull(named, "„smlouva.pdf.json“ nese jméno dokumentu, to se má použít");
    }

    @Test
    @DisplayName("Zploštělá serializace se pozná stejně jako obecná")
    void flattenedSerialization() {
        String json = "{\"payload\":\"" + b64u(PDF) + "\","
                + "\"protected\":\"" + b64u(header()) + "\","
                + "\"signature\":\"" + b64u(new byte[]{1, 2, 3}) + "\"}";

        Result result = inspect(json, "podpis.json");

        assertTrue(result.summary().get("Serializace").startsWith("zploštělá"));
        assertArrayEquals(PDF, find(result.root(), "dokument.pdf").getData());
    }

    @Test
    @DisplayName("Nekódovaný payload (RFC 7797) se bere jako bajty, ne jako base64")
    void unencodedPayload() {
        String header = "{\"alg\":\"RS512\",\"b64\":false,\"crit\":[\"b64\"]}";
        String json = "{\"payload\":\"Ahoj\",\"signatures\":[{"
                + "\"protected\":\"" + b64u(header) + "\","
                + "\"signature\":\"" + b64u(new byte[]{9}) + "\"}]}";

        Result result = inspect(json, "podpis.json");

        Node payload = find(result.root(), "dokument.txt");
        assertNotNull(payload, "nekódovaný payload musí vyjít jako obsah, ne jako base64");
        assertArrayEquals("Ahoj".getBytes(StandardCharsets.UTF_8), payload.getData());
    }

    // ---------------------------------------------------------------- oddělený podpis

    @Test
    @DisplayName("Oddělený podpis pojmenuje soubor, který kryje, a jeho otisk")
    void detachedNamesTheData() {
        byte[] digest = ContainerInspector.digestHex(PDF, "SHA-256").getBytes(StandardCharsets.UTF_8);
        String header = "{\"alg\":\"RS512\",\"crit\":[\"sigD\"],\"sigD\":{"
                + "\"mId\":\"http://uri.etsi.org/19182/ObjectIdByURIHash\","
                + "\"pars\":[\"dokument.pdf\"],\"hashM\":\"S256\","
                + "\"hashV\":[\"" + b64u(digest) + "\"]}}";
        String json = "{\"signatures\":[{\"protected\":\"" + b64u(header) + "\","
                + "\"signature\":\"" + b64u(new byte[]{4}) + "\"}]}";

        Result result = inspect(json, "podpis.json");

        assertEquals("oddělený podpis JAdES (JSON)", result.typeLabel());
        assertNull(find(result.root(), "dokument.pdf"), "oddělený podpis obsah nenese");
        assertTrue(result.summary().get("Podepsaný obsah").contains("dokument.pdf"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("dokument.pdf")),
                "poznámka má říct, který soubor uživateli chybí");
        Node signature = find(result.root(), "Podpis");
        assertNotNull(signature);
        assertNotNull(signature.getInfo().get("Podepsaná data — dokument.pdf"));
    }

    // ---------------------------------------------------------------- vlastnosti etsiU

    @Test
    @DisplayName("Certifikáty z etsiU vyjdou jako samostatné soubory .cer")
    void certificatesFromEtsiU() {
        String etsiU = "{\"xVals\":[{\"x509Cert\":{\"val\":\""
                + Base64.getEncoder().encodeToString(certificate) + "\"}}]}";
        Result result = inspect(withEtsiU(etsiU), "podpis.json");

        Node cert = find(result.root(), "Zkusebni vydavatel.cer");
        assertNotNull(cert, "certifikát se má pojmenovat podle CN a nést vlastní bajty");
        assertArrayEquals(certificate, cert.getData());
        assertTrue(cert.getInfo().get("Subjekt").contains("Zkusebni vydavatel"));
        assertTrue(result.summary().get("Nepodepsané vlastnosti").contains("certifikáty pro ověření"));
    }

    @Test
    @DisplayName("Nečitelné časové razítko strom neshodí a řekne se to u něj")
    void unreadableTimestampIsKept() {
        String etsiU = "{\"arcTst\":{\"tstTokens\":[{\"val\":\""
                + Base64.getEncoder().encodeToString(new byte[]{0x30, 0x03, 0x02, 0x01, 0x00}) + "\"}]}}";

        Result result = inspect(withEtsiU(etsiU), "podpis.json");

        Node token = find(result.root(), "razitko.tst");
        assertNotNull(token, "bajty razítka se vydávají i tehdy, když je nelze přečíst");
        assertNotNull(token.getInfo().get("Poznámka"));
        // Pořadí v etsiU není ozdoba: archivní razítko kryje to, co je před ním.
        Node component = find(result.root(), "1. Archivní časové razítko (arcTst)");
        assertNotNull(component);
        assertNotNull(component.getInfo().get("Co razítko kryje"));
    }

    @Test
    @DisplayName("Neznámá vlastnost se nezahodí — vyjde jako .json se svým obsahem")
    void unknownPropertySurvives() {
        Result result = inspect(withEtsiU("{\"budouciVlastnost\":{\"a\":1}}"), "podpis.json");

        Node component = find(result.root(), "1. budouciVlastnost.json");
        assertNotNull(component, "vlastnost mimo výčet se má ukázat, ne přeskočit");
        assertTrue(component.getInfo().get("Obsah").contains("a: 1"));
    }

    // ---------------------------------------------------------------- co podpis není

    @Test
    @DisplayName("Obyčejný JSON se členem „signatures“ se za podpis nevydává")
    void ordinaryJsonIsNotASignature() {
        Result result = inspect("{\"signatures\":[{\"kdo\":\"Novák\"}]}", "seznam.json");

        assertEquals("dokument JSON", result.typeLabel());
        assertEquals(1, result.root().getChildren().size(), "nemá se rozebírat na části");
        assertEquals("seznam.json", result.root().getChildren().get(0).getName());
    }

    @Test
    @DisplayName("Formátování JSON pro náhled nechává prázdné struktury na jednom řádku")
    void prettyPrinting() {
        assertEquals("{}", ContainerInspector.prettyPrintJson("{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("[\n  1,\n  2\n]",
                ContainerInspector.prettyPrintJson("[1,2]".getBytes(StandardCharsets.UTF_8)));
        // Dvojtečka a čárka uvnitř řetězce nejsou oddělovače; kdyby se podle nich lámalo,
        // změnil by náhled obsah, který má jen ukázat.
        assertEquals("{\n  \"a\": \"x,y:z\"\n}", ContainerInspector.prettyPrintJson(
                "{\"a\":\"x,y:z\"}".getBytes(StandardCharsets.UTF_8)));
        assertNull(ContainerInspector.prettyPrintJson("ne json".getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- přípravky

    private static Result inspect(String json, String name) {
        return ContainerInspector.inspect(json.getBytes(StandardCharsets.UTF_8), name);
    }

    private static String header() {
        return "{\"alg\":\"RS512\",\"cty\":\"pdf\",\"typ\":\"jose+json\",\"iat\":1768384595}";
    }

    private static String enveloping(byte[] payload) {
        return "{\"payload\":\"" + b64u(payload) + "\",\"signatures\":[{"
                + "\"protected\":\"" + b64u(header()) + "\","
                + "\"signature\":\"" + b64u(new byte[]{1, 2, 3}) + "\"}]}";
    }

    private static String withEtsiU(String component) {
        return "{\"payload\":\"" + b64u(PDF) + "\",\"signatures\":[{"
                + "\"protected\":\"" + b64u(header()) + "\","
                + "\"header\":{\"etsiU\":[\"" + b64u(component) + "\"]},"
                + "\"signature\":\"" + b64u(new byte[]{1}) + "\"}]}";
    }

    private static String b64u(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String b64u(String text) {
        return b64u(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Node find(Node node, String name) {
        for (Node child : node.getChildren()) {
            if (child.getName().equals(name)) return child;
            Node deeper = find(child, name);
            if (deeper != null) return deeper;
        }
        return null;
    }
}
