package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ocsp.BasicOCSPResponse;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.ocsp.OCSPResponse;
import org.bouncycastle.asn1.ocsp.ResponderID;
import org.bouncycastle.asn1.ocsp.SingleResponse;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.CertificateList;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Rozklad objektu CMS (podpis, časové razítko) na uzly Inspektoru.
 *
 * <p>Společný pro všechny formáty: CMS je v {@code /Contents} podpisu PDF, v souboru {@code .p7s}
 * či {@code .p7m}, v ASiC i uvnitř každého časového razítka. Proto se tu nic neví o tom, odkud
 * bajty přišly — volající dodá jen „kandidáty“ na podepsaný obsah ({@link Content}), nad kterými
 * se přepočítá otisk a porovná s atributem {@code message-digest} či s otiskem v razítku.
 *
 * <p>Nic se tu neověřuje v kryptografickém smyslu (podpis nad atributy, řetěz certifikátů):
 * to dělá DSS a jeho závěr připisuje {@link StructureAnnotations}. Shoda otisků je ale fakt
 * o souboru, který se dá zjistit bez jakékoli politiky, a odborník se na ni ptá jako první.
 */
public final class CmsStructure {

    private CmsStructure() {}

    /**
     * Kandidát na podepsaný obsah: popisek pro čtenáře a způsob, jak jeho bajty projdou otiskovou
     * funkcí. Funkce místo bajtů proto, aby se rozsah {@code /ByteRange} nemusel kopírovat.
     */
    public record Content(String label, Consumer<MessageDigest> feeder) {
        public static Content of(String label, byte[] bytes) {
            return new Content(label, md -> md.update(bytes));
        }
    }

    // ------------------------------------------------------------------ klíče totožnosti

    public static String certKey(byte[] der)     { return "cert:" + ContainerInspector.sha256Hex(der); }
    public static String crlKey(byte[] der)      { return "crl:" + ContainerInspector.sha256Hex(der); }
    /** U OCSP se klíčuje vnitřní BasicOCSPResponse — CMS ji nese holou, PDF i DSS v obálce. */
    public static String ocspKey(byte[] basicDer) { return "ocsp:" + ContainerInspector.sha256Hex(basicDer); }
    public static String tstKey(byte[] der)      { return "tst:" + ContainerInspector.sha256Hex(der); }
    public static String signatureValueKey(byte[] value) {
        return "sigval:" + ContainerInspector.sha256Hex(value);
    }

    // ------------------------------------------------------------------ SignedData

    /**
     * Uzel celého objektu CMS se vším, co nese.
     *
     * @param der        DER objektu (bez výplně, kterou PDF za podpis přidává)
     * @param title      nadpis uzlu
     * @param candidates kandidáti na podepsaný obsah u odděleného podpisu; u vloženého obsahu se
     *                   jako kandidát použije sám vložený obsah
     */
    public static StructureNode signedData(byte[] der, String title, List<Content> candidates) {
        StructureNode node = new StructureNode(Kind.CMS, title).data(der, "podpis.p7s");
        CMSSignedData cms;
        try {
            cms = new CMSSignedData(der);
        } catch (Exception e) {
            node.note(Severity.ERROR, "Objekt CMS se nepodařilo přečíst (" + describe(e) + ").");
            node.row("Velikost", ContainerInspector.formatSize(der.length));
            return node;
        }
        String ct = cms.getSignedContentTypeOID();
        byte[] content = cms.getSignedContent() != null ? ContainerInspector.contentBytes(cms) : null;
        node.row("Struktura", "SignedData, verze " + cms.getVersion());
        node.row("Velikost", ContainerInspector.formatSize(der.length));
        node.row("Typ obsahu (eContentType)", contentTypeLabel(ct));
        node.row("Obsah", content != null
                ? "vložen v objektu (" + ContainerInspector.formatSize(content.length) + ")"
                : "oddělený — podepsaná data jsou mimo objekt CMS");
        List<String> algs = new ArrayList<>();
        for (AlgorithmIdentifier a : cms.getDigestAlgorithmIDs()) algs.add(digestLabel(a.getAlgorithm().getId()));
        node.row("Otiskové funkce (digestAlgorithms)", String.join(", ", algs));
        node.subtitle(cms.getSignerInfos().size() + "× SignerInfo, "
                + cms.getCertificates().getMatches(null).size() + " certifikátů");

        List<Content> effective = content != null
                ? List.of(Content.of("obsah vložený v objektu CMS (eContent)", content))
                : candidates;
        for (StructureNode child : contents(cms, effective)) node.add(child);
        return node;
    }

