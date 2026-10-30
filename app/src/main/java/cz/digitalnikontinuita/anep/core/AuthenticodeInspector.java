package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.cms.SignerInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.cms.SignerInformation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Podpis kódu (Authenticode) ve spustitelném souboru Windows (.exe, .dll, … — formát PE)
 * a v instalačním balíčku Windows Installer (.msi).
 * <p>
 * Uživatel 30. 10. 2026 požádal o ověřování podpisů na kódu. DSS formát nezná, a tak se postupuje
 * po etapách. Etapa 1 ukazuje v prohlížeči kontejnerů, co podpis obsahuje: podepisujícího,
 * podepsaný otisk souboru, certifikáty, časová razítka a vnořené podpisy. Etapa 2
 * ({@link AuthenticodeVerifier}) k tomu přepočte otisk souboru a ověří matematiku podpisu
 * a razítka. Certifikát (řetěz, účel, revokace) se zatím neposuzuje a souhrn to říká výslovně.
 * <p>
 * Kde podpis leží:
 * <ul>
 *   <li><b>PE</b> — v tabulce certifikátů (datový adresář 4 volitelné hlavičky; jeho „RVA“ je
 *       výjimečně posun v souboru). Tabulka se skládá ze struktur WIN_CERTIFICATE zarovnaných
 *       na 8 bajtů; podpis je typ 2, PKCS #7 SignedData (Microsoft, „Windows Authenticode
 *       Portable Executable Signature Format“).</li>
 *   <li><b>MSI</b> — v proudu {@code \u0005DigitalSignature} složeného dokumentu OLE; vedle může
 *       ležet {@code \u0005MsiDigitalSignatureEx} s otiskem metadat.</li>
 * </ul>
 * Podepsaným obsahem SignedData je SpcIndirectDataContent: typ podepsaného objektu a otisk
 * souboru (DigestInfo). Časové razítko je buď razítko RFC 3161 v atributu Microsoftu, nebo starší
 * protipodpis Authenticode (countersignature, RFC 2985), který nese čas v atributu signingTime.
 * Další podpisy (typicky SHA-256 vedle SHA-1) se vnořují do nepodepsaného atributu prvního.
 */
public final class AuthenticodeInspector {

    private AuthenticodeInspector() {}

    static final String SPC_INDIRECT_DATA = "1.3.6.1.4.1.311.2.1.4";
    static final String SPC_PE_IMAGE_DATA = "1.3.6.1.4.1.311.2.1.15";
    static final String SPC_SIPINFO = "1.3.6.1.4.1.311.2.1.30";
    static final String SPC_SP_OPUS_INFO = "1.3.6.1.4.1.311.2.1.12";
    static final String SPC_STATEMENT_TYPE = "1.3.6.1.4.1.311.2.1.11";
    static final String SPC_INDIVIDUAL = "1.3.6.1.4.1.311.2.1.21";
    static final String SPC_COMMERCIAL = "1.3.6.1.4.1.311.2.1.22";
    static final String SPC_NESTED_SIGNATURE = "1.3.6.1.4.1.311.2.4.1";
    /** Razítko RFC 3161 v nepodepsaném atributu (Microsoft); hodnota je token jako v CAdES. */
    static final String MS_RFC3161_TIMESTAMP = "1.3.6.1.4.1.311.3.3.1";
    static final String COUNTERSIGNATURE = "1.2.840.113549.1.9.6";

    static final String MSI_SIGNATURE_STREAM = "\u0005DigitalSignature";
    static final String MSI_SIGNATURE_EX_STREAM = "\u0005MsiDigitalSignatureEx";
    private static final Map<String, String> OLE_CLASSES = Map.of(
            "{000C1084-0000-0000-C000-000000000046}", "instalační balíček Windows Installer (MSI)",
            "{000C1086-0000-0000-C000-000000000046}", "opravný balíček Windows Installer (MSP)",
            "{000C1082-0000-0000-C000-000000000046}", "transformace Windows Installer (MST)");

