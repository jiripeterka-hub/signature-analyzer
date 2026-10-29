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
 * Obálky CMS (.p7m, .p7s) v prohlížeči kontejnerů.
 *
 * <p>Jméno vloženého obsahu — dotaz uživatele z 29. 10. 2026 nad obálkou CAdES B-LTA vytvořenou DSS: prohlížeč ukázal
 * vložené PDF pod jménem obálky, ne pod jménem původního souboru. CMS jméno souboru nenese;
 * nese ho jen nepovinný podepsaný atribut content-hints, kam ho nástroje zapisují jako hlavičku
 * MIME. Když ho obálka má, platí to jméno; když ne, jméno se odvodí a karta to řekne.
 */
class ContainerCmsTest {

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

    /**
     * Nález uživatele z 29. 10. 2026: u podpisu CAdES B-LTA z DSS prohlížeč neukázal žádnou
     * vloženou odpověď OCSP, protože ji DSS ukládá celou pod id-ri-ocsp-response (RFC 5940)
     * a prohlížeč hledal jen holou BasicOCSPResponse pod id-pkix-ocsp-basic.
     */
    @Test
    @DisplayName("odpověď OCSP uložená celá (id-ri-ocsp-response) se ukáže a vyjme beze změny")
    void wholeOcspResponseShown() throws Exception {
        // Jen úspěšnou odpověď BC do CMS pustí.
        org.bouncycastle.cert.ocsp.BasicOCSPResp basic = goodOcsp();
        byte[] ocsp = new org.bouncycastle.cert.ocsp.OCSPRespBuilder()
                .build(org.bouncycastle.cert.ocsp.OCSPRespBuilder.SUCCESSFUL, basic).getEncoded();
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()), cert));
        gen.addOtherRevocationInfo(ContainerInspector.ID_RI_OCSP_RESPONSE,
                org.bouncycastle.asn1.ASN1Primitive.fromByteArray(ocsp));
        byte[] p7s = gen.generate(new CMSProcessableByteArray("obsah".getBytes(StandardCharsets.UTF_8)), false)
                .getEncoded();

        ContainerInspector.Result r = ContainerInspector.inspect(p7s, "podpis.p7s");
        assertEquals("0 / 1", r.summary().get("Vložené CRL / OCSP"));
        ContainerInspector.Node folder = r.root().getChildren().stream()
                .filter(n -> n.getName().startsWith("Odpovědi OCSP")).findFirst().orElseThrow();
        ContainerInspector.Node item = folder.getChildren().get(0);
        assertEquals("ocsp-1.ocsp", item.getName());
        assertTrue(java.util.Arrays.equals(ocsp, item.getData()), "bajty vyjmuté beze změny");
        assertEquals("1", item.getInfo().get("Počet záznamů"));
    }

    /**
     * Forma CAdES-X-L nese ověřovací data v nepodepsaných atributech certificate-values
     * a revocation-values. Prohlížeč je do 29. 10. 2026 nevypisoval (ve sbírkách 5 ze 66
     * objektů CMS); Inspektor ano.
     */
    @Test
    @DisplayName("CRL a OCSP z atributu revocation-values se ukážou i s místem uložení")
    void revocationValuesShown() throws Exception {
        org.bouncycastle.cert.X509CRLHolder crl = new org.bouncycastle.cert.X509v2CRLBuilder(
                cert.getSubject(), new Date())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
        org.bouncycastle.cert.ocsp.BasicOCSPResp basic = goodOcsp();
        org.bouncycastle.asn1.esf.RevocationValues values = new org.bouncycastle.asn1.esf.RevocationValues(
                new org.bouncycastle.asn1.x509.CertificateList[]{crl.toASN1Structure()},
                new org.bouncycastle.asn1.ocsp.BasicOCSPResponse[]{
                        org.bouncycastle.asn1.ocsp.BasicOCSPResponse.getInstance(basic.getEncoded())},
                null);
        ASN1EncodableVector certValues = new ASN1EncodableVector();
        certValues.add(cert.toASN1Structure());

        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()), cert));
        gen.addCertificate(cert);
        org.bouncycastle.cms.CMSSignedData plain = gen.generate(
                new CMSProcessableByteArray("obsah".getBytes(StandardCharsets.UTF_8)), false);
        ASN1EncodableVector unsigned = new ASN1EncodableVector();
        unsigned.add(new Attribute(new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.23"),
                new DERSet(new DERSequence(certValues))));
        unsigned.add(new Attribute(new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.24"),
                new DERSet(values)));
        org.bouncycastle.cms.SignerInformation si = plain.getSignerInfos().getSigners().iterator().next();
        si = org.bouncycastle.cms.SignerInformation.replaceUnsignedAttributes(si, new AttributeTable(unsigned));
        byte[] p7s = org.bouncycastle.cms.CMSSignedData.replaceSigners(plain,
                new org.bouncycastle.cms.SignerInformationStore(si)).getEncoded();

        ContainerInspector.Result r = ContainerInspector.inspect(p7s, "podpis.p7s");
        assertEquals("1 / 1", r.summary().get("Vložené CRL / OCSP"));
        // Certifikát je v poli certificates i v atributu: jednou, s oběma místy.
        assertEquals("1", r.summary().get("Vložené certifikáty"));
        assertEquals("pole certificates objektu CMS; nepodepsaný atribut certificate-values",
                folder(r, "Certifikáty").getChildren().get(0).getInfo().get("Uloženo v"));
        assertEquals("nepodepsaný atribut revocation-values",
                folder(r, "Seznamy CRL").getChildren().get(0).getInfo().get("Uloženo v"));
        ContainerInspector.Node ocspItem = folder(r, "Odpovědi OCSP").getChildren().get(0);
        assertEquals("nepodepsaný atribut revocation-values", ocspItem.getInfo().get("Uloženo v"));
        assertEquals("ocsp-1.ocsp", ocspItem.getName(), "holá odpověď se vyjme obalená");
    }

    private static ContainerInspector.Node folder(ContainerInspector.Result r, String prefix) {
        return r.root().getChildren().stream()
                .filter(n -> n.getName().startsWith(prefix)).findFirst().orElseThrow();
    }

    /** Odpověď „good“ na vlastní certifikát, podepsaná týmž klíčem. */
    private static org.bouncycastle.cert.ocsp.BasicOCSPResp goodOcsp() throws Exception {
        return new org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder(
                new org.bouncycastle.cert.ocsp.RespID(cert.getSubject()))
                .addResponse(new org.bouncycastle.cert.ocsp.CertificateID(
                        new JcaDigestCalculatorProviderBuilder().build().get(
                                org.bouncycastle.cert.ocsp.CertificateID.HASH_SHA1),
                        cert, cert.getSerialNumber()),
                        org.bouncycastle.cert.ocsp.CertificateStatus.GOOD)
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()), null, new Date());
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
