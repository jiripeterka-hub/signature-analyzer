package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSVerifierCertificateNotValidException;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampToken;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Podpis kódu (Authenticode) — etapa 2: <b>integrita a matematika</b>. Přepočte otisk souboru
 * a porovná ho s podepsaným, ověří podpis nad SpcIndirectDataContent klíčem z certifikátu
 * podepisujícího a totéž u časového razítka. Certifikát sám (řetěz k důvěryhodné autoritě,
 * účel podepisování kódu, revokace) se tu neposuzuje — to je etapa 3.
 * <p>
 * <b>Otisk souboru PE</b> („Windows Authenticode Portable Executable Signature Format“): celý
 * soubor kromě pole CheckSum volitelné hlavičky, položky tabulky certifikátů v datových
 * adresářích a tabulky certifikátů samotné. Specifikace popisuje hašování po sekcích seřazených
 * podle posunu; u souborů bez mezer mezi sekcemi, jaké vyrábějí běžné linkery, vyjde totéž
 * jako souvislé hašování, které se tu používá (stejně jako v jsign a osslsigncode).
 * <p>
 * <b>Otisk balíčku MSI</b>: obsah všech proudů složeného dokumentu po úložištích, seřazených
 * podle jména v UTF-16LE (při shodném začátku jde delší jméno první), za každým úložištěm jeho
 * identifikátor třídy; proudy {@code \u0005DigitalSignature} a {@code \u0005MsiDigitalSignatureEx}
 * pod kořenem se vynechají, obsah druhého se ale vloží na jeho místo v pořadí. Ten nese
 * „předotisk“ metadat adresáře (jména, velikosti, časy), který se ověří zvlášť. Postup podle
 * osslsigncode ({@code msi.c}: {@code msi_hash_dir}, {@code msi_prehash_dir}), Microsoft ho
 * nezveřejnil.
 */
final class AuthenticodeVerifier {

    private AuthenticodeVerifier() {}

    private static final Map<String, String> DIGESTS = Map.of(
            "1.3.14.3.2.26", "SHA-1",
            "2.16.840.1.101.3.4.2.1", "SHA-256",
            "2.16.840.1.101.3.4.2.2", "SHA-384",
            "2.16.840.1.101.3.4.2.3", "SHA-512",
            "1.2.840.113549.2.5", "MD5");

    /** Název hašovací funkce pro JCA, nebo {@code null}, když ji neznáme. */
    static String jcaDigest(String oid) { return DIGESTS.get(oid); }

    /** Přepočítá otisk souboru danou funkcí; výjimka nese důvod, proč to nejde. */
    interface FileDigest {
        byte[] compute(String jcaAlgorithm) throws Exception;
    }

    // ---------------------------------------------------------------- PE

    static FileDigest pe(byte[] file, int checksumOff, int certDirOff, long certOff, long certSize) {
        return alg -> {
            MessageDigest md = MessageDigest.getInstance(alg);
            md.update(file, 0, checksumOff);
            md.update(file, checksumOff + 4, certDirOff - checksumOff - 4);
            md.update(file, certDirOff + 8, (int) (certOff - certDirOff - 8));
            long after = certOff + certSize;
            if (after < file.length) md.update(file, (int) after, (int) (file.length - after));
            return md.digest();
        };
    }

    // ---------------------------------------------------------------- MSI

    private static final String DS = AuthenticodeInspector.MSI_SIGNATURE_STREAM;
    private static final String DSEX = AuthenticodeInspector.MSI_SIGNATURE_EX_STREAM;
    private static final byte[] DS_NAME = utf16z(DS), DSEX_NAME = utf16z(DSEX);

    static FileDigest msi(CompoundFile cf) {
        return alg -> {
            byte[] ex = cf.stream(DSEX);
            if (ex != null) {
                MessageDigest pre = MessageDigest.getInstance(alg);
                prehashDir(cf.root(), pre, true);
                if (!Arrays.equals(pre.digest(), ex)) {
                    throw new IntegrityException("otisk metadat balíčku (MsiDigitalSignatureEx)"
                            + " nesouhlasí — jména, velikosti nebo časy proudů se po podpisu změnily");
                }
            }
            MessageDigest md = MessageDigest.getInstance(alg);
            hashDir(cf, cf.root(), md, true, ex);
            return md.digest();
        };
    }

    /** Nesoulad zjištěný už při výpočtu otisku — soubor je po podpisu změněný. */
    static final class IntegrityException extends Exception {
        IntegrityException(String message) { super(message); }
    }

    private static void hashDir(CompoundFile cf, CompoundFile.Entry dir, MessageDigest md,
                                boolean isRoot, byte[] ex) {
        for (CompoundFile.Entry e : sorted(dir.children)) {
            if (isRoot && Arrays.equals(e.nameBytes(), DS_NAME)) continue;
            if (isRoot && Arrays.equals(e.nameBytes(), DSEX_NAME)) {
                if (ex != null) md.update(ex);
                continue;
            }
            if (e.type() == CompoundFile.STREAM) {
                if (e.size() == 0) continue;
                md.update(cf.read(e));
            } else if (e.type() == CompoundFile.STORAGE) {
                hashDir(cf, e, md, false, null);
            }
        }
        md.update(dir.clsid());
    }