    /**
     * Podepisující a s nimi přibalené certifikáty, CRL a OCSP — bez obalového uzlu, aby je
     * slovník podpisu v PDF i razítko mohly zavěsit pod sebe.
     */
    static List<StructureNode> contents(CMSSignedData cms, List<Content> candidates) {
        List<StructureNode> out = new ArrayList<>();
        Collection<X509CertificateHolder> certs = cms.getCertificates().getMatches(null);
        int idx = 0;
        Collection<SignerInformation> signers = cms.getSignerInfos().getSigners();
        for (SignerInformation si : signers) {
            idx++;
            out.add(signer(si, cms, signers.size() > 1 ? idx : 0, candidates, certs));
        }
        if (!certs.isEmpty()) {
            StructureNode g = new StructureNode(Kind.GROUP,
                    "Certifikáty v CMS (certificates) (" + certs.size() + ")");
            for (X509CertificateHolder h : certs) {
                try {
                    g.add(certificate(h.getEncoded()));
                } catch (Exception ignored) {
                    // Holder se z DER už jednou sestavil, zakódovat ho znovu nejde jen u poškozeného.
                }
            }
            out.add(g);
        }
        Collection<X509CRLHolder> crls = cms.getCRLs().getMatches(null);
        if (!crls.isEmpty()) {
            StructureNode g = new StructureNode(Kind.GROUP, "Seznamy CRL v CMS (crls) (" + crls.size() + ")");
            for (X509CRLHolder h : crls) {
                try { g.add(crl(h.getEncoded())); } catch (Exception ignored) { }
            }
            out.add(g);
        }
        List<byte[]> ocsps = new ArrayList<>();
        try {
            for (Object o : cms.getOtherRevocationInfo(OCSPObjectIdentifiers.id_pkix_ocsp_basic).getMatches(null)) {
                if (o instanceof ASN1Encodable enc) ocsps.add(enc.toASN1Primitive().getEncoded("DER"));
            }
        } catch (Exception ignored) {
            // Starší objekty CMS pole otherRevocationInfo vůbec neznají.
        }
        if (!ocsps.isEmpty()) {
            StructureNode g = new StructureNode(Kind.GROUP,
                    "Odpovědi OCSP v CMS (otherRevocationInfo) (" + ocsps.size() + ")");
            for (byte[] o : ocsps) g.add(ocsp(o));
            out.add(g);
        }
        return out;
    }

    // ------------------------------------------------------------------ SignerInfo

    private static StructureNode signer(SignerInformation si, CMSSignedData cms, int index,
                                        List<Content> candidates, Collection<X509CertificateHolder> certs) {
        StructureNode node = new StructureNode(Kind.SIGNER,
                index > 0 ? "Podepisující " + index + " (SignerInfo)" : "Podepisující (SignerInfo)");
        node.row("Verze", String.valueOf(si.getVersion()));
        SignerId sid = si.getSID();
        if (sid.getIssuer() != null) {
            node.row("Identifikace certifikátu", "vydavatel a sériové číslo (issuerAndSerialNumber)");
            node.row("Vydavatel", String.valueOf(sid.getIssuer()));
            node.row("Sériové číslo", ContainerInspector.hexNumber(sid.getSerialNumber()));
        } else if (sid.getSubjectKeyIdentifier() != null) {
            node.row("Identifikace certifikátu", "identifikátor klíče (subjectKeyIdentifier)");
            node.row("Identifikátor klíče", hex(sid.getSubjectKeyIdentifier()));
        }
        X509CertificateHolder signerCert = null;
        try {
            var matches = cms.getCertificates().getMatches(sid);
            if (!matches.isEmpty()) signerCert = (X509CertificateHolder) matches.iterator().next();
        } catch (Exception ignored) { }
        if (signerCert != null) {
            try {
                node.row("Certifikát podepisujícího", label(signerCert), certKey(signerCert.getEncoded()));
            } catch (Exception ignored) { }
            node.subtitle(ContainerInspector.commonName(String.valueOf(signerCert.getSubject())));
        } else {
            node.note(Severity.WARNING, "Certifikát podepisujícího objekt CMS nenese; ověřovatel ho musí"
                    + " najít jinde (v /DSS, ve VRI či online).");
        }
        node.row("Otisková funkce", digestLabel(si.getDigestAlgOID()));
        node.row("Podpisový algoritmus", ContainerInspector.oidLabel(si.getEncryptionAlgOID())
                + " (" + si.getEncryptionAlgOID() + ")");
        byte[] value = si.getSignature();
        node.row("Hodnota podpisu", value.length + " B");
        node.dssKey(signatureValueKey(value));

        AttributeTable signed = si.getSignedAttributes();
        if (signed != null) {
            Attribute[] attrs = signed.toASN1Structure().getAttributes();
            StructureNode g = node.add(new StructureNode(Kind.GROUP,
                    "Podepsané atributy (signedAttrs) (" + attrs.length + ")"));
            for (Attribute a : attrs) g.add(attribute(a, true, si, candidates, certs));
            if (signed.get(new ASN1ObjectIdentifier("1.2.840.113549.1.9.4")) == null) {
                node.note(Severity.ERROR, "Mezi podepsanými atributy chybí message-digest.");
            }
        } else {
            node.note(Severity.INFO, "Podpis nemá podepsané atributy — podepisuje se přímo obsah.");
            checkDigest(node, si.getDigestAlgOID(), null, candidates, "Otisk obsahu");
        }
        AttributeTable unsigned = si.getUnsignedAttributes();
        if (unsigned != null) {
            Attribute[] attrs = unsigned.toASN1Structure().getAttributes();
            StructureNode g = node.add(new StructureNode(Kind.GROUP,
                    "Nepodepsané atributy (unsignedAttrs) (" + attrs.length + ")"));
            for (Attribute a : attrs) g.add(attribute(a, false, si, candidates, certs));
        }
        node.add(new StructureNode(Kind.VALUE, "Hodnota podpisu (signature)")
                .row("Délka", value.length + " B")
                .row("Hodnota (hex)", hex(value))
                .data(value, "hodnota-podpisu.bin"));
        return node;
    }

    // ------------------------------------------------------------------ atributy

