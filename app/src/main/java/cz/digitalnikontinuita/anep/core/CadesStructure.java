package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.CmsStructure.Content;
import cz.digitalnikontinuita.anep.core.StructureNode.Kind;
import cz.digitalnikontinuita.anep.core.StructureNode.Severity;
import org.bouncycastle.cms.CMSSignedData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Podpisová struktura samostatného CAdES ({@code .p7s}, {@code .p7m}, ZFO) a kontejneru ASiC
 * s podpisy CAdES či časovými razítky.
 *
 * <p>Stavba stromu kopíruje PDF tam, kde to jde — podpisy, razítka, ověřovací údaje —, aby
 * se odborník v Inspektoru orientoval stejně, ať otevře kterýkoli formát. Rozdíl je v tom, kde
 * ověřovací údaje leží: v CAdES uvnitř objektu CMS (pole {@code certificates}, {@code crls},
 * nepodepsané atributy), v ASiC navíc v samostatných souborech v {@code META-INF}.
 */
final class CadesStructure {

    private CadesStructure() {}

    static StructureInspector.Inspection inspectCms(byte[] bytes, String fileName, byte[] original)
            throws Exception {
        CMSSignedData cms = new CMSSignedData(bytes);
        byte[] content = cms.getSignedContent() != null ? ContainerInspector.contentBytes(cms) : null;
        List<Content> candidates = new ArrayList<>();
        if (content == null && original != null) {
            candidates.add(Content.of("oddělený podepsaný soubor", original));
        }
        StructureNode root = CmsStructure.signedData(bytes, "Objekt CMS (SignedData)", candidates);
        root.expanded(true);
        if (content == null && original == null) {
            root.note(Severity.INFO, "Podpis je oddělený a podepsaný soubor nebyl k ověření předložen;"
                    + " otisk obsahu se proto nepřepočítává.");
        }
        List<StructureNode.Row> summary = new ArrayList<>();
        summary.add(new StructureNode.Row("Velikost", ContainerInspector.formatSize(bytes.length), null));
        summary.add(new StructureNode.Row("Obsah", content != null ? "vložený (.p7m)" : "oddělený (.p7s)", null));
        summary.add(new StructureNode.Row("Podepisujících", String.valueOf(cms.getSignerInfos().size()), null));
        StructureNode tsGroup = timestampGroup(List.of(), List.of(root));
        summary.add(new StructureNode.Row("Časových razítek", String.valueOf(tsGroup.children().size()), null));
        return new StructureInspector.Inspection(StructureInspector.Format.CADES, fileName, bytes.length,
                List.of(), List.of(root, tsGroup), summary, null);
    }

