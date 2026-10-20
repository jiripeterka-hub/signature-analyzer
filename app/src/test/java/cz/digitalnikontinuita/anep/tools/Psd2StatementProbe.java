package cz.digitalnikontinuita.anep.tools;

import cz.digitalnikontinuita.anep.core.ContainerInspector;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * No PSD2 certificate exists in the corpus, so the test vector is built here — the structure
 * written out by hand from ETSI TS 119 495 clause 5.1, so that reading it back exercises the
 * decoder against the specification rather than against itself.
 *
 * <p>Usage: {@code java -cp <kam se přeložilo>;app/target/classes;$(cat cp.txt)
 * cz.digitalnikontinuita.anep.tools.Psd2StatementProbe <adresář pro certifikáty>}. The certificates it
 * writes can be checked against the specification with
 * {@code openssl asn1parse -inform DER -in <cert> -i}.
 *
 * <pre>
 *   PSD2QcType ::= SEQUENCE { rolesOfPSP RolesOfPSP, nCAName NCAName, nCAId NCAId }
 *   RolesOfPSP ::= SEQUENCE OF RoleOfPSP
 *   RoleOfPSP  ::= SEQUENCE { roleOfPspOid OBJECT IDENTIFIER, roleOfPspName UTF8String }
 * </pre>
 */
public class Psd2StatementProbe {

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Path out = Paths.get(args[0]);

        // 1. Two known roles, a named authority — an ordinary Czech PISP/AISP certificate.
        show("běžné (dvě známé role, ČNB)", out.resolve("psd2-bezne.cer"),
                psd2(new String[][]{
                        {"0.4.0.19495.1.2", "PSP_PI"},
                        {"0.4.0.19495.1.3", "PSP_AI"}},
                        "Czech National Bank", "CZ-CNB"));

        // 2. A role OID outside the four in the specification: the name the CA wrote is then the
        //    only thing that can be said, and it must not be dropped.
        show("neznámý OID role", out.resolve("psd2-neznama-role.cer"),
                psd2(new String[][]{
                        {"0.4.0.19495.1.1", "PSP_AS"},
                        {"1.2.203.99.1", "PSP_XX"}},
                        "Národní banka", "CZ-XX"));

        // 3. All four roles.
        show("všechny čtyři role", out.resolve("psd2-vsechny.cer"),
                psd2(new String[][]{
                        {"0.4.0.19495.1.1", "PSP_AS"},
                        {"0.4.0.19495.1.2", "PSP_PI"},
                        {"0.4.0.19495.1.3", "PSP_AI"},
                        {"0.4.0.19495.1.4", "PSP_IC"}},
                        "Bundesanstalt für Finanzdienstleistungsaufsicht", "DE-BAFIN"));
    }

    /** Builds the PSD2QcType structure exactly as the specification lays it out. */
    private static ASN1Encodable psd2(String[][] roles, String ncaName, String ncaId) {
        ASN1EncodableVector rolesOfPsp = new ASN1EncodableVector();
        for (String[] role : roles) {
            ASN1EncodableVector one = new ASN1EncodableVector();
            one.add(new ASN1ObjectIdentifier(role[0]));
            one.add(new DERUTF8String(role[1]));
            rolesOfPsp.add(new DERSequence(one));
        }
        ASN1EncodableVector psd2 = new ASN1EncodableVector();
        psd2.add(new DERSequence(rolesOfPsp));
        psd2.add(new DERUTF8String(ncaName));
        psd2.add(new DERUTF8String(ncaId));
        return new DERSequence(psd2);
    }

    private static void show(String what, Path file, ASN1Encodable psd2Info) throws Exception {
        // The statement sits beside the ones a real PSD2 certificate carries.
        ASN1EncodableVector statements = new ASN1EncodableVector();
        statements.add(new DERSequence(new ASN1ObjectIdentifier("0.4.0.1862.1.1")));
        statements.add(new DERSequence(new ASN1Encodable[]{
                new ASN1ObjectIdentifier("0.4.0.1862.1.6"),
                new DERSequence(new ASN1ObjectIdentifier("0.4.0.1862.1.6.2"))}));
        statements.add(new DERSequence(new ASN1Encodable[]{
                new ASN1ObjectIdentifier("0.4.0.19495.2"), psd2Info}));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keys = gen.generateKeyPair();
        X500Name who = new X500Name("CN=Zkušební poskytovatel platebních služeb,O=Test,C=CZ");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                who, BigInteger.valueOf(1), new Date(), new Date(System.currentTimeMillis()
                + 365L * 24 * 3600 * 1000), who, keys.getPublic());
        builder.addExtension(Extension.qCStatements, false, new DERSequence(statements));
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider("BC").build(keys.getPrivate())));

        byte[] der = cert.getEncoded();
        Files.write(file, der);
        System.out.println("=== " + what + "   → " + file);
        String text = ContainerInspector.describeCertificate(der);
        int at = text == null ? -1 : text.indexOf("Kvalifikovaná prohlášení");
        System.out.println(at < 0 ? "   ŽÁDNÁ PROHLÁŠENÍ" : text.substring(at));
    }
}