    private static StructureNode attribute(Attribute a, boolean signedAttr, SignerInformation si,
                                           List<Content> candidates, Collection<X509CertificateHolder> certs) {
        String oid = a.getAttrType().getId();
        StructureNode node = new StructureNode(Kind.ATTRIBUTE, attributeName(oid));
        node.row("OID", oid);
        ASN1Set values = a.getAttrValues();
        try {
            node.data(a.getEncoded("DER"), "atribut-" + oid + ".der");
        } catch (Exception ignored) { }
        if (values.size() != 1) node.row("Počet hodnot", String.valueOf(values.size()));
        if (values.size() == 0) return node;
        ASN1Encodable v = values.getObjectAt(0);
        try {
            switch (oid) {
                case "1.2.840.113549.1.9.3" -> {
                    String ct = ASN1ObjectIdentifier.getInstance(v).getId();
                    node.row("Typ obsahu", contentTypeLabel(ct));
                    node.subtitle(contentTypeLabel(ct));
                }
                case "1.2.840.113549.1.9.4" -> {
                    byte[] digest = ASN1OctetString.getInstance(v).getOctets();
                    node.row("Otisk (" + digestLabel(si.getDigestAlgOID()) + ")", hex(digest));
                    checkDigest(node, si.getDigestAlgOID(), digest, candidates, "Otisk");
                }
                case "1.2.840.113549.1.9.5" -> {
                    String when = ContainerInspector.formatDate(
                            org.bouncycastle.asn1.cms.Time.getInstance(v).getDate());
                    node.row("Deklarovaný čas podpisu", when);
                    node.subtitle(when);
                    node.note(Severity.INFO, "Čas uvádí podepisující sám; nedokazuje, kdy podpis vznikl.");
                }
                case "1.2.840.113549.1.9.16.2.47" -> {
                    var sc = org.bouncycastle.asn1.ess.SigningCertificateV2.getInstance(v);
                    int i = 0;
                    for (var id : sc.getCerts()) {
                        i++;
                        AlgorithmIdentifier alg = id.getHashAlgorithm();
                        String algOid = alg != null ? alg.getAlgorithm().getId() : "2.16.840.1.101.3.4.2.1";
                        essCertId(node, i, algOid, id.getCertHash(), id.getIssuerSerial(), certs);
                    }
                }
                case "1.2.840.113549.1.9.16.2.12" -> {
                    var sc = org.bouncycastle.asn1.ess.SigningCertificate.getInstance(v);
                    int i = 0;
                    for (var id : sc.getCerts()) {
                        i++;
                        essCertId(node, i, "1.3.14.3.2.26", id.getCertHash(), id.getIssuerSerial(), certs);
                    }
                }
                case "1.2.840.113549.1.9.16.2.15" -> {
                    var spi = org.bouncycastle.asn1.esf.SignaturePolicyIdentifier.getInstance(v);
                    if (spi.isSignaturePolicyImplied()) {
                        node.row("Politika", "implicitní (signaturePolicyImplied)");
                    } else {
                        var id = spi.getSignaturePolicyId();
                        node.row("Identifikátor politiky", id.getSigPolicyId().getId());
                        node.subtitle(id.getSigPolicyId().getId());
                        if (id.getSigPolicyHash() != null) {
                            node.row("Otisk politiky (" + digestLabel(id.getSigPolicyHash()
                                    .getHashAlgorithm().getAlgorithm().getId()) + ")",
                                    hex(id.getSigPolicyHash().getHashValue().getOctets()));
                        }
                        var q = id.getSigPolicyQualifiers();
                        if (q != null) {
                            for (int i = 0; i < q.size(); i++) {
                                var info = q.getInfoAt(i);
                                String qid = info.getSigPolicyQualifierId().getId();
                                String qv = info.getSigQualifier() instanceof org.bouncycastle.asn1.ASN1String s
                                        ? s.getString() : String.valueOf(info.getSigQualifier());
                                node.row("1.2.840.113549.1.9.16.5.1".equals(qid) ? "Adresa politiky (SPuri)"
                                        : "Kvalifikátor " + qid, qv);
                            }
                        }
                    }
                }
                case "1.2.840.113549.1.9.16.2.16" -> {
                    var cti = org.bouncycastle.asn1.esf.CommitmentTypeIndication.getInstance(v);
                    String id = cti.getCommitmentTypeId().getId();
                    node.row("Typ závazku", commitmentLabel(id) + " (" + id + ")");
                    node.subtitle(commitmentLabel(id));
                }
                case "1.2.840.113549.1.9.16.2.17" -> {
                    var loc = org.bouncycastle.asn1.esf.SignerLocation.getInstance(v);
                    if (loc.getCountry() != null) node.row("Země", loc.getCountry().getString());
                    if (loc.getLocality() != null) {
                        node.row("Místo", loc.getLocality().getString());
                        node.subtitle(loc.getLocality().getString());
                    }
                }
                case "1.2.840.113549.1.9.52" -> {
                    var p = org.bouncycastle.asn1.cms.CMSAlgorithmProtection.getInstance(v);
                    node.row("Otisková funkce", digestLabel(p.getDigestAlgorithm().getAlgorithm().getId()));
                    if (p.getSignatureAlgorithm() != null) {
                        String sa = p.getSignatureAlgorithm().getAlgorithm().getId();
                        node.row("Podpisový algoritmus", ContainerInspector.oidLabel(sa) + " (" + sa + ")");
                    }
                }
                case "1.2.840.113583.1.1.8" -> adobeRevocationArchival(node, v);
                case "1.2.840.113549.1.9.16.2.23" -> {
                    ASN1Sequence seq = ASN1Sequence.getInstance(v);
                    node.subtitle(seq.size() + " certifikátů");
                    for (ASN1Encodable c : seq) node.add(certificate(c.toASN1Primitive().getEncoded("DER")));
                }
                case "1.2.840.113549.1.9.16.2.24" -> {
                    var rv = org.bouncycastle.asn1.esf.RevocationValues.getInstance(v);
                    int n = 0;
                    if (rv.getCrlVals() != null) {
                        for (CertificateList cl : rv.getCrlVals()) { node.add(crl(cl.getEncoded("DER"))); n++; }
                    }
                    if (rv.getOcspVals() != null) {
                        for (BasicOCSPResponse b : rv.getOcspVals()) { node.add(ocsp(b.getEncoded("DER"))); n++; }
                    }
                    node.subtitle(n + " revokačních údajů");
                }
                case "1.2.840.113549.1.9.16.2.14", "1.2.840.113549.1.9.16.2.20",
                     "1.2.840.113549.1.9.16.2.25", "1.2.840.113549.1.9.16.2.26",
                     "1.2.840.113549.1.9.16.2.27", "1.2.840.113549.1.9.16.2.48",
                     "0.4.0.1733.2.4" -> {
                    List<Content> imprint = switch (oid) {
                        // RFC 3161 příloha A: razítko podpisu kryje hodnotu podpisu SignerInfo.
                        case "1.2.840.113549.1.9.16.2.14" -> List.of(
                                Content.of("hodnota podpisu (SignerInfo.signature)", si.getSignature()));
                        // Razítko obsahu kryje týž obsah jako podpis.
                        case "1.2.840.113549.1.9.16.2.20" -> candidates;
                        // Vstup archivních razítek a razítek nad referencemi se skládá z více
                        // částí podle verze normy; tady se jen čte, nepřepočítává.
                        default -> List.of();
                    };
                    for (int i = 0; i < values.size(); i++) {
                        byte[] tst = values.getObjectAt(i).toASN1Primitive().getEncoded("DER");
                        node.add(timestamp(tst, attributeName(oid), imprint)).title(timestampTitle(oid));
                    }
                    node.subtitle(values.size() == 1 ? "1 razítko" : values.size() + " razítka");
                }
                default -> {
                    node.row("Velikost hodnoty", v.toASN1Primitive().getEncoded("DER").length + " B");
                    if (!knownAttribute(oid)) {
                        node.note(Severity.INFO, "Atribut, který Inspektor nerozkládá; bajty lze uložit.");
                    }
                }
            }
        } catch (Exception e) {
            node.note(Severity.WARNING, "Hodnotu atributu se nepodařilo přečíst (" + describe(e) + ").");
        }
        if (!signedAttr && "1.2.840.113549.1.9.16.2.47".equals(oid)) {
            node.note(Severity.WARNING, "Atribut signing-certificate je mezi nepodepsanými —"
                    + " podpisem tedy chráněn není.");
        }
        return node;
    }