    /**
     * Skupina všech časových razítek: samostatných ({@code standalone}) i vnořených v podpisech.
     * Vnořené uzly se nekopírují — strom je ukáže na obou místech a „Týž prvek je také zde“
     * vede k tomu, ve kterém podpisu a atributu razítko leží.
     */
    static StructureNode timestampGroup(List<StructureNode> standalone, List<StructureNode> signatures) {
        StructureNode g = new StructureNode(Kind.GROUP, "Časová razítka");
        for (StructureNode t : standalone) g.add(t);
        for (StructureNode s : signatures) {
            for (StructureNode t : CmsStructure.embeddedTimestamps(s)) g.add(t);
        }
        // Napříč podpisy podle času vzniku, jako uvnitř jednoho podpisu.
        g.children().sort(java.util.Comparator.comparing(StructureNode::time,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        g.title("Časová razítka (" + g.children().size() + ")");
        if (g.children().isEmpty()) g.note(Severity.INFO, "Soubor žádné časové razítko nenese.");
        return g;
    }

    static StructureInspector.Inspection inspectAsic(byte[] bytes, String fileName) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            long total = 0;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] data = zis.readNBytes(256 * 1024 * 1024);
                total += data.length;
                if (total > 1024L * 1024 * 1024) break;
                entries.put(e.getName(), data);
            }
        }
        String mimetype = entries.containsKey("mimetype")
                ? new String(entries.get("mimetype"), java.nio.charset.StandardCharsets.US_ASCII).trim() : null;
        List<String> dataFiles = entries.keySet().stream()
                .filter(n -> !n.startsWith("META-INF/") && !n.equals("mimetype")).toList();
        boolean asicS = mimetype != null && mimetype.endsWith("asic-s");

        StructureNode sigGroup = new StructureNode(Kind.GROUP, "Podpisy a pečetě");
        List<StructureNode> containerTimestamps = new ArrayList<>();
        StructureNode manifests = new StructureNode(Kind.GROUP, "Manifesty ASiC");
        StructureNode other = new StructureNode(Kind.GROUP, "Jiné prvky v META-INF");
        StructureNode files = new StructureNode(Kind.GROUP, "Podepisované soubory");

        Map<String, String> manifestFor = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String n = e.getKey();
            String lower = n.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("meta-inf/") || !lower.endsWith(".xml") || !lower.contains("manifest")) continue;
            manifests.add(manifest(n, e.getValue(), entries, manifestFor));
        }

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String n = e.getKey();
            String lower = n.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("meta-inf/")) continue;
            List<Content> candidates = candidates(n, asicS, dataFiles, entries, manifestFor);
            if (lower.endsWith(".p7s")) {
                StructureNode s = CmsStructure.signedData(e.getValue(), "Podpis " + n, candidates);
                s.row("Soubor v kontejneru", n);
                sigGroup.add(s);
            } else if (lower.endsWith(".tst")) {
                StructureNode t = CmsStructure.timestamp(e.getValue(), "časové razítko kontejneru", candidates);
                t.title("Razítko " + n);
                containerTimestamps.add(t);
            } else if (lower.contains("signatures") && lower.endsWith(".xml")) {
                // ASiC s XAdES: odkazy podpisu míří přímo na soubory kontejneru.
                try {
                    org.w3c.dom.Document doc = ContainerInspector.parseXml(e.getValue());
                    for (StructureNode s : XadesStructure.signatures(doc, entries, false)) {
                        s.row("Soubor v kontejneru", n);
                        sigGroup.add(s);
                    }
                } catch (Exception ex) {
                    sigGroup.add(new StructureNode(Kind.FILE, n).data(e.getValue(), baseName(n))
                            .note(Severity.ERROR, "Podpis XAdES se nepodařilo přečíst ("
                                    + CmsStructure.describe(ex) + ")."));
                }
            } else if (!lower.contains("manifest") || !lower.endsWith(".xml")) {
                StructureNode o = new StructureNode(Kind.FILE, n).data(e.getValue(), baseName(n))
                        .subtitle(ContainerInspector.formatSize(e.getValue().length));
                if (lower.contains("evidencerecord")) {
                    o.note(Severity.INFO, "Záznam o důkazu (evidence record); Inspektor ho nerozkládá.");
                }
                other.add(o);
            }
        }
        for (String n : dataFiles) {
            byte[] d = entries.get(n);
            files.add(new StructureNode(Kind.FILE, n).data(d, baseName(n))
                    .subtitle(ContainerInspector.formatSize(d.length))
                    .row("Velikost", ContainerInspector.formatSize(d.length))
                    .row("SHA-256", ContainerInspector.sha256Hex(d)));
        }

        // Podpisy XAdES z různých souborů signatures*.xml se jmenují jen „Podpis“ (Id v názvu
        // čtenáři nic neříká); vedle sebe je odliší pořadí v kontejneru.
        List<StructureNode> plain = sigGroup.children().stream()
                .filter(s -> s.kind() == Kind.SIGNATURE && s.title().startsWith("Podpis") && !s.title().contains("META-INF"))
                .toList();
        if (plain.size() > 1) {
            for (int i = 0; i < plain.size(); i++) plain.get(i).title("Podpis " + (i + 1));
        }
        sigGroup.title("Podpisy a pečetě (" + sigGroup.children().size() + ")");
        StructureNode tsGroup = timestampGroup(containerTimestamps, sigGroup.children());
        manifests.title("Manifesty ASiC (" + manifests.children().size() + ")");
        files.title("Podepisované soubory (" + files.children().size() + ")");
        List<StructureNode> roots = new ArrayList<>(List.of(sigGroup, tsGroup));
        // ASiC-S manifest nemá; prázdná skupina by vypadala jako chybějící část.
        if (!manifests.children().isEmpty() || !asicS) roots.add(manifests);
        if (!other.children().isEmpty()) {
            other.title("Jiné prvky v META-INF (" + other.children().size() + ")");
            roots.add(other);
        }
        roots.add(files);

        List<StructureNode.Row> summary = new ArrayList<>();
        summary.add(new StructureNode.Row("Typ", mimetype != null ? mimetype : "bez souboru mimetype", null));
        summary.add(new StructureNode.Row("Velikost", ContainerInspector.formatSize(bytes.length), null));
        summary.add(new StructureNode.Row("Podpisů / razítek", sigGroup.children().size() + " / "
                + tsGroup.children().size(), null));
        return new StructureInspector.Inspection(StructureInspector.Format.ASIC, fileName, bytes.length,
                List.of(), roots, summary, null);
    }

    /**
     * Co může podpis či razítko v ASiC krýt: u ASiC-S jediný podepisovaný soubor, u ASiC-E manifest,
     * který na ně odkazuje ({@code SigReference}); pro jistotu se zkusí i ostatní.
     */
    private static List<Content> candidates(String entry, boolean asicS, List<String> dataFiles,
                                            Map<String, byte[]> entries, Map<String, String> manifestFor) {
        List<Content> out = new ArrayList<>();
        String m = manifestFor.get(entry);
        if (m != null) out.add(Content.of("manifest " + m, entries.get(m)));
        if (asicS || m == null) {
            for (String f : dataFiles) out.add(Content.of("soubor " + f, entries.get(f)));
        }
        for (String other : manifestFor.values()) {
            if (!other.equals(m)) out.add(Content.of("manifest " + other, entries.get(other)));
        }
        return out;
    }

    private static StructureNode manifest(String name, byte[] xml, Map<String, byte[]> entries,
                                          Map<String, String> manifestFor) {
        StructureNode n = new StructureNode(Kind.MANIFEST, name).data(xml, baseName(name));
        try {
            Document doc = ContainerInspector.parseXml(xml);
            NodeList sigRefs = doc.getElementsByTagNameNS("*", "SigReference");
            for (int i = 0; i < sigRefs.getLength(); i++) {
                String uri = ((Element) sigRefs.item(i)).getAttribute("URI");
                n.row("Odkaz na podpis (SigReference)", uri);
                manifestFor.put(uri, name);
                n.subtitle("pro " + uri);
            }
            NodeList refs = doc.getElementsByTagNameNS("*", "DataObjectReference");
            for (int i = 0; i < refs.getLength(); i++) {
                Element r = (Element) refs.item(i);
                String uri = r.getAttribute("URI");
                String alg = null, value = null;
                NodeList dm = r.getElementsByTagNameNS("*", "DigestMethod");
                if (dm.getLength() > 0) alg = ((Element) dm.item(0)).getAttribute("Algorithm");
                NodeList dv = r.getElementsByTagNameNS("*", "DigestValue");
                if (dv.getLength() > 0) value = dv.item(0).getTextContent().trim();
                String p = " " + (i + 1);
                n.row("Soubor" + p, uri);
                n.row("Otisk" + p, alg + " — " + value);
                // URI v manifestu je procentně kódované („ASiC%20dokument.pdf“); bez dekódování
                // hlásil sken D:\Downloads 13. 10. 2026 chybějící soubory, které v kontejneru byly.
                byte[] data = entries.get(uri);
                if (data == null) {
                    try {
                        data = entries.get(java.net.URLDecoder.decode(uri.replace("+", "%2B"),
                                java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException ignored) { }
                }
                String jca = jcaName(alg);
                if (data == null) {
                    n.note(Severity.ERROR, "Soubor „" + uri + "“ v kontejneru není.");
                } else if (jca != null && value != null) {
                    String actual = ContainerInspector.digestHex(data, jca);
                    boolean ok = actual != null && java.util.Arrays.equals(
                            java.util.HexFormat.of().parseHex(actual), Base64.getMimeDecoder().decode(value));
                    n.note(ok ? Severity.OK : Severity.ERROR, "Otisk souboru „" + uri + "“ "
                            + (ok ? "souhlasí." : "nesouhlasí."));
                }
            }
            if (sigRefs.getLength() == 0 && refs.getLength() == 0) {
                n.note(Severity.INFO, "Seznam souborů kontejneru podle OpenDocument, ne manifest ASiC;"
                        + " podpisy XAdES v ASiC-E odkazují na soubory přímo.");
                odfEntries(n, doc, entries);
            }
        } catch (Exception e) {
            n.note(Severity.ERROR, "Manifest se nepodařilo přečíst (" + CmsStructure.describe(e) + ").");
        }
        return n;
    }

    /**
     * Položky seznamu {@code manifest:file-entry} a jejich soulad s obsahem kontejneru.
     *
     * <p>Do 13. 10. 2026 Inspektor u {@code META-INF/manifest.xml} jen říkal, co to je, a obsah
     * nevypsal (připomínka uživatele). Soubor, který seznam uvádí a kontejner nemá, i soubor
     * v kontejneru, který seznam nezná, je strukturní vada, kterou ASiC-E zakazuje
     * (EN 319 162-1, 4.4.1) — ověřovatel na ni může reagovat různě.
     */
    private static void odfEntries(StructureNode n, Document doc, Map<String, byte[]> entries) {
        NodeList fe = doc.getElementsByTagNameNS("*", "file-entry");
        java.util.Set<String> listed = new java.util.LinkedHashSet<>();
        int i = 0;
        for (int k = 0; k < fe.getLength(); k++) {
            Element e = (Element) fe.item(k);
            String path = attrNs(e, "full-path");
            String type = attrNs(e, "media-type");
            if (path == null || path.isEmpty()) continue;
            if ("/".equals(path)) {
                n.row("Celý kontejner (/)", type);
                continue;
            }
            i++;
            listed.add(path);
            boolean present = entries.containsKey(path);
            n.row("Soubor " + i, path + (type != null && !type.isEmpty() ? " — " + type : "")
                    + (present ? "" : " — v kontejneru chybí"));
            if (!present) n.note(Severity.ERROR, "Seznam uvádí „" + path + "“, v kontejneru ale není.");
        }
        for (String name : entries.keySet()) {
            if (name.equals("mimetype") || name.startsWith("META-INF/")) continue;
            if (!listed.contains(name)) {
                n.note(Severity.WARNING, "Soubor „" + name + "“ je v kontejneru, ale seznam ho neuvádí.");
            }
        }
        n.subtitle("seznam souborů (OpenDocument) · " + i + (i == 1 ? " soubor" : i < 5 ? " soubory" : " souborů"));
    }

    /** Atribut s předponou jmenného prostoru ({@code manifest:full-path}) i bez ní. */
    private static String attrNs(Element e, String local) {
        String v = e.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:manifest:1.0", local);
        if (v == null || v.isEmpty()) v = e.getAttribute("manifest:" + local);
        if (v == null || v.isEmpty()) v = e.getAttribute(local);
        return v;
    }

    private static String jcaName(String uri) {
        if (uri == null) return null;
        if (uri.endsWith("#sha256")) return "SHA-256";
        if (uri.endsWith("#sha384") || uri.endsWith("sha384")) return "SHA-384";
        if (uri.endsWith("#sha512")) return "SHA-512";
        if (uri.endsWith("#sha1")) return "SHA-1";
        return null;
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
