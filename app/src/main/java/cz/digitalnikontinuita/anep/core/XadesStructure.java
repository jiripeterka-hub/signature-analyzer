package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.CmsStructure.Content;
import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.Reference;
import org.apache.xml.security.signature.SignedInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.signature.XMLSignatureInput;
import org.apache.xml.security.utils.resolver.ResourceResolverContext;
import org.apache.xml.security.utils.resolver.ResourceResolverException;
import org.apache.xml.security.utils.resolver.ResourceResolverSpi;
import org.bouncycastle.cert.X509CertificateHolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rozklad podpisu XAdES pro Inspektor — samostatný dokument XML (obalený, obalující i oddělený
 * podpis) a podpisy {@code signatures*.xml} v kontejneru ASiC.
 *
 * <p>Doplněno 13. 10. 2026 na pokyn uživatele. Stavba stromu kopíruje CAdES: podpis, pod ním
 * podepsané údaje, hodnota podpisu, certifikáty, podepsané a nepodepsané vlastnosti; razítka
 * a ověřovací údaje se rozkládají týmiž uzly jako v CMS ({@link CmsStructure}), aby certifikát
 * nebo odpověď OCSP vypadaly stejně, ať leží kdekoli, a aby je šlo spárovat s DSS.
 *
 * <p><b>Otisky odkazů přepočítává Apache Santuario</b> (je v aplikaci přes DSS) se zapnutou
 * bezpečnou validací — ta mimo jiné nepustí transformaci XSLT, kterou by podvržený dokument
 * mohl spustit cizí kód. Odkazy na soubory mimo dokument se berou z kontejneru nebo z předloženého
 * odděleného souboru; jiné (síťové) adresy se nenačítají. Ověření podpisu samotného (kryptografie,
 * řetěz) tu není: to dělá DSS.
 */
final class XadesStructure {

    private XadesStructure() {}

    static final String DS = "http://www.w3.org/2000/09/xmldsig#";

    static {
        org.apache.xml.security.Init.init();
    }