    private static void essCertId(StructureNode node, int i, String algOid, byte[] hash,
                                  org.bouncycastle.asn1.x509.IssuerSerial issuerSerial,
                                  Collection<X509CertificateHolder> certs) {
        String p = i > 1 ? " " + i : "";
        node.row("Otisk certifikátu" + p + " (" + digestLabel(algOid) + ")", hex(hash));
        if (issuerSerial != null) {
            for (GeneralName gn : issuerSerial.getIssuer().getNames()) {
                if (gn.getTagNo() == GeneralName.directoryName) {
                    node.row("Vydavatel" + p, String.valueOf(X500Name.getInstance(gn.getName())));
                }
            }
            node.row("Sériové číslo" + p, ContainerInspector.hexNumber(issuerSerial.getSerial().getValue()));
        }
        X509CertificateHolder match = null;
        for (X509CertificateHolder c : certs) {
            try {
                byte[] d = digest(algOid, md -> {
                    try { md.update(c.getEncoded()); } catch (Exception e) { throw new IllegalStateException(e); }
                });
                if (d != null && Arrays.equals(d, hash)) { match = c; break; }
            } catch (Exception ignored) { }
        }
        if (match != null) {
            try {
                node.row("Odpovídá certifikátu" + p, label(match), certKey(match.getEncoded()));
            } catch (Exception ignored) { }
            if (i == 1) node.subtitle(ContainerInspector.commonName(String.valueOf(match.getSubject())));
        } else if (i == 1) {
            node.note(Severity.WARNING, "Otisku neodpovídá žádný certifikát přibalený v objektu CMS.");
        }
    }

    /**
     * Atribut Adobe {@code adbe-revocationInfoArchival}: CRL a OCSP přibalené k podpisu PDF
     * podle Adobe (před PAdES LT), mezi podepsanými atributy.
     */
    private static void adobeRevocationArchival(StructureNode node, ASN1Encodable v) throws Exception {
        ASN1Sequence seq = ASN1Sequence.getInstance(v);
        int crls = 0, ocsps = 0;
        for (ASN1Encodable e : seq) {
            ASN1TaggedObject t = ASN1TaggedObject.getInstance(e);
            ASN1Sequence items = ASN1Sequence.getInstance(t.getExplicitBaseObject());
            for (ASN1Encodable item : items) {
                byte[] der = item.toASN1Primitive().getEncoded("DER");
                if (t.getTagNo() == 0) { node.add(crl(der)); crls++; }
                else if (t.getTagNo() == 1) { node.add(ocsp(der)); ocsps++; }
            }
        }
        node.subtitle(crls + " CRL, " + ocsps + " OCSP");
    }

