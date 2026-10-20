package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inspektor nad dokumentem sestaveným v testu: podpis CAdES v revizi 2 a v revizi 3 doplněný
 * {@code /DSS} s VRI — tvar, jaký mívá PAdES B-LT. Sestavuje se, protože do repozitáře nesmí
 * žádný soubor ze sbírek; na nich se Inspektor zkouší sondou mimo testy.
 */
class StructureInspectorTest {

    private static KeyPair keys;
    private static X509CertificateHolder cert;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        X500Name dn = new X500Name("CN=Zkusebni podepisujici,O=Test");
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        cert = new JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(42),
                new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L * 365), dn, keys.getPublic()).build(cs);
    }

    private static byte[] cms(byte[] content) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        // signing-certificate-v2, jak ho nese každý podpis CAdES/PAdES.
        var essId = new org.bouncycastle.asn1.ess.ESSCertIDv2(
                java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        var ess = new org.bouncycastle.asn1.cms.Attribute(
                org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                new org.bouncycastle.asn1.DERSet(new org.bouncycastle.asn1.ess.SigningCertificateV2(
                        new org.bouncycastle.asn1.ess.ESSCertIDv2[]{essId})));
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().build())
                .setSignedAttributeGenerator(new org.bouncycastle.cms.DefaultSignedAttributeTableGenerator(
                        new org.bouncycastle.asn1.cms.AttributeTable(new org.bouncycastle.asn1.DERSet(ess))))
                .build(cs, cert));
        gen.addCertificates(new JcaCertStore(List.of(cert)));
        return gen.generate(new CMSProcessableByteArray(content), false).getEncoded();
    }

    /** Revize 1 prázdný dokument, revize 2 podpis, revize 3 /DSS s certifikátem a VRI. */
    private static byte[] pades() throws Exception {
        byte[] rev1;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            rev1 = out.toByteArray();
        }
        byte[] rev2;
        try (PDDocument doc = Loader.loadPDF(rev1)) {
            PDSignature sig = new PDSignature();
            sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            sig.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            sig.setName("Zkusebni podepisujici");
            sig.setSignDate(Calendar.getInstance());
            doc.addSignature(sig, content -> {
                try {
                    return cms(content.readAllBytes());
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            rev2 = out.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(rev2)) {
            PDSignature sig = doc.getLastSignatureDictionary();
            byte[] contents = sig.getContents();
            byte[] der = Arrays.copyOf(contents, PdfStructure.derLength(contents));
            String vriKey = ContainerInspector.digestHex(der, "SHA-1").toUpperCase(Locale.ROOT);

            COSStream certStream = doc.getDocument().createCOSStream();
            try (OutputStream os = certStream.createOutputStream()) {
                os.write(cert.getEncoded());
            }
            COSArray certs = new COSArray();
            certs.add(certStream);
            COSDictionary vriEntry = new COSDictionary();
            COSArray vriCerts = new COSArray();
            vriCerts.add(certStream);
            vriEntry.setItem(COSName.getPDFName("Cert"), vriCerts);
            COSDictionary vri = new COSDictionary();
            vri.setItem(COSName.getPDFName(vriKey), vriEntry);
            vri.setItem(COSName.getPDFName("0000000000000000000000000000000000000000"), new COSDictionary());
            COSDictionary dss = new COSDictionary();
            dss.setItem(COSName.getPDFName("Certs"), certs);
            dss.setItem(COSName.getPDFName("VRI"), vri);
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
            catalog.setItem(COSName.getPDFName("DSS"), dss);
            catalog.setNeedToBeUpdated(true);
            dss.setNeedToBeUpdated(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("PDF: tři revize, podpis kryje druhou, DSS přibyl ve třetí a VRI patří k podpisu")
    void padesStructure(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("dokument.pdf");
        Files.write(f, pades());
        assertEquals(StructureInspector.Format.PDF, StructureInspector.formatOf(f.toFile()));

        StructureInspector.Inspection in = StructureInspector.inspect(f.toFile(), null, null);
        assertEquals(null, in.error());
        assertEquals(3, in.revisionEnds().size(), "revize: " + in.revisionEnds());

        StructureNode sigs = in.roots().get(0);
        StructureNode sig = sigs.children().stream().filter(n -> n.kind() == Kind.SIGNATURE).findFirst().orElseThrow();
        assertEquals("2", value(sig, "Revize").orElseThrow().split(" ")[0]);
        assertTrue(hasNote(sig, Severity.INFO, "Po podpisu následuje 1 další revize"), notes(sig));

        StructureNode md = find(sig, n -> n.title().startsWith("Otisk obsahu (message-digest)"));
        assertNotNull(md, dump(sig, ""));
        assertTrue(hasNote(md, Severity.OK, "souhlasí"), "message-digest: " + notes(md));
        StructureNode signer = find(sig, n -> n.kind() == Kind.SIGNER);
        assertNotNull(value(signer, "Certifikát podepisujícího").orElse(null));
        StructureNode ess = find(sig, n -> n.title().startsWith("Podpisový certifikát"));
        assertTrue(value(ess, "Odpovídá certifikátu").isPresent(), "ESS má najít certifikát z CMS");

        StructureNode dss = in.roots().get(2);
        assertEquals(Kind.DSS, dss.kind());
        StructureNode item = find(dss, n -> n.kind() == Kind.CERTIFICATE);
        assertEquals("3", value(item, "Poprvé v revizi").orElseThrow());
        assertEquals(CmsStructure.certKey(cert.getEncoded()), item.key(),
                "Táž totožnost jako certifikát v CMS — kvůli odkazům mezi výskyty");

        StructureNode vri = in.roots().get(3);
        assertEquals(2, vri.children().size());
        StructureNode owned = vri.children().stream().filter(n -> value(n, "Patří k").isPresent()).findFirst().orElseThrow();
        assertEquals(sig.key(), owned.rows().stream().filter(r -> r.label().equals("Patří k")).findFirst().orElseThrow().link());
        assertTrue(vri.children().stream().anyMatch(n -> hasNote(n, Severity.INFO, "neodpovídá")),
                "VRI bez podpisu má být označen");
        assertTrue(value(sig, "Záznam VRI").isPresent(), "Podpis má odkázat na svůj záznam VRI");

        StructureNode revs = in.roots().get(4);
        assertEquals(3, revs.children().size());
        assertTrue(value(revs.children().get(1), "Uzavírá ji").isPresent());
        StructureNode rev3 = revs.children().get(2);
        assertTrue(value(rev3, "Nové v /DSS").orElseThrow().startsWith("1 certifikátů"), rows(rev3));
        assertTrue(hasNote(rev3, Severity.INFO, "nekryje"));
        assertTrue(rev3.children().stream().anyMatch(n -> n.kind() == Kind.DSS),
                "Revize s /DSS ukazuje jeho tehdejší podobu");
        assertTrue(value(revs.children().get(1), "/DSS").isPresent(), "Revize 2 ještě /DSS neměla");
    }

    @Test
    @DisplayName("CAdES: otisk souhlasí s předloženým souborem, s jiným ne")
    void cadesDetached(@TempDir Path dir) throws Exception {
        byte[] content = "obsah dokumentu".getBytes();
        Path p7s = dir.resolve("podpis.p7s");
        Files.write(p7s, cms(content));
        Path good = dir.resolve("dokument.txt");
        Files.write(good, content);
        Path bad = dir.resolve("jiny.txt");
        Files.write(bad, "jiny obsah".getBytes());

        assertEquals(StructureInspector.Format.CADES, StructureInspector.formatOf(p7s.toFile()));
        StructureNode okMd = find(StructureInspector.inspect(p7s.toFile(), good.toFile(), null).roots().get(0),
                n -> n.title().startsWith("Otisk obsahu"));
        assertTrue(hasNote(okMd, Severity.OK, "souhlasí"), notes(okMd));
        StructureNode badMd = find(StructureInspector.inspect(p7s.toFile(), bad.toFile(), null).roots().get(0),
                n -> n.title().startsWith("Otisk obsahu"));
        assertTrue(hasNote(badMd, Severity.ERROR, "nesouhlasí"), notes(badMd));
    }

    @Test
    @DisplayName("Razítko vnořené v podpisu je i ve skupině Časová razítka, pojmenované podle role")
    void embeddedTimestampListed(@TempDir Path dir) throws Exception {
        byte[] content = "obsah".getBytes();
        var signed = new org.bouncycastle.cms.CMSSignedData(cms(content));
        var si = signed.getSignerInfos().getSigners().iterator().next();

        // Razítkovací autorita na jedno použití: certifikát s kritickým EKU timeStamping.
        X500Name tsaDn = new X500Name("CN=Zkusebni TSA");
        var tsaCert = new JcaX509v3CertificateBuilder(tsaDn, BigInteger.valueOf(7),
                new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L), tsaDn, keys.getPublic())
                .addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, true,
                        new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                                org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_timeStamping))
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
        var tsGen = new org.bouncycastle.tsp.TimeStampTokenGenerator(
                new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()), tsaCert),
                new JcaDigestCalculatorProviderBuilder().build().get(
                        new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                org.bouncycastle.asn1.oiw.OIWObjectIdentifiers.idSHA1)),
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.3.4"));
        byte[] imprint = java.security.MessageDigest.getInstance("SHA-256").digest(si.getSignature());
        var req = new org.bouncycastle.tsp.TimeStampRequestGenerator().generate(
                org.bouncycastle.tsp.TSPAlgorithms.SHA256, imprint);
        var token = tsGen.generate(req, BigInteger.ONE, new Date());
        var attr = new org.bouncycastle.asn1.cms.Attribute(
                org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                new org.bouncycastle.asn1.DERSet(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(token.getEncoded())));
        var withTs = org.bouncycastle.cms.SignerInformation.replaceUnsignedAttributes(si,
                new org.bouncycastle.asn1.cms.AttributeTable(new org.bouncycastle.asn1.DERSet(attr)));
        var out = org.bouncycastle.cms.CMSSignedData.replaceSigners(signed,
                new org.bouncycastle.cms.SignerInformationStore(withTs));
        Path p7s = dir.resolve("podpis.p7s");
        Files.write(p7s, out.getEncoded());

        StructureInspector.Inspection in = StructureInspector.inspect(p7s.toFile(), null, null);
        StructureNode group = in.roots().stream().filter(n -> n.title().startsWith("Časová razítka"))
                .findFirst().orElseThrow();
        assertEquals("Časová razítka (1)", group.title());
        StructureNode ts = group.children().get(0);
        assertEquals("Razítko podpisu", ts.title());
        assertTrue(hasNote(ts, Severity.OK, "souhlasí"), notes(ts));
        assertTrue(find(in.roots().get(0), n -> n == ts) != null, "Týž uzel zůstává i na svém místě v podpisu");
    }

    @Test
    @DisplayName("Revize bez jediného objektu (hybridní trailer jako z Wordu) se tak označí")
    void emptyRevision(@TempDir Path dir) throws Exception {
        byte[] rev1;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            rev1 = out.toByteArray();
        }
        int prev = new String(rev1, java.nio.charset.StandardCharsets.ISO_8859_1).lastIndexOf("startxref");
        String xrefAt = new String(rev1, java.nio.charset.StandardCharsets.ISO_8859_1).substring(prev + 9).trim().split("\\s+")[0];
        String tail = "xref\r\n0 0\r\ntrailer\r\n<</Size 1/Prev " + xrefAt + ">>\r\nstartxref\r\n" + rev1.length + "\r\n%%EOF\r\n";
        byte[] all = java.util.Arrays.copyOf(rev1, rev1.length + tail.length());
        System.arraycopy(tail.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, all, rev1.length, tail.length());
        Path f = dir.resolve("word.pdf");
        Files.write(f, all);

        StructureInspector.Inspection in = StructureInspector.inspect(f.toFile(), null, null);
        StructureNode revs = in.roots().get(4);
        assertEquals(2, revs.children().size());
        assertTrue(value(revs.children().get(1), "Objekty").isPresent(), rows(revs.children().get(1)));
        assertTrue(value(revs.children().get(0), "Objekty").isEmpty(), "Původní dokument objekty má");
    }

    @Test
    @DisplayName("Obálka .p7m s vloženým PDF je CAdES, ne PDF")
    void p7mWithPdfInside(@TempDir Path dir) throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()), cert));
        byte[] p7m = gen.generate(new CMSProcessableByteArray("%PDF-1.7\n%obsah".getBytes()), true).getEncoded();
        Path f = dir.resolve("dokument.pdf.p7m");
        Files.write(f, p7m);
        assertEquals(StructureInspector.Format.CADES, StructureInspector.formatOf(f.toFile()));
        StructureNode md = find(StructureInspector.inspect(f.toFile(), null, null).roots().get(0),
                n -> n.title().startsWith("Otisk obsahu"));
        assertTrue(hasNote(md, Severity.OK, "vložený v objektu CMS"), notes(md));
    }

    @Test
    @DisplayName("Délka DER bez výplně nulami")
    void derLength() {
        byte[] padded = new byte[300];
        padded[0] = 0x30;
        padded[1] = (byte) 0x82;
        padded[2] = 0x00;
        padded[3] = (byte) 0x80;
        assertEquals(4 + 128, PdfStructure.derLength(padded));
    }

    // ------------------------------------------------------------------ pomocné

    private static StructureNode find(StructureNode root, java.util.function.Predicate<StructureNode> p) {
        if (p.test(root)) return root;
        for (StructureNode c : root.children()) {
            StructureNode f = find(c, p);
            if (f != null) return f;
        }
        return null;
    }

    private static Optional<String> value(StructureNode n, String label) {
        return n.rows().stream().filter(r -> r.label().equals(label)).map(StructureNode.Row::value).findFirst();
    }

    private static boolean hasNote(StructureNode n, Severity s, String fragment) {
        return n.notes().stream().anyMatch(x -> x.severity() == s && x.text().contains(fragment));
    }

    private static String dump(StructureNode n, String indent) {
        StringBuilder sb = new StringBuilder(indent + n.title() + " " + n.notes() + "\n");
        for (StructureNode c : n.children()) sb.append(dump(c, indent + "  "));
        return sb.toString();
    }

    private static String notes(StructureNode n) {
        return n.notes().toString();
    }

    private static String rows(StructureNode n) {
        return n.rows().toString();
    }
}
