package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.DigestInfo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Podpis PDF se {@code /SubFilter adbe.x509.rsa_sha1} — přečtený a kryptograficky zkontrolovaný.
 *
 * <p>DSS tento typ nezpracovává vůbec: v {@code /Contents} není CMS, ale holá podpisová hodnota
 * PKCS #1 zabalená do OCTET STRING, a certifikáty leží zvlášť v položce {@code /Cert} (první je
 * certifikát podpisu). Kontrola tady je jen kryptografická — veřejným klíčem certifikátu se hodnota
 * rozšifruje a otisk z ní ({@code DigestInfo}) se porovná s otiskem podepsaných bajtů. Certifikát
 * samotný (řetěz, revokaci, kvalifikovanost) posuzuje {@link Pkcs1SignatureValidator}.
 *
 * @param signatureValue podpisová hodnota, nebo null, když se nedala přečíst
 * @param certificates   certifikáty z {@code /Cert}, první je certifikát podpisu
 * @param digestAlgorithm jméno hašovací funkce z {@code DigestInfo} (např. „SHA-1“), nebo OID,
 *                        nemá-li jméno; null, když se hodnota nedala rozšifrovat
 * @param intact         TRUE/FALSE podle shody otisků; null, když se porovnání nedalo provést
 * @param problem        proč se něco nedalo přečíst či porovnat (pro čtenáře), jinak null
 */
public record Pkcs1PdfSignature(
        byte[] signatureValue,
        List<X509Certificate> certificates,
        String digestAlgorithm,
        Boolean intact,
        String problem) {

    public static final String SUB_FILTER = "adbe.x509.rsa_sha1";

    public X509Certificate signingCertificate() {
        return certificates.isEmpty() ? null : certificates.get(0);
    }

    /** Délka klíče RSA certifikátu podpisu v bitech, nebo 0, není-li klíč RSA. */
    public int keySize() {
        X509Certificate c = signingCertificate();
        return c != null && c.getPublicKey() instanceof RSAPublicKey rsa
                ? rsa.getModulus().bitLength() : 0;
    }

    /**
     * @param pdfBytes celý soubor PDF — z něj se berou podepsané bajty podle {@code /ByteRange}
     */
    public static Pkcs1PdfSignature read(PDSignature sig, byte[] pdfBytes) {
        byte[] value = null;
        List<X509Certificate> certs = new ArrayList<>();
        try {
            byte[] contents = sig.getContents();
            if (contents != null && contents.length > 0) {
                // Čte se jen první objekt: výplň nulami za ním (rezervované místo) se tak
                // nemusí odřezávat, a nehrozí uříznout nulový bajt, který k hodnotě patří.
                try (ASN1InputStream in = new ASN1InputStream(contents)) {
                    value = ASN1OctetString.getInstance(in.readObject()).getOctets();
                }
            }
        } catch (Exception e) {
            return new Pkcs1PdfSignature(null, certs, null, null,
                    "podpisovou hodnotu se nepodařilo přečíst (" + e.getMessage() + ")");
        }
        try {
            COSBase certObj = sig.getCOSObject().getDictionaryObject(COSName.getPDFName("Cert"));
            List<byte[]> ders = new ArrayList<>();
            if (certObj instanceof COSString s) {
                ders.add(s.getBytes());
            } else if (certObj instanceof COSArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.getObject(i) instanceof COSString s) ders.add(s.getBytes());
                }
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (byte[] der : ders) {
                certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (Exception e) {
            return new Pkcs1PdfSignature(value, certs, null, null,
                    "certifikáty z položky /Cert se nepodařilo přečíst (" + e.getMessage() + ")");
        }
        if (value == null) {
            return new Pkcs1PdfSignature(null, certs, null, null, "podpis nemá podpisovou hodnotu");
        }
        if (certs.isEmpty()) {
            return new Pkcs1PdfSignature(value, certs, null, null,
                    "podpis nenese certifikát (položka /Cert chybí)");
        }

        DigestInfo di;
        try {
            javax.crypto.Cipher rsa = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(javax.crypto.Cipher.DECRYPT_MODE, certs.get(0).getPublicKey());
            di = DigestInfo.getInstance(ASN1Primitive.fromByteArray(rsa.doFinal(value)));
        } catch (Exception e) {
            // Hodnotu nelze rozšifrovat klíčem z certifikátu: podpis nevytvořil klíč, ke kterému
            // certifikát patří, nebo je hodnota poškozená. O obsahu to neříká nic.
            return new Pkcs1PdfSignature(value, certs, null, Boolean.FALSE,
                    "podpisovou hodnotu nelze ověřit veřejným klíčem z certifikátu");
        }
        String oid = di.getAlgorithmId().getAlgorithm().getId();
        String alg = switch (oid) {
            case "1.3.14.3.2.26"          -> "SHA-1";
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512";
            case "1.2.840.113549.2.5"     -> "MD5";
            default -> null;
        };
        if (alg == null) {
            return new Pkcs1PdfSignature(value, certs, oid, null,
                    "hašovací funkci " + oid + " aplikace nezná");
        }
        try {
            byte[] signed;
            try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
                signed = sig.getSignedContent(in);
            }
            boolean match = MessageDigest.isEqual(di.getDigest(),
                    MessageDigest.getInstance(alg).digest(signed));
            return new Pkcs1PdfSignature(value, certs, alg, match, null);
        } catch (Exception e) {
            return new Pkcs1PdfSignature(value, certs, alg, null,
                    "podepsané bajty se nepodařilo přečíst (" + e.getMessage() + ")");
        }
    }
}