    private static void checkDigest(StructureNode node, String algOid, byte[] expected,
                                    List<Content> candidates, String what) {
        if (candidates == null || candidates.isEmpty()) {
            if (expected != null) {
                node.note(Severity.INFO, "Podepsaná data nejsou k dispozici, otisk se nepřepočítává.");
            }
            return;
        }
        for (Content c : candidates) {
            byte[] actual = digest(algOid, c.feeder());
            if (actual == null) {
                node.note(Severity.INFO, "Otiskovou funkci " + digestLabel(algOid) + " nelze spočítat.");
                return;
            }
            if (expected == null) {
                node.row(what + " — " + c.label(), hex(actual));
                return;
            }
            if (Arrays.equals(actual, expected)) {
                node.row("Odpovídá", c.label());
                node.note(Severity.OK, what + " souhlasí s přepočtem nad: " + c.label() + ".");
                return;
            }
        }
        if (expected != null) {
            // Otisk prázdných dat: vystavitel razítko či podpis spočetl nad ničím. Nalezeno
            // 13. 10. 2026 u razítka obsahu ve dvou kontejnerech ASiC-E ze sbírky D:\tmp;
            // obecné „nesouhlasí“ by tu čtenáře posílalo hledat změněný soubor, který neexistuje.
            if (Arrays.equals(expected, digest(algOid, md -> { }))) {
                node.note(Severity.ERROR, what + " je otiskem prázdných dat — nekryje žádný obsah.");
                return;
            }
            if (candidates.size() == 1) {
                node.row("Přepočtený otisk", hex(digest(algOid, candidates.get(0).feeder())));
            }
            node.note(Severity.ERROR, what + " nesouhlasí s přepočtem nad: "
                    + String.join("; ", candidates.stream().map(Content::label).toList()) + ".");
        }
    }

    // ------------------------------------------------------------------ časové razítko

    /**
     * Uzel časového razítka (TimeStampToken): údaje TSTInfo nahoře, pod nimi podepisující
     * autorita a přibalené certifikáty.
     *
     * @param imprint kandidáti, nad kterými se přepočítá otisk z razítka; prázdné = nepřepočítávat
     */
    public static StructureNode timestamp(byte[] der, String role, List<Content> imprint) {
        StructureNode node = new StructureNode(Kind.TIMESTAMP, "Časové razítko")
                .data(der, "razitko.tst").key(tstKey(der)).dssKey(tstKey(der));
        if (role != null) node.row("Role", role);
        CMSSignedData cms;
        TimeStampTokenInfo info;
        try {
            cms = new CMSSignedData(der);
            info = new TimeStampToken(cms).getTimeStampInfo();
        } catch (Exception e) {
            node.note(Severity.ERROR, "Razítko se nepodařilo přečíst (" + describe(e) + ").");
            return node;
        }
        String when = ContainerInspector.formatDate(info.getGenTime());
        node.time(info.getGenTime());
        node.row("Čas (genTime)", when);
        X509CertificateHolder tsa = null;
        try {
            var m = cms.getCertificates().getMatches(cms.getSignerInfos().getSigners().iterator().next().getSID());
            if (!m.isEmpty()) tsa = (X509CertificateHolder) m.iterator().next();
        } catch (Exception ignored) { }
        String issuer = tsa != null ? ContainerInspector.commonName(String.valueOf(tsa.getSubject())) : null;
        node.subtitle(when + (issuer != null && !issuer.isEmpty() ? " — " + issuer : ""));
        if (info.getTsa() != null) node.row("Autorita (pole tsa)", String.valueOf(info.getTsa().getName()));
        if (tsa != null) {
            try { node.row("Certifikát autority", label(tsa), certKey(tsa.getEncoded())); } catch (Exception ignored) { }
        }
        node.row("Politika", info.getPolicy() != null ? info.getPolicy().getId() : null);
        node.row("Sériové číslo", ContainerInspector.hexNumber(info.getSerialNumber()));
        var acc = info.getGenTimeAccuracy();
        if (acc != null) {
            node.row("Přesnost", acc.getSeconds() + " s " + acc.getMillis() + " ms " + acc.getMicros() + " µs");
        }
        node.row("Řazení (ordering)", info.isOrdered() ? "ano" : null);
        node.row("Nonce", info.getNonce() != null ? ContainerInspector.hexNumber(info.getNonce()) : null);
        String algOid = info.getMessageImprintAlgOID().getId();
        node.row("Otisk v razítku (" + digestLabel(algOid) + ")", hex(info.getMessageImprintDigest()));
        if (!imprint.isEmpty()) {
            checkDigest(node, algOid, info.getMessageImprintDigest(), imprint, "Otisk v razítku");
        }
        byte[] tstInfo = ContainerInspector.contentBytes(cms);
        List<Content> own = tstInfo != null ? List.of(Content.of("TSTInfo razítka", tstInfo)) : List.of();
        for (StructureNode child : contents(cms, own)) node.add(child);
        return node;
    }

    // ------------------------------------------------------------------ certifikát, CRL, OCSP

