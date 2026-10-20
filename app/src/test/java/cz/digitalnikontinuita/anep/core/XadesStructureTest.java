package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozklad XAdES nad podpisem sestaveným v testu (obalený XML Signature): rozpoznání formátu,
 * přepočet otisku odkazu a jeho selhání po změně podepsaného obsahu. Soubory ze sbírek do
 * repozitáře nesmí; na nich se rozklad zkouší sondou {@code StructureInspectorScan}.
 */
class XadesStructureTest {

    private static KeyPair keys;
    private static X509CertificateHolder cert;

    @BeforeAll
    static void init() throws Exception {
        org.apache.xml.security.Init.init();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        X500Name dn = new X500Name("CN=Zkusebni XML");
        cert = new JcaX509v3CertificateBuilder(dn, BigInteger.ONE, new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), dn, keys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
    }

    /** Obalený podpis nad celým dokumentem; {@code tamper} pak změní podepsaný text. */
    private static byte[] enveloped(boolean tamper) throws Exception {
        Document doc = ContainerInspector.parseXml("<dokument><text>obsah</text></dokument>"
                .getBytes(StandardCharsets.UTF_8));
        XMLSignature sig = new XMLSignature(doc, null, XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256);
        doc.getDocumentElement().appendChild(sig.getElement());
        Transforms t = new Transforms(doc);
        t.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        sig.addDocument("", t, "http://www.w3.org/2001/04/xmlenc#sha256");
        sig.addKeyInfo(new JcaX509CertificateConverter().getCertificate(cert));
        sig.sign(keys.getPrivate());
        if (tamper) {
            ((Element) doc.getElementsByTagName("text").item(0)).setTextContent("jiný obsah");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer()
                .transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(out));
        return out.toByteArray();
    }

    @Test
    @DisplayName("Obalený podpis: Inspektor XAdES, otisk odkazu souhlasí, KeyInfo s certifikátem")
    void enveloped(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("podepsany.xml");
        Files.write(f, enveloped(false));
        assertEquals(StructureInspector.Format.XADES, StructureInspector.formatOf(f.toFile()));

        StructureInspector.Inspection in = StructureInspector.inspect(f.toFile(), null, null);
        assertNull(in.error());
        StructureNode sig = in.roots().get(0).children().get(0);
        assertEquals(Kind.SIGNATURE, sig.kind());
        assertNotNull(sig.dssKey(), "Hodnota podpisu má klíč k párování s DSS");
        StructureNode ref = find(sig, n -> n.title().startsWith("Odkaz 1"));
        assertTrue(ref.notes().stream().anyMatch(x -> x.severity() == Severity.OK), ref.notes().toString());
        assertTrue(sig.notes().stream().anyMatch(x -> x.text().contains("není XAdES") || x.text().contains("holý")),
                "Bez QualifyingProperties to má říct");
        assertNotNull(find(sig, n -> n.kind() == Kind.CERTIFICATE));
    }

    @Test
    @DisplayName("Změněný podepsaný obsah: otisk odkazu nesouhlasí")
    void tampered(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("zmeneny.xml");
        Files.write(f, enveloped(true));
        StructureNode ref = find(StructureInspector.inspect(f.toFile(), null, null).roots().get(0),
                n -> n.title().startsWith("Odkaz 1"));
        assertTrue(ref.notes().stream().anyMatch(x -> x.severity() == Severity.ERROR), ref.notes().toString());
    }

    @Test
    @DisplayName("XML s jmenným prostorem xmldsig, ale bez podpisu, Inspektor nenabízí")
    void namespaceOnly(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("protokol.xml");
        Files.writeString(f, "<protokol xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data/>"
                + "<SignatureIdentifier/></protokol>");
        assertNull(StructureInspector.formatOf(f.toFile()));
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