    private static void prehashDir(CompoundFile.Entry dir, MessageDigest md, boolean isRoot) {
        prehashMetadata(dir, md);
        for (CompoundFile.Entry e : sorted(dir.children)) {
            if (isRoot && (prefixEquals(e.nameBytes(), DS_NAME) || prefixEquals(e.nameBytes(), DSEX_NAME))) continue;
            if (e.type() == CompoundFile.STREAM) prehashMetadata(e, md);
            else if (e.type() == CompoundFile.STORAGE) prehashDir(e, md, false);
        }
    }

    private static void prehashMetadata(CompoundFile.Entry e, MessageDigest md) {
        byte[] raw = e.raw;
        if (e.type() != CompoundFile.ROOT) md.update(raw, 0, Math.max(0, e.nameLen() - 2));
        if (e.type() != CompoundFile.STREAM) md.update(raw, 0x50, 16);   // CLSID
        else md.update(raw, 0x78, 4);                                    // dolní polovina velikosti
        md.update(raw, 0x60, 4);                                         // stateBits
        if (e.type() != CompoundFile.ROOT) md.update(raw, 0x64, 16);     // vytvoření a změna
    }

    /** Pořadí jako {@code dirent_cmp_hash}: memcmp jmen v UTF-16LE, při shodě delší první. */
    private static List<CompoundFile.Entry> sorted(List<CompoundFile.Entry> entries) {
        List<CompoundFile.Entry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparing(CompoundFile.Entry::nameBytes, (a, b) -> {
            int n = Math.min(a.length, b.length);
            for (int i = 0; i < n; i++) {
                int d = (a[i] & 0xFF) - (b[i] & 0xFF);
                if (d != 0) return d;
            }
            return Integer.compare(b.length, a.length);
        }));
        return out;
    }

    private static boolean prefixEquals(byte[] name, byte[] known) {
        int n = Math.min(name.length, known.length);
        return Arrays.equals(name, 0, n, known, 0, n);
    }

    private static byte[] utf16z(String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        return Arrays.copyOf(b, b.length + 2);
    }

    // ---------------------------------------------------------------- podpis a razítko

    /** Výsledek jedné kontroly: {@code ok} je {@code null}, když se provést nedala. */
    record Check(Boolean ok, String text) {
        static Check yes(String t) { return new Check(true, t); }
        static Check no(String t) { return new Check(false, t); }
        static Check unknown(String t) { return new Check(null, t); }
    }

    static Check integrity(FileDigest file, String digestOid, byte[] signed) {
        String alg = jcaDigest(digestOid);
        if (alg == null) return Check.unknown("nelze ověřit — neznámá hašovací funkce " + digestOid);
        try {
            byte[] actual = file.compute(alg);
            return Arrays.equals(actual, signed)
                    ? Check.yes("otisk souhlasí — soubor se od podpisu nezměnil")
                    : Check.no("otisk NESOUHLASÍ — soubor byl po podpisu změněn"
                            + " (přepočtený " + alg + ": " + java.util.HexFormat.of().formatHex(actual) + ")");
        } catch (IntegrityException e) {
            return Check.no(e.getMessage());
        } catch (Exception e) {
            return Check.unknown("nelze ověřit — " + ContainerInspector.describe(e));
        }
    }

    /** Matematika podpisu SignerInfo: atribut messageDigest proti obsahu a podpis klíčem certifikátu. */
    static Check signature(SignerInformation si, X509CertificateHolder cert) {
        if (cert == null) return Check.unknown("nelze ověřit — certifikát podepisujícího v podpisu chybí");
        try {
            return si.verify(new JcaSimpleSignerInfoVerifierBuilder().build(cert))
                    ? Check.yes("platná — podpis sedí ke klíči certifikátu podepisujícího")
                    : Check.no("NEPLATNÁ — podpis ke klíči certifikátu nesedí");
        } catch (CMSVerifierCertificateNotValidException e) {
            // Matematika prošla, BouncyCastle jen odmítá certifikát mimo dobu platnosti
            // v deklarovaném čase podpisu; o certifikátu rozhoduje etapa 3.
            return Check.yes("platná — podpis sedí ke klíči certifikátu (certifikát v deklarovaném"
                    + " čase podpisu neplatil)");
        } catch (Exception e) {
            return Check.no("NEPLATNÁ — " + ContainerInspector.describe(e));
        }
    }

    /** Razítko RFC 3161: podpis autority nad tokenem a otisk hodnoty podpisu, který razítkuje. */
    static Check rfc3161(TimeStampToken token, byte[] signatureValue) {
        try {
            X509CertificateHolder tsa = null;
            for (Object o : token.getCertificates().getMatches(token.getSID())) {
                if (o instanceof X509CertificateHolder h) tsa = h;
            }
            String alg = jcaDigest(token.getTimeStampInfo().getMessageImprintAlgOID().getId());
            if (alg == null) return Check.unknown("nelze ověřit — neznámá hašovací funkce razítka");
            boolean imprint = Arrays.equals(token.getTimeStampInfo().getMessageImprintDigest(),
                    MessageDigest.getInstance(alg).digest(signatureValue));
            if (!imprint) return Check.no("NEPLATNÉ — razítko kryje jiná data než tento podpis");
            if (tsa == null) return Check.unknown("kryje tento podpis; podpis autority nelze ověřit"
                    + " — certifikát autority v razítku chybí");
            Check sig = signature(token.toCMSSignedData().getSignerInfos().getSigners().iterator().next(), tsa);
            return sig.ok() == Boolean.TRUE ? Check.yes("platné — kryje tento podpis a podpis autority sedí")
                    : sig.ok() == null ? sig : Check.no("NEPLATNÉ — podpis autority nesedí");
        } catch (Exception e) {
            return Check.unknown("nelze ověřit — " + ContainerInspector.describe(e));
        }
    }

    private static boolean rawRsaDigestMatches(X509CertificateHolder cert, byte[] signature,
                                               String digestAlg, byte[] data) {
        try {
            var key = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(cert).getPublicKey();
            if (!"RSA".equals(key.getAlgorithm())) return false;
            javax.crypto.Cipher rsa = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(javax.crypto.Cipher.DECRYPT_MODE, key);
            return Arrays.equals(rsa.doFinal(signature), MessageDigest.getInstance(digestAlg).digest(data));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Protipodpis Authenticode: kryje hodnotu podpisu, ověří se klíčem certifikátu autority.
     * <p>
     * Ověřuje se ručně, ne {@link SignerInformation#verify}: protipodpis Authenticode (podle
     * PKCS #9 v1.5) nese v podepsaných atributech content-type, který RFC 5652 u protipodpisu
     * zakazuje, a BouncyCastle ho proto odmítne celý (30. 10. 2026: všech 75 protipodpisů ve
     * sbírkách, přitom Windows je u týchž souborů přijímají). Kontroluje se totéž, co by
     * zkontroloval BouncyCastle bez té námitky: atribut messageDigest proti otisku hodnoty
     * podpisu a podpis nad zakódovanými podepsanými atributy.
     */
    static Check countersignature(CMSSignedData cms, SignerInformation si) {
        try {
            for (SignerInformation csi : si.getCounterSignatures().getSigners()) {
                X509CertificateHolder cert = null;
                for (Object o : cms.getCertificates().getMatches(csi.getSID())) {
                    if (o instanceof X509CertificateHolder h) cert = h;
                }
                String alg = jcaDigest(csi.getDigestAlgOID());
                if (alg == null) return Check.unknown("nelze ověřit — neznámá hašovací funkce protipodpisu");
                var md = csi.getSignedAttributes() == null ? null : csi.getSignedAttributes().get(
                        org.bouncycastle.asn1.cms.CMSAttributes.messageDigest);
                if (md == null) return Check.unknown("nelze ověřit — protipodpis nemá atribut messageDigest");
                byte[] declared = org.bouncycastle.asn1.ASN1OctetString.getInstance(
                        md.getAttrValues().getObjectAt(0)).getOctets();
                if (!Arrays.equals(declared, MessageDigest.getInstance(alg).digest(si.getSignature()))) {
                    return Check.no("NEPLATNÝ — protipodpis kryje jiná data než tento podpis");
                }
                if (cert == null) return Check.unknown("kryje tento podpis; podpis autority nelze ověřit"
                        + " — certifikát autority v podpisu chybí");
                String sigAlg = new org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator().getSignatureName(
                        csi.getDigestAlgorithmID(),
                        new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                new org.bouncycastle.asn1.ASN1ObjectIdentifier(csi.getEncryptionAlgOID())));
                java.security.Signature verifier = java.security.Signature.getInstance(sigAlg);
                verifier.initVerify(new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                        .getCertificate(cert).getPublicKey());
                byte[] signedAttrs = csi.getEncodedSignedAttributes();
                verifier.update(signedAttrs);
                if (verifier.verify(csi.getSignature())) {
                    return Check.yes("platný — kryje tento podpis a podpis autority sedí");
                }
                // Razítka „Symantec Time Stamping Services Signer - G4“ (a VeriSign G2) podepisují
                // RSA holý otisk bez obálky DigestInfo, kterou PKCS #1 předepisuje; Windows je
                // přijímají. Naměřeno 30. 10. 2026: 20 ze 75 protipodpisů ve sbírkách, všechny od
                // těchto autorit. Uzná se jen přesná shoda otisku podepsaných atributů.
                if (rawRsaDigestMatches(cert, csi.getSignature(), alg, signedAttrs)) {
                    return Check.yes("platný — kryje tento podpis a podpis autority sedí (autorita"
                            + " podepsala holý otisk bez struktury DigestInfo, jak to dělala razítka"
                            + " Symantec/VeriSign; Windows takový podpis přijímají)");
                }
                return Check.no("NEPLATNÝ — podpis autority nesedí");
            }
            return Check.unknown("nelze ověřit — protipodpis se nepodařilo přečíst");
        } catch (Exception e) {
            return Check.unknown("nelze ověřit — " + ContainerInspector.describe(e));
        }
    }
}