    public static StructureNode certificate(byte[] der) {
        StructureNode node = new StructureNode(Kind.CERTIFICATE, "Certifikát")
                .data(der, "certifikat.cer").key(certKey(der)).dssKey(certKey(der));
        try {
            X509CertificateHolder h = new X509CertificateHolder(der);
            String cn = ContainerInspector.commonName(String.valueOf(h.getSubject()));
            node.title("Certifikát: " + (cn.isEmpty() ? String.valueOf(h.getSubject()) : cn));
            boolean self = h.getSubject().equals(h.getIssuer());
            String issuerCn = ContainerInspector.commonName(String.valueOf(h.getIssuer()));
            node.subtitle((self ? "sám sebou vydaný" : "vydal " + issuerCn)
                    + ", platný do " + TimeFormat.day(h.getNotAfter().toInstant()));
            node.data(der, ContainerInspector.safeFileName(cn.isEmpty() ? "certifikat" : cn) + ".cer");
            node.row("Subjekt", String.valueOf(h.getSubject()));
            node.row("Vydavatel", String.valueOf(h.getIssuer()));
            node.row("Sériové číslo", ContainerInspector.hexNumber(h.getSerialNumber()));
            node.row("Platnost od", ContainerInspector.formatDate(h.getNotBefore()));
            node.row("Platnost do", ContainerInspector.formatDate(h.getNotAfter()));
            node.row("SHA-256", ContainerInspector.sha256Hex(der));
        } catch (Exception e) {
            node.note(Severity.ERROR, "Certifikát se nepodařilo přečíst (" + describe(e) + ").");
        }
        return node;
    }

    public static StructureNode crl(byte[] der) {
        StructureNode node = new StructureNode(Kind.CRL, "Seznam CRL")
                .data(der, "seznam.crl").key(crlKey(der)).dssKey(crlKey(der));
        try {
            X509CRLHolder h = new X509CRLHolder(der);
            String cn = ContainerInspector.commonName(String.valueOf(h.getIssuer()));
            node.title("Seznam CRL: " + (cn.isEmpty() ? String.valueOf(h.getIssuer()) : cn));
            int entries = h.getRevokedCertificates().size();
            node.subtitle("vydán " + TimeFormat.day(h.getThisUpdate().toInstant()) + ", " + entries + " záznamů");
            node.data(der, ContainerInspector.safeFileName(cn.isEmpty() ? "seznam" : cn) + ".crl");
            node.row("Vydavatel", String.valueOf(h.getIssuer()));
            node.row("Vydán (thisUpdate)", ContainerInspector.formatDate(h.getThisUpdate()));
            node.row("Příští vydání (nextUpdate)", ContainerInspector.formatDate(h.getNextUpdate()));
            node.row("Zneplatněných certifikátů", String.valueOf(entries));
            node.row("Velikost", ContainerInspector.formatSize(der.length));
            node.row("SHA-256", ContainerInspector.sha256Hex(der));
        } catch (Exception e) {
            node.note(Severity.ERROR, "Seznam CRL se nepodařilo přečíst (" + describe(e) + ").");
        }
        return node;
    }

    /** Přijme celou odpověď OCSPResponse i holou BasicOCSPResponse, jak ji nese CMS. */
    public static StructureNode ocsp(byte[] der) {
        StructureNode node = new StructureNode(Kind.OCSP, "Odpověď OCSP");
        BasicOCSPResponse basic = basicOcsp(der);
        if (basic == null) {
            node.data(der, "odpoved.ocsp").key("ocsp-raw:" + ContainerInspector.sha256Hex(der));
            node.note(Severity.ERROR, "Odpověď OCSP se nepodařilo přečíst.");
            return node;
        }
        try {
            byte[] basicDer = basic.getEncoded("DER");
            byte[] whole = ContainerInspector.wrapAsOcspResponse(basicDer);
            node.key(ocspKey(basicDer)).dssKey(ocspKey(basicDer));
            var data = basic.getTbsResponseData();
            String responder = responderLabel(data.getResponderID());
            String produced = ContainerInspector.formatDate(data.getProducedAt().getDate());
            node.title("Odpověď OCSP: " + responder);
            node.data(whole != null ? whole : der,
                    ContainerInspector.safeFileName(responder) + ".ocsp");
            node.row("Respondér", responder);
            node.row("Vydána (producedAt)", produced);
            ASN1Sequence responses = data.getResponses();
            List<String> states = new ArrayList<>();
            for (int i = 0; i < responses.size(); i++) {
                SingleResponse sr = SingleResponse.getInstance(responses.getObjectAt(i));
                String state = switch (sr.getCertStatus().getTagNo()) {
                    case 0 -> "platný (good)";
                    case 1 -> "zneplatněný (revoked)";
                    default -> "neznámý (unknown)";
                };
                states.add(state);
                String p = responses.size() > 1 ? " " + (i + 1) : "";
                node.row("Certifikát" + p + " — sériové číslo",
                        ContainerInspector.hexNumber(sr.getCertID().getSerialNumber().getValue()));
                node.row("Stav" + p, state);
                node.row("Stav k (thisUpdate)" + p, ContainerInspector.formatDate(sr.getThisUpdate().getDate()));
                if (sr.getNextUpdate() != null) {
                    node.row("Příští aktualizace" + p, ContainerInspector.formatDate(sr.getNextUpdate().getDate()));
                }
            }
            node.subtitle("vydána " + TimeFormat.day(data.getProducedAt().getDate().toInstant())
                    + ", " + String.join(", ", states));
            int certs = basic.getCerts() != null ? basic.getCerts().size() : 0;
            node.row("Přibalené certifikáty", String.valueOf(certs));
            if (certs > 0) {
                for (ASN1Encodable c : basic.getCerts()) node.add(certificate(c.toASN1Primitive().getEncoded("DER")));
            }
            node.row("SHA-256 vnitřní BasicOCSPResponse", ContainerInspector.sha256Hex(basicDer));
        } catch (Exception e) {
            node.note(Severity.WARNING, "Část odpovědi OCSP se nepodařilo přečíst (" + describe(e) + ").");
        }
        return node;
    }

