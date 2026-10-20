package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Identifikátor klienta MPSV ve výpisu certifikátu.
 *
 * <p>Pokyn uživatele ze 16. 9. 2026: nese-li certifikát v alternativním jménu položku
 * {@code otherName} s OID {@code 1.3.6.1.4.1.11801.2.1}, má prohlížeč vypsat řádek „IK MPSV"
 * s číslem — a to hned za předmětem. Nenese-li ji, řádek tam být nemá; takových certifikátů
 * je většina, takže prázdný výpis není nález, nýbrž pravidlo.
 *
 * <p>Číslo je v certifikátu uloženo jako řetězec ASN.1 (v praxi {@code 0C 0A} a deset číslic
 * v ASCII). Test proto staví certifikát, do kterého to sám zapíše, a ptá se na desítkovou
 * podobu — tedy na to, co uvidí čtenář, ne na bajty.
 */
class IkMpsvTest {

    private static final String DN = "CN=Kdosi,C=CZ";
    private static final String IK = "1680937599";

    /** Certifikát s alternativním jménem podle {@code san}; {@code null} rozšíření vynechá. */
    private static X509CertificateHolder cert(GeneralNames san) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keys = gen.generateKeyPair();
        X500Name name = new X500Name(DN);
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, new Date(now - 86_400_000L), new Date(now + 86_400_000L),
                name, keys.getPublic());
        if (san != null) builder.addExtension(Extension.subjectAlternativeName, false, san);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        return builder.build(signer);
    }

    /** Alternativní jméno s jedinou položkou otherName daného OID a hodnoty. */
    private static GeneralNames otherName(String oid, org.bouncycastle.asn1.ASN1Encodable value) {
        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(new ASN1ObjectIdentifier(oid));
        v.add(new DERTaggedObject(true, 0, value));
        return new GeneralNames(new GeneralName(GeneralName.otherName, new DERSequence(v)));
    }

    @Test
    @DisplayName("Číslo se přečte a vrátí v desítkové podobě")
    void readsTheNumber() throws Exception {
        assertEquals(IK, IkMpsv.of(cert(otherName(IkMpsv.OID, new DERUTF8String(IK)))));
    }

    @Test
    @DisplayName("Jiný druh řetězce nevadí — rozhoduje délka a číslice")
    void printableStringToo() throws Exception {
        // Norma připouští víc druhů řetězce; certifikáty z praxe používají UTF8String, ale
        // vázat se na jeho značku by znamenalo přehlédnout identifikátor kvůli něčemu, na čem
        // nezáleží.
        assertEquals(IK, IkMpsv.of(cert(otherName(IkMpsv.OID, new DERPrintableString(IK)))));
    }

    @Test
    @DisplayName("Certifikát bez toho rozšíření žádné číslo nevydá")
    void withoutTheExtension() throws Exception {
        assertNull(IkMpsv.of(cert(null)));
    }

    @Test
    @DisplayName("Jiné OID v alternativním jménu se nezamění za to naše")
    void otherOidIgnored() throws Exception {
        assertNull(IkMpsv.of(cert(otherName("1.3.6.1.4.1.11801.2.9", new DERUTF8String(IK)))));
    }

    @Test
    @DisplayName("Ve výpisu certifikátu stojí řádek hned za předmětem")
    void rowSitsRightAfterTheSubject() throws Exception {
        List<ContainerInspector.TextRow> rows = ContainerInspector.splitRows(
                ContainerInspector.describeCertificate(
                        cert(otherName(IkMpsv.OID, new DERUTF8String(IK))).getEncoded()));
        assertEquals("Subjekt", rows.get(0).label());
        assertEquals("IK MPSV", rows.get(1).label(), "řádek po předmětu: " + rows.get(1));
        assertEquals(IK, rows.get(1).value());
    }

    @Test
    @DisplayName("Bez identifikátoru se řádek do výpisu vůbec nedostane")
    void rowAbsentWithoutTheIdentifier() throws Exception {
        String text = ContainerInspector.describeCertificate(cert(null).getEncoded());
        assertFalse(text.contains("IK MPSV"), "výpis: " + text);
    }
}
