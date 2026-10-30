package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Podpis kódu v prohlížeči kontejnerů — etapa 1, jen výpis (30. 10. 2026).
 *
 * <p>Soubor PE se tu skládá ručně a nejmenší možný: hlavička DOS, „PE\0\0“, hlavička COFF,
 * volitelná hlavička PE32 s šestnácti datovými adresáři a za ní tabulka certifikátů s jedním
 * WIN_CERTIFICATE. Podpis je skutečný objekt CMS s obsahem SpcIndirectDataContent; že by ho
 * Windows přijaly, se nezkouší — etapa 1 nic neověřuje.
 */
class AuthenticodeInspectorTest {

    private static KeyPair keys;
    private static X509CertificateHolder cert;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        X500Name dn = new X500Name("CN=Zkusebni vydavatel kodu,O=Test");
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        cert = new JcaX509v3CertificateBuilder(dn, BigInteger.TEN,
                new Date(System.currentTimeMillis() - 86_400_000L),
                new Date(System.currentTimeMillis() + 86_400_000L * 365), dn, keys.getPublic()).build(cs);
    }

    /** PKCS #7 s obsahem SpcIndirectDataContent (obraz PE s daným otiskem SHA-256). */
    private static byte[] authenticode(byte[] digest) throws Exception {
        ASN1EncodableVector data = new ASN1EncodableVector();
        data.add(new ASN1ObjectIdentifier(AuthenticodeInspector.SPC_PE_IMAGE_DATA));
        data.add(new DERSequence());
        ASN1EncodableVector digestInfo = new ASN1EncodableVector();
        digestInfo.add(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE));
        digestInfo.add(new DEROctetString(digest));
        ASN1EncodableVector spc = new ASN1EncodableVector();
        spc.add(new DERSequence(data));
        spc.add(new DERSequence(digestInfo));

        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()), cert));
        gen.addCertificate(cert);
        return gen.generate(new CMSProcessableByteArray(
                new ASN1ObjectIdentifier(AuthenticodeInspector.SPC_INDIRECT_DATA),
                new DERSequence(spc).getEncoded()), true).getEncoded();
    }

    /** Nejmenší soubor PE32 (x64 by měl magic 0x20B); s podpisem, nebo bez něj. */
    private static byte[] pe(byte[] pkcs7) {
        int peOff = 0x40, opt = peOff + 24, optSize = 96 + 16 * 8;
        int headersEnd = opt + optSize;
        int certOff = (headersEnd + 7) & ~7;
        int certLen = pkcs7 == null ? 0 : 8 + pkcs7.length;
        ByteBuffer b = ByteBuffer.allocate(certOff + ((certLen + 7) & ~7)).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'M').put(1, (byte) 'Z').putInt(0x3C, peOff);
        b.put(peOff, (byte) 'P').put(peOff + 1, (byte) 'E');
        b.putShort(peOff + 4, (short) 0x14C);            // Machine: x86
        b.putShort(peOff + 20, (short) optSize);
        b.putShort(peOff + 22, (short) 0x0102);          // spustitelný, 32bitový
        b.putShort(opt, (short) 0x10B);                  // PE32
        b.putInt(opt + 92, 16);                          // NumberOfRvaAndSizes
        if (pkcs7 != null) {
            b.putInt(opt + 96 + 4 * 8, certOff);         // datový adresář 4: tabulka certifikátů
            b.putInt(opt + 96 + 4 * 8 + 4, (certLen + 7) & ~7);
            b.putInt(certOff, certLen);
            b.putShort(certOff + 4, (short) 0x0200);     // WIN_CERT_REVISION_2_0
            b.putShort(certOff + 6, (short) 0x0002);     // WIN_CERT_TYPE_PKCS_SIGNED_DATA
            b.position(certOff + 8);
            b.put(pkcs7);
        }
        return b.array();
    }

    /**
     * Otisk Authenticode souboru, který se teprve podepíše: tabulka certifikátů začne na konci
     * nepodepsaného souboru a z otisku se vynechává, stejně jako CheckSum a položka adresáře.
     */
    private static byte[] peDigest() throws Exception {
        byte[] unsigned = pe(null);
        int opt = 0x40 + 24;
        return AuthenticodeVerifier.pe(unsigned, opt + 64, opt + 96 + 4 * 8, unsigned.length, 0)
                .compute("SHA-256");
    }

    @Test
    @DisplayName("podepsaný program: podpis, podepisující a podepsaný otisk se vypíšou")
    void signedPe() throws Exception {
        byte[] file = pe(authenticode(peDigest()));
        assertEquals(ContainerInspector.Kind.CODE, ContainerInspector.detectKind(file));
        ContainerInspector.Result r = ContainerInspector.inspect(file, "setup.exe");
        assertEquals(null, r.error());
        assertEquals("spustitelný soubor Windows (PE32, x86)", r.summary().get("Formát"));
        assertEquals("ano (Authenticode)", r.summary().get("Podpis kódu"));
        assertEquals("Zkusebni vydavatel kodu", r.summary().get("Podepisující"));
        assertTrue(r.summary().get("Podepsaný otisk souboru").contains("SHA"), r.summary().toString());
        assertTrue(r.summary().get("Integrita souboru").startsWith("✓ otisk souhlasí"), r.summary().toString());
        assertTrue(r.summary().get("Matematika podpisu").startsWith("✓ platná"), r.summary().toString());
        assertTrue(r.summary().get("Certifikát podepisujícího").startsWith("AnEP ho zatím neověřuje"));

        ContainerInspector.Node sig = r.root().getChildren().get(0);
        assertEquals("podpis-authenticode.p7s", sig.getName());
        assertEquals("obraz souboru PE (SpcPeImageData)", sig.getInfo().get("Podepsaný objekt"));
        assertTrue(sig.getInfo().get("Podepsaný otisk souboru").endsWith(
                java.util.HexFormat.of().formatHex(peDigest())), sig.getInfo().toString());
        assertTrue(sig.getInfo().get("Uložen v").startsWith("tabulka certifikátů souboru PE"));
        // Vyjmutý podpis je objekt CMS, který jde otevřít zvlášť.
        assertEquals(ContainerInspector.Kind.CMS, ContainerInspector.detectKind(sig.getData()));
    }

    @Test
    @DisplayName("změna souboru po podpisu: otisk nesouhlasí, podpis sám zůstává platný")
    void tamperedPe() throws Exception {
        byte[] file = pe(authenticode(peDigest()));
        file[0x10] ^= 1;   // hlavička DOS, kterou otisk kryje
        ContainerInspector.Result r = ContainerInspector.inspect(file, "setup.exe");
        assertTrue(r.summary().get("Integrita souboru").startsWith("✗ otisk NESOUHLASÍ"), r.summary().toString());
        assertTrue(r.summary().get("Matematika podpisu").startsWith("✓"), r.summary().toString());
    }

    @Test
    @DisplayName("pole CheckSum otisk nekryje — jeho změna integritu neporuší")
    void checksumExcluded() throws Exception {
        byte[] file = pe(authenticode(peDigest()));
        file[0x40 + 24 + 64] ^= 1;
        ContainerInspector.Result r = ContainerInspector.inspect(file, "setup.exe");
        assertTrue(r.summary().get("Integrita souboru").startsWith("✓"), r.summary().toString());
    }

    @Test
    @DisplayName("poškozená hodnota podpisu: matematika podpisu neplatí")
    void brokenSignatureValue() throws Exception {
        byte[] pkcs7 = authenticode(peDigest());
        byte[] value = new org.bouncycastle.cms.CMSSignedData(pkcs7).getSignerInfos().getSigners()
                .iterator().next().getSignature();
        int at = indexOf(pkcs7, value) + value.length / 2;
        pkcs7[at] ^= 1;
        ContainerInspector.Result r = ContainerInspector.inspect(pe(pkcs7), "setup.exe");
        assertTrue(r.summary().get("Matematika podpisu").startsWith("✗ NEPLATNÁ"), r.summary().toString());
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        throw new AssertionError("hodnota podpisu nenalezena");
    }

    @Test
    @DisplayName("nepodepsaný program: souhrn řekne, že podpis nemá")
    void unsignedPe() {
        ContainerInspector.Result r = ContainerInspector.inspect(pe(null), "nepodepsany.exe");
        assertEquals(ContainerInspector.Kind.CODE, r.kind());
        assertEquals("ne — soubor není podepsaný", r.summary().get("Podpis kódu"));
        assertTrue(r.root().getChildren().isEmpty());
    }

    @Test
    @DisplayName("„MZ“ bez hlavičky PE ani dokument Wordu 97–2003 programem nejsou")
    void notCode() {
        byte[] mz = new byte[256];
        mz[0] = 'M';
        mz[1] = 'Z';
        assertEquals(ContainerInspector.Kind.OTHER, ContainerInspector.detectKind(mz));
        byte[] ole = Arrays.copyOf(CompoundFile.MAGIC, 1024);
        assertEquals(ContainerInspector.Kind.OTHER, ContainerInspector.detectKind(ole));
    }
}