    // ---------------------------------------------------------------- rozpoznání

    /**
     * Je to program, který může nést podpis kódu? Spustitelný soubor podle podpisu „MZ“ (stačí
     * hlavička, volající předávají i jen šestnáct bajtů), balíček Windows Installer podle třídy
     * kořene složeného dokumentu — dokument Wordu či Excelu 97–2003 je složený dokument také,
     * ale program není.
     */
    static boolean looksLikeCode(byte[] b) {
        if (b == null || b.length < 2) return false;
        if (b[0] == 'M' && b[1] == 'Z') return b.length < 64 || peOffset(b) > 0;
        if (CompoundFile.looksLike(b) && b.length >= 512) {
            try {
                return OLE_CLASSES.containsKey(new CompoundFile(b).rootClsid());
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /** Posun hlavičky „PE\0\0“, nebo -1. */
    private static int peOffset(byte[] b) {
        if (b.length < 0x40) return -1;
        long off = CompoundFile.le32(b, 0x3C);
        if (off < 0x40 || off + 24 > b.length) return -1;
        int o = (int) off;
        return b[o] == 'P' && b[o + 1] == 'E' && b[o + 2] == 0 && b[o + 3] == 0 ? o : -1;
    }

    // ---------------------------------------------------------------- vstupní bod

    static Result inspect(byte[] bytes, String fileName) throws Exception {
        Node root = new Node(fileName, "", true, null);
        List<String> warnings = new ArrayList<>();
        Map<String, String> summary = new LinkedHashMap<>();

        byte[] pkcs7;
        String typeLabel;
        String where;
        AuthenticodeVerifier.FileDigest fileDigest;
        if (bytes[0] == 'M') {
            Pe pe = readPe(bytes, warnings);
            typeLabel = pe.label;
            summary.put("Formát", pe.label);
            summary.put("Velikost souboru", ContainerInspector.formatSize(bytes.length));
            pkcs7 = pe.pkcs7;
            where = pe.where;
            fileDigest = pe.pkcs7 == null ? null : AuthenticodeVerifier.pe(bytes, pe.checksumOff,
                    pe.certDirOff, pe.certOff, pe.certSize);
        } else {
            CompoundFile cf = new CompoundFile(bytes);
            fileDigest = AuthenticodeVerifier.msi(cf);
            typeLabel = OLE_CLASSES.getOrDefault(cf.rootClsid(), "složený dokument OLE");
            summary.put("Formát", typeLabel);
            summary.put("Velikost souboru", ContainerInspector.formatSize(bytes.length));
            pkcs7 = cf.stream(MSI_SIGNATURE_STREAM);
            where = "proud \\u0005DigitalSignature složeného dokumentu";
            if (pkcs7 != null && cf.hasStream(MSI_SIGNATURE_EX_STREAM)) {
                where += "; vedle něj \\u0005MsiDigitalSignatureEx (otisk metadat balíčku)";
            }
        }

        if (pkcs7 == null || pkcs7.length == 0) {
            summary.put("Podpis kódu", "ne — soubor není podepsaný");
            return new Result(Kind.CODE, typeLabel, root, summary, warnings, null);
        }

        CMSSignedData cms = new CMSSignedData(pkcs7);
        Node sigNode = root.add(new Node("podpis-authenticode.p7s", "podpis-authenticode.p7s", false,
                "podpis kódu Authenticode (PKCS #7)"));
        sigNode.data(pkcs7);
        sigNode.put("Role", "podpis kódu Authenticode (PKCS #7)");
        sigNode.put("Uložen v", where);
        Signature main = describeSignature(cms, sigNode, fileDigest);

        ContainerInspector.CmsCounts counts = ContainerInspector.addCmsSignatureNodes(root, cms, false);

        // Vnořené podpisy: každý je samostatný objekt CMS, jde ho vyjmout a otevřít zvlášť.
        List<byte[]> nested = nestedSignatures(cms);
        List<Signature> nestedResults = new ArrayList<>();
        int i = 0;
        for (byte[] n : nested) {
            i++;
            String name = "podpis-vnoreny-" + i + ".p7s";
            Node nn = root.add(new Node(name, name, false, "vnořený podpis kódu Authenticode"));
            nn.data(n);
            nn.put("Role", "vnořený podpis kódu Authenticode (PKCS #7)");
            nn.put("Uložen v", "nepodepsaný atribut prvního podpisu (1.3.6.1.4.1.311.2.4.1)");
            try {
                nestedResults.add(describeSignature(new CMSSignedData(n), nn, fileDigest));
            } catch (Exception e) {
                nn.put("Poznámka", "vnořený podpis se nepodařilo přečíst (" + ContainerInspector.describe(e) + ")");
            }
        }

        summary.put("Podpis kódu", "ano (Authenticode)");
        if (main.signer != null) summary.put("Podepisující", main.signer);
        if (main.digest != null) summary.put("Podepsaný otisk souboru", main.digest);
        summary.put("Integrita souboru", mark(main.integrity));
        summary.put("Matematika podpisu", mark(main.math));
        summary.put("Časové razítko", main.timestamp != null
                ? main.timestamp + (main.timestampCheck != null ? "; " + mark(main.timestampCheck) : "")
                : "ne");
        if (!nested.isEmpty()) summary.put("Vnořené podpisy", nested.size() + " — " + nestedSummary(nestedResults, nested.size()));
        summary.put("Vložené certifikáty", String.valueOf(counts.certificates()));
        summary.put("Vložené CRL / OCSP", counts.crls() + " / " + counts.ocsps());
        summary.put("Certifikát podepisujícího", "AnEP ho zatím neověřuje (řetěz k důvěryhodné"
                + " autoritě, účel podepisování kódu, revokace)");
        return new Result(Kind.CODE, typeLabel, root, summary, warnings, null);
    }

    // ---------------------------------------------------------------- PE

    /** Rozložení souboru PE; posuny jsou to, co otisk Authenticode vynechává. */
    private record Pe(String label, byte[] pkcs7, String where,
                      int checksumOff, int certDirOff, long certOff, long certSize) {
        Pe(String label) { this(label, null, null, 0, 0, 0, 0); }
    }

    private static Pe readPe(byte[] b, List<String> warnings) {
        int pe = peOffset(b);
        if (pe < 0) throw new IllegalArgumentException("soubor nemá hlavičku PE");
        int machine = CompoundFile.le16(b, pe + 4);
        int optSize = CompoundFile.le16(b, pe + 20);
        int characteristics = CompoundFile.le16(b, pe + 22);
        int opt = pe + 24;
        if (opt + optSize > b.length || optSize < 2) throw new IllegalArgumentException("zkrácená hlavička PE");
        int magic = CompoundFile.le16(b, opt);
        boolean plus = magic == 0x20B;
        if (magic != 0x10B && !plus) throw new IllegalArgumentException("neznámý typ volitelné hlavičky PE");
        String kind = (characteristics & 0x2000) != 0 ? "knihovna DLL" : "spustitelný soubor";
        String arch = switch (machine) {
            case 0x14C -> "x86";
            case 0x8664 -> "x64";
            case 0xAA64 -> "ARM64";
            case 0x1C4 -> "ARM";
            default -> String.format("stroj 0x%04X", machine);
        };
        String label = kind + " Windows (" + (plus ? "PE32+" : "PE32") + ", " + arch + ")";

        int nDirs = (int) CompoundFile.le32(b, opt + (plus ? 108 : 92));
        int dirs = opt + (plus ? 112 : 96);
        if (nDirs <= 4 || dirs + 5 * 8 > opt + optSize) return new Pe(label);
        long certOff = CompoundFile.le32(b, dirs + 4 * 8);
        long certSize = CompoundFile.le32(b, dirs + 4 * 8 + 4);
        if (certOff == 0 || certSize == 0) return new Pe(label);
        if (certOff + certSize > b.length || certSize < 8) {
            warnings.add("Tabulka certifikátů ukazuje mimo soubor; podpis se nepodařilo vyjmout.");
            return new Pe(label);
        }

        // WIN_CERTIFICATE: dwLength, wRevision, wCertificateType, data; zarovnáno na 8 bajtů.
        byte[] first = null;
        int entries = 0;
        long pos = certOff, end = certOff + certSize;
        while (pos + 8 <= end) {
            int p = (int) pos;
            long len = CompoundFile.le32(b, p);
            int type = CompoundFile.le16(b, p + 6);
            if (len < 8 || pos + len > end) break;
            entries++;
            if (type == 2 && first == null) {
                first = java.util.Arrays.copyOfRange(b, p + 8, (int) (pos + len));
                // signtool zarovnává i uvnitř záznamu: za objektem PKCS #7 bývají nuly, které
                // BouncyCastle odmítne jako „Extra data“ (naměřeno 30. 10. 2026 na 151 ze 207
                // podepsaných programů ve sbírkách). Platí délka z hlavičky DER.
                int der = PdfStructure.derLength(first);
                if (der > 0 && der < first.length) first = java.util.Arrays.copyOf(first, der);
            }
            pos += (len + 7) & ~7L;
        }
        if (entries > 1) {
            warnings.add("Tabulka certifikátů obsahuje " + entries + " záznamy; zobrazen je první"
                    + " podpis PKCS #7.");
        }
        if (first == null) {
            warnings.add("Tabulka certifikátů neobsahuje podpis PKCS #7.");
            return new Pe(label);
        }
        String where = String.format("tabulka certifikátů souboru PE (posun 0x%X, %s)",
                certOff, ContainerInspector.formatSize(certSize));
        if (certOff + certSize < b.length) {
            where += "; za ní ještě " + ContainerInspector.formatSize(b.length - certOff - certSize)
                    + " dat";
        }
        return new Pe(label, first, where, opt + 64, dirs + 4 * 8, certOff, certSize);
    }

    // ---------------------------------------------------------------- SignedData

    /** Co se z podpisu dá vypsat do souhrnu. */
    private record Signature(String signer, String digest, String timestamp,
                             AuthenticodeVerifier.Check integrity, AuthenticodeVerifier.Check math,
                             AuthenticodeVerifier.Check timestampCheck) {}

    private static Signature describeSignature(CMSSignedData cms, Node node,
                                               AuthenticodeVerifier.FileDigest fileDigest) {
        String contentType = cms.getSignedContentTypeOID();
        String signerName = null, digest = null, timestamp = null;
        AuthenticodeVerifier.Check integrity = AuthenticodeVerifier.Check.unknown(
                "nelze ověřit — podepsaný otisk se nepodařilo přečíst");
        AuthenticodeVerifier.Check math, tsCheck = null;
        if (!SPC_INDIRECT_DATA.equals(contentType)) {
            node.put("Podepsaný obsah", contentType + " — neočekávaný typ, podpis Authenticode"
                    + " podepisuje SpcIndirectDataContent");
        } else {
            try {
                ASN1Sequence spc = spcIndirectData(cms);
                ASN1Sequence data = ASN1Sequence.getInstance(spc.getObjectAt(0));
                String objType = ASN1ObjectIdentifier.getInstance(data.getObjectAt(0)).getId();
                node.put("Podepsaný objekt", switch (objType) {
                    case SPC_PE_IMAGE_DATA -> "obraz souboru PE (SpcPeImageData)";
                    case SPC_SIPINFO -> "soubor podle rozhraní SIP (SpcSipInfo; typicky MSI)";
                    default -> objType;
                });
                ASN1Sequence digestInfo = ASN1Sequence.getInstance(spc.getObjectAt(1));
                String alg = ContainerInspector.oidName(org.bouncycastle.asn1.x509.AlgorithmIdentifier
                        .getInstance(digestInfo.getObjectAt(0)).getAlgorithm().getId());
                byte[] value = ASN1OctetString.getInstance(digestInfo.getObjectAt(1)).getOctets();
                digest = alg;
                node.put("Podepsaný otisk souboru", alg + ": " + HexFormat.of().formatHex(value));
                String digestOid = org.bouncycastle.asn1.x509.AlgorithmIdentifier
                        .getInstance(digestInfo.getObjectAt(0)).getAlgorithm().getId();
                integrity = fileDigest == null
                        ? AuthenticodeVerifier.Check.unknown("nelze ověřit — rozložení souboru neznámé")
                        : AuthenticodeVerifier.integrity(fileDigest, digestOid, value);
            } catch (Exception e) {
                node.put("Podepsaný obsah", "SpcIndirectDataContent se nepodařilo přečíst ("
                        + ContainerInspector.describe(e) + ")");
            }
        }

        SignerInformation si = cms.getSignerInfos().getSigners().stream().findFirst().orElse(null);
        node.put("Integrita souboru", mark(integrity));
        if (si == null) {
            return new Signature(null, digest, null, integrity,
                    AuthenticodeVerifier.Check.unknown("nelze ověřit — podpis nemá SignerInfo"), null);
        }
        X509CertificateHolder cert = certificateFor(cms, si.getSID());
        if (cert != null) {
            signerName = commonName(cert);
            node.put("Podepisující", String.valueOf(cert.getSubject()));
            node.put("Vydavatel certifikátu", String.valueOf(cert.getIssuer()));
            node.put("Certifikát platí", ContainerInspector.formatDate(cert.getNotBefore()) + " – "
                    + ContainerInspector.formatDate(cert.getNotAfter()));
        }
        node.put("Otisková funkce podpisu", ContainerInspector.oidName(si.getDigestAlgOID()));
        node.put("Podpisový algoritmus", ContainerInspector.oidName(si.getEncryptionAlgOID()));
        math = AuthenticodeVerifier.signature(si, cert);
        node.put("Matematika podpisu", mark(math));

        AttributeTable signed = si.getSignedAttributes();
        if (signed != null) {
            Attribute opus = signed.get(new ASN1ObjectIdentifier(SPC_SP_OPUS_INFO));
            if (opus != null) describeOpusInfo(opus.getAttrValues().getObjectAt(0), node);
            Attribute statement = signed.get(new ASN1ObjectIdentifier(SPC_STATEMENT_TYPE));
            if (statement != null) {
                try {
                    for (ASN1Encodable o : ASN1Sequence.getInstance(statement.getAttrValues().getObjectAt(0))) {
                        String id = ASN1ObjectIdentifier.getInstance(o).getId();
                        if (SPC_INDIVIDUAL.equals(id)) node.put("Druh podpisu (dle podepisujícího)", "individuální");
                        if (SPC_COMMERCIAL.equals(id)) node.put("Druh podpisu (dle podepisujícího)", "komerční");
                    }
                } catch (Exception ignored) {
                    // Nečitelný údaj vynechat.
                }
            }
        }

        AttributeTable unsigned = si.getUnsignedAttributes();
        if (unsigned != null) {
            Attribute rfc = unsigned.get(new ASN1ObjectIdentifier(MS_RFC3161_TIMESTAMP));
            if (rfc != null) {
                Date t = timestampTime(rfc.getAttrValues().getObjectAt(0));
                timestamp = "ano — RFC 3161" + (t != null ? ", " + ContainerInspector.formatDate(t) : "");
                try {
                    tsCheck = AuthenticodeVerifier.rfc3161(new org.bouncycastle.tsp.TimeStampToken(
                            new CMSSignedData(ContentInfo.getInstance(rfc.getAttrValues().getObjectAt(0)))),
                            si.getSignature());
                } catch (Exception e) {
                    tsCheck = AuthenticodeVerifier.Check.unknown("nelze ověřit — razítko se nepodařilo"
                            + " přečíst (" + ContainerInspector.describe(e) + ")");
                }
            }
            Attribute counter = unsigned.get(new ASN1ObjectIdentifier(COUNTERSIGNATURE));
            if (counter != null) {
                String legacy = describeCountersignature(cms, counter.getAttrValues().getObjectAt(0));
                node.put("Časové razítko (protipodpis Authenticode)", legacy);
                if (timestamp == null) {
                    timestamp = "ano — protipodpis Authenticode, " + legacy;
                    tsCheck = AuthenticodeVerifier.countersignature(cms, si);
                }
            }
        }
        if (timestamp != null) node.put("Časové razítko", timestamp);
        if (tsCheck != null) node.put("Ověření razítka", mark(tsCheck));
        return new Signature(signerName, digest, timestamp, integrity, math, tsCheck);
    }

    /** Výsledek kontroly se značkou, jakou čtenář zná z karty Analýzy. */
    private static String mark(AuthenticodeVerifier.Check c) {
        return (c.ok() == null ? "? " : c.ok() ? "✓ " : "✗ ") + c.text();
    }

    private static String nestedSummary(List<Signature> results, int total) {
        long bad = results.stream().filter(r -> r.integrity().ok() == Boolean.FALSE
                || r.math().ok() == Boolean.FALSE).count();
        long good = results.stream().filter(r -> r.integrity().ok() == Boolean.TRUE
                && r.math().ok() == Boolean.TRUE).count();
        if (bad > 0) return "✗ u " + bad + " nesouhlasí otisk nebo podpis";
        if (good == total) return "✓ integrita i matematika podpisu souhlasí";
        return "? u některých se ověření nepodařilo provést";
    }

    /** SpcIndirectDataContent; Authenticode ho ukládá jako SEQUENCE, ne jako OCTET STRING. */
    private static ASN1Sequence spcIndirectData(CMSSignedData cms) throws Exception {
        ContentInfo ci = cms.toASN1Structure();
        ASN1Encodable content = SignedData.getInstance(ci.getContent()).getEncapContentInfo().getContent();
        if (content instanceof ASN1OctetString os) {
            return ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(os.getOctets()));
        }
        return ASN1Sequence.getInstance(content);
    }

    /**
     * CN předmětu přímo z RDN. Ne z textové podoby DN: vydavatelé kódu mají v CN čárku
     * („Atomi Systems, Inc.“), BouncyCastle ji v textu escapuje a rozdělení podle čárek
     * ji uřízlo (30. 10. 2026, srovnání s Get-AuthenticodeSignature na 207 programech).
     */
    private static String commonName(X509CertificateHolder cert) {
        var rdns = cert.getSubject().getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.CN);
        if (rdns.length > 0 && rdns[0].getFirst() != null
                && rdns[0].getFirst().getValue() instanceof ASN1String s) {
            return s.getString();
        }
        return String.valueOf(cert.getSubject());
    }

    private static X509CertificateHolder certificateFor(CMSSignedData cms, SignerId sid) {
        try {
            for (Object o : cms.getCertificates().getMatches(sid)) {
                if (o instanceof X509CertificateHolder h) return h;
            }
        } catch (Exception ignored) {
            // Bez certifikátu se vypíše jen vydavatel a sériové číslo ze SignerInfo.
        }
        return null;
    }

    /** SpcSpOpusInfo: název programu a odkaz, jak je podepisující uvedl. */
    private static void describeOpusInfo(ASN1Encodable value, Node node) {
        try {
            for (ASN1Encodable o : ASN1Sequence.getInstance(value)) {
                ASN1TaggedObject t = ASN1TaggedObject.getInstance(o);
                ASN1TaggedObject inner = t.getExplicitBaseObject() instanceof ASN1TaggedObject x ? x : null;
                if (t.getTagNo() == 0 && inner != null) {
                    node.put("Název programu (dle podepisujícího)", spcString(inner));
                } else if (t.getTagNo() == 1) {
                    ASN1TaggedObject link = ASN1TaggedObject.getInstance(t.getExplicitBaseObject());
                    if (link.getTagNo() == 0) {
                        node.put("Odkaz (dle podepisujícího)", new String(
                                ASN1OctetString.getInstance(link, false).getOctets(), StandardCharsets.ISO_8859_1));
                    } else if (link.getTagNo() == 2 && link.getExplicitBaseObject() instanceof ASN1TaggedObject s) {
                        node.put("Odkaz (dle podepisujícího)", spcString(s));
                    }
                }
            }
        } catch (Exception ignored) {
            // Nepovinný popisný údaj; nečitelný se vynechá.
        }
    }

    /** SpcString: [0] BMPString (UTF-16BE) nebo [1] IA5String, oba implicitně značkované. */
    private static String spcString(ASN1TaggedObject t) {
        byte[] octets = ASN1OctetString.getInstance(t, false).getOctets();
        return t.getTagNo() == 0 ? new String(octets, StandardCharsets.UTF_16BE)
                : new String(octets, StandardCharsets.ISO_8859_1);
    }

    private static Date timestampTime(ASN1Encodable token) {
        try {
            return new org.bouncycastle.tsp.TimeStampToken(new CMSSignedData(ContentInfo.getInstance(token)))
                    .getTimeStampInfo().getGenTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** Protipodpis: čas z jeho atributu signingTime a jméno autority z certifikátu. */
    private static String describeCountersignature(CMSSignedData cms, ASN1Encodable value) {
        try {
            SignerInfo csi = SignerInfo.getInstance(value);
            String when = null;
            ASN1Set attrs = csi.getAuthenticatedAttributes();
            if (attrs != null) {
                Attribute st = new AttributeTable(attrs).get(new ASN1ObjectIdentifier("1.2.840.113549.1.9.5"));
                if (st != null) {
                    when = ContainerInspector.formatDate(org.bouncycastle.asn1.cms.Time.getInstance(
                            st.getAttrValues().getObjectAt(0)).getDate());
                }
            }
            String who = null;
            var sid = csi.getSID();
            if (!sid.isTagged()) {
                var iss = org.bouncycastle.asn1.cms.IssuerAndSerialNumber.getInstance(sid.getId());
                X509CertificateHolder cert = certificateFor(cms, new SignerId(iss.getName(), iss.getSerialNumber().getValue()));
                if (cert != null) who = commonName(cert);
            }
            return (when != null ? when : "čas neuveden") + (who != null && !who.isEmpty() ? " (" + who + ")" : "");
        } catch (Exception e) {
            return "nečitelný (" + ContainerInspector.describe(e) + ")";
        }
    }

    private static List<byte[]> nestedSignatures(CMSSignedData cms) {
        List<byte[]> out = new ArrayList<>();
        for (SignerInformation si : cms.getSignerInfos().getSigners()) {
            AttributeTable u = si.getUnsignedAttributes();
            if (u == null) continue;
            Attribute nested = u.get(new ASN1ObjectIdentifier(SPC_NESTED_SIGNATURE));
            if (nested == null) continue;
            for (ASN1Encodable v : nested.getAttrValues()) {
                try {
                    out.add(v.toASN1Primitive().getEncoded("DER"));
                } catch (Exception ignored) {
                    // Nečitelnou hodnotu přeskočit.
                }
            }
        }
        return out;
    }
}