    /**
     * Nese dokument XML aspoň jeden prvek {@code ds:Signature}?
     *
     * <p>Rozebírá se, nehledá v textu: protokoly ETSI o ověření jmenný prostor {@code xmldsig}
     * obsahují, podpis ne (sonda nad sbírkou D:\tmp 13. 10. 2026) — tlačítko by vedlo do prázdného okna.
     */
    static boolean looksLikeXmlSignature(byte[] xml) {
        String s = new String(xml, StandardCharsets.UTF_8);
        if (!s.contains(DS)) return false;
        try {
            return ContainerInspector.parseXml(xml).getElementsByTagNameNS(DS, "Signature").getLength() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static StructureInspector.Inspection inspect(byte[] bytes, String fileName, byte[] original,
                                                 String originalName) throws Exception {
        Document doc = ContainerInspector.parseXml(bytes);
        Map<String, byte[]> external = new java.util.LinkedHashMap<>();
        if (original != null) external.put(originalName != null ? originalName : "", original);
        List<StructureNode> sigs = signatures(doc, external, original != null);
        StructureNode group = new StructureNode(Kind.GROUP, "Podpisy a pečetě (" + sigs.size() + ")");
        for (StructureNode s : sigs) group.add(s);
        if (sigs.isEmpty()) group.note(Severity.INFO, "Dokument žádný podpis XML Signature nenese.");
        StructureNode ts = CadesStructure.timestampGroup(List.of(), sigs);
        List<StructureNode.Row> summary = new ArrayList<>();
        summary.add(new StructureNode.Row("Velikost", ContainerInspector.formatSize(bytes.length), null));
        summary.add(new StructureNode.Row("Kořen", ContainerInspector.localName(doc.getDocumentElement()), null));
        summary.add(new StructureNode.Row("Podpisů", String.valueOf(sigs.size()), null));
        summary.add(new StructureNode.Row("Časových razítek", String.valueOf(ts.children().size()), null));
        return new StructureInspector.Inspection(StructureInspector.Format.XADES, fileName, bytes.length,
                List.of(), List.of(group, ts), summary, null);
    }

    /**
     * Všechny podpisy nejvyšší úrovně v dokumentu (kontrasignatury visí pod svým podpisem).
     *
     * @param external     soubory, na které mohou odkazy ukazovat: jméno → bajty
     * @param singleDetached předložen jediný oddělený soubor — odkaz na cokoli mimo dokument
     *                     se pak vztáhne k němu, i když se jméno neshoduje (soubor mohl být přejmenován)
     */
    static List<StructureNode> signatures(Document doc, Map<String, byte[]> external, boolean singleDetached) {
        registerIds(doc.getDocumentElement());
        List<StructureNode> out = new ArrayList<>();
        NodeList all = doc.getElementsByTagNameNS(DS, "Signature");
        for (int i = 0; i < all.getLength(); i++) {
            Element sig = (Element) all.item(i);
            if (insideCounterSignature(sig)) continue;
            out.add(signature(sig, external, singleDetached, false));
        }
        if (out.size() > 1) {
            for (int i = 0; i < out.size(); i++) out.get(i).title("Podpis " + (i + 1));
        }
        return out;
    }

    // ------------------------------------------------------------------ podpis

    private static StructureNode signature(Element sig, Map<String, byte[]> external, boolean singleDetached,
                                           boolean counter) {
        // Atribut Id bývá strojový („id-a966973cf6…“) a čtenáři nic neřekne; v názvu je jen
        // druh a pořadí, Id zůstává jako řádek (připomínka uživatele 13. 10. 2026).
        String id = sig.getAttribute("Id");
        String label = counter ? "Kontrasignatura" : "Podpis";
        StructureNode node = new StructureNode(Kind.SIGNATURE, label);
        node.row("Id", id);
        Element signedInfo = child(sig, "SignedInfo");
        if (signedInfo != null) {
            node.row("Kanonikalizace", algLabel(attr(child(signedInfo, "CanonicalizationMethod"), "Algorithm")));
            node.row("Podpisový algoritmus", algLabel(attr(child(signedInfo, "SignatureMethod"), "Algorithm")));
        }
        Element qp = descendant(sig, "QualifyingProperties");
        if (qp != null) node.row("Verze XAdES", xadesVersion(qp.getNamespaceURI()));
        else node.note(Severity.INFO, "Podpis nemá QualifyingProperties — je to holý XML Signature, ne XAdES.");

        byte[] value = null;
        Element sv = child(sig, "SignatureValue");
        if (sv != null) {
            value = b64(sv.getTextContent());
            if (value != null) node.dssKey(CmsStructure.signatureValueKey(value));
        }

        // Certifikáty z KeyInfo a z CertificateValues — k párování signing-certificate.
        List<byte[]> certs = new ArrayList<>();
        Element keyInfo = child(sig, "KeyInfo");
        if (keyInfo != null) for (Element c : descendants(keyInfo, "X509Certificate")) addB64(certs, c);
        for (Element c : descendants(sig, "EncapsulatedX509Certificate")) addB64(certs, c);

        node.add(signedInfo(sig, signedInfo, external, singleDetached));
        if (value != null) {
            node.add(new StructureNode(Kind.VALUE, "Hodnota podpisu (SignatureValue)")
                    .row("Délka", value.length + " B").row("Hodnota (hex)", CmsStructure.hex(value))
                    .data(value, "hodnota-podpisu.bin"));
        } else {
            node.note(Severity.ERROR, "Podpis nemá čitelnou hodnotu SignatureValue.");
        }
        if (keyInfo != null) {
            List<Element> kc = descendants(keyInfo, "X509Certificate");
            StructureNode g = node.add(new StructureNode(Kind.GROUP, "Certifikáty v KeyInfo (" + kc.size() + ")"));
            for (Element c : kc) {
                byte[] der = b64(c.getTextContent());
                if (der != null) g.add(CmsStructure.certificate(der));
            }
            if (!kc.isEmpty() && node.subtitle() == null) {
                try {
                    node.subtitle(ContainerInspector.commonName(String.valueOf(
                            new X509CertificateHolder(b64(kc.get(0).getTextContent())).getSubject())));
                } catch (Exception ignored) { }
            }
        }
        if (qp != null) {
            Element sp = descendant(qp, "SignedProperties");
            if (sp != null) {
                StructureNode g = node.add(new StructureNode(Kind.GROUP, "Podepsané vlastnosti (SignedProperties)"));
                for (Element section : childElements(sp)) {
                    for (Element p : childElements(section)) g.add(property(p, sig, certs, node, external, singleDetached));
                }
                g.title("Podepsané vlastnosti (SignedProperties) (" + g.children().size() + ")");
            } else {
                node.note(Severity.WARNING, "QualifyingProperties nemá SignedProperties.");
            }
            Element usp = descendant(qp, "UnsignedSignatureProperties");
            if (usp != null) {
                StructureNode g = node.add(new StructureNode(Kind.GROUP, "Nepodepsané vlastnosti (UnsignedSignatureProperties)"));
                for (Element p : childElements(usp)) g.add(property(p, sig, certs, node, external, singleDetached));
                g.title("Nepodepsané vlastnosti (UnsignedSignatureProperties) (" + g.children().size() + ")");
            }
        }
        // Obsah vložený do ds:Object (obalující podpis) — kromě vlastností XAdES.
        for (Element obj : children(sig, "Object")) {
            if (child(obj, "QualifyingProperties") != null) continue;
            String oid = obj.getAttribute("Id");
            StructureNode o = node.add(new StructureNode(Kind.VALUE, "Vložený obsah (ds:Object" + (oid.isEmpty() ? "" : " „" + oid + "“") + ")"));
            o.row("Id", oid);
            o.row("Typ (MimeType)", obj.getAttribute("MimeType"));
            o.row("Kódování (Encoding)", obj.getAttribute("Encoding"));
            o.row("Velikost textu", obj.getTextContent().length() + " znaků");
        }
        return node;
    }

    private static StructureNode signedInfo(Element sig, Element signedInfo, Map<String, byte[]> external,
                                            boolean singleDetached) {
        StructureNode g = new StructureNode(Kind.GROUP, "Podepsané údaje (SignedInfo)");
        if (signedInfo == null) {
            g.note(Severity.ERROR, "Podpis nemá SignedInfo.");
            return g;
        }
        XMLSignature xs = null;
        try {
            xs = new XMLSignature(sig, null, true);
            xs.addResourceResolver(new LocalResolver(external, singleDetached));
        } catch (Exception e) {
            g.note(Severity.WARNING, "Odkazy se nepodařilo připravit k přepočtu (" + CmsStructure.describe(e) + ").");
        }
        List<Element> refs = children(signedInfo, "Reference");
        g.title("Podepsané údaje (SignedInfo) (" + refs.size() + ")");
        for (int i = 0; i < refs.size(); i++) {
            Element r = refs.get(i);
            String uri = r.getAttribute("URI");
            String type = r.getAttribute("Type");
            StructureNode n = g.add(new StructureNode(Kind.VALUE, "Odkaz " + (i + 1) + " — "
                    + (uri.isEmpty() ? "celý dokument" : uri)));
            n.row("URI", uri.isEmpty() ? "(prázdné — celý dokument)" : uri);
            n.row("Id", r.getAttribute("Id"));
            n.row("Typ (Type)", type.isEmpty() ? null : type + typeHint(type));
            List<String> transforms = new ArrayList<>();
            Element tr = child(r, "Transforms");
            if (tr != null) for (Element t : children(tr, "Transform")) transforms.add(algLabel(t.getAttribute("Algorithm")));
            n.row("Transformace", transforms.isEmpty() ? "žádné" : String.join(", ", transforms));
            n.row("Otisková funkce", algLabel(attr(child(r, "DigestMethod"), "Algorithm")));
            byte[] dv = child(r, "DigestValue") != null ? b64(child(r, "DigestValue").getTextContent()) : null;
            n.row("Otisk", CmsStructure.hex(dv));
            n.subtitle(type.endsWith("#SignedProperties") ? "podepsané vlastnosti" : null);
            if (xs == null) continue;
            try {
                SignedInfo si = xs.getSignedInfo();
                Reference ref = si.item(i);
                boolean ok = ref.verify();
                n.note(ok ? Severity.OK : Severity.ERROR, ok ? "Otisk souhlasí s přepočtem."
                        : "Otisk nesouhlasí s přepočtem — odkazovaná data se od podpisu změnila.");
            } catch (Exception e) {
                String m = CmsStructure.describe(e);
                n.note(Severity.INFO, "Otisk se nepřepočítává: " + (m.contains("resolve") || m.contains("Resolver")
                        ? "odkazovaná data nejsou k dispozici." : m));
            }
        }
        return g;
    }

    // ------------------------------------------------------------------ vlastnosti

    private static StructureNode property(Element p, Element sig, List<byte[]> certs, StructureNode owner,
                                          Map<String, byte[]> external, boolean singleDetached) {
        String name = ContainerInspector.localName(p);
        StructureNode node = new StructureNode(Kind.ATTRIBUTE, propertyName(name));
        try {
            switch (name) {
                case "SigningTime" -> {
                    String t = p.getTextContent().trim();
                    Date d = date(t);
                    node.row("Deklarovaný čas podpisu", d != null ? ContainerInspector.formatDate(d) + " — " + t : t);
                    node.subtitle(d != null ? ContainerInspector.formatDate(d) : t);
                    node.note(Severity.INFO, "Čas uvádí podepisující sám; nedokazuje, kdy podpis vznikl.");
                }
                case "SigningCertificate", "SigningCertificateV2" -> {
                    int i = 0;
                    for (Element c : children(p, "Cert")) {
                        i++;
                        String pf = i > 1 ? " " + i : "";
                        Element cd = child(c, "CertDigest");
                        String alg = attr(child(cd, "DigestMethod"), "Algorithm");
                        byte[] dv = cd != null && child(cd, "DigestValue") != null ? b64(child(cd, "DigestValue").getTextContent()) : null;
                        node.row("Otisk certifikátu" + pf + " (" + algShort(alg) + ")", CmsStructure.hex(dv));
                        Element is = child(c, "IssuerSerial");
                        if (is != null) {
                            node.row("Vydavatel" + pf, text(is, "X509IssuerName"));
                            String sn = text(is, "X509SerialNumber");
                            node.row("Sériové číslo" + pf, sn != null ? ContainerInspector.hexNumber(sn) : null);
                        }
                        if (child(c, "IssuerSerialV2") != null) node.row("IssuerSerialV2" + pf, "přítomno (DER v Base64)");
                        byte[] match = matchCert(certs, alg, dv);
                        if (match != null) {
                            try {
                                X509CertificateHolder h = new X509CertificateHolder(match);
                                node.row("Odpovídá certifikátu" + pf, CmsStructure.label(h), CmsStructure.certKey(match));
                                if (i == 1) {
                                    node.subtitle(ContainerInspector.commonName(String.valueOf(h.getSubject())));
                                    owner.subtitle(ContainerInspector.commonName(String.valueOf(h.getSubject())));
                                }
                            } catch (Exception ignored) { }
                        } else if (i == 1) {
                            node.note(Severity.WARNING, "Otisku neodpovídá žádný certifikát v KeyInfo ani v CertificateValues.");
                        }
                    }
                }
                case "SignaturePolicyIdentifier" -> {
                    if (descendant(p, "SignaturePolicyImplied") != null) {
                        node.row("Politika", "implicitní (SignaturePolicyImplied)");
                    } else {
                        Element idEl = descendant(p, "Identifier");
                        String id = idEl != null ? idEl.getTextContent().trim() : null;
                        node.row("Identifikátor politiky", id);
                        node.subtitle(id);
                        Element h = descendant(p, "SigPolicyHash");
                        if (h != null) {
                            node.row("Otisk politiky (" + algShort(attr(child(h, "DigestMethod"), "Algorithm")) + ")",
                                    CmsStructure.hex(b64(text(h, "DigestValue"))));
                        }
                        for (Element u : descendants(p, "SPURI")) node.row("Adresa politiky (SPURI)", u.getTextContent().trim());
                    }
                }
                case "SignatureProductionPlace", "SignatureProductionPlaceV2" -> {
                    for (String f : List.of("StreetAddress", "City", "StateOrProvince", "PostalCode", "CountryName")) {
                        node.row(placeLabel(f), text(p, f));
                    }
                    node.subtitle(text(p, "City"));
                }
                case "SignerRole", "SignerRoleV2" -> {
                    List<String> roles = new ArrayList<>();
                    for (Element r : descendants(p, "ClaimedRole")) roles.add(r.getTextContent().trim());
                    node.row("Deklarované role", String.join(", ", roles));
                    if (descendant(p, "CertifiedRoles") != null || descendant(p, "CertifiedRolesV2") != null) {
                        node.row("Certifikované role", "přítomny");
                    }
                    node.subtitle(String.join(", ", roles));
                }
                case "DataObjectFormat" -> {
                    node.row("Odkaz (ObjectReference)", p.getAttribute("ObjectReference"));
                    node.row("Popis", text(p, "Description"));
                    node.row("Typ (MimeType)", text(p, "MimeType"));
                    node.row("Kódování", text(p, "Encoding"));
                    node.subtitle(text(p, "MimeType"));
                }
                case "CommitmentTypeIndication" -> {
                    Element idEl = descendant(p, "Identifier");
                    String id = idEl != null ? idEl.getTextContent().trim() : null;
                    node.row("Typ závazku", id);
                    node.subtitle(id);
                    if (child(p, "AllSignedDataObjects") != null) node.row("Vztahuje se k", "všem podepsaným datům");
                }
                case "AllDataObjectsTimeStamp", "IndividualDataObjectsTimeStamp", "SignatureTimeStamp",
                     "SigAndRefsTimeStamp", "RefsOnlyTimeStamp", "ArchiveTimeStamp" -> {
                    List<Content> imprint = List.of();
                    if (name.equals("SignatureTimeStamp")) {
                        // Razítko podpisu kryje kanonikalizovaný prvek ds:SignatureValue (EN 319 132-1).
                        Element sv = child(sig, "SignatureValue");
                        String c14n = attr(child(p, "CanonicalizationMethod"), "Algorithm");
                        byte[] canon = canonicalize(sv, c14n.isEmpty() ? "http://www.w3.org/TR/2001/REC-xml-c14n-20010315" : c14n);
                        if (canon != null) imprint = List.of(Content.of("kanonikalizovaný prvek SignatureValue", canon));
                    }
                    int n = 0;
                    for (Element e : children(p, "EncapsulatedTimeStamp")) {
                        byte[] der = b64(e.getTextContent());
                        if (der == null) continue;
                        n++;
                        node.add(CmsStructure.timestamp(der, propertyName(name), imprint)).title(timestampTitle(name));
                    }
                    if (n == 0 && child(p, "XMLTimeStamp") != null) {
                        node.note(Severity.INFO, "Razítko ve tvaru XML (XMLTimeStamp) Inspektor nerozkládá.");
                    }
                    node.subtitle(n == 1 ? "1 razítko" : n + " razítka");
                }
                case "CertificateValues" -> {
                    List<Element> cs = children(p, "EncapsulatedX509Certificate");
                    for (Element c : cs) {
                        byte[] der = b64(c.getTextContent());
                        if (der != null) node.add(CmsStructure.certificate(der));
                    }
                    node.subtitle(cs.size() + " certifikátů");
                }
                case "RevocationValues" -> revocationValues(node, p);
                case "TimeStampValidationData" -> {
                    for (Element c : descendants(p, "EncapsulatedX509Certificate")) {
                        byte[] der = b64(c.getTextContent());
                        if (der != null) node.add(CmsStructure.certificate(der));
                    }
                    Element rv = child(p, "RevocationValues");
                    if (rv != null) revocationValues(node, rv);
                    node.subtitle(node.children().size() + " údajů");
                }
                case "CompleteCertificateRefs", "CompleteCertificateRefsV2", "AttributeCertificateRefs",
                     "AttributeCertificateRefsV2" -> {
                    int n = descendants(p, "Cert").size();
                    node.row("Odkazů na certifikáty", String.valueOf(n));
                    node.subtitle(n + " odkazů");
                }
                case "CompleteRevocationRefs", "AttributeRevocationRefs" -> {
                    int crl = descendants(p, "CRLRef").size(), ocsp = descendants(p, "OCSPRef").size();
                    node.row("Odkazů na CRL / OCSP", crl + " / " + ocsp);
                    node.subtitle(crl + " CRL, " + ocsp + " OCSP");
                }
                case "SealingEvidenceRecords" -> {
                    // EN 319 132-1 v1.1.1; nalezeno skenem D:\Downloads 13. 10. 2026.
                    int n = childElements(p).size();
                    node.row("Záznamů o důkazu", String.valueOf(n));
                    node.subtitle(n + (n == 1 ? " záznam" : " záznamy"));
                    node.note(Severity.INFO, "Záznam o důkazu (evidence record) vložený do podpisu;"
                            + " jeho strukturu Inspektor nerozkládá, prvek lze uložit.");
                }
                case "CounterSignature" -> {
                    Element cs = child(p, "Signature");
                    if (cs != null) node.add(signature(cs, external, singleDetached, true));
                }
                default -> {
                    node.row("Prvek", p.getNamespaceURI() + " " + name);
                    node.note(Severity.INFO, "Vlastnost, kterou Inspektor nerozkládá.");
                }
            }
        } catch (Exception e) {
            node.note(Severity.WARNING, "Vlastnost se nepodařilo přečíst (" + CmsStructure.describe(e) + ").");
        }
        byte[] xml = serialize(p);
        if (xml != null) node.data(xml, name + ".xml");
        return node;
    }

    private static void revocationValues(StructureNode node, Element rv) {
        int crl = 0, ocsp = 0;
        for (Element c : descendants(rv, "EncapsulatedCRLValue")) {
            byte[] der = b64(c.getTextContent());
            if (der != null) { node.add(CmsStructure.crl(der)); crl++; }
        }
        for (Element o : descendants(rv, "EncapsulatedOCSPValue")) {
            byte[] der = b64(o.getTextContent());
            if (der != null) { node.add(CmsStructure.ocsp(der)); ocsp++; }
        }
        node.subtitle(crl + " CRL, " + ocsp + " OCSP");
    }

    // ------------------------------------------------------------------ odkazy na soubory

    /**
     * Načítá odkazy mimo dokument z předložených souborů — z kontejneru, nebo z odděleného
     * podepsaného souboru. Síťové adresy záměrně neobsluhuje (ani nikdo jiný, Santuario pak
     * odkaz vzdá a uzel řekne, že data nejsou k dispozici).
     */
    private static final class LocalResolver extends ResourceResolverSpi {
        private final Map<String, byte[]> files;
        private final boolean single;

        LocalResolver(Map<String, byte[]> files, boolean single) {
            this.files = files;
            this.single = single;
        }

        @Override
        public boolean engineCanResolveURI(ResourceResolverContext ctx) {
            return find(ctx.uriToResolve) != null;
        }

        @Override
        public XMLSignatureInput engineResolveURI(ResourceResolverContext ctx) throws ResourceResolverException {
            byte[] b = find(ctx.uriToResolve);
            if (b == null) throw new ResourceResolverException("resolve", new Object[]{ctx.uriToResolve},
                    ctx.uriToResolve, ctx.baseUri);
            XMLSignatureInput in = new XMLSignatureInput(b);
            in.setSourceURI(ctx.uriToResolve);
            return in;
        }

        private byte[] find(String uri) {
            if (uri == null || uri.isEmpty() || uri.startsWith("#")) return null;
            if (files.containsKey(uri)) return files.get(uri);
            String decoded;
            try {
                decoded = URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                decoded = uri;
            }
            if (files.containsKey(decoded)) return files.get(decoded);
            String base = decoded.substring(decoded.lastIndexOf('/') + 1);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (e.getKey().equals(base) || e.getKey().endsWith("/" + base)) return e.getValue();
            }
            if (single && files.size() == 1 && !uri.contains("://")) return files.values().iterator().next();
            return null;
        }
    }

    // ------------------------------------------------------------------ pomocné

    /** Santuario hledá cíle odkazů „#id“ jen mezi atributy označenými jako ID. */
    private static void registerIds(Element el) {
        for (String a : new String[]{"Id", "ID", "id"}) {
            if (el.hasAttribute(a)) el.setIdAttribute(a, true);
        }
        for (Element c : childElements(el)) registerIds(c);
    }

    private static boolean insideCounterSignature(Element sig) {
        for (Node p = sig.getParentNode(); p instanceof Element e; p = p.getParentNode()) {
            if ("CounterSignature".equals(ContainerInspector.localName(e))) return true;
        }
        return false;
    }

    private static byte[] matchCert(List<byte[]> certs, String algUri, byte[] digest) {
        String jca = jcaDigest(algUri);
        if (jca == null || digest == null) return null;
        for (byte[] c : certs) {
            try {
                if (Arrays.equals(MessageDigest.getInstance(jca).digest(c), digest)) return c;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String jcaDigest(String uri) {
        if (uri == null) return null;
        String u = uri.toLowerCase(Locale.ROOT);
        if (u.endsWith("sha3-256")) return "SHA3-256";
        if (u.endsWith("sha3-384")) return "SHA3-384";
        if (u.endsWith("sha3-512")) return "SHA3-512";
        if (u.endsWith("#sha1")) return "SHA-1";
        if (u.endsWith("sha224")) return "SHA-224";
        if (u.endsWith("sha256")) return "SHA-256";
        if (u.endsWith("sha384")) return "SHA-384";
        if (u.endsWith("sha512")) return "SHA-512";
        return null;
    }

    private static byte[] canonicalize(Element el, String alg) {
        if (el == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Canonicalizer.getInstance(alg).canonicalizeSubtree(el, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] serialize(Element el) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Canonicalizer.getInstance("http://www.w3.org/TR/2001/REC-xml-c14n-20010315").canonicalizeSubtree(el, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String algLabel(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        String l = switch (uri) {
            case "http://www.w3.org/TR/2001/REC-xml-c14n-20010315" -> "C14N 1.0";
            case "http://www.w3.org/TR/2001/REC-xml-c14n-20010315#WithComments" -> "C14N 1.0 s komentáři";
            case "http://www.w3.org/2006/12/xml-c14n11" -> "C14N 1.1";
            case "http://www.w3.org/2006/12/xml-c14n11#WithComments" -> "C14N 1.1 s komentáři";
            case "http://www.w3.org/2001/10/xml-exc-c14n#" -> "výhradní C14N";
            case "http://www.w3.org/2001/10/xml-exc-c14n#WithComments" -> "výhradní C14N s komentáři";
            case "http://www.w3.org/2000/09/xmldsig#enveloped-signature" -> "vyjmutí podpisu (enveloped-signature)";
            case "http://www.w3.org/2000/09/xmldsig#base64" -> "dekódování Base64";
            case "http://www.w3.org/TR/1999/REC-xpath-19991116" -> "XPath";
            case "http://www.w3.org/2002/06/xmldsig-filter2" -> "XPath Filter 2.0";
            default -> {
                int h = uri.lastIndexOf('#');
                yield h >= 0 && h < uri.length() - 1 ? uri.substring(h + 1) : null;
            }
        };
        return l != null ? l + " (" + uri + ")" : uri;
    }

    /** Jen jméno algoritmu bez URI — do popisku řádku. */
    private static String algShort(String uri) {
        if (uri == null || uri.isEmpty()) return "?";
        int h = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
        return h >= 0 && h < uri.length() - 1 ? uri.substring(h + 1) : uri;
    }

    private static String typeHint(String type) {
        if (type.endsWith("#SignedProperties")) return " — podepsané vlastnosti XAdES";
        if (type.endsWith("#Object")) return " — obsah ds:Object";
        if (type.endsWith("#Manifest")) return " — ds:Manifest";
        return "";
    }

    private static String xadesVersion(String ns) {
        if (ns == null) return null;
        if (ns.contains("v1.3.2")) return "1.3.2 (" + ns + ")";
        if (ns.contains("v1.4.1")) return "1.4.1 (" + ns + ")";
        if (ns.contains("v1.2.2")) return "1.2.2 (" + ns + ")";
        if (ns.contains("v1.1.1")) return "1.1.1 (" + ns + ")";
        return ns;
    }

    private static String propertyName(String local) {
        return switch (local) {
            case "SigningTime" -> "Čas podpisu (SigningTime)";
            case "SigningCertificate" -> "Podpisový certifikát (SigningCertificate)";
            case "SigningCertificateV2" -> "Podpisový certifikát (SigningCertificateV2)";
            case "SignaturePolicyIdentifier" -> "Podpisová politika (SignaturePolicyIdentifier)";
            case "SignatureProductionPlace" -> "Místo podpisu (SignatureProductionPlace)";
            case "SignatureProductionPlaceV2" -> "Místo podpisu (SignatureProductionPlaceV2)";
            case "SignerRole" -> "Role podepisujícího (SignerRole)";
            case "SignerRoleV2" -> "Role podepisujícího (SignerRoleV2)";
            case "DataObjectFormat" -> "Formát podepsaných dat (DataObjectFormat)";
            case "CommitmentTypeIndication" -> "Typ závazku (CommitmentTypeIndication)";
            case "AllDataObjectsTimeStamp" -> "Razítko všech dat (AllDataObjectsTimeStamp)";
            case "IndividualDataObjectsTimeStamp" -> "Razítko jednotlivých dat (IndividualDataObjectsTimeStamp)";
            case "SignatureTimeStamp" -> "Razítko podpisu (SignatureTimeStamp)";
            case "SigAndRefsTimeStamp" -> "Razítko nad podpisem a odkazy (SigAndRefsTimeStamp)";
            case "RefsOnlyTimeStamp" -> "Razítko nad odkazy (RefsOnlyTimeStamp)";
            case "ArchiveTimeStamp" -> "Archivní razítko (ArchiveTimeStamp)";
            case "CertificateValues" -> "Certifikáty (CertificateValues)";
            case "RevocationValues" -> "Revokační údaje (RevocationValues)";
            case "TimeStampValidationData" -> "Ověřovací údaje razítek (TimeStampValidationData)";
            case "CompleteCertificateRefs", "CompleteCertificateRefsV2" -> "Odkazy na certifikáty (" + local + ")";
            case "CompleteRevocationRefs" -> "Odkazy na revokační údaje (CompleteRevocationRefs)";
            case "AttributeCertificateRefs", "AttributeCertificateRefsV2" -> "Odkazy na atributové certifikáty (" + local + ")";
            case "AttributeRevocationRefs" -> "Odkazy na revokaci atributových certifikátů (AttributeRevocationRefs)";
            case "CounterSignature" -> "Kontrasignatura (CounterSignature)";
            case "SealingEvidenceRecords" -> "Záznamy o důkazu (SealingEvidenceRecords)";
            default -> local;
        };
    }

    private static String timestampTitle(String local) {
        return switch (local) {
            case "AllDataObjectsTimeStamp" -> "Razítko obsahu";
            case "IndividualDataObjectsTimeStamp" -> "Razítko jednotlivých dat";
            case "SignatureTimeStamp" -> "Razítko podpisu";
            case "SigAndRefsTimeStamp" -> "Razítko nad podpisem a odkazy";
            case "RefsOnlyTimeStamp" -> "Razítko nad odkazy";
            case "ArchiveTimeStamp" -> "Archivní razítko";
            default -> "Časové razítko";
        };
    }

    private static String placeLabel(String f) {
        return switch (f) {
            case "StreetAddress" -> "Ulice";
            case "City" -> "Obec";
            case "StateOrProvince" -> "Kraj";
            case "PostalCode" -> "PSČ";
            default -> "Země";
        };
    }

    private static Date date(String t) {
        try {
            return Date.from(OffsetDateTime.parse(t).toInstant());
        } catch (Exception e) {
            try {
                return Date.from(java.time.Instant.parse(t));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static void addB64(List<byte[]> out, Element e) {
        byte[] b = b64(e.getTextContent());
        if (b != null) out.add(b);
    }

    static byte[] b64(String s) {
        if (s == null) return null;
        try {
            return Base64.getMimeDecoder().decode(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String attr(Element e, String name) {
        return e == null ? "" : e.getAttribute(name);
    }

    private static String text(Element parent, String local) {
        Element c = descendant(parent, local);
        if (c == null) return null;
        String t = c.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }

    static List<Element> childElements(Element el) {
        List<Element> out = new ArrayList<>();
        if (el == null) return out;
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) out.add(e);
        }
        return out;
    }

    private static List<Element> children(Element el, String local) {
        List<Element> out = new ArrayList<>();
        for (Element c : childElements(el)) if (local.equals(ContainerInspector.localName(c))) out.add(c);
        return out;
    }

    private static Element child(Element el, String local) {
        if (el == null) return null;
        for (Element c : childElements(el)) if (local.equals(ContainerInspector.localName(c))) return c;
        return null;
    }

    private static Element descendant(Element el, String local) {
        if (el == null) return null;
        for (Element c : childElements(el)) {
            if (local.equals(ContainerInspector.localName(c))) return c;
            Element d = descendant(c, local);
            if (d != null) return d;
        }
        return null;
    }

    private static List<Element> descendants(Element el, String local) {
        List<Element> out = new ArrayList<>();
        for (Element c : childElements(el)) {
            if (local.equals(ContainerInspector.localName(c))) out.add(c);
            out.addAll(descendants(c, local));
        }
        return out;
    }
}