    /** Vnitřní BasicOCSPResponse z celé odpovědi či z holé struktury; {@code null}, když to OCSP není. */
    public static BasicOCSPResponse basicOcsp(byte[] der) {
        ASN1Primitive p;
        try {
            p = ASN1Primitive.fromByteArray(der);
        } catch (Exception e) {
            return null;
        }
        try {
            OCSPResponse resp = OCSPResponse.getInstance(p);
            if (resp.getResponseBytes() != null
                    && OCSPObjectIdentifiers.id_pkix_ocsp_basic.equals(resp.getResponseBytes().getResponseType())) {
                return BasicOCSPResponse.getInstance(resp.getResponseBytes().getResponse().getOctets());
            }
        } catch (Exception ignored) {
            // Není to obálka — zkusí se holá struktura.
        }
        try {
            BasicOCSPResponse b = BasicOCSPResponse.getInstance(p);
            return b.getTbsResponseData() != null ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String responderLabel(ResponderID id) {
        if (id.getName() != null) {
            String cn = ContainerInspector.commonName(String.valueOf(id.getName()));
            return cn.isEmpty() ? String.valueOf(id.getName()) : cn;
        }
        return "klíč " + hex(id.getKeyHash());
    }

    // ------------------------------------------------------------------ pomocné

    static String label(X509CertificateHolder h) {
        String cn = ContainerInspector.commonName(String.valueOf(h.getSubject()));
        return (cn.isEmpty() ? String.valueOf(h.getSubject()) : cn)
                + " (sériové číslo " + ContainerInspector.hexNumber(h.getSerialNumber()) + ")";
    }

    /** Otisk podle OID; {@code null}, když ho nezná ani JDK, ani BouncyCastle. */
    public static byte[] digest(String oid, Consumer<MessageDigest> feeder) {
        MessageDigest md = null;
        String name = switch (oid) {
            case "1.3.14.3.2.26" -> "SHA-1";
            case "2.16.840.1.101.3.4.2.4" -> "SHA-224";
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512";
            case "2.16.840.1.101.3.4.2.8" -> "SHA3-256";
            case "2.16.840.1.101.3.4.2.9" -> "SHA3-384";
            case "2.16.840.1.101.3.4.2.10" -> "SHA3-512";
            case "1.2.840.113549.2.5" -> "MD5";
            // Někteří vystavitelé uvádějí v digestAlgorithm OID podpisového algoritmu
            // (sken D:\Downloads 13. 10. 2026); otisková funkce se z něj dá vyčíst.
            case "1.2.840.113549.1.1.5", "1.2.840.10045.4.1" -> "SHA-1";
            case "1.2.840.113549.1.1.11", "1.2.840.10045.4.3.2" -> "SHA-256";
            case "1.2.840.113549.1.1.12", "1.2.840.10045.4.3.3" -> "SHA-384";
            case "1.2.840.113549.1.1.13", "1.2.840.10045.4.3.4" -> "SHA-512";
            default -> null;
        };
        try {
            md = MessageDigest.getInstance(name != null ? name : oid);
        } catch (Exception e) {
            try {
                md = MessageDigest.getInstance(oid, new org.bouncycastle.jce.provider.BouncyCastleProvider());
            } catch (Exception ignored) {
                return null;
            }
        }
        feeder.accept(md);
        return md.digest();
    }

    static String digestLabel(String oid) {
        if (oid == null) return null;
        String l = switch (oid) {
            case "2.16.840.1.101.3.4.2.8" -> "SHA3-256";
            case "2.16.840.1.101.3.4.2.9" -> "SHA3-384";
            case "2.16.840.1.101.3.4.2.10" -> "SHA3-512";
            case "1.3.36.3.2.1" -> "RIPEMD-160";
            case "1.2.840.113549.2.5" -> "MD5";
            default -> ContainerInspector.oidLabel(oid);
        };
        return l.equals(oid) ? oid : l;
    }

    static String contentTypeLabel(String oid) {
        if (oid == null) return null;
        return switch (oid) {
            case "1.2.840.113549.1.7.1" -> "data (id-data)";
            case "1.2.840.113549.1.9.16.1.4" -> "časové razítko (id-ct-TSTInfo)";
            case "1.2.840.113549.1.7.2" -> "SignedData (id-signedData)";
            default -> oid;
        };
    }

    /** Název uzlu razítka podle atributu, ve kterém leží — ve skupině razítek je jich víc vedle sebe. */
    private static String timestampTitle(String oid) {
        return switch (oid) {
            case "1.2.840.113549.1.9.16.2.14" -> "Razítko podpisu";
            case "1.2.840.113549.1.9.16.2.20" -> "Razítko obsahu";
            case "1.2.840.113549.1.9.16.2.25" -> "Razítko nad podpisem a odkazy";
            case "1.2.840.113549.1.9.16.2.26" -> "Razítko nad odkazy";
            case "1.2.840.113549.1.9.16.2.27" -> "Archivní razítko";
            case "1.2.840.113549.1.9.16.2.48" -> "Archivní razítko v2";
            case "0.4.0.1733.2.4" -> "Archivní razítko v3";
            default -> "Časové razítko";
        };
    }

    /**
     * Razítka vnořená v podpisech pod {@code root} — razítko obsahu, podpisu, archivní —, bez
     * razítek uvnitř jiných razítek. Skupina „Časová razítka“ je ukazuje vedle samostatných:
     * do 13. 10. 2026 v ní byla jen samostatná a u ASiC-S s třemi razítky v podpisu hlásila
     * nulu, zatímco AnEP o témže souboru mluví o třech razítkách.
     */
    public static List<StructureNode> embeddedTimestamps(StructureNode root) {
        List<StructureNode> out = new ArrayList<>();
        collectTimestamps(root, out);
        // Podle času vzniku: v atributech stojí archivní razítko klidně mezi razítky z doby podpisu.
        out.sort(java.util.Comparator.comparing(StructureNode::time,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    private static void collectTimestamps(StructureNode n, List<StructureNode> out) {
        for (StructureNode c : n.children()) {
            if (c.kind() == Kind.TIMESTAMP) out.add(c);
            else collectTimestamps(c, out);
        }
    }

    private static boolean knownAttribute(String oid) {
        return !attributeName(oid).equals("Atribut " + oid);
    }

    /** Česká jména atributů CMS/CAdES; neznámý atribut zůstane pod svým OID. */
    public static String attributeName(String oid) {
        return switch (oid) {
            case "1.2.840.113549.1.9.3" -> "Typ obsahu (content-type)";
            case "1.2.840.113549.1.9.4" -> "Otisk obsahu (message-digest)";
            case "1.2.840.113549.1.9.5" -> "Čas podpisu (signing-time)";
            case "1.2.840.113549.1.9.16.2.4" -> "Popis obsahu (content-hints)";
            case "1.2.840.113549.1.9.15" ->"Schopnosti S/MIME (smime-capabilities)";
            case "1.2.840.113549.1.9.52" -> "Ochrana algoritmů (cms-algorithm-protection)";
            case "1.2.840.113549.1.9.16.2.12" -> "Podpisový certifikát (signing-certificate)";
            case "1.2.840.113549.1.9.16.2.47" -> "Podpisový certifikát (signing-certificate-v2)";
            case "1.2.840.113549.1.9.16.2.15" -> "Podpisová politika (signature-policy-identifier)";
            case "1.2.840.113549.1.9.16.2.16" -> "Typ závazku (commitment-type-indication)";
            case "1.2.840.113549.1.9.16.2.17" -> "Místo podpisu (signer-location)";
            case "1.2.840.113549.1.9.16.2.18" -> "Role podepisujícího (signer-attributes)";
            case "0.4.0.19122.1.1" -> "Role podepisujícího (signer-attributes-v2)";
            case "1.2.840.113549.1.9.16.2.20" -> "Razítko obsahu (content-time-stamp)";
            case "1.2.840.113549.1.9.16.2.14" -> "Razítko podpisu (signature-time-stamp)";
            case "1.2.840.113549.1.9.16.2.21" -> "Odkazy na certifikáty (complete-certificate-references)";
            case "1.2.840.113549.1.9.16.2.22" -> "Odkazy na revokační údaje (complete-revocation-references)";
            case "1.2.840.113549.1.9.16.2.23" -> "Certifikáty (certificate-values)";
            case "1.2.840.113549.1.9.16.2.24" -> "Revokační údaje (revocation-values)";
            case "1.2.840.113549.1.9.16.2.25" -> "Razítko nad podpisem a odkazy (CAdES-C-time-stamp)";
            case "1.2.840.113549.1.9.16.2.26" -> "Razítko nad odkazy (time-stamped-certs-crls-references)";
            case "1.2.840.113549.1.9.16.2.27" -> "Archivní razítko (archive-time-stamp)";
            case "1.2.840.113549.1.9.16.2.48" -> "Archivní razítko (archive-time-stamp-v2)";
            case "0.4.0.1733.2.4" -> "Archivní razítko (archive-time-stamp-v3)";
            case "0.4.0.1733.2.5" -> "Index otisků archivního razítka (ats-hash-index)";
            case "0.4.0.19122.1.5" -> "Index otisků archivního razítka (ats-hash-index-v3)";
            case "1.2.840.113549.1.9.6" -> "Kontrasignatura (countersignature)";
            case "1.2.840.113583.1.1.8" -> "Revokační údaje Adobe (adbe-revocationInfoArchival)";
            case "1.2.840.113549.1.9.16.2.19" -> "Jiný podpisový certifikát (other-signing-certificate)";
            default -> "Atribut " + oid;
        };
    }

    private static String commitmentLabel(String oid) {
        return switch (oid) {
            case "1.2.840.113549.1.9.16.6.1" -> "prokázání původu";
            case "1.2.840.113549.1.9.16.6.2" -> "prokázání přijetí";
            case "1.2.840.113549.1.9.16.6.3" -> "prokázání doručení";
            case "1.2.840.113549.1.9.16.6.4" -> "prokázání odeslání";
            case "1.2.840.113549.1.9.16.6.5" -> "schválení";
            case "1.2.840.113549.1.9.16.6.6" -> "vytvoření";
            default -> oid;
        };
    }

    static String hex(byte[] data) {
        return data == null ? null : HexFormat.of().withUpperCase().formatHex(data);
    }

    static String hex(BigInteger v) {
        return v == null ? null : v.toString(16).toUpperCase(Locale.ROOT);
    }

    static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
