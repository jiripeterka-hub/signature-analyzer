package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozklad JAdES nad podpisem sestaveným v testu: zploštělá serializace s {@code x5c},
 * {@code x5t#S256} a {@code sigD}, a táž hlavička v kompaktní serializaci. Soubory ze sbírek
 * do repozitáře nesmí; na nich se rozklad zkouší sondou {@code StructureInspectorScan}.
 */
class JadesStructureTest {

    private static KeyPair keys;
    private static X509CertificateHolder cert;
    private static final byte[] CONTENT = "obsah dokumentu".getBytes(StandardCharsets.UTF_8);
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    @BeforeAll
    static void init() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        X500Name dn = new X500Name("CN=Zkusebni JAdES");
        cert = new JcaX509v3CertificateBuilder(dn, BigInteger.TEN, new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), dn, keys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
    }

    /** Chráněná hlavička odděleného podpisu a podpis nad ní (obsah je mimo, RFC 7515 příloha F). */
    private static String[] parts() throws Exception {
        String x5t = URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        // TS 119 182-1: bez b64 = false se otisk počítá nad zápisem obsahu v base64url.
        String hashV = URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(URL.encode(CONTENT)));
        String header = "{\"alg\":\"RS256\",\"x5c\":[\"" + Base64.getEncoder().encodeToString(cert.getEncoded())
                + "\"],\"x5t#S256\":\"" + x5t + "\",\"sigT\":\"2026-10-13T10:00:00Z\","
                + "\"sigD\":{\"mId\":\"http://uri.etsi.org/19182/ObjectIdByURIHash\",\"pars\":[\"dokument.txt\"],"
                + "\"hashM\":\"S256\",\"hashV\":[\"" + hashV + "\"]}}";
        String prot = URL.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(keys.getPrivate());
        s.update((prot + ".").getBytes(StandardCharsets.US_ASCII));
        return new String[]{prot, URL.encodeToString(s.sign())};
    }

    @Test
    @DisplayName("Zploštělý JAdES: rozpoznání, otisk certifikátu v hlavičce a sigD proti souboru")
    void flattened(@TempDir Path dir) throws Exception {
        String[] p = parts();
        Path f = dir.resolve("podpis.json");
        Files.writeString(f, "{\"protected\":\"" + p[0] + "\",\"signature\":\"" + p[1] + "\"}");
        Path good = dir.resolve("dokument.txt");
        Files.write(good, CONTENT);
        Path bad = dir.resolve("jiny.txt");
        Files.writeString(bad, "jiný obsah");
        assertEquals(StructureInspector.Format.JADES, StructureInspector.formatOf(f.toFile()));

        StructureInspector.Inspection in = StructureInspector.inspect(f.toFile(), good.toFile(), null);
        assertNull(in.error());
        StructureNode sig = in.roots().get(0).children().get(0);
        assertEquals(Kind.SIGNATURE, sig.kind());
        assertNotNull(sig.dssKey());
        StructureNode header = find(sig, n -> n.title().startsWith("Chráněná hlavička"));
        assertTrue(hasNote(header, Severity.OK, "souhlasí s prvním certifikátem"), header.notes().toString());
        assertTrue(hasNote(find(sig, n -> n.title().startsWith("Oddělená data")), Severity.OK, "souhlasí"));

        StructureNode sigDBad = find(StructureInspector.inspect(f.toFile(), bad.toFile(), null).roots().get(0),
                n -> n.title().startsWith("Oddělená data"));
        assertTrue(hasNote(sigDBad, Severity.ERROR, "nesouhlasí"), sigDBad.notes().toString());
    }

    @Test
    @DisplayName("Kompaktní serializace se pozná a rozloží")
    void compact(@TempDir Path dir) throws Exception {
        String[] p = parts();
        Path f = dir.resolve("podpis.jws");
        Files.writeString(f, p[0] + ".." + p[1]);
        assertEquals(StructureInspector.Format.JADES, StructureInspector.formatOf(f.toFile()));
        StructureInspector.Inspection in = StructureInspector.inspect(f.toFile(), null, null);
        assertTrue(in.summary().stream().anyMatch(r -> r.value().startsWith("kompaktní")), in.summary().toString());
        assertNotNull(find(in.roots().get(0), n -> n.kind() == Kind.CERTIFICATE));
    }

    @Test
    @DisplayName("Obyčejný JSON, i s členem „signatures“, Inspektor nenabízí")
    void plainJson(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nastaveni.json");
        Files.writeString(f, "{\"signatures\":[{\"jmeno\":\"x\"}],\"verze\":2}");
        assertNull(StructureInspector.formatOf(f.toFile()));
    }

    private static boolean hasNote(StructureNode n, Severity s, String fragment) {
        return n != null && n.notes().stream().anyMatch(x -> x.severity() == s && x.text().contains(fragment));
    }

    private static StructureNode find(StructureNode root, java.util.function.Predicate<StructureNode> p) {
        if (p.test(root)) return root;
        for (StructureNode c : root.children()) {
            StructureNode f = find(c, p);
            if (f != null) return f;
        }
        return null;
    }
}
