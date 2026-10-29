package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jméno vloženého obsahu obálky .p7m v prohlížeči kontejnerů.
 *
 * <p>Dotaz uživatele z 29. 10. 2026 nad obálkou CAdES B-LTA vytvořenou DSS: prohlížeč ukázal
 * vložené PDF pod jménem obálky, ne pod jménem původního souboru. CMS jméno souboru nenese;
 * nese ho jen nepovinný podepsaný atribut content-hints, kam ho nástroje zapisují jako hlavičku
 * MIME. Když ho obálka má, platí to jméno; když ne, jméno se odvodí a karta to řekne.
 */
class ContainerCmsNameTest {

    private static KeyPair keys;
    private static X509CertificateHolder cert;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        X500Name dn = new X500Name("CN=Zkusebni podepisujici,O=Test");
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        cert = new JcaX509v3CertificateBuilder(dn, BigInteger.ONE,
                new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L * 365), dn, keys.getPublic()).build(cs);
    }

    /** Obálka s vloženým „PDF“, s popisem content-hints, nebo bez něj ({@code null}). */
    private static byte[] p7m(String hintDescription) throws Exception {
        JcaSignerInfoGeneratorBuilder sb =
                new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build());
        if (hintDescription != null) {
            ASN1EncodableVector hint = new ASN1EncodableVector();
            hint.add(new DERUTF8String(hintDescription));
            hint.add(CMSObjectIdentifiers.data);
            ASN1EncodableVector attrs = new ASN1EncodableVector();
            attrs.add(new Attribute(new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.4"),
                    new DERSet(new DERSequence(hint))));
            sb.setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator(new AttributeTable(attrs)));
        }
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(sb.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()), cert));
        return gen.generate(new CMSProcessableByteArray(
                "%PDF-1.7\n%obsah".getBytes(StandardCharsets.ISO_8859_1)), true).getEncoded();
    }

    private static ContainerInspector.Node payload(ContainerInspector.Result r) {
        return r.root().getChildren().get(0);
    }

    @Test
    @DisplayName("jméno z content-hints má přednost před názvem obálky")
    void nameFromContentHints() throws Exception {
        ContainerInspector.Result r = ContainerInspector.inspect(p7m(
                "MIME-Version: 1.0\r\nContent-Type: application/pdf; name=\"dokument.pdf\"\r\n"
                        + "Content-Disposition: attachment; filename=\"dokument.pdf\"\r\n"),
                "CAdES B-LTA obalujici.p7m");
        assertEquals("dokument.pdf", payload(r).getName());
        assertEquals("podepsaný atribut content-hints", payload(r).getInfo().get("Původ názvu"));
        assertTrue(r.summary().getOrDefault("Jméno podepsaného souboru", "").startsWith("dokument.pdf"),
                r.summary().toString());
    }

    @Test
    @DisplayName("bez content-hints se jméno odvodí z obálky a karta to řekne")
    void derivedNameSaysSo() throws Exception {
        ContainerInspector.Result r = ContainerInspector.inspect(p7m(null), "CAdES B-LTA obalujici.p7m");
        assertEquals("CAdES B-LTA obalujici.pdf", payload(r).getName());
        assertTrue(payload(r).getInfo().get("Původ názvu").startsWith("odvozen z názvu obálky"));
        assertFalse(r.summary().containsKey("Jméno podepsaného souboru"));
    }

    @Test
    @DisplayName("z hlavičky MIME se bere jen jméno, ne cesta ani jiný text")
    void mimeFileName() {
        assertEquals("smlouva.pdf", ContainerInspector.mimeFileName(
                "Content-Disposition: attachment; filename=\"C:\\dokumenty\\smlouva.pdf\""));
        assertEquals("a.pdf", ContainerInspector.mimeFileName("Content-Type: application/pdf; name=a.pdf"));
        assertNull(ContainerInspector.mimeFileName("Podepsaný dokument"));
        assertNull(ContainerInspector.mimeFileName("Content-Disposition: attachment; filename=\"../\""));
    }
}
