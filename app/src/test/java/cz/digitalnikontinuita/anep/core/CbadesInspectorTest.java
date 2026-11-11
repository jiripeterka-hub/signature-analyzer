package cz.digitalnikontinuita.anep.core;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozbor podpisu CB-AdES (COSE) v prohlížeči kontejneru.
 *
 * <p>Do 11. 11. 2026 se soubor .cose v prohlížeči nepoznal a ikona pro prohlížení obsahu se
 * k němu nenabízela, ačkoli ho aplikace ověřuje. Hlavní tvrzení je proto dvojí: soubor se pozná
 * jako kontejner už z prvních bajtů (jen tolik dostane rozhodnutí o ikoně) a vložený dokument
 * vyjde bajt po bajtu.
 *
 * <p>Přípravky se skládají ručně z prvků CBOR v tvaru, v jakém je zapisuje DSS 6.5.1 (změřeno na
 * vzorcích CB-AdES B-T a LTA, odděleném i obalujícím). Hodnoty podpisů jsou libovolné bajty —
 * prohlížeč platnost neověřuje.
 */
class CbadesInspectorTest {

    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes(StandardCharsets.UTF_8);

    private static byte[] certificate;

    @BeforeAll
    static void certificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Principal subject = new X500Principal("CN=Zkusebni podepisujici, C=CZ");
        long now = System.currentTimeMillis();
        certificate = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(11),
                new Date(now - 86_400_000L), new Date(now + 86_400_000L), subject, pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()))
                .getEncoded();
    }

    // ---------------------------------------------------------------- rozpoznání

    @Test
    @DisplayName("COSE_Sign i COSE_Sign1 se poznají z prvních bajtů jako kontejner")
    void detectedFromHead() throws Exception {
        byte[] sign = coseSign(PDF, protectedHeader(true, false), null);
        byte[] sign1 = coseSign1(PDF, protectedHeader(true, false));

        // MainView rozhoduje o ikoně ze šestnácti bajtů (ContainerView.isContainer).
        assertEquals(Kind.COSE, ContainerInspector.detectKind(Arrays.copyOf(sign, 16)));
        assertEquals(Kind.COSE, ContainerInspector.detectKind(Arrays.copyOf(sign1, 16)));
        assertTrue(ContainerInspector.isContainerKind(Kind.COSE));
    }

    @Test
    @DisplayName("Neoznačené pole CBOR se za podpis nepovažuje")
    void untaggedArrayIsNotCose() {
        // 0x84 0x41 … se v binární příloze najde náhodou; odznak podpisu by tam lhal.
        byte[] untagged = {(byte) 0x84, 0x41, (byte) 0xA0, (byte) 0xA0, (byte) 0xF6, 0x40, 1, 2};
        assertEquals(Kind.OTHER, ContainerInspector.detectKind(untagged));
    }

    // ---------------------------------------------------------------- obalující podpis

    @Test
    @DisplayName("Obalující podpis vydá podepsaný dokument bajt po bajtu")
    void envelopingYieldsPayload() throws Exception {
        Result result = ContainerInspector.inspect(
                coseSign(PDF, protectedHeader(true, false), null), "podpis.cose");

        assertNull(result.error());
        assertEquals(Kind.COSE, result.kind());
        assertEquals("podpis CB-AdES s vloženým obsahem (COSE)", result.typeLabel());
        Node payload = find(result.root(), "dokument.pdf");
        assertNotNull(payload, "podepsaný obsah musí být samostatným prvkem stromu");
        assertArrayEquals(PDF, payload.getData());
        // Na roli „podep…“ a krytí „ano…“ stojí výběr prvního prvku v ContainerView.
        assertTrue(payload.getInfo().get("Role").startsWith("podep"));
        assertTrue(payload.getInfo().get("Kryto podpisem").startsWith("ano"));
        assertTrue(result.summary().get("Podepsaný obsah").contains("dokument PDF"));
    }

    @Test
    @DisplayName("Jméno dokumentu se bere z názvu podpisu jen tehdy, když v něm opravdu je")
    void payloadName() throws Exception {
        byte[] cose = coseSign(PDF, protectedHeader(true, false), null);
        assertNotNull(find(ContainerInspector.inspect(cose, "smlouva.pdf.cose").root(), "smlouva.pdf"));
        assertNotNull(find(ContainerInspector.inspect(cose, "CB-AdES B-T.cose").root(), "dokument.pdf"));
    }

    @Test
    @DisplayName("Hlavička vydá podepisujícího, algoritmus a čas; certifikát je samostatný prvek")
    void headerAndChain() throws Exception {
        Result result = ContainerInspector.inspect(
                coseSign(PDF, protectedHeader(true, false), null), "podpis.cose");

        Node signature = find(result.root(), "Podpis");
        assertNotNull(signature);
        assertEquals("Zkusebni podepisujici", signature.getInfo().get("Podepisující"));
        assertTrue(signature.getInfo().get("Podpisový algoritmus").startsWith("RS256"));
        assertNotNull(signature.getInfo().get("Deklarovaný čas podpisu"));
        Node cert = find(result.root(), "Zkusebni podepisujici.cer");
        assertNotNull(cert);
        assertArrayEquals(certificate, cert.getData());
        Node header = find(result.root(), "chranena-hlavicka.cbor");
        assertNotNull(header, "chráněná hlavička se dá vyjmout v přesných bajtech");
    }

    @Test
    @DisplayName("Ověřovací údaje v uHeaders se rozloží na certifikáty")
    void validationDataInUHeaders() throws Exception {
        Map valData = new Map();
        Map xVal = new Map();
        xVal.put(u(1), map(u(1), new ByteString(certificate)));
        valData.put(u(1), array(xVal));
        byte[] uEntry = encode(map(u(2), valData));

        Result result = ContainerInspector.inspect(
                coseSign(PDF, protectedHeader(true, false), uEntry), "podpis.cose");

        Node folder = find(result.root(), "2. Ověřovací údaje");
        assertNull(folder, "pořadí je 1, ne 2");
        Node data = find(result.root(), "1. Ověřovací údaje");
        assertNotNull(data);
        assertEquals(1, data.getChildren().size());
        assertArrayEquals(certificate, data.getChildren().get(0).getData());
        assertTrue(result.summary().get("Nepodepsané vlastnosti").contains("ověřovací údaje"));
    }

    // ---------------------------------------------------------------- oddělený podpis

    @Test
    @DisplayName("Oddělený podpis jmenuje odkazovaný soubor a jeho otisk")
    void detached() throws Exception {
        Result result = ContainerInspector.inspect(
                coseSign(null, protectedHeader(false, true), null), "podpis.cose");

        assertNull(result.error());
        assertEquals("oddělený podpis CB-AdES (COSE)", result.typeLabel());
        assertTrue(result.summary().get("Podepsaný obsah").contains("dokument.pdf"));
        Node signature = find(result.root(), "Podpis");
        assertEquals("SHA-256: " + "ab".repeat(32),
                signature.getInfo().get("Podepsaná data — dokument.pdf"));
        assertFalse(result.warnings().isEmpty(), "čtenář se má dozvědět, že dokument tu není");
    }

    @Test
    @DisplayName("COSE_Sign1 nese hlavičky v těle a rozebere se stejně")
    void sign1() throws Exception {
        Result result = ContainerInspector.inspect(
                coseSign1(PDF, protectedHeader(true, false)), "podpis.cose");

        assertNull(result.error());
        assertTrue(result.summary().get("Struktura").startsWith("COSE_Sign1"));
        assertArrayEquals(PDF, find(result.root(), "dokument.pdf").getData());
        assertNotNull(find(result.root(), "Zkusebni podepisujici.cer"));
    }

    @Test
    @DisplayName("Poškozený soubor vrátí chybu, nevyhodí výjimku")
    void brokenFile() {
        byte[] broken = {(byte) 0xD8, 0x62, (byte) 0x84, 0x41};
        Result result = ContainerInspector.inspect(broken, "rozbity.cose");
        assertNotNull(result.error());
        assertFalse(result.isBrowsable());
    }

    // ---------------------------------------------------------------- přípravky

    private static Map protectedHeader(boolean contentType, boolean detached) {
        Map h = new Map();
        h.put(u(1), new NegativeInteger(-257));
        if (contentType) h.put(u(3), new UnicodeString("application/pdf"));
        h.put(u(33), new ByteString(certificate));
        h.put(u(15), map(u(6), u(1_792_487_834L)));
        if (detached) {
            Map sigD = new Map();
            sigD.put(u(1), new UnicodeString("http://uri.etsi.org/19152/ObjectIdByURIHash"));
            sigD.put(u(2), array(new UnicodeString("dokument.pdf")));
            sigD.put(u(3), new NegativeInteger(-16));
            byte[] digest = new byte[32];
            Arrays.fill(digest, (byte) 0xAB);
            sigD.put(u(4), array(new ByteString(digest)));
            h.put(u(267), sigD);
            h.put(u(2), array(u(267)));
        }
        return h;
    }

    private static byte[] coseSign(byte[] payload, Map header, byte[] uHeaderEntry) throws Exception {
        Map unprotected = new Map();
        if (uHeaderEntry != null) unprotected.put(u(268), array(new ByteString(uHeaderEntry)));
        Array signature = array(new ByteString(encode(header)), unprotected,
                new ByteString(new byte[]{1, 2, 3}));
        Array top = array(new ByteString(encode(new Map())), new Map(),
                payload == null ? SimpleValue.NULL : new ByteString(payload), array(signature));
        top.setTag(98);
        return encode(top);
    }

    private static byte[] coseSign1(byte[] payload, Map header) throws Exception {
        Array top = array(new ByteString(encode(header)), new Map(), new ByteString(payload),
                new ByteString(new byte[]{4, 5, 6}));
        top.setTag(18);
        return encode(top);
    }

    private static UnsignedInteger u(long v) {
        return new UnsignedInteger(v);
    }

    private static Map map(DataItem k, DataItem v) {
        Map m = new Map();
        m.put(k, v);
        return m;
    }

    private static Array array(DataItem... items) {
        Array a = new Array();
        for (DataItem d : items) a.add(d);
        return a;
    }

    private static byte[] encode(DataItem item) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).nonCanonical().encode(item);
        return out.toByteArray();
    }

    private static Node find(Node node, String name) {
        if (name.equals(node.getDisplayName())) return node;
        for (Node c : node.getChildren()) {
            Node f = find(c, name);
            if (f != null) return f;
        }
        return null;
    }
}
