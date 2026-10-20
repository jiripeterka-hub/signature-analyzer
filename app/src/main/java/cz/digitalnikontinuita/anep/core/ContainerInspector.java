package cz.digitalnikontinuita.anep.core;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Takes the bytes of a signed object and, when that object is a <em>container</em> — an ASiC
 * (or any other) ZIP, a CMS/PKCS #7 object with encapsulated content, an XML document, or a
 * JAdES signature in JSON — exposes what is inside it as a browsable tree.
 * <p>
 * This is deliberately independent of DSS: the point is to show the container exactly as it is
 * stored on disk, including parts DSS ignores (unsigned files, ZIP metadata, ordering of
 * entries), so that a user who has a question about the container itself can answer it.
 * DSS's own view of the container is what the validation tabs already show.
 * <p>
 * Nothing here mutates the input, and every node carries its raw bytes so the UI can preview,
 * hash, extract or re-inspect (a container inside a container) without touching the file again.
 */
public final class ContainerInspector {

    private ContainerInspector() {}

    /** Entries above this size keep their metadata but not their bytes — nothing needs them in memory. */
    private static final long MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    /** Total budget across all entries; a zip bomb stops filling memory once this is reached. */
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    /** Hard cap on tree size — a pathological XML must not lock the UI up. */
    private static final int MAX_NODES = 20_000;

    /** What the top-level object turned out to be. */
    public enum Kind {
        /** A ZIP: ASiC, ODF, OOXML or a plain archive. */
        ZIP,
        /** A CMS / PKCS #7 SignedData object (.p7m with content, .p7s when detached). */
        CMS,
        /**
         * An ISDS data message (.zfo) — a CMS object whose signed content is the XML of a
         * message downloaded from a Czech data box, or of its delivery receipt. Structurally a
         * CMS, but the thing inside is a message with an envelope and attachments, so it is
         * shown as one; see {@link IsdsMessageInspector}.
         */
        ZFO,
        /** An XML document — XAdES enveloping/enveloped/detached, or plain XML. */
        XML,
        /** A JSON object — JAdES. */
        JSON,
        /** A PDF. Not browsed here: the application shows PDFs in the platform viewer. */
        PDF,
        /** Anything else — nothing to browse. */
        OTHER
    }

    /**
     * One element of a container. Directories are synthesised for ZIP paths; everything else
     * corresponds to something really present in the file (a ZIP entry, a CMS field, an XML element).
     */
    public static final class Node {
        private final String name;
        private final String path;
        private final boolean directory;
        private final String role;
        private byte[] data;
        private long size = -1;
        private long compressedSize = -1;
        private String method;
        private Long crc32;
        private Date modified;
        private boolean attachment;
        private boolean collapsed;
        private boolean fragment;
        private String displayName;
        private final Map<String, String> info = new LinkedHashMap<>();
        private final List<Node> children = new ArrayList<>();

        Node(String name, String path, boolean directory, String role) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.role = role;
        }

        public String getName()             { return name; }
        public String getPath()             { return path; }
        public boolean isDirectory()        { return directory; }
        /** Human-readable role of this element inside the container, or {@code null} if it has none. */
        public String getRole()             { return role; }
        /** Raw bytes, or {@code null} for directories and entries too large to hold. */
        public byte[] getData()             { return data; }
        public long getSize()               { return size; }
        public long getCompressedSize()     { return compressedSize; }
        public String getMethod()           { return method; }
        public Long getCrc32()              { return crc32; }
        public Date getModified()           { return modified; }
        /**
         * True for an element the container carries as a document in its own right — an ISDS
         * message's písemnost. Such elements can be taken out as a group, which is what a
         * reader of a data message usually wants; nothing else in a container is that.
         */
        public boolean isAttachment()       { return attachment; }
        /**
         * True for a folder that should open shut — machinery the reader can go to when they want
         * it, rather than the first thing they have to look past to find the documents.
         */
        public boolean isCollapsed()        { return collapsed; }
        /**
         * True when the bytes are a piece cut out of a larger document rather than a document.
         * They are exact, and hashing or saving them is meaningful, but they do not parse on
         * their own — an XML fragment refers to namespace prefixes its parent declared — so the
         * UI must not offer them as something to read.
         */
        public boolean isFragment()         { return fragment; }
        /**
         * Jméno pro strom a pro návrh názvu při ukládání — skutečné jméno položky, pokud se
         * dá číst, jinak náhrada.
         *
         * <p>{@link #getName()} zůstává pravdou o kontejneru a karta ho vypisuje pod
         * „Název v kontejneru". Rozcházejí se jen u validačních dat se strojovými jmény — viz
         * {@link #readableValidationDataName}.
         */
        public String getDisplayName()      { return displayName != null ? displayName : name; }

        /** Extra label→value pairs shown in the detail pane, in insertion order. */
        public Map<String, String> getInfo() { return info; }

        /**
         * Údaje se dají členit do oddílů (16. 10. 2026, obálka datové zprávy): oddělující čára
         * a tučný nadpis bez hodnoty jsou záznamy se značkovým klíčem, protože údaje jsou jedna
         * uspořádaná mapa a pořadí je to, co oddíly dělá. Značka začíná neviditelným oddělovačem
         * U+2063; za ním smí stát i u obyčejného popisku, aby dva oddíly mohly mít řádek téhož
         * jména („ID schránky“) — zobrazí se jen část před ním ({@link #labelOf}).
         */
        public static final char KEY_MARK = '\u2063';
        private static final String SEPARATOR_KEY = KEY_MARK + "sep";
        private static final String HEADING_KEY = KEY_MARK + "head" + KEY_MARK;

        public static boolean isSeparatorKey(String key) { return key.startsWith(SEPARATOR_KEY); }

        /** Text nadpisu oddílu, nebo {@code null}, není-li klíč nadpisem. */
        public static String headingOf(String key) {
            return key.startsWith(HEADING_KEY) ? key.substring(HEADING_KEY.length()) : null;
        }

        /** Popisek k zobrazení: klíč bez případné rozlišovací přípony za {@link #KEY_MARK}. */
        public static String labelOf(String key) {
            int mark = key.indexOf(KEY_MARK);
            return mark < 0 ? key : key.substring(0, mark);
        }

        static String separatorKey(int n) { return SEPARATOR_KEY + n; }
        static String headingKey(String text) { return HEADING_KEY + text; }
        public List<Node> getChildren()     { return children; }

        Node add(Node child) { children.add(child); return child; }
        Node put(String k, String v) { if (v != null && !v.isBlank()) info.put(k, v); return this; }

        /** Attaches the element's bytes; size follows from them. Used by the other inspectors. */
        Node data(byte[] bytes) {
            this.data = bytes;
            this.size = bytes == null ? -1 : bytes.length;
            return this;
        }

        Node modified(Date when) { this.modified = when; return this; }

        Node attachment() { this.attachment = true; return this; }

        Node collapsed() { this.collapsed = true; return this; }

        Node fragment() { this.fragment = true; return this; }

        Node displayName(String v) { this.displayName = v; return this; }
    }

    /**
     * The outcome of an inspection.
     *
     * @param kind       what the object turned out to be
     * @param typeLabel  Czech label for the concrete flavour ("kontejner ASiC-E", "CMS / PKCS #7", …)
     * @param root       the tree root; its children are the top-level elements
     * @param summary    label→value pairs describing the container as a whole
     * @param warnings   structural observations worth showing (ASiC rule violations, parse trouble)
     * @param error      a fatal parse failure, or {@code null}
     */
    public record Result(Kind kind, String typeLabel, Node root,
                         Map<String, String> summary, List<String> warnings, String error) {

        /** True when there is a tree worth opening a window for. */
        public boolean isBrowsable() {
            return root != null && !root.getChildren().isEmpty();
        }
    }

    // ---------------------------------------------------------------- detection

    /** Classifies bytes by content, not by extension — the same rule DSS validation follows. */
    public static Kind detectKind(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return Kind.OTHER;
        if (startsWith(bytes, "%PDF")) return Kind.PDF;
        if (bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7)) return Kind.ZIP;
        if ((bytes[0] & 0xFF) == 0x30) return looksLikeCms(bytes) ? Kind.CMS : Kind.OTHER;
        if (startsWith(bytes, "-----BEGIN")) {
            return startsWith(bytes, "-----BEGIN CERT") ? Kind.OTHER : Kind.CMS;
        }
        int i = skipTextPreamble(bytes);
        if (i < bytes.length) {
            if (bytes[i] == '<') return Kind.XML;
            if (bytes[i] == '{') return Kind.JSON;
        }
        return Kind.OTHER;
    }

    /**
     * Tells a CMS object from any other DER structure that opens with a SEQUENCE.
     *
     * <p>A leading {@code 0x30} was taken for a CMS object on its own, and an X.509 certificate
     * begins with exactly that — so a {@code .cer} attachment was labelled "obálka CMS", offered
     * as a container, and then failed to parse when anybody took the offer. What actually
     * separates them is the first element inside the SEQUENCE: a CMS {@code ContentInfo} starts
     * with its content-type OID (tag {@code 0x06}), a certificate with the SEQUENCE of its
     * {@code tbsCertificate} (tag {@code 0x30}).
     *
     * <p>Deliberately reads only the header, because callers hand it as little as sixteen bytes.
     */
    private static boolean looksLikeCms(byte[] bytes) {
        if (bytes.length < 3) return false;
        int lengthByte = bytes[1] & 0xFF;
        // Short form and BER's indefinite length (0x80) both put the content at offset 2;
        // the long form spends that many further bytes stating the length first.
        int contentStart = lengthByte <= 0x80 ? 2 : 2 + (lengthByte & 0x7F);
        return contentStart < bytes.length && (bytes[contentStart] & 0xFF) == 0x06;
    }

    /**
     * Names a DER structure that is not a CMS object, or {@code null} when it is neither of the
     * two worth naming. Parses rather than sniffs — at this size that is cheap, and the point is
     * to say "certifikát X.509" where a guess would have to say nothing.
     */
    public static String describeDer(byte[] data) {
        if (data == null || data.length < 4 || (data[0] & 0xFF) != 0x30) return null;
        try {
            new X509CertificateHolder(data);
            return "certifikát X.509";
        } catch (Exception ignored) {
            // Not a certificate; try the other structure that looks like this.
        }
        try {
            new X509CRLHolder(data);
            return "seznam CRL";
        } catch (Exception ignored) {
            return null;
        }
    }

    /** True when {@link #inspect} would produce something the user can browse. */
    public static boolean isContainerKind(Kind kind) {
        return kind == Kind.ZIP || kind == Kind.CMS || kind == Kind.ZFO
                || kind == Kind.XML || kind == Kind.JSON;
    }

    /** The three things a data box hands out as a ZFO. */
    public enum IsdsKind { MESSAGE, DELIVERY, ENVELOPE }

    /**
     * Which end of the message the file was downloaded from.
     * <p>
     * ISDS does not say so in any field — it says it in the namespace of the response, because
     * the sender and the recipient fetch their copy through different services
     * ({@code SignedSentMessageDownload} against {@code SignedMessageDownload}). The envelope
     * inside is identical either way, so this namespace is the only thing that tells the two
     * copies of one message apart.
     */
    public enum IsdsDirection { RECEIVED, SENT, UNKNOWN }

    /** Namespace of the response a recipient's copy arrives in. */
    private static final String NS_RECEIVED = "isds.czechpoint.cz/v20/message";
    /** Namespace of the response a sender's copy arrives in — also used by the envelope service. */
    private static final String NS_SENT = "isds.czechpoint.cz/v20/sentmessage";

    /**
     * What ISDS artefact a file holds, and which side's copy it is. All the naming the UI needs
     * is derived here, so that "přijatá" and "odeslaná" never have to be spelled out twice.
     */
    public record IsdsArtefact(IsdsKind kind, IsdsDirection direction) {

        /** Names the format as it reads inside a sentence: "…je odeslaná datová zpráva ISDS". */
        public String typeLabel() {
            return switch (kind) {
                case MESSAGE  -> adjective() + "datová zpráva ISDS (ZFO)";
                case DELIVERY -> "doručenka datové zprávy ISDS (ZFO)";
                case ENVELOPE -> "obálka " + genitiveAdjective()
                        + "datové zprávy ISDS (ZFO), bez písemností";
            };
        }

        /** Names it as the subject of a sentence: "Odeslaná datová zpráva ISDS (ZFO) má…". */
        public String label() {
            String text = typeLabel();
            return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }

        /** Names it after a noun: "Zobrazit obsah <em>odeslané datové zprávy ISDS</em>". */
        public String genitive() {
            return switch (kind) {
                case MESSAGE  -> genitiveAdjective() + "datové zprávy ISDS";
                case DELIVERY -> "doručenky datové zprávy ISDS";
                case ENVELOPE -> "obálky " + genitiveAdjective() + "datové zprávy ISDS";
            };
        }

        /** Names the box the file was downloaded from, or {@code null} when that is not known. */
        public String downloadedByRole() {
            return switch (direction) {
                case SENT     -> "odesílatele";
                case RECEIVED -> "příjemce";
                case UNKNOWN  -> null;
            };
        }

        private String adjective() {
            return switch (direction) {
                case RECEIVED -> "přijatá ";
                case SENT     -> "odeslaná ";
                case UNKNOWN  -> "";
            };
        }

        private String genitiveAdjective() {
            return switch (direction) {
                case RECEIVED -> "přijaté ";
                case SENT     -> "odeslané ";
                case UNKNOWN  -> "";
            };
        }
    }

    /**
     * What ISDS artefact the bytes hold, or {@code null} when they are not one.
     * <p>
     * Deliberately shallow — it looks for the response's root element in the head of the file
     * rather than parsing the CMS, because the callers are UI decisions (which icon, which
     * wording) taken for every file and for every row scrolled into view, and a data message can
     * be tens of megabytes. The signed XML starts within the first hundred bytes of a ZFO and its
     * root element carries every marker looked for here, so the window below never cuts the
     * answer in half. Anything this says yes to is still parsed properly by
     * {@link IsdsMessageInspector} before it is presented as a message; a false positive costs
     * nothing but a fallback to the plain CMS view.
     */
    public static IsdsArtefact isdsArtefact(byte[] bytes) {
        if (bytes == null || bytes.length < 64) return null;
        String head = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        if (!head.contains("isds.czechpoint.cz/v20")) return null;
        // Matched on the whole namespace URI rather than on "sentmessage" alone: the window
        // scanned reaches into base64 attachment data, where any short word can turn up by chance.
        IsdsDirection direction = head.contains(NS_SENT) ? IsdsDirection.SENT
                : head.contains(NS_RECEIVED) ? IsdsDirection.RECEIVED
                : IsdsDirection.UNKNOWN;
        if (head.contains("getdeliveryinforesponse") || head.contains("dmdelivery")) {
            return new IsdsArtefact(IsdsKind.DELIVERY, direction);
        }
        if (head.contains("dmreturnedmessageenvelope")) {
            return new IsdsArtefact(IsdsKind.ENVELOPE, direction);
        }
        if (head.contains("messagedownloadresponse") || head.contains("dmreturnedmessage")) {
            return new IsdsArtefact(IsdsKind.MESSAGE, direction);
        }
        return null;
    }

    /** {@link #isdsArtefact(byte[])} for a file, reading only its head. */
    public static IsdsArtefact isdsArtefact(java.io.File file) {
        if (file == null || !file.isFile()) return null;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            return isdsArtefact(in.readNBytes(8192));
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the bytes are any ISDS artefact — message, delivery receipt or bare envelope. */
    public static boolean isIsdsMessage(byte[] bytes) {
        return isdsArtefact(bytes) != null;
    }

    /**
     * The direction read from a response's namespace. Used by {@link IsdsMessageInspector}, which
     * has the parsed document and therefore the authoritative answer rather than a scan of a
     * fixed-size window.
     */
    static IsdsDirection isdsDirection(String namespaceUri) {
        if (namespaceUri == null) return IsdsDirection.UNKNOWN;
        String ns = namespaceUri.toLowerCase(Locale.ROOT);
        if (ns.contains(NS_SENT)) return IsdsDirection.SENT;
        if (ns.contains(NS_RECEIVED)) return IsdsDirection.RECEIVED;
        return IsdsDirection.UNKNOWN;
    }

    // ---------------------------------------------------------------- entry point

    /**
     * Builds the tree for {@code bytes}. Never throws: a parse failure comes back as a
     * {@link Result} carrying {@code error}, because the caller is a UI action, not a validation.
     */
    public static Result inspect(byte[] bytes, String fileName) {
        Kind kind = detectKind(bytes);
        try {
            return switch (kind) {
                case ZIP  -> inspectZip(bytes, fileName);
                case CMS  -> inspectCms(bytes, fileName);
                case XML  -> inspectXml(bytes, fileName);
                case JSON -> inspectJson(bytes, fileName);
                default   -> new Result(kind, typeLabelFor(kind), new Node(fileName, "", true, null),
                        new LinkedHashMap<>(), List.of(), null);
            };
        } catch (Exception e) {
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("Velikost", formatSize(bytes.length));
            return new Result(kind, typeLabelFor(kind), new Node(fileName, "", true, null),
                    summary, List.of(), describe(e));
        }
    }

    private static String typeLabelFor(Kind kind) {
        return switch (kind) {
            case ZIP  -> "archiv ZIP";
            case CMS  -> "objekt CMS / PKCS #7";
            case ZFO  -> "datová zpráva ISDS (ZFO)";
            case XML  -> "dokument XML";
            case JSON -> "dokument JSON";
            case PDF  -> "dokument PDF";
            case OTHER -> "neznámý formát";
        };
    }

    // ---------------------------------------------------------------- ZIP / ASiC

    private static Result inspectZip(byte[] bytes, String fileName) throws Exception {
        Node root = new Node(fileName, "", true, null);
        Map<String, Node> byPath = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long totalLoaded = 0;
        int entryCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (byPath.size() > MAX_NODES) {
                    warnings.add("Archiv obsahuje více než " + MAX_NODES
                            + " položek; zobrazena je jen jejich část.");
                    break;
                }
                String path = normalizeZipPath(entry.getName());
                if (path.isEmpty()) continue;
                order.add(path);
                entryCount++;

                boolean dir = entry.isDirectory();
                byte[] data = null;
                if (!dir) {
                    long declared = entry.getSize();
                    if (declared >= 0 && declared > MAX_ENTRY_BYTES) {
                        skip(zis);
                    } else if (totalLoaded > MAX_TOTAL_BYTES) {
                        skip(zis);
                    } else {
                        data = readCapped(zis, MAX_ENTRY_BYTES);
                        totalLoaded += data.length;
                    }
                }

                Node node = ensurePath(root, byPath, path, dir);
                if (data != null) {
                    node.data = data;
                    node.size = data.length;
                } else if (entry.getSize() >= 0) {
                    node.size = entry.getSize();
                }
                if (!dir) {
                    if (entry.getCompressedSize() >= 0) node.compressedSize = entry.getCompressedSize();
                    node.method = entry.getMethod() == ZipEntry.STORED ? "uloženo bez komprese"
                            : entry.getMethod() == ZipEntry.DEFLATED ? "Deflate"
                            : entry.getMethod() >= 0 ? "metoda " + entry.getMethod() : null;
                    if (entry.getCrc() >= 0) node.crc32 = entry.getCrc();
                    if (entry.getLastModifiedTime() != null) {
                        node.modified = new Date(entry.getLastModifiedTime().toMillis());
                    }
                    if (entry.getComment() != null && !entry.getComment().isBlank()) {
                        node.put("Poznámka v archivu", entry.getComment());
                    }
                }
                zis.closeEntry();
            }
        }

        String mimetype = null;
        Node mimeNode = byPath.get("mimetype");
        if (mimeNode != null && mimeNode.data != null) {
            mimetype = new String(mimeNode.data, StandardCharsets.US_ASCII).trim();
        }

        String typeLabel = zipTypeLabel(mimetype, byPath);
        boolean asic = typeLabel.startsWith("kontejner ASiC");

        annotateZipRoles(byPath);
        applyAsicCoverage(byPath, warnings);
        if (asic) checkAsicStructure(order, byPath, mimetype, warnings);

        sortTree(root);

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Formát", typeLabel);
        if (mimetype != null && !mimetype.isEmpty()) summary.put("Deklarovaný mimetype", mimetype);
        summary.put("Velikost souboru", formatSize(bytes.length));
        summary.put("Počet položek", String.valueOf(entryCount));
        long payload = byPath.values().stream().filter(n -> !n.directory && n.size > 0)
                .mapToLong(n -> n.size).sum();
        if (payload > 0) summary.put("Rozbalený obsah", formatSize(payload));

        return new Result(Kind.ZIP, typeLabel, root, summary, warnings, null);
    }

    private static String zipTypeLabel(String mimetype, Map<String, Node> byPath) {
        if (mimetype != null) {
            String m = mimetype.toLowerCase(Locale.ROOT);
            if (m.contains("asic-e")) return "kontejner ASiC-E";
            if (m.contains("asic-s")) return "kontejner ASiC-S";
            if (m.startsWith("application/vnd.oasis.opendocument")) return "dokument OpenDocument (ODF)";
            if (m.startsWith("application/vnd.etsi")) return "kontejner ASiC";
        }
        boolean hasAsicSignatures = byPath.keySet().stream().anyMatch(p -> {
            String lower = p.toLowerCase(Locale.ROOT);
            return lower.startsWith("meta-inf/") && (lower.contains("signature") || lower.contains("timestamp")
                    || lower.contains("asicmanifest"));
        });
        if (hasAsicSignatures) return "kontejner ASiC (bez deklarovaného mimetype)";
        if (byPath.containsKey("[Content_Types].xml")) return "dokument Office Open XML (OOXML)";
        return "archiv ZIP";
    }

    /** Names the well-known ASiC members so the tree explains itself without the user knowing the spec. */
    private static void annotateZipRoles(Map<String, Node> byPath) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : byPath.entrySet()) {
            String p = e.getKey();
            String lower = p.toLowerCase(Locale.ROOT);
            Node n = e.getValue();
            if (n.directory) continue;
            String role = null;
            if (lower.equals("mimetype")) {
                role = "deklarace typu kontejneru";
            } else if (lower.startsWith("meta-inf/")) {
                String base = lower.substring("meta-inf/".length());
                if (base.startsWith("asicevidencerecordmanifest"))
                    role = "manifest evidenčního záznamu (které soubory záznam kryje)";
                else if (base.startsWith("asicarchivemanifest")) role = "archivní manifest ASiC";
                else if (base.startsWith("asicmanifest")) role = "manifest ASiC (které soubory podpis kryje)";
                else if (base.startsWith("signatures") && base.endsWith(".xml")) role = "podpis XAdES";
                else if (base.startsWith("signature") && base.endsWith(".p7s")) role = "podpis CAdES";
                // Podle přípony, ne podle začátku jména. ETSI EN 319 162-1 sice říká
                // {@code timestamp001.tst}, jenže kontejnery z praxe se tím neřídí:
                // {@code arch_timestamp_2021-12-20_103015860_efc9.tst} je archivní razítko
                // a spadávalo do koše „metadata kontejneru". Soubor .tst v META-INF razítko je,
                // ať se jmenuje jakkoli.
                else if (base.endsWith(".tst") || base.endsWith(".tsd"))
                    role = "časové razítko";
                // ETSI EN 319 162-1 names these evidencerecord.ers / evidencerecord.xml in ASiC-S
                // and evidencerecord001.ers / .xml in ASiC-E, so match on the stem, not the whole
                // name. The .xml case has to come after the XAdES check above, which is narrower.
                else if (base.startsWith("evidencerecord") && base.endsWith(".ers"))
                    role = "evidenční záznam ERS (RFC 4998)";
                else if (base.startsWith("evidencerecord") && base.endsWith(".xml"))
                    role = "evidenční záznam XMLERS (RFC 6283)";
                else if (base.equals("manifest.xml")) role = "manifest ODF";
                // Validační data uložená jako samostatné položky. Jejich jména bývají
                // strojová a na pohled nesrozumitelná — například
                // {@code SCIsSnProducedAt!…!…!20201111203255Z.ors} —, takže právě u nich
                // záleží na tom, aby strom řekl, co to je. Do 15. 9. 2026 padaly do koše
                // „metadata kontejneru" a čtenář neměl jak poznat odpověď OCSP od certifikátu.
                //
                // Ta jména AnEP nevyrábí ani nekomolí: jsou to skutečná jména položek v ZIPu,
                // jak je zapsal program, který kontejner vyrobil. Struktura v nich je — časové
                // razítko na konci odpovídá {@code producedAt} odpovědi, resp. {@code notBefore}
                // certifikátu (ověřeno na {@code T kvalifikovany_Uchovano_do_03_08_2034.asice}) —,
                // ale přepisovat je by znamenalo tvrdit o obsahu kontejneru něco, co v něm nestojí.
                else if (base.endsWith(".ors")) role = "odpověď OCSP (validační data)";
                else if (base.endsWith(".crl")) role = "seznam CRL (validační data)";
                else if (base.endsWith(".cer") || base.endsWith(".crt")
                        || base.endsWith(".der")) role = "certifikát (validační data)";
                else role = "metadata kontejneru";
            } else if (lower.endsWith(".p7s")) {
                role = "podpis CAdES";
            } else if (lower.endsWith(".tst")) {
                role = "časové razítko";
            } else {
                role = "podepisovaný obsah";
            }
            if (role != null) n.info.put("Role", role);
            // Razítko uložené jako samostatná položka se čte stejně jako to v nepodepsaném
            // atributu CMS: čtenář se u obou ptá na totéž a jiná cesta do stromu není důvod,
            // proč by se mu mělo dostat jiné odpovědi.
            if ("časové razítko".equals(role)) describeTimestamp(n, n.data);
            // Až tady, ne uprostřed řetězu else-if nad tím. Vložení doprostřed ho rozpáralo:
            // koncové „else role = …" se navázalo na nový if a přebilo role všem ostatním
            // položkám — manifesty i podpis se začaly hlásit jako metadata. Java to přeloží
            // bez hlesu; chytila to až sonda nad skutečným kontejnerem.
            String readable = readableValidationDataName(p, role, counters);
            if (readable != null) n.displayName(readable);
        }
    }

    /**
     * Čitelná náhrada za strojové jméno validačních dat, nebo {@code null}, když je jméno
     * v pořádku a nahrazovat se nemá.
     *
     * <p>Kontejnery nosí odpovědi OCSP a certifikáty pod jmény, která vyrobil stroj pro stroj:
     * {@code SCIsSnProducedAt!d7omj-NRzi19EUas2hv4Z9tglAs!ALNGmA!20201111203255Z.ors}. Ve stromu
     * zaberou celý řádek a čtenáři neřeknou nic. Nález uživatele z 15. 9. 2026 nad
     * {@code T kvalifikovany_Uchovano_do_03_08_2034.asice}.
     *
     * <p><b>Původní jméno se nezahazuje</b>, jen ustupuje: {@link Node#getName()} ho drží dál
     * a karta ho vypisuje pod „Název v kontejneru". Nahradit jméno všude by znamenalo tvrdit
     * o obsahu kontejneru něco, co v něm nestojí.
     *
     * <p>Číslování je tu proto, že jedna odpověď OCSP bývá výjimka: kontejner po několika
     * uchováních jich nese i půl tuctu a šestkrát „odpoved-ocsp.ors" by čtenáři vzalo i to
     * málo, co měl — možnost je od sebe odlišit.
     *
     * <p>Nahrazuje se jen tam, kde je čemů: jméno s vykřičníkem, nebo delší než čtyřicet znaků.
     * Kontejner s poctivým {@code cert.cer} si ho nechá.
     */
    private static String readableValidationDataName(String path, String role,
                                                     Map<String, Integer> counters) {
        if (role == null || !role.contains("validační data")) return null;
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (!stem.contains("!") && stem.length() <= 40) return null;
        String kind = role.startsWith("odpověď") ? "odpoved-ocsp"
                : role.startsWith("seznam") ? "crl" : "certifikat";
        int n = counters.merge(kind, 1, Integer::sum);
        return kind + "-" + n + ext;
    }

    /**
     * Reads the digests every signature and manifest declares, and marks each referenced entry
     * with whether the file present in the container still matches that digest.
     * <p>
     * This is the question a container raises that nothing else in the application answers:
     * <em>which of these files is actually signed, and by which signature?</em>
     */
    private static void applyAsicCoverage(Map<String, Node> byPath, List<String> warnings) {
        for (Map.Entry<String, Node> e : new LinkedHashMap<>(byPath).entrySet()) {
            String path = e.getKey();
            Node source = e.getValue();
            if (source.directory || source.data == null) continue;
            String lower = path.toLowerCase(Locale.ROOT);
            boolean isXmlSigOrManifest = lower.startsWith("meta-inf/") && lower.endsWith(".xml");
            if (!isXmlSigOrManifest) continue;

            List<Reference> refs;
            try {
                refs = readReferences(source.data);
            } catch (Exception ex) {
                warnings.add("Soubor " + path + " se nepodařilo přečíst jako XML: " + describe(ex));
                continue;
            }
            String sourceName = path.substring(path.lastIndexOf('/') + 1);
            for (Reference ref : refs) {
                Node target = byPath.get(ref.uri());
                if (target == null || target.data == null) continue;
                String verdict;
                if (ref.digestValue() == null || ref.algorithm() == null) {
                    verdict = "ano — " + sourceName;
                } else {
                    boolean match = digestMatches(target.data, ref.algorithm(), ref.digestValue());
                    verdict = "ano — " + sourceName + " (" + ref.algorithm()
                            + (match ? ": otisk souhlasí ✔" : ": otisk NESOUHLASÍ ✘") + ")";
                    if (!match) {
                        warnings.add("Otisk souboru " + ref.uri() + " neodpovídá hodnotě uvedené v "
                                + sourceName + " — obsah kontejneru byl po podepsání změněn.");
                    }
                }
                String existing = target.info.get("Kryto podpisem");
                target.info.put("Kryto podpisem", existing == null ? verdict : existing + "; " + verdict);
            }
        }
        // Everything that carries content but no reference is unsigned — say so explicitly.
        for (Map.Entry<String, Node> e : byPath.entrySet()) {
            Node n = e.getValue();
            if (n.directory) continue;
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            if (lower.startsWith("meta-inf/") || lower.equals("mimetype")) continue;
            if (!n.info.containsKey("Kryto podpisem")) {
                n.info.put("Kryto podpisem", "podle manifestů/podpisů XML nezjištěno");
            }
        }
    }

    /** A digest reference taken from a {@code ds:Reference} or an ASiC {@code DataObjectReference}. */
    private record Reference(String uri, String algorithm, byte[] digestValue) {}

    private static List<Reference> readReferences(byte[] xml) throws Exception {
        Document doc = parseXml(xml);
        List<Reference> out = new ArrayList<>();
        collectReferences(doc.getDocumentElement(), out);
        return out;
    }

    private static void collectReferences(Element el, List<Reference> out) {
        String local = localName(el);
        if ("Reference".equals(local) || "DataObjectReference".equals(local)) {
            String uri = el.getAttribute("URI");
            if (uri != null && !uri.isEmpty() && !uri.startsWith("#")) {
                String algorithm = null;
                byte[] digest = null;
                NodeList kids = el.getElementsByTagName("*");
                for (int i = 0; i < kids.getLength(); i++) {
                    Element kid = (Element) kids.item(i);
                    String kidName = localName(kid);
                    if ("DigestMethod".equals(kidName) && algorithm == null) {
                        algorithm = digestAlgFromUri(kid.getAttribute("Algorithm"));
                    } else if ("DigestValue".equals(kidName) && digest == null) {
                        try {
                            digest = Base64.getMimeDecoder().decode(kid.getTextContent().trim());
                        } catch (Exception ignored) {
                            // A malformed digest simply means "cannot check", not "invalid".
                        }
                    }
                }
                out.add(new Reference(decodeUri(uri), algorithm, digest));
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) collectReferences(child, out);
        }
    }

    /** Checks the structural rules ASiC imposes on the ZIP itself, which no validator reports. */
    private static void checkAsicStructure(List<String> order, Map<String, Node> byPath,
                                           String mimetype, List<String> warnings) {
        if (mimetype == null) {
            warnings.add("Kontejner neobsahuje soubor \"mimetype\"; podle ETSI EN 319 162-1 jej "
                    + "kontejner ASiC obsahovat má.");
        } else {
            if (order.isEmpty() || !"mimetype".equals(order.get(0))) {
                warnings.add("Soubor \"mimetype\" není první položkou archivu, jak ASiC vyžaduje.");
            }
            Node mimeNode = byPath.get("mimetype");
            if (mimeNode != null && mimeNode.method != null && !mimeNode.method.startsWith("uloženo")) {
                warnings.add("Soubor \"mimetype\" je komprimovaný; ASiC vyžaduje uložení bez komprese.");
            }
        }
        boolean hasSignature = byPath.keySet().stream().anyMatch(p -> {
            String l = p.toLowerCase(Locale.ROOT);
            return l.startsWith("meta-inf/")
                    && (l.endsWith(".p7s") || l.endsWith(".tst")
                        || (l.contains("signatures") && l.endsWith(".xml")));
        });
        // An evidence record preserves the container's contents without signing them. Such a
        // container is complete and valid, so the missing-signature warning would be wrong —
        // but the reader still needs to know nothing here is signed.
        boolean hasEvidenceRecord = byPath.keySet().stream().anyMatch(p -> {
            String l = p.toLowerCase(Locale.ROOT);
            return l.startsWith("meta-inf/") && l.contains("evidencerecord")
                    && (l.endsWith(".ers") || l.endsWith(".xml"));
        });
        if (!hasSignature && !hasEvidenceRecord) {
            warnings.add("V adresáři META-INF nebyl nalezen žádný podpis ani časové razítko.");
        } else if (!hasSignature) {
            warnings.add("Kontejner neobsahuje podpis ani časové razítko — jeho obsah je "
                    + "uchován evidenčním záznamem, který dokládá existenci dat, nikoli jejich původ.");
        }
        boolean hasContent = byPath.entrySet().stream().anyMatch(e -> !e.getValue().directory
                && !e.getKey().toLowerCase(Locale.ROOT).startsWith("meta-inf/")
                && !"mimetype".equals(e.getKey()));
        if (!hasContent) {
            warnings.add("Kontejner neobsahuje mimo META-INF žádný podepisovaný soubor.");
        }
    }

    private static Node ensurePath(Node root, Map<String, Node> byPath, String path, boolean directory) {
        String[] parts = path.split("/");
        Node current = root;
        StringBuilder sofar = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (sofar.length() > 0) sofar.append('/');
            sofar.append(parts[i]);
            boolean last = i == parts.length - 1;
            String key = sofar.toString();
            Node existing = byPath.get(key);
            if (existing == null) {
                existing = new Node(parts[i], key, !last || directory, null);
                byPath.put(key, existing);
                current.add(existing);
            }
            current = existing;
        }
        return current;
    }

    private static String normalizeZipPath(String name) {
        String p = name.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    // ---------------------------------------------------------------- CMS / PKCS #7

    private static Result inspectCms(byte[] raw, String fileName) throws Exception {
        byte[] bytes = maybeDecodePem(raw);
        CMSSignedData cms = new CMSSignedData(bytes);
        Node root = new Node(fileName, "", true, null);
        List<String> warnings = new ArrayList<>();

        String contentTypeOid = cms.getSignedContentTypeOID();
        boolean detached = cms.getSignedContent() == null;

        // 1. The encapsulated document, if this is a .p7m rather than a detached .p7s.
        if (!detached) {
            byte[] content = contentBytes(cms);
            // An ISDS data message is a CMS object whose content is a message, not a document.
            // Showing it as "eContent, 11 kB of XML" would be true and useless, so it gets an
            // inspection of its own — falling back here if the XML turns out not to be one.
            if (content != null && IsdsMessageInspector.looksLikeIsdsMessage(content)) {
                Result isds = IsdsMessageInspector.inspect(raw, cms, content, fileName, warnings);
                if (isds != null) return isds;
            }
            if (content != null && content.length > 0) {
                String payloadName = payloadName(fileName, content);
                Node payload = root.add(new Node(payloadName, payloadName, false, "podepsaný obsah"));
                payload.data = content;
                payload.size = content.length;
                payload.put("Role", "podepsaný obsah vložený v CMS (eContent)");
                payload.put("Typ obsahu (OID)", contentTypeOid + oidHint(contentTypeOid));
            }
        } else {
            warnings.add("Podpis je oddělený (detached) — podepsaná data nejsou součástí objektu.");
        }

        CmsCounts counts = addCmsSignatureNodes(root, cms, false);

        Map<String, String> summary = new LinkedHashMap<>();
        String typeLabel = detached ? "oddělený podpis CMS / PKCS #7"
                                    : "obálka CMS / PKCS #7 s vloženým obsahem";
        summary.put("Formát", typeLabel);
        summary.put("Velikost souboru", formatSize(raw.length));
        summary.put("Typ zapouzdřeného obsahu", contentTypeOid + oidHint(contentTypeOid));
        summary.put("Obsah vložen", detached ? "ne (oddělený podpis)" : "ano");
        summary.put("Počet podpisů", String.valueOf(counts.signers()));
        summary.put("Vložené certifikáty", String.valueOf(counts.certificates()));
        summary.put("Vložené CRL / OCSP", counts.crls() + " / " + counts.ocsps());

        return new Result(Kind.CMS, typeLabel, root, summary, warnings, null);
    }

    /** How much validation material the CMS object turned out to carry. */
    record CmsCounts(int signers, int certificates, int crls, int ocsps) {}

    /**
     * Adds the signature side of a CMS object under {@code root}: the signers with their
     * timestamps, and the certificates, CRLs and OCSP responses carried along for validation.
     * <p>
     * Shared with {@link IsdsMessageInspector}, because a data message is a CMS object too and
     * the seal on it is worth the same look — only the thing it seals is presented differently.
     * There it is not what the reader came for, which is what {@code collapsed} says: the folders
     * are added shut so the documents stay at the top of the tree.
     */
    static CmsCounts addCmsSignatureNodes(Node root, CMSSignedData cms, boolean collapsed)
            throws Exception {
        return addCmsSignatureNodes(root, cms, collapsed, false);
    }

    /**
     * @param isdsSeal objekt CMS je datová zpráva ISDS. Tu provozovatel systému vždy pečetí,
     *        nepodepisuje — a čtenář, který řeší právní účinky, ten rozdíl čte. Prvky se proto
     *        jmenují „pečeť“, ne „podpis“, a časové razítko pečeti se označí jako výstupní: ISDS
     *        ho přidává v okamžiku stažení zprávy (16. 10. 2026, pokyn uživatele). Názvy
     *        struktur CMS („Podepsané atributy“ a cesty {@code signers/…}) zůstávají, jsou to
     *        pojmy formátu, ne výklad.
     */
    static CmsCounts addCmsSignatureNodes(Node root, CMSSignedData cms, boolean collapsed,
                                          boolean isdsSeal) throws Exception {
        // Signers, each with its own timestamps.
        List<SignerInformation> signers = new ArrayList<>(cms.getSignerInfos().getSigners());
        if (!signers.isEmpty()) {
            Node folder = root.add(new Node(
                    (isdsSeal ? "Pečeti (" : "Podepisující (") + signers.size() + ")",
                    "signers", true, "SignerInfo"));
            if (collapsed) folder.collapsed();
            int idx = 0;
            for (SignerInformation signer : signers) {
                idx++;
                String label = (isdsSeal ? "Pečeť " : "Podpis ") + idx;
                Node sn = folder.add(new Node(label, "signers/" + idx, true, "SignerInfo"));
                sn.put("Vydavatel certifikátu", String.valueOf(signer.getSID().getIssuer()));
                BigInteger serial = signer.getSID().getSerialNumber();
                if (serial != null) sn.put("Sériové číslo", hexNumber(serial));
                sn.put("Otisková funkce", oidName(signer.getDigestAlgOID()));
                sn.put(isdsSeal ? "Algoritmus pečeti" : "Podpisový algoritmus",
                        oidName(signer.getEncryptionAlgOID()));
                AttributeTable signed = signer.getSignedAttributes();
                if (signed != null) {
                    sn.put("Podepsané atributy",
                            String.valueOf(signed.toASN1Structure().getAttributes().length));
                    Date signingTime = signingTime(signed);
                    if (signingTime != null) {
                        sn.put(isdsSeal ? "Deklarovaný čas pečetění" : "Deklarovaný čas podpisu",
                                formatDate(signingTime));
                    }
                }
                AttributeTable unsigned = signer.getUnsignedAttributes();
                if (unsigned != null) {
                    sn.put("Nepodepsané atributy",
                            String.valueOf(unsigned.toASN1Structure().getAttributes().length));
                    addTimestampNodes(sn, unsigned, isdsSeal);
                }
                byte[] sig = signer.getSignature();
                if (sig != null) {
                    Node sigNode = sn.add(new Node((isdsSeal ? "pecet-" : "podpis-") + idx + ".bin",
                            "signers/" + idx + "/signature", false,
                            isdsSeal ? "hodnota pečeti" : "hodnota podpisu"));
                    sigNode.data = sig;
                    sigNode.size = sig.length;
                }
            }
        }

        // Certificates, CRLs and OCSP responses carried along for validation.
        List<X509CertificateHolder> certs =
                new ArrayList<>(cms.getCertificates().getMatches(null));
        if (!certs.isEmpty()) {
            Node folder = root.add(new Node("Certifikáty (" + certs.size() + ")",
                    "certs", true, "certifikáty vložené v CMS"));
            if (collapsed) folder.collapsed();
            int i = 0;
            for (X509CertificateHolder holder : certs) {
                i++;
                String cn = commonName(String.valueOf(holder.getSubject()));
                String nodeName = safeFileName(cn.isEmpty() ? "certifikat-" + i : cn) + ".cer";
                Node n = folder.add(new Node(nodeName, "certs/" + i, false, "certifikát X.509"));
                n.data = holder.getEncoded();
                n.size = n.data.length;
                n.put("Subjekt", String.valueOf(holder.getSubject()));
                n.put("Vydavatel", String.valueOf(holder.getIssuer()));
                n.put("Sériové číslo", hexNumber(holder.getSerialNumber()));
                n.put("Platnost od", formatDate(holder.getNotBefore()));
                n.put("Platnost do", formatDate(holder.getNotAfter()));
            }
        }

        List<X509CRLHolder> crls = new ArrayList<>(cms.getCRLs().getMatches(null));
        if (!crls.isEmpty()) {
            Node folder = root.add(new Node("Seznamy CRL (" + crls.size() + ")",
                    "crls", true, "revokační seznamy vložené v CMS"));
            if (collapsed) folder.collapsed();
            int i = 0;
            for (X509CRLHolder holder : crls) {
                i++;
                String cn = commonName(String.valueOf(holder.getIssuer()));
                String nodeName = safeFileName(cn.isEmpty() ? "crl-" + i : cn) + ".crl";
                Node n = folder.add(new Node(nodeName, "crls/" + i, false, "seznam CRL"));
                n.data = holder.getEncoded();
                n.size = n.data.length;
                n.put("Vydavatel", String.valueOf(holder.getIssuer()));
                n.put("Vydáno", formatDate(holder.getThisUpdate()));
                if (holder.getNextUpdate() != null) n.put("Příští vydání", formatDate(holder.getNextUpdate()));
            }
        }

        List<byte[]> ocsps = otherRevocationInfo(cms);
        if (!ocsps.isEmpty()) {
            Node folder = root.add(new Node("Odpovědi OCSP (" + ocsps.size() + ")",
                    "ocsp", true, "OCSP odpovědi vložené v CMS"));
            if (collapsed) folder.collapsed();
            int i = 0;
            for (byte[] enc : ocsps) {
                i++;
                // CMS carries the bare BasicOCSPResponse: RFC 5940 puts it into
                // otherRevocationInfo without the OCSPResponse envelope a responder would have
                // sent it in. Taken out as those bytes it is a file nothing opens — openssl, DSS
                // and this application's own "Ulož" buttons all speak whole responses. So the
                // element is handed over wrapped, and the envelope costs nothing: a status word
                // and an OID, both constants, no evidence added and none taken away. It also
                // makes the element agree with the rest of AnEP — the same response saved from
                // the Rozšířený přehled, or found in a forensic package, is this same file down
                // to the digest, because DSS wraps it the same way.
                byte[] wrapped = wrapAsOcspResponse(enc);
                boolean whole = wrapped != null;
                Node n = folder.add(new Node("ocsp-" + i + (whole ? ".ocsp" : ".ors"),
                        "ocsp/" + i, false, "odpověď OCSP"));
                n.data = whole ? wrapped : enc;
                n.size = n.data.length;
                try {
                    BasicOCSPResp basic = new BasicOCSPResp(
                            org.bouncycastle.asn1.ocsp.BasicOCSPResponse.getInstance(
                                    ASN1Primitive.fromByteArray(enc)));
                    n.put("Vydáno", formatDate(basic.getProducedAt()));
                    n.put("Počet záznamů", String.valueOf(basic.getResponses().length));
                } catch (Exception ignored) {
                    // Metadata are a nicety; the bytes are still extractable.
                }
                if (whole) {
                    n.put("Forma", "úplná odpověď OCSP; v CMS je uložena samotná struktura"
                            + " BasicOCSPResponse a obálka se stavem „successful“ se doplňuje"
                            + " až při vyjmutí, aby soubor šlo otevřít samostatně");
                    n.put("SHA-256 vnitřní BasicOCSPResponse", sha256Hex(enc));
                }
            }
        }

        return new CmsCounts(signers.size(), certs.size(), crls.size(), ocsps.size());
    }

    static byte[] contentBytes(CMSSignedData cms) {
        try {
            Object content = cms.getSignedContent().getContent();
            if (content instanceof byte[] b) return b;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cms.getSignedContent().write(out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Timestamp tokens live in unsigned attributes; each is a complete CMS object worth extracting. */
    private static void addTimestampNodes(Node parent, AttributeTable unsigned, boolean isdsSeal) {
        String[][] known = {
                {"1.2.840.113549.1.9.16.2.14", isdsSeal
                        ? "časové razítko pečeti (tzv. výstupní časové razítko, přidávané"
                          + " v okamžiku stažení datové zprávy)"
                        : "časové razítko podpisu"},
                {"1.2.840.113549.1.9.16.2.25", "časové razítko nad referencemi"},
                {"1.2.840.113549.1.9.16.2.26", "časové razítko nad referencemi (RefsOnly)"},
                {"1.2.840.113549.1.9.16.2.27", "archivní časové razítko (v2)"},
                {"0.4.0.1733.2.4",             "archivní časové razítko (v3)"},
                {"1.2.840.113549.1.9.16.2.20", "časové razítko obsahu"},
        };
        int i = 0;
        for (String[] pair : known) {
            Attribute attr = unsigned.get(new ASN1ObjectIdentifier(pair[0]));
            if (attr == null) continue;
            ASN1Set values = attr.getAttrValues();
            for (int v = 0; v < values.size(); v++) {
                i++;
                try {
                    byte[] enc = values.getObjectAt(v).toASN1Primitive().getEncoded("DER");
                    Node n = parent.add(new Node("razitko-" + i + ".tst",
                            parent.path + "/ts" + i, false, pair[1]));
                    n.data = enc;
                    n.size = enc.length;
                    n.put("Role", pair[1]);
                    n.put("OID atributu", pair[0]);
                    describeTimestamp(n, enc);
                } catch (Exception ignored) {
                    // Unreadable attribute value — skip it rather than fail the whole inspection.
                }
            }
        }
    }

    /**
     * Vypíše u tokenu časového razítka to, kvůli čemu ho čtenář otevírá: kdy razítko vzniklo, kdo
     * je vydal a do kdy platí certifikát, kterým je podepsáno.
     *
     * <p>Poslední údaj je tu proto, že z něj plyne, dokdy má ověření šanci: po expiraci
     * certifikátu razítkovací autority už razítko samo o sobě nestačí a je potřeba další nad ním.
     * Bez těchto tří řádků je razítko v kartě prvku jen několik kilobajtů DER a jediná otázka,
     * kterou u něj čtenář má, stojí o dvě okna dál.
     *
     * <p><b>„Čas dle razítka", ne „čas připojení".</b> Token nese jediný čas — {@code genTime},
     * tedy okamžik, kdy jej autorita vydala. Kdy se dostal k podpisu, v něm nestojí a nikde jinde
     * v souboru také ne; pojmenovat ho jako čas připojení by znamenalo tvrdit něco, co dokument
     * nedokládá.
     *
     * <p>Vydavatele hledá nejprve v nepovinném poli {@code tsa} a teprve pak v certifikátu, jímž
     * je token podepsán — týž postup jako u nahlédnutí do podpisu v kancelářském dokumentu
     * ({@code OfficeSignaturePeek}). V praxi bývá pole {@code tsa} prázdné, takže rozhoduje
     * certifikát.
     *
     * <p>Nic se tu neověřuje: že podpis autority nad tokenem sedí, říkají záložky s výsledkem.
     * Tady se čte, co v tokenu stojí.
     */
    static void describeTimestamp(Node node, byte[] der) {
        if (der == null || der.length == 0) return;
        try {
            TimeStampToken token = new TimeStampToken(new CMSSignedData(der));
            TimeStampTokenInfo info = token.getTimeStampInfo();
            node.put("Čas dle razítka", formatDate(info.getGenTime()));

            X509CertificateHolder signer = timestampSignerCertificate(token);
            String issuer = info.getTsa() != null ? generalName(info.getTsa())
                    : signer != null ? String.valueOf(signer.getSubject()) : null;
            if (issuer != null && !issuer.isBlank()) {
                String cn = commonName(issuer);
                node.put("Vydavatel razítka", cn.isEmpty() ? issuer : cn);
            }
            if (signer != null) {
                node.put("Certifikát razítka platí do", formatDate(signer.getNotAfter()));
            }

            node.put("Otisková funkce", oidLabel(info.getMessageImprintAlgOID().getId()));
            node.put("Otisk v razítku", hexBytes(info.getMessageImprintDigest()));
            if (info.getPolicy() != null) node.put("Politika razítka", info.getPolicy().getId());
            if (info.getSerialNumber() != null) {
                node.put("Sériové číslo razítka", hexNumber(info.getSerialNumber()));
            }
        } catch (Exception e) {
            node.put("Poznámka", "obsah razítka se nepodařilo přečíst (" + describe(e)
                    + "); bajty lze uložit a ověřit zvlášť");
        }
    }

    /** Certifikát, kterým je token podepsán — token si ho skoro vždy veze s sebou. */
    private static X509CertificateHolder timestampSignerCertificate(TimeStampToken token) {
        try {
            var matches = token.getCertificates().getMatches(token.getSID());
            if (matches.isEmpty()) return null;
            return (X509CertificateHolder) matches.iterator().next();
        } catch (Exception e) {
            return null;
        }
    }

    /** Jméno z pole {@code tsa}: adresářové se vypisuje jako DN, ostatní tak, jak jsou. */
    private static String generalName(org.bouncycastle.asn1.x509.GeneralName name) {
        if (name.getTagNo() == org.bouncycastle.asn1.x509.GeneralName.directoryName) {
            return String.valueOf(org.bouncycastle.asn1.x500.X500Name.getInstance(name.getName()));
        }
        return String.valueOf(name.getName());
    }

    private static String hexBytes(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Wraps a bare {@code BasicOCSPResponse} in the {@code OCSPResponse} envelope, status
     * successful — the form in which an OCSP response is a file rather than a fragment of one.
     *
     * <p>This is what DSS does with the same bytes ({@code DSSRevocationUtils.fromBasicToResp}),
     * so a response taken out of a container here and the same response saved anywhere else in
     * the application are the same file.
     *
     * @return the wrapped response, or {@code null} when these bytes turn out not to be a
     *         BasicOCSPResponse — then the caller keeps them as found and says so in the name.
     */
    static byte[] wrapAsOcspResponse(byte[] basic) {
        try {
            org.bouncycastle.asn1.ocsp.BasicOCSPResponse parsed =
                    org.bouncycastle.asn1.ocsp.BasicOCSPResponse.getInstance(
                            ASN1Primitive.fromByteArray(basic));
            if (parsed == null) return null;
            org.bouncycastle.asn1.ocsp.OCSPResponse resp =
                    new org.bouncycastle.asn1.ocsp.OCSPResponse(
                            new org.bouncycastle.asn1.ocsp.OCSPResponseStatus(
                                    org.bouncycastle.asn1.ocsp.OCSPResponseStatus.SUCCESSFUL),
                            new org.bouncycastle.asn1.ocsp.ResponseBytes(
                                    OCSPObjectIdentifiers.id_pkix_ocsp_basic,
                                    new org.bouncycastle.asn1.DEROctetString(
                                            parsed.getEncoded("DER"))));
            return resp.getEncoded("DER");
        } catch (Exception e) {
            return null;
        }
    }

    private static List<byte[]> otherRevocationInfo(CMSSignedData cms) {
        List<byte[]> out = new ArrayList<>();
        try {
            for (Object o : cms.getOtherRevocationInfo(OCSPObjectIdentifiers.id_pkix_ocsp_basic).getMatches(null)) {
                if (o instanceof ASN1Encodable enc) {
                    out.add(enc.toASN1Primitive().getEncoded("DER"));
                }
            }
        } catch (Exception ignored) {
            // Older CMS objects may not carry this field at all.
        }
        return out;
    }

    private static Date signingTime(AttributeTable signed) {
        try {
            Attribute attr = signed.get(new ASN1ObjectIdentifier("1.2.840.113549.1.9.5"));
            if (attr == null || attr.getAttrValues().size() == 0) return null;
            return org.bouncycastle.asn1.cms.Time.getInstance(attr.getAttrValues().getObjectAt(0)).getDate();
        } catch (Exception e) {
            return null;
        }
    }

    /** Names the extracted payload after the container, minus the signature extension. */
    private static String payloadName(String fileName, byte[] content) {
        String base = fileName == null || fileName.isBlank() ? "obsah" : fileName;
        String lower = base.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".p7m", ".p7s", ".cms", ".pkcs7", ".der"}) {
            if (lower.endsWith(ext)) {
                base = base.substring(0, base.length() - ext.length());
                break;
            }
        }
        if (base.contains(".")) return base;
        return base + guessExtension(content);
    }

    static String guessExtension(byte[] content) {
        return switch (detectKind(content)) {
            case PDF  -> ".pdf";
            case ZIP  -> ".zip";
            case XML  -> ".xml";
            case JSON -> ".json";
            default   -> looksTextual(content) ? ".txt" : ".bin";
        };
    }

    // ---------------------------------------------------------------- XML

    private static Result inspectXml(byte[] bytes, String fileName) throws Exception {
        Document doc = parseXml(bytes);
        Element docEl = doc.getDocumentElement();
        Node root = new Node(fileName, "", true, null);
        List<String> warnings = new ArrayList<>();
        int[] budget = {MAX_NODES};

        Node top = buildXmlNode(docEl, "", budget);
        top.data = bytes;
        top.size = bytes.length;
        root.add(top);
        if (budget[0] <= 0) {
            warnings.add("Dokument je rozsáhlý; strom je zobrazen jen do " + MAX_NODES + " prvků.");
        }

        int signatures = countElements(docEl, "Signature");
        int objects = countElements(docEl, "Object");
        String typeLabel = signatures > 0 ? "podepsaný dokument XML (XAdES / XML-DSig)" : "dokument XML";

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Formát", typeLabel);
        summary.put("Kořenový prvek", qualifiedName(docEl));
        String ns = docEl.getNamespaceURI();
        if (ns != null) summary.put("Jmenný prostor", ns);
        summary.put("Velikost souboru", formatSize(bytes.length));
        summary.put("Počet podpisů", String.valueOf(signatures));
        if (objects > 0) summary.put("Vložené objekty (ds:Object)", String.valueOf(objects));

        List<Reference> refs = new ArrayList<>();
        collectReferences(docEl, refs);
        if (!refs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Reference r : refs) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(r.uri());
            }
            summary.put("Odkazy na oddělená data", sb.toString());
            warnings.add("Podpis odkazuje na data mimo tento soubor (" + sb
                    + "); jejich obsah zde nelze zobrazit.");
        }

        return new Result(Kind.XML, typeLabel, root, summary, warnings, null);
    }

    /**
     * Mirrors the XML element tree. Every element keeps its own serialised form as data, so any
     * subtree can be saved on its own; base64 text content is additionally offered decoded,
     * which is how an enveloping XAdES carries the document it signs.
     */
    private static Node buildXmlNode(Element el, String parentPath, int[] budget) {
        String name = qualifiedName(el);
        String path = parentPath.isEmpty() ? name : parentPath + "/" + name;

        List<Element> childElements = new ArrayList<>();
        NodeList kids = el.getChildNodes();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node k = kids.item(i);
            if (k instanceof Element e) childElements.add(e);
            else if (k.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                     || k.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                text.append(k.getNodeValue());
            }
        }
        String trimmed = text.toString().trim();
        byte[] decoded = childElements.isEmpty() ? tryDecodeBase64(trimmed) : null;
        boolean hasChildren = !childElements.isEmpty() || (decoded != null && decoded.length > 64);

        Node node = new Node(name, path, hasChildren, xmlRole(el));
        if (node.role != null) node.put("Role", node.role);

        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node a = attrs.item(i);
            if (a.getNodeName().startsWith("xmlns")) continue;
            node.put("@" + a.getNodeName(), a.getNodeValue());
        }

        try {
            byte[] serialized = serializeElement(el);
            node.data = serialized;
            node.size = serialized.length;
        } catch (Exception ignored) {
            // A subtree that cannot be re-serialised is still browsable.
        }

        if (childElements.isEmpty() && !trimmed.isEmpty()) {
            if (decoded != null && decoded.length > 64) {
                String childName = "obsah" + guessExtension(decoded);
                Node payload = node.add(new Node(childName, path + "/" + childName, false,
                        "dekódovaný obsah (base64)"));
                payload.data = decoded;
                payload.size = decoded.length;
                payload.put("Role", "obsah prvku " + name + " dekódovaný z base64");
            } else if (trimmed.length() <= 4096) {
                node.put("Hodnota", trimmed);
            } else {
                node.put("Hodnota", trimmed.substring(0, 4096) + "… (zkráceno)");
            }
        }

        for (Element child : childElements) {
            if (budget[0]-- <= 0) break;
            node.add(buildXmlNode(child, path, budget));
        }
        return node;
    }

    private static String xmlRole(Element el) {
        return switch (localName(el)) {
            case "Signature"           -> "podpis XML-DSig / XAdES";
            case "SignedInfo"          -> "podepsané údaje";
            case "Reference"           -> "odkaz na podepsaná data";
            case "Object"              -> "vložený objekt";
            case "KeyInfo"             -> "údaje o podpisovém klíči";
            case "X509Certificate"     -> "certifikát podepisujícího";
            case "SignatureValue"      -> "hodnota podpisu";
            case "QualifyingProperties",
                 "UnsignedProperties",
                 "SignedProperties"    -> "vlastnosti XAdES";
            case "EncapsulatedTimeStamp",
                 "SignatureTimeStamp",
                 "ArchiveTimeStamp"    -> "časové razítko";
            default -> null;
        };
    }

    private static int countElements(Element el, String local) {
        int count = local.equals(localName(el)) ? 1 : 0;
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) count += countElements(child, local);
        }
        return count;
    }

    // ---------------------------------------------------------------- JSON / JAdES

    private static Result inspectJson(byte[] bytes, String fileName) {
        // A JAdES signature is a JSON document, but showing it as one means showing the document
        // it carries as a line of base64 nobody can get at. {@link JadesInspector} takes it apart;
        // it hands back null for JSON that is not a signature, which falls through to here.
        Result jades = JadesInspector.inspect(bytes, fileName);
        if (jades != null) return jades;

        Node root = new Node(fileName, "", true, null);
        Node doc = root.add(new Node(fileName, fileName, false, "dokument JSON"));
        doc.data = bytes;
        doc.size = bytes.length;

        String text = new String(bytes, StandardCharsets.UTF_8);
        // Both members at once, because either alone is a word an ordinary JSON document may well
        // use — and calling a configuration file a signature would be worse than saying nothing.
        boolean jadesLike = text.contains("\"protected\"") && text.contains("\"signature\"");
        String typeLabel = jadesLike ? "podpis JAdES (JSON)" : "dokument JSON";

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Formát", typeLabel);
        summary.put("Velikost souboru", formatSize(bytes.length));
        List<String> warnings = new ArrayList<>();
        if (jadesLike) {
            warnings.add("Soubor se podobá podpisu JAdES, ale nepodařilo se jej rozebrat na "
                    + "jednotlivé části; zobrazuje se proto jako text.");
        }
        return new Result(Kind.JSON, typeLabel, root, summary, warnings, null);
    }

    // ---------------------------------------------------------------- shared helpers

    /** SHA-256 over the node's bytes, as lowercase hex — the value manifests and reports quote. */
    public static String sha256Hex(byte[] data) {
        return digestHex(data, "SHA-256");
    }

    public static String digestHex(byte[] data, String algorithm) {
        try {
            byte[] d = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean digestMatches(byte[] data, String algorithm, byte[] expected) {
        try {
            return MessageDigest.isEqual(MessageDigest.getInstance(algorithm).digest(data), expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static String digestAlgFromUri(String uri) {
        if (uri == null) return null;
        String u = uri.toLowerCase(Locale.ROOT);
        if (u.endsWith("#sha1"))   return "SHA-1";
        if (u.endsWith("#sha224")) return "SHA-224";
        if (u.endsWith("#sha256")) return "SHA-256";
        if (u.endsWith("#sha384")) return "SHA-384";
        if (u.endsWith("#sha512")) return "SHA-512";
        if (u.contains("sha3-256")) return "SHA3-256";
        if (u.contains("sha3-384")) return "SHA3-384";
        if (u.contains("sha3-512")) return "SHA3-512";
        return null;
    }

    /**
     * Percent-decodes a manifest URI — and does only that.
     *
     * <p>{@link URLDecoder} used to do this job, but it implements
     * {@code application/x-www-form-urlencoded}, where {@code +} stands for a space. A URI path does
     * not work that way. A container holding "dokument+dodatek č. 2.pdf" therefore had its reference
     * resolved to "dokument dodatek č. 2.pdf", which matched no entry, and the file came out as
     * "podle manifestů/podpisů XML nezjištěno" even though the manifest covers it and DSS validates
     * it — silently, because an unmatched reference produces no warning at all.
     *
     * <p>Text between escapes is copied a run at a time rather than character by character, so a
     * name outside the basic multilingual plane keeps its surrogate pairs. Anything that is not a
     * valid escape passes through as itself: DSS writes diacritics literally and only escapes
     * spaces, so a manifest routinely mixes both.
     */
    private static String decodeUri(String uri) {
        if (uri == null || uri.indexOf('%') < 0) return uri;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(uri.length());
        int i = 0;
        while (i < uri.length()) {
            if (uri.charAt(i) == '%' && i + 2 < uri.length()) {
                int hi = Character.digit(uri.charAt(i + 1), 16);
                int lo = Character.digit(uri.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 3;
                    continue;
                }
            }
            int next = uri.indexOf('%', i + 1);
            int end = next < 0 ? uri.length() : next;
            out.writeBytes(uri.substring(i, end).getBytes(StandardCharsets.UTF_8));
            i = end;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** True when the bytes read as text rather than as an opaque blob. */
    public static boolean looksTextual(byte[] data) {
        if (data == null || data.length == 0) return false;
        int limit = Math.min(data.length, 4096);
        int suspicious = 0;
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) return false;
            if (b < 0x09 || (b > 0x0D && b < 0x20)) suspicious++;
        }
        return suspicious * 20 < limit;
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "neznámá";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f kB", kb).replace('.', ',');
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.2f MB", mb).replace('.', ',');
    }

    public static String formatDate(Date date) {
        if (date == null) return null;
        return TimeFormat.full(date);
    }

    static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = dbf.newDocumentBuilder();
        // Xerces prints "[Fatal Error] …" to stderr before throwing, which every caller here
        // already handles by falling back. One legitimate case reaches this: the envelope element
        // of a data message, cut out of its document for hashing, uses namespace prefixes the
        // response root declared — perfectly well-formed in place, unparseable on its own.
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            public void warning(org.xml.sax.SAXParseException e) { }
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        return builder.parse(new ByteArrayInputStream(xml));
    }

    static byte[] serializeElement(Element el) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(el), new StreamResult(sw));
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- certificates and CRLs in words

    /**
     * Renders a certificate as text, for the preview pane.
     *
     * <p>Only what can be read out of the bytes themselves. Whether the certificate is qualified,
     * whose trust list it is on, whether it was revoked — none of that is in here, it comes from
     * validation, and this window does not validate. Saying so by omission is the point: the
     * browser shows what the container holds, and „Ověřit v AnEP“ is one button away.
     *
     * @return the description, or {@code null} when the bytes are not a certificate.
     */
    public static String describeCertificate(byte[] data) {
        return describeCertificate(parse(data));
    }

    private static String describeCertificate(ASN1Primitive parsed) {
        if (parsed == null) return null;
        X509CertificateHolder cert;
        try {
            cert = new X509CertificateHolder(
                    org.bouncycastle.asn1.x509.Certificate.getInstance(parsed));
            // Reading one field that a CRL does not have keeps a lenient parse from passing off
            // something else as a certificate.
            if (cert.getSubjectPublicKeyInfo() == null) return null;
        } catch (Exception e) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        line(sb, "", "Subjekt", String.valueOf(cert.getSubject()));
        // Hned za předmětem, protože je to údaj o <b>držiteli</b>, ne o certifikátu: identifikátor,
        // pod kterým ho zná MPSV. V předmětu samotném nestojí — leží v alternativním jméně, kam se
        // čtenář jinak nedostane — a nese jej jen hrstka certifikátů, takže se řádek u ostatních
        // vůbec nevypíše. Viz {@link IkMpsv}.
        line(sb, "", "IK MPSV", IkMpsv.of(cert));
        line(sb, "", "Vydavatel", cert.getSubject().equals(cert.getIssuer())
                ? cert.getIssuer() + "  (podepsán sám sebou)" : String.valueOf(cert.getIssuer()));
        java.math.BigInteger serial = cert.getSerialNumber();
        line(sb, "", "Sériové číslo", serial + " (" + hexNumber(serial) + ")");
        line(sb, "", "Platnost", formatDate(cert.getNotBefore()) + " – " + formatDate(cert.getNotAfter()));
        if (cert.getSignatureAlgorithm() != null) {
            line(sb, "", "Algoritmus podpisu", oidLabel(cert.getSignatureAlgorithm().getAlgorithm().getId()));
        }
        line(sb, "", "Veřejný klíč", publicKeyLabel(cert.getSubjectPublicKeyInfo()));
        line(sb, "", "Klíčové užití", keyUsageLabel(cert));
        line(sb, "", "Rozšířené užití", extendedKeyUsageLabel(cert));
        line(sb, "", "Základní omezení", basicConstraintsLabel(cert));
        line(sb, "", "Přístup k OCSP", accessLocations(cert,
                org.bouncycastle.asn1.x509.Extension.authorityInfoAccess,
                org.bouncycastle.asn1.x509.X509ObjectIdentifiers.id_ad_ocsp));
        line(sb, "", "Vydavatel ke stažení", accessLocations(cert,
                org.bouncycastle.asn1.x509.Extension.authorityInfoAccess,
                org.bouncycastle.asn1.x509.X509ObjectIdentifiers.id_ad_caIssuers));
        line(sb, "", "Distribuční bod CRL", crlDistributionPoints(cert));
        String qc = qcStatements(cert);
        if (qc != null) sb.append(qc);
        String documents = identityDocuments(cert);
        if (documents != null) sb.append(documents);
        return sb.toString();
    }

    /**
     * Doklad totožnosti zapsaný v předmětu certifikátu, jako vlastní blok na konci výpisu;
     * {@code null}, když certifikát žádný nenese.
     *
     * <p>Ve výpisu je to údaj už jednou — uvnitř DN předmětu, jako {@code SERIALNUMBER=IDCCZ-…}
     * mezi ostatními atributy. Tam ho ale čtenář nenajde, pokud neví, co hledá: schéma
     * {@code IDCCZ-} není samovysvětlující a sériových čísel je v certifikátu víc. Vlastní blok
     * na konci to pojmenuje česky. Pokyn uživatele 15. 9. 2026.
     *
     * <p>Popiskem je <b>druh dokladu</b> a hodnotou <b>jeho číslo</b>, takže přibývající druhy
     * (dnes dva, viz {@link IdentityDocument}) nevyžadují změnu názvu bloku.
     */
    private static String identityDocuments(X509CertificateHolder cert) {
        org.bouncycastle.asn1.x500.RDN[] rdns;
        try {
            rdns = cert.getSubject().getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.SERIALNUMBER);
        } catch (Exception e) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (org.bouncycastle.asn1.x500.RDN rdn : rdns) {
            for (org.bouncycastle.asn1.x500.AttributeTypeAndValue atv : rdn.getTypesAndValues()) {
                IdentityDocument.Document doc = IdentityDocument.of(
                        org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(atv.getValue()));
                if (doc == null) continue;
                if (sb.length() == 0) sb.append("\nZapsané osobní doklady\n");
                line(sb, "  ", doc.kind(), doc.number());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * The QCStatements extension, statement by statement.
     *
     * <p>This is where a certificate says of itself that it is qualified, what for, and whether
     * its private key sits in a qualified device. Worth reading out in a forensic window, and
     * worth reading out <em>as the certificate has it</em>: the same three facts appear in the
     * Rozšířený přehled, but there they come from DSS after validation and against a trusted
     * list, and a claim a certificate makes about itself is not the same statement as a verdict
     * that the claim holds. What is printed here is the claim.
     *
     * <p>Statements this application does not decode are still listed — under their own short
     * name where there is one, under their OID otherwise, with the raw structure beside them.
     * A statement silently dropped would read as a statement that is not there.
     *
     * @return the block, starting with a blank line, or {@code null} when there is no such extension
     */
    private static String qcStatements(X509CertificateHolder cert) {
        org.bouncycastle.asn1.x509.Extension ext;
        org.bouncycastle.asn1.ASN1Sequence statements;
        try {
            ext = cert.getExtension(org.bouncycastle.asn1.x509.Extension.qCStatements);
            if (ext == null) return null;
            statements = org.bouncycastle.asn1.ASN1Sequence.getInstance(ext.getParsedValue());
        } catch (Exception e) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\nKvalifikovaná prohlášení (QCStatements)\n");
        int printed = 0;
        for (org.bouncycastle.asn1.ASN1Encodable encoded : statements) {
            org.bouncycastle.asn1.x509.qualified.QCStatement st;
            try {
                st = org.bouncycastle.asn1.x509.qualified.QCStatement.getInstance(encoded);
            } catch (Exception e) {
                continue;
            }
            String oid = st.getStatementId().getId();
            org.bouncycastle.asn1.ASN1Encodable info = st.getStatementInfo();
            String label = qcStatementName(oid);
            String value = qcStatementValue(oid, info);
            line(sb, "  ", label == null ? oid : label,
                    value == null ? "bez dalších údajů" : value);
            if (PSD2_STATEMENT.equals(oid)) line(sb, "    ", "dohledový orgán", psd2Authority(info));
            printed++;
        }
        return printed == 0 ? null : sb.toString();
    }

    private static String qcStatementName(String oid) {
        return switch (oid) {
            case "0.4.0.1862.1.1" -> "QcCompliance";
            case "0.4.0.1862.1.2" -> "QcLimitValue";
            case "0.4.0.1862.1.3" -> "QcRetentionPeriod";
            case "0.4.0.1862.1.4" -> "QcSSCD";
            case "0.4.0.1862.1.5" -> "QcPDS";
            case "0.4.0.1862.1.6" -> "QcType";
            case "0.4.0.1862.1.7" -> "QcCClegislation";
            case "1.3.6.1.5.5.7.11.1" -> "QcSyntax v1";
            case "1.3.6.1.5.5.7.11.2" -> "QcSyntax v2";
            case PSD2_STATEMENT -> "PSD2";
            default -> null;
        };
    }

    /** What one statement says, in words where the structure is known, raw where it is not. */
    private static String qcStatementValue(String oid, org.bouncycastle.asn1.ASN1Encodable info) {
        try {
            switch (oid) {
                case "0.4.0.1862.1.1":
                    return "kvalifikovaný certifikát podle nařízení eIDAS";
                case "0.4.0.1862.1.4":
                    return "soukromý klíč v kvalifikovaném prostředku (QSCD)";
                case "0.4.0.1862.1.6": {
                    if (info == null) return null;
                    StringBuilder sb = new StringBuilder();
                    for (org.bouncycastle.asn1.ASN1Encodable e :
                            org.bouncycastle.asn1.ASN1Sequence.getInstance(info)) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(qcTypeLabel(ASN1ObjectIdentifier.getInstance(e).getId()));
                    }
                    return sb.length() == 0 ? null : sb.toString();
                }
                case "0.4.0.1862.1.3": {
                    if (info == null) return null;
                    java.math.BigInteger years = org.bouncycastle.asn1.ASN1Integer
                            .getInstance(info).getValue();
                    // Czech number agreement, done here rather than through CzechPlural: that
                    // class lives in the UI package and core must not reach into it.
                    int n = years.intValue();
                    return "údaje se uchovávají " + years
                            + (n == 1 ? " rok" : n >= 2 && n <= 4 ? " roky" : " let");
                }
                case "0.4.0.1862.1.2": {
                    if (info == null) return null;
                    org.bouncycastle.asn1.x509.qualified.MonetaryValue value =
                            org.bouncycastle.asn1.x509.qualified.MonetaryValue.getInstance(info);
                    java.math.BigDecimal amount = new java.math.BigDecimal(value.getAmount())
                            .scaleByPowerOfTen(value.getExponent().intValue());
                    String currency = value.getCurrency().getAlphabetic() != null
                            ? value.getCurrency().getAlphabetic()
                            : String.valueOf(value.getCurrency().getNumeric());
                    return "limit transakce " + amount.toPlainString() + " " + currency;
                }
                case "0.4.0.1862.1.5": {
                    if (info == null) return null;
                    StringBuilder sb = new StringBuilder();
                    for (org.bouncycastle.asn1.ASN1Encodable e :
                            org.bouncycastle.asn1.ASN1Sequence.getInstance(info)) {
                        org.bouncycastle.asn1.ASN1Sequence pds =
                                org.bouncycastle.asn1.ASN1Sequence.getInstance(e);
                        if (pds.size() < 1) continue;
                        String url = org.bouncycastle.asn1.ASN1String.class
                                .cast(pds.getObjectAt(0)).getString();
                        String lang = pds.size() > 1 ? org.bouncycastle.asn1.ASN1String.class
                                .cast(pds.getObjectAt(1)).getString() : null;
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(url);
                        if (lang != null) sb.append(" (").append(lang.toLowerCase(Locale.ROOT)).append(')');
                    }
                    return sb.length() == 0 ? null : sb.toString();
                }
                case "0.4.0.1862.1.7": {
                    if (info == null) return null;
                    StringBuilder sb = new StringBuilder();
                    for (org.bouncycastle.asn1.ASN1Encodable e :
                            org.bouncycastle.asn1.ASN1Sequence.getInstance(info)) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(org.bouncycastle.asn1.ASN1String.class.cast(e).getString());
                    }
                    return sb.length() == 0 ? null : "vydán podle práva zemí: " + sb;
                }
                case PSD2_STATEMENT:
                    return psd2Roles(info);
                case "1.3.6.1.5.5.7.11.1":
                case "1.3.6.1.5.5.7.11.2": {
                    if (info == null) return null;
                    org.bouncycastle.asn1.x509.qualified.SemanticsInformation semantics =
                            org.bouncycastle.asn1.x509.qualified.SemanticsInformation.getInstance(info);
                    ASN1ObjectIdentifier id = semantics.getSemanticsIdentifier();
                    return id == null ? null : semanticsLabel(id.getId());
                }
                default:
                    return info == null ? null : shorten(info.toASN1Primitive().toString());
            }
        } catch (Exception e) {
            // A statement whose value will not decode is still a statement that is present.
            try {
                return info == null ? null : shorten(info.toASN1Primitive().toString());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** ETSI TS 119 495, the statement a payment service provider's certificate carries. */
    private static final String PSD2_STATEMENT = "0.4.0.19495.2";

    /**
     * The roles in a PSD2 statement — what the holder is authorised to do under the directive.
     *
     * <p>Read from the role's OID rather than from the name string beside it: the name is text
     * the issuing CA wrote and nothing constrains it, the OID is the statement. Where the OID is
     * not one of the four, the CA's own name is the best that can be said, and it is said as such.
     */
    private static String psd2Roles(org.bouncycastle.asn1.ASN1Encodable info) {
        if (info == null) return null;
        try {
            org.bouncycastle.asn1.ASN1Sequence psd2 =
                    org.bouncycastle.asn1.ASN1Sequence.getInstance(info);
            if (psd2.size() < 1) return null;
            StringBuilder sb = new StringBuilder();
            for (org.bouncycastle.asn1.ASN1Encodable e :
                    org.bouncycastle.asn1.ASN1Sequence.getInstance(psd2.getObjectAt(0))) {
                org.bouncycastle.asn1.ASN1Sequence role =
                        org.bouncycastle.asn1.ASN1Sequence.getInstance(e);
                if (role.size() < 1) continue;
                String roleOid = ASN1ObjectIdentifier.getInstance(role.getObjectAt(0)).getId();
                String known = psd2RoleLabel(roleOid);
                if (known == null && role.size() > 1) {
                    known = asText(role.getObjectAt(1));
                }
                if (sb.length() > 0) sb.append(", ");
                sb.append(known == null ? roleOid : known);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** The national competent authority named in a PSD2 statement, with its identifier. */
    private static String psd2Authority(org.bouncycastle.asn1.ASN1Encodable info) {
        if (info == null) return null;
        try {
            org.bouncycastle.asn1.ASN1Sequence psd2 =
                    org.bouncycastle.asn1.ASN1Sequence.getInstance(info);
            String name = psd2.size() > 1 ? asText(psd2.getObjectAt(1)) : null;
            String id = psd2.size() > 2 ? asText(psd2.getObjectAt(2)) : null;
            if (name == null && id == null) return null;
            if (name == null) return id;
            return id == null ? name : name + " (" + id + ")";
        } catch (Exception e) {
            return null;
        }
    }

    private static String psd2RoleLabel(String oid) {
        return switch (oid) {
            case "0.4.0.19495.1.1" -> "PSP_AS — vedení platebního účtu";
            case "0.4.0.19495.1.2" -> "PSP_PI — nepřímé dání platebního příkazu";
            case "0.4.0.19495.1.3" -> "PSP_AI — informování o platebním účtu";
            case "0.4.0.19495.1.4" -> "PSP_IC — vydávání karetních platebních prostředků";
            default -> null;
        };
    }

    private static String asText(org.bouncycastle.asn1.ASN1Encodable value) {
        return value instanceof org.bouncycastle.asn1.ASN1String text ? text.getString() : null;
    }

    private static String qcTypeLabel(String oid) {
        return switch (oid) {
            case "0.4.0.1862.1.6.1" -> "pro elektronický podpis";
            case "0.4.0.1862.1.6.2" -> "pro elektronickou pečeť";
            case "0.4.0.1862.1.6.3" -> "pro autentizaci webových stránek";
            default -> oid;
        };
    }

    private static String semanticsLabel(String oid) {
        return switch (oid) {
            case "0.4.0.194121.1.1" -> "identifikátor fyzické osoby";
            case "0.4.0.194121.1.2" -> "identifikátor právnické osoby";
            default -> oid;
        };
    }

    /** Keeps an undecoded structure to one readable line. */
    private static String shorten(String raw) {
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 100 ? flat : flat.substring(0, 100) + "…";
    }

    /**
     * Renders a CRL as text, for the preview pane.
     *
     * <p>The entries are listed only while there are few enough to read. A list of thousands says
     * nothing to a reader scrolling past it, and the one question they would ask it — is this
     * certificate on it — is not a question a preview can answer honestly by showing the first
     * twenty of them.
     *
     * @return the description, or {@code null} when the bytes are not a CRL.
     */
    public static String describeCrl(byte[] data) {
        return describeCrl(parse(data));
    }

    private static String describeCrl(ASN1Primitive parsed) {
        if (parsed == null) return null;
        // Deliberately the ASN.1 structure rather than X509CRLHolder: the holder parses the
        // issuing distribution point in its constructor to decide whether the list is indirect,
        // and refuses the whole file when that one extension is malformed. Measured on a 2005
        // CRL that openssl reads without complaint. In a tool for old material, a list that
        // cannot be read because of an extension nobody asked about is the wrong failure.
        org.bouncycastle.asn1.x509.CertificateList crl;
        try {
            crl = org.bouncycastle.asn1.x509.CertificateList.getInstance(parsed);
            if (crl.getIssuer() == null || crl.getThisUpdate() == null) return null;
        } catch (Exception e) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        line(sb, "", "Vydavatel", String.valueOf(crl.getIssuer()));
        line(sb, "", "Vydáno (thisUpdate)", formatDate(crl.getThisUpdate().getDate()));
        line(sb, "", "Příští vydání (nextUpdate)", crl.getNextUpdate() == null
                ? null : formatDate(crl.getNextUpdate().getDate()));
        if (crl.getSignatureAlgorithm() != null) {
            line(sb, "", "Algoritmus podpisu", oidLabel(crl.getSignatureAlgorithm().getAlgorithm().getId()));
        }
        org.bouncycastle.asn1.x509.Extensions extensions = crl.getTBSCertList().getExtensions();
        line(sb, "", "Číslo seznamu",
                extensionInteger(extensions, org.bouncycastle.asn1.x509.Extension.cRLNumber));
        line(sb, "", "Delta k číslu",
                extensionInteger(extensions, org.bouncycastle.asn1.x509.Extension.deltaCRLIndicator));

        org.bouncycastle.asn1.x509.TBSCertList.CRLEntry[] revoked;
        try {
            revoked = crl.getRevokedCertificates();
        } catch (Exception e) {
            return sb.toString();
        }
        int count = revoked == null ? 0 : revoked.length;
        line(sb, "", "Revokovaných certifikátů", String.valueOf(count));
        if (count == 0) return sb.toString();
        if (count > CRL_ENTRIES_LISTED) {
            sb.append("\nJednotlivé záznamy se v náhledu nevypisují, je jich příliš mnoho.")
              .append("\nCelý seznam uložíte tlačítkem „Uložit prvek…“.\n");
            return sb.toString();
        }

        sb.append("\nRevokované certifikáty (").append(count).append(")\n");
        for (org.bouncycastle.asn1.x509.TBSCertList.CRLEntry entry : revoked) {
            StringBuilder row = new StringBuilder("  ");
            String hex = hexNumber(entry.getUserCertificate().getValue());
            row.append(hex);
            for (int i = hex.length(); i < 24; i++) row.append(' ');
            row.append("  ").append(formatDate(entry.getRevocationDate().getDate()));
            String reason = extensionReason(entry.getExtensions());
            if (reason != null) row.append("  ").append(reason);
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    /** How many revoked entries the preview will list before it stops and states the count only. */
    private static final int CRL_ENTRIES_LISTED = 30;

    /**
     * Číslo šestnáctkově, po celých bajtech a s předponou {@code 0x} — např. {@code 0x016CA2FE}.
     *
     * <p>Předpona je tu proto, aby bylo z řádku poznáno, že jde o šestnáctkový zápis: sériová
     * čísla se v nástrojích uvádějí jednou dekadicky a jednou šestnáctkově a bez předpony se ta
     * dvě podoby dají zaměnit — {@code 16CA2FE} vypadá jako číslo i v desítkové soustavě.
     * Doplnění na celé bajty je tvar, jakým se sériová čísla píšou na certifikátech samotných.
     *
     * <p>Jediné místo pro celou aplikaci, včetně oken v balíčku {@code ui}: tři opisy téhož zápisu
     * by se při první změně rozešly a týž certifikát by se na dvou kartách četl jinak.
     */
    public static String hexNumber(java.math.BigInteger value) {
        if (value == null) return null;
        String hex = value.toString(16).toUpperCase(Locale.ROOT);
        if (!hex.startsWith("-") && hex.length() % 2 == 1) hex = "0" + hex;
        return hex.startsWith("-") ? "-0x" + hex.substring(1) : "0x" + hex;
    }

    /** Totéž z dekadického zápisu, jaký drží DSS; {@code null} při nesmyslu na vstupu. */
    public static String hexNumber(String decimal) {
        if (decimal == null || decimal.isBlank()) return null;
        try {
            return hexNumber(new java.math.BigInteger(decimal.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String publicKeyLabel(org.bouncycastle.asn1.x509.SubjectPublicKeyInfo spki) {
        try {
            String oid = spki.getAlgorithm().getAlgorithm().getId();
            switch (oid) {
                case "1.2.840.113549.1.1.1", "1.2.840.113549.1.1.10" -> {
                    org.bouncycastle.asn1.pkcs.RSAPublicKey key =
                            org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(spki.parsePublicKey());
                    return "RSA, " + key.getModulus().bitLength() + " bitů";
                }
                case "1.2.840.10045.2.1" -> {
                    Object params = spki.getAlgorithm().getParameters();
                    String curve = params instanceof ASN1ObjectIdentifier id ? curveLabel(id.getId()) : null;
                    return curve == null ? "EC" : "EC, křivka " + curve;
                }
                case "1.3.101.112" -> {
                    return "Ed25519";
                }
                case "1.3.101.113" -> {
                    return "Ed448";
                }
                default -> {
                    return oidLabel(oid);
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String curveLabel(String oid) {
        return switch (oid) {
            case "1.2.840.10045.3.1.7" -> "P-256 (prime256v1)";
            case "1.3.132.0.34"        -> "P-384 (secp384r1)";
            case "1.3.132.0.35"        -> "P-521 (secp521r1)";
            case "1.3.36.3.3.2.8.1.1.7"  -> "brainpoolP256r1";
            case "1.3.36.3.3.2.8.1.1.11" -> "brainpoolP384r1";
            case "1.3.36.3.3.2.8.1.1.13" -> "brainpoolP512r1";
            default -> oid;
        };
    }

    private static String keyUsageLabel(X509CertificateHolder cert) {
        try {
            org.bouncycastle.asn1.x509.KeyUsage ku = org.bouncycastle.asn1.x509.KeyUsage.fromExtensions(
                    cert.getExtensions());
            if (ku == null) return null;
            // „nonRepudiation" je jeden zápor, ne dva: bit říká, že klíč slouží k podpisu,
            // který podepisující nemůže vzít zpět. „Popření neodmítnutelnosti" ten zápor
            // zdvojilo a význam tím obrátilo. Oprava na pokyn uživatele 15. 9. 2026.
            String[] names = {"digitální podpis", "neodvolatelnost", "šifrování klíče",
                    "šifrování dat", "dohoda o klíči", "podepisování certifikátů",
                    "podepisování CRL", "jen šifrování", "jen dešifrování"};
            int[] bits = {org.bouncycastle.asn1.x509.KeyUsage.digitalSignature,
                    org.bouncycastle.asn1.x509.KeyUsage.nonRepudiation,
                    org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment,
                    org.bouncycastle.asn1.x509.KeyUsage.dataEncipherment,
                    org.bouncycastle.asn1.x509.KeyUsage.keyAgreement,
                    org.bouncycastle.asn1.x509.KeyUsage.keyCertSign,
                    org.bouncycastle.asn1.x509.KeyUsage.cRLSign,
                    org.bouncycastle.asn1.x509.KeyUsage.encipherOnly,
                    org.bouncycastle.asn1.x509.KeyUsage.decipherOnly};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bits.length; i++) {
                if (!ku.hasUsages(bits[i])) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(names[i]);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extendedKeyUsageLabel(X509CertificateHolder cert) {
        try {
            org.bouncycastle.asn1.x509.ExtendedKeyUsage eku =
                    org.bouncycastle.asn1.x509.ExtendedKeyUsage.fromExtensions(cert.getExtensions());
            if (eku == null) return null;
            StringBuilder sb = new StringBuilder();
            for (org.bouncycastle.asn1.x509.KeyPurposeId id : eku.getUsages()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(purposeLabel(id.getId()));
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String purposeLabel(String oid) {
        return switch (oid) {
            case "1.3.6.1.5.5.7.3.1" -> "ověření serveru";
            case "1.3.6.1.5.5.7.3.2" -> "ověření klienta";
            case "1.3.6.1.5.5.7.3.3" -> "podepisování kódu";
            case "1.3.6.1.5.5.7.3.4" -> "ochrana e-mailu";
            case "1.3.6.1.5.5.7.3.8" -> "časová razítka";
            case "1.3.6.1.5.5.7.3.9" -> "podepisování odpovědí OCSP";
            case "1.3.6.1.4.1.311.10.3.12" -> "podepisování dokumentů (Microsoft)";
            case "2.5.29.37.0" -> "libovolné užití";
            default -> oid;
        };
    }

    private static String basicConstraintsLabel(X509CertificateHolder cert) {
        try {
            org.bouncycastle.asn1.x509.BasicConstraints bc =
                    org.bouncycastle.asn1.x509.BasicConstraints.fromExtensions(cert.getExtensions());
            if (bc == null) return null;
            if (!bc.isCA()) return "koncový certifikát (není CA)";
            return bc.getPathLenConstraint() != null
                    ? "certifikační autorita, hloubka " + bc.getPathLenConstraint()
                    : "certifikační autorita";
        } catch (Exception e) {
            return null;
        }
    }

    /** URLs of one access method in the AIA extension, comma separated. */
    private static String accessLocations(X509CertificateHolder cert, ASN1ObjectIdentifier extension,
                                          ASN1ObjectIdentifier method) {
        try {
            org.bouncycastle.asn1.x509.Extension ext = cert.getExtension(extension);
            if (ext == null) return null;
            org.bouncycastle.asn1.x509.AuthorityInformationAccess aia =
                    org.bouncycastle.asn1.x509.AuthorityInformationAccess.getInstance(
                            ext.getParsedValue());
            StringBuilder sb = new StringBuilder();
            for (org.bouncycastle.asn1.x509.AccessDescription ad : aia.getAccessDescriptions()) {
                if (!method.equals(ad.getAccessMethod())) continue;
                String url = generalNameUri(ad.getAccessLocation());
                if (url == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(url);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String crlDistributionPoints(X509CertificateHolder cert) {
        try {
            org.bouncycastle.asn1.x509.Extension ext =
                    cert.getExtension(org.bouncycastle.asn1.x509.Extension.cRLDistributionPoints);
            if (ext == null) return null;
            org.bouncycastle.asn1.x509.CRLDistPoint points =
                    org.bouncycastle.asn1.x509.CRLDistPoint.getInstance(ext.getParsedValue());
            StringBuilder sb = new StringBuilder();
            for (org.bouncycastle.asn1.x509.DistributionPoint dp : points.getDistributionPoints()) {
                if (dp.getDistributionPoint() == null) continue;
                Object name = dp.getDistributionPoint().getName();
                if (!(name instanceof org.bouncycastle.asn1.x509.GeneralNames names)) continue;
                for (org.bouncycastle.asn1.x509.GeneralName gn : names.getNames()) {
                    String url = generalNameUri(gn);
                    if (url == null) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(url);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String generalNameUri(org.bouncycastle.asn1.x509.GeneralName name) {
        if (name == null || name.getTagNo() != org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier) {
            return null;
        }
        return name.getName() instanceof org.bouncycastle.asn1.ASN1String text ? text.getString() : null;
    }

    private static String extensionInteger(org.bouncycastle.asn1.x509.Extensions extensions,
                                           ASN1ObjectIdentifier oid) {
        try {
            if (extensions == null) return null;
            org.bouncycastle.asn1.x509.Extension ext = extensions.getExtension(oid);
            if (ext == null) return null;
            return org.bouncycastle.asn1.ASN1Integer.getInstance(ext.getParsedValue())
                    .getValue().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extensionReason(org.bouncycastle.asn1.x509.Extensions extensions) {
        try {
            if (extensions == null) return null;
            org.bouncycastle.asn1.x509.Extension ext =
                    extensions.getExtension(org.bouncycastle.asn1.x509.Extension.reasonCode);
            if (ext == null) return null;
            return crlReasonLabel(org.bouncycastle.asn1.ASN1Enumerated.getInstance(
                    ext.getParsedValue()).getValue().intValue());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The DER behind an element: the bytes themselves, or what a PEM wrapper holds.
     *
     * <p>PEM turns up in containers because that is how people are handed certificates, and a
     * screen of base64 answers no question at all. The label has to match, so a certificate is
     * never read as a CRL for want of one.
     *
     * @return the DER bytes, or {@code null} when they are neither
     */
    private static byte[] derOrPem(byte[] data, String... labels) {
        if (data == null || data.length == 0) return null;
        if (looksLikeWholeDer(data)) return data;
        // Only text can be PEM. Without this, every binary element that is not DER pays for a
        // full copy of itself as a String before the markers turn out not to be there.
        if (data.length > MAX_PEM_BYTES || !looksTextual(data)) return null;
        String text = new String(data, StandardCharsets.US_ASCII);
        for (String label : labels) {
            String begin = "-----BEGIN " + label + "-----";
            int from = text.indexOf(begin);
            if (from < 0) continue;
            int to = text.indexOf("-----END", from);
            if (to < 0) continue;
            try {
                byte[] der = Base64.getDecoder().decode(
                        text.substring(from + begin.length(), to).replaceAll("\\s", ""));
                if (der.length > 0 && (der[0] & 0xFF) == 0x30) return der;
            } catch (Exception ignored) {
                // Not this label's block after all; another may still match.
            }
        }
        return null;
    }

    /**
     * True when the bytes are one DER structure and nothing else — the outer length has to span
     * the element, give or take the zero padding some writers leave behind.
     *
     * <p>Reading two bytes before handing anything to the ASN.1 parser: an attachment that merely
     * happens to open with 0x30 would otherwise be parsed in full only to be rejected, and in a
     * container that attachment can be tens of megabytes.
     */
    private static boolean looksLikeWholeDer(byte[] data) {
        if (data.length < 2 || (data[0] & 0xFF) != 0x30) return false;
        int lengthByte = data[1] & 0xFF;
        long length;
        int header;
        if (lengthByte < 0x80) {
            length = lengthByte;
            header = 2;
        } else if (lengthByte == 0x80) {
            return false;                       // indefinite length is BER; none of the three use it
        } else {
            int count = lengthByte & 0x7F;
            if (count > 4 || data.length < 2 + count) return false;
            length = 0;
            for (int i = 0; i < count; i++) length = (length << 8) | (data[2 + i] & 0xFF);
            header = 2 + count;
        }
        long end = header + length;
        if (end > data.length) return false;
        for (int i = (int) end; i < data.length; i++) {
            if (data[i] != 0) return false;
        }
        return true;
    }

    /** Above this size the bytes are not a PEM certificate or CRL worth searching for markers. */
    private static final int MAX_PEM_BYTES = 4 * 1024 * 1024;

    // ---------------------------------------------------------------- DER elements in words

    /** A title and the text under it — what the preview pane needs to show one element. */
    /**
     * Který ze tří prvků to je.
     *
     * <p>Vedle nadpisu, protože volající se na druh potřebuje ptát: prohlížeč CRL umí víc než
     * výpis ve slovech (třídění a hledání v záznamech), takže se seznam otevírá v něm. Ptát
     * se místo toho na {@link Readable#title()} by znamenalo rozhodovat se podle prózy — a ta
     * se smí přepsat, aniž by si toho kód všiml.
     */
    public enum ReadableKind { OCSP, CERTIFICATE, CRL }

    public record Readable(ReadableKind kind, String title, String text) {}

    /**
     * Reads a DER (or PEM) element out in words: an OCSP response, a certificate or a CRL.
     *
     * <p>One parse serves all three attempts. Tried separately, each would parse the bytes again
     * before finding out they are not its kind — measured at 74 ms per parse on a four-megabyte
     * CRL, three times over, on the thread that draws the window.
     *
     * @return what to show, or {@code null} when the bytes are none of the three
     */
    public static Readable readable(byte[] data) {
        byte[] der = derOrPem(data, "CERTIFICATE", "X509 CRL", "CRL");
        if (der == null) return null;
        ASN1Primitive parsed;
        try {
            parsed = ASN1Primitive.fromByteArray(der);
        } catch (Exception e) {
            return null;
        }
        String text = describeOcspResponse(parsed);
        if (text != null) return new Readable(ReadableKind.OCSP, "Odpověď OCSP", text);
        text = describeCertificate(parsed);
        if (text != null) return new Readable(ReadableKind.CERTIFICATE, "Certifikát", text);
        text = describeCrl(parsed);
        if (text != null) return new Readable(ReadableKind.CRL, "Seznam CRL", text);
        return null;
    }

    // ---------------------------------------------------------------- OCSP in words

    /**
     * Renders an OCSP response as text, for the preview pane.
     *
     * <p>An OCSP response is one of the few things in a container a reader has a real question
     * about — which certificate, in what state, said when — and a hex dump answers none of it.
     * The wording follows the OCSP dialog in „Detailní rozbor“ deliberately: the same response
     * should read the same way wherever the application shows it.
     *
     * <p>Both shapes are accepted: the whole {@code OCSPResponse} a responder sends, and the bare
     * {@code BasicOCSPResponse} that CMS carries. The bare form has no status word, so that line
     * is absent rather than invented.
     *
     * @return the description, or {@code null} when the bytes are not an OCSP response at all —
     *         then the caller falls back to the byte view.
     */
    public static String describeOcspResponse(byte[] data) {
        return describeOcspResponse(parse(data));
    }

    private static String describeOcspResponse(ASN1Primitive parsed) {
        if (parsed == null) return null;
        StringBuilder sb = new StringBuilder();
        BasicOCSPResp basic;
        try {
            org.bouncycastle.cert.ocsp.OCSPResp resp = new org.bouncycastle.cert.ocsp.OCSPResp(
                    org.bouncycastle.asn1.ocsp.OCSPResponse.getInstance(parsed));
            line(sb, "", "Stav odpovědi", ocspRespStatusLabel(resp.getStatus()));
            Object obj = null;
            try {
                obj = resp.getResponseObject();
            } catch (Exception noBody) {
                // A refused response carries no body at all, and a damaged one carries no
                // readable body. Either way the status line above is the whole answer.
            }
            if (!(obj instanceof BasicOCSPResp b)) return sb.toString();
            basic = b;
        } catch (Exception notWhole) {
            basic = asBasicOcsp(parsed);
            if (basic == null) return null;
        }

        if (sb.length() > 0) sb.append('\n');
        sb.append("Základní informace\n");
        line(sb, "  ", "ID respondéra", ocspResponderId(basic));
        line(sb, "  ", "Vydáno (producedAt)", formatDate(basic.getProducedAt()));
        if (basic.getSignatureAlgOID() != null) {
            line(sb, "  ", "Algoritmus podpisu", oidLabel(basic.getSignatureAlgOID().getId()));
        }
        if (basic.getCerts() != null && basic.getCerts().length > 0) {
            line(sb, "  ", "Certifikátů v odpovědi", String.valueOf(basic.getCerts().length));
        }

        org.bouncycastle.cert.ocsp.SingleResp[] responses;
        try {
            responses = basic.getResponses();
        } catch (Exception e) {
            return sb.toString();
        }
        sb.append("\nStav certifikátů (").append(responses.length).append(")\n");
        for (int i = 0; i < responses.length; i++) {
            org.bouncycastle.cert.ocsp.SingleResp sr = responses[i];
            if (responses.length > 1) sb.append("  Záznam č. ").append(i + 1).append('\n');
            String in = responses.length > 1 ? "    " : "  ";
            String serial = hexNumber(sr.getCertID().getSerialNumber());
            line(sb, in, "Sériové č. certifikátu", serial);
            if (sr.getCertID().getHashAlgOID() != null) {
                line(sb, in, "Hash algoritmus (CertID)",
                        oidLabel(sr.getCertID().getHashAlgOID().getId()));
            }
            Object status = sr.getCertStatus();
            if (status == null) {
                line(sb, in, "Stav certifikátu", "Dobrý (nerevokovaný)");
            } else if (status instanceof org.bouncycastle.cert.ocsp.RevokedStatus rs) {
                line(sb, in, "Stav certifikátu", "Revokovaný");
                line(sb, in, "Datum revokace", formatDate(rs.getRevocationTime()));
                if (rs.hasRevocationReason()) {
                    line(sb, in, "Důvod revokace", crlReasonLabel(rs.getRevocationReason()));
                }
            } else if (status instanceof org.bouncycastle.cert.ocsp.UnknownStatus) {
                line(sb, in, "Stav certifikátu", "Neznámý");
            }
            line(sb, in, "thisUpdate", formatDate(sr.getThisUpdate()));
            if (sr.getNextUpdate() != null) line(sb, in, "nextUpdate", formatDate(sr.getNextUpdate()));
        }
        return sb.toString();
    }

    /** Reads a structure that is a bare {@code BasicOCSPResponse}, or {@code null} if it is not. */
    private static BasicOCSPResp asBasicOcsp(ASN1Primitive parsed) {
        try {
            return new BasicOCSPResp(
                    org.bouncycastle.asn1.ocsp.BasicOCSPResponse.getInstance(parsed));
        } catch (Exception e) {
            return null;
        }
    }

    /** The DER structure behind an element, or {@code null} when the bytes are not DER at all. */
    private static ASN1Primitive parse(byte[] data) {
        byte[] der = derOrPem(data, "CERTIFICATE", "X509 CRL", "CRL");
        if (der == null) return null;
        try {
            return ASN1Primitive.fromByteArray(der);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Width of the label column: the longest label plus the deepest indent it can appear under,
     * so that no row falls out of line — including inside a response answering for several
     * certificates, where every row is indented one step further.
     */
    private static final int LABEL_COLUMN = 28;

    /** One "label   value" line, padded so that the values line up under each other. */
    private static void line(StringBuilder sb, String indent, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(indent).append(label);
        for (int i = indent.length() + label.length(); i < LABEL_COLUMN; i++) sb.append(' ');
        sb.append("  ").append(value).append('\n');
    }

    /**
     * Jeden řádek výpisu rozebraný zpět na popisek a hodnotu. {@code label} je {@code null}
     * u řádku, který dvojici nenese — nadpis bloku, prázdná řádka.
     */
    public record TextRow(String indent, String label, String value) {
        public boolean hasLabel() { return label != null; }
    }

    /**
     * Rozebere výpis z {@code describe*} zpět na řádky — <b>protiklad k {@link #line}</b>.
     *
     * <p>Existuje kvůli oknu, které výpis ukazuje: dokud byl jedním blokem textu v neproporcionálním
     * písmu, dlouhé hodnoty (DN předmětu, distribuční body CRL) se nezalomily a čtenář je doháněl
     * vodorovným posuvníkem. Ve dvou sloupcích se hodnota zalomí sama a zůstane odsazená pod
     * sebou, místo aby pokračovala pod popiskem.
     *
     * <p><b>Dělí podle prvního výskytu dvou mezer za textem</b>, ne podle {@link #LABEL_COLUMN}:
     * popisek delší než sloupec se od hodnoty stejně odděluje dvěma mezerami, takže obojí pokrývá
     * jediné pravidlo. Dvě mezery uvnitř hodnoty nevadí — hledá se ten <b>první</b> výskyt, a to
     * je vždycky výplň za popiskem. (Řádek „Vydavatel" takový případ obsahuje: za DN následuje
     * „  (podepsán sám sebou)".)
     *
     * <p>Řádek, jehož odsazení je jedinou mezerou před textem bez dalšího dvojmezeří, se vrací
     * jako řádek bez popisku. Tak vypadají nadpisy bloků a v odpovědi OCSP i „  Záznam č. 1".
     *
     * <p>Mění-li se {@link #line}, musí se změnit i tohle. Drží je pohromadě
     * {@code ContainerTextRowTest}.
     */
    public static List<TextRow> splitRows(String text) {
        List<TextRow> rows = new ArrayList<>();
        if (text == null) return rows;
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            int start = 0;
            while (start < line.length() && line.charAt(start) == ' ') start++;
            int sep = -1;
            for (int i = start; i + 1 < line.length(); i++) {
                if (line.charAt(i) == ' ' && line.charAt(i + 1) == ' ') { sep = i; break; }
            }
            String value = sep < 0 ? null : line.substring(sep).stripLeading();
            if (sep <= start || value == null || value.isEmpty()) {
                rows.add(new TextRow("", null, line));
            } else {
                rows.add(new TextRow(line.substring(0, start), line.substring(start, sep), value));
            }
        }
        // split("\n", -1) nad textem zakončeným novou řádkou vydá na konci prázdný řetězec;
        // v okně by z něj byla mezera navíc pod posledním údajem.
        if (!rows.isEmpty() && !rows.get(rows.size() - 1).hasLabel()
                && rows.get(rows.size() - 1).value().isEmpty()) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    /** The full DN of a byName responderId, or the hex of a byKey hash. */
    private static String ocspResponderId(BasicOCSPResp basic) {
        try {
            org.bouncycastle.asn1.ocsp.ResponderID respId =
                    org.bouncycastle.asn1.ocsp.ResponderID.getInstance(
                            basic.getResponderId().toASN1Primitive());
            org.bouncycastle.asn1.x500.X500Name byName = respId.getName();
            if (byName != null) return byName.toString();
            byte[] keyHash = respId.getKeyHash();
            if (keyHash != null) {
                StringBuilder sb = new StringBuilder("KeyHash:");
                for (byte b : keyHash) sb.append(String.format("%02X", b & 0xFF));
                return sb.toString();
            }
        } catch (Exception ignored) {
            // An unreadable responder id costs one line, not the whole description.
        }
        return null;
    }

    private static String ocspRespStatusLabel(int status) {
        return switch (status) {
            case 0 -> "Úspěšná (successful)";
            case 1 -> "Chybný požadavek (malformedRequest)";
            case 2 -> "Vnitřní chyba (internalError)";
            case 3 -> "Zkuste později (tryLater)";
            case 5 -> "Vyžadován podpis (sigRequired)";
            case 6 -> "Neautorizováno (unauthorized)";
            default -> "Neznámý stav (" + status + ")";
        };
    }

    private static String crlReasonLabel(int reason) {
        return switch (reason) {
            case 0  -> "Neurčeno (unspecified)";
            case 1  -> "Kompromitace klíče (keyCompromise)";
            case 2  -> "Kompromitace CA (cACompromise)";
            case 3  -> "Změna příslušnosti (affiliationChanged)";
            case 4  -> "Nahrazen (superseded)";
            case 5  -> "Ukončena operace (cessationOfOperation)";
            case 6  -> "Pozastaven (certificateHold)";
            case 8  -> "Odblokován (removeFromCRL)";
            case 9  -> "Odvolána výjimka (privilegeWithdrawn)";
            case 10 -> "Kompromitace AA (aACompromise)";
            default -> "Kód " + reason;
        };
    }

    /**
     * Jméno algoritmu místo jeho OID — {@code 1.2.840.113549.1.1.11} je pro čtenáře nic,
     * {@code SHA256withRSA} odpověď.
     *
     * <p>Neznámé OID se vrací tak, jak přišlo: říct „neznámý algoritmus" by zahodilo jediný
     * údaj, který o něm je, a s OID se dá dohledat, co znamená.
     *
     * <p>Veřejné kvůli prohlížeči seznamů CRL, který si záhlaví sází sám a do 16. 9. 2026
     * v něm ukazoval holé OID — ačkoli textový výpis téhož seznamu vedle něj psal jméno.
     * Týž seznam se má číst stejně, ať se na něj čtenář dívá kdekoli.
     */
    public static String oidLabel(String oid) {
        return switch (oid) {
            case "1.3.14.3.2.26"          -> "SHA-1";
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512";
            case "2.16.840.1.101.3.4.2.4" -> "SHA-224";
            case "1.2.840.113549.1.1.1"   -> "RSA";
            case "1.2.840.113549.1.1.5"   -> "SHA1withRSA";
            case "1.2.840.113549.1.1.10"  -> "RSASSA-PSS";
            case "1.2.840.113549.1.1.11"  -> "SHA256withRSA";
            case "1.2.840.113549.1.1.12"  -> "SHA384withRSA";
            case "1.2.840.113549.1.1.13"  -> "SHA512withRSA";
            case "1.2.840.10045.2.1"      -> "EC";
            case "1.2.840.10045.4.3.2"    -> "SHA256withECDSA";
            case "1.2.840.10045.4.3.3"    -> "SHA384withECDSA";
            case "1.2.840.10045.4.3.4"    -> "SHA512withECDSA";
            default -> oid;
        };
    }

    /** Pretty-prints XML for the preview pane; returns {@code null} when the bytes are not XML. */
    public static String prettyPrintXml(byte[] xml) {
        try {
            Document doc = parseXml(xml);
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pretty-prints JSON for the preview pane; returns {@code null} when the bytes are not JSON.
     *
     * <p>Reformats the text rather than parsing and re-writing it, so members keep the order the
     * file gave them and nothing is lost on the way. That matters here more than elsewhere: in a
     * JAdES signature the order of the properties in {@code etsiU} is part of what was signed,
     * and a preview that quietly sorted them would be showing a different document.
     */
    public static String prettyPrintJson(byte[] json) {
        String text = new String(json, StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || (text.charAt(0) != '{' && text.charAt(0) != '[')) return null;
        StringBuilder out = new StringBuilder(text.length() + text.length() / 4);
        int indent = 0;
        // The line break a bracket or comma asks for is held back until something is actually
        // written after it. That is what keeps "{}" on one line instead of wrapping an indented
        // blank line between its two halves.
        int pending = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (Character.isWhitespace(c)) continue;
            if (c == '}' || c == ']') {
                indent--;
                pending = pending >= 0 ? -1 : indent;
            }
            if (pending >= 0) {
                // Past this depth the leading run of spaces would be wider than the pane, and
                // such a structure is being read for its shape rather than its indentation.
                out.append('\n').append("  ".repeat(Math.min(pending, 40)));
                pending = -1;
            }
            out.append(c);
            switch (c) {
                case ':'      -> out.append(' ');
                case '{', '[' -> pending = ++indent;
                case ','      -> pending = indent;
                case '"'      -> inString = true;
                default       -> { /* an ordinary character of a number, true, false or null */ }
            }
        }
        return out.toString();
    }

    private static String qualifiedName(Element el) {
        String prefix = el.getPrefix();
        return prefix == null || prefix.isEmpty() ? localName(el) : prefix + ":" + localName(el);
    }

    static String localName(Element el) {
        return el.getLocalName() != null ? el.getLocalName() : el.getNodeName();
    }

    private static byte[] tryDecodeBase64(String text) {
        if (text.length() < 32) return null;
        // Cheap pre-check: base64 payloads are long runs of the base64 alphabet plus whitespace.
        for (int i = 0; i < Math.min(text.length(), 256); i++) {
            char c = text.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '+' || c == '/' || c == '='
                    || Character.isWhitespace(c);
            if (!ok) return null;
        }
        try {
            return Base64.getMimeDecoder().decode(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] maybeDecodePem(byte[] bytes) {
        if (!startsWith(bytes, "-----BEGIN")) return bytes;
        String text = new String(bytes, StandardCharsets.US_ASCII);
        int start = text.indexOf('\n', text.indexOf("-----BEGIN"));
        int end = text.indexOf("-----END");
        if (start < 0 || end < 0 || end <= start) return bytes;
        try {
            return Base64.getMimeDecoder().decode(text.substring(start, end));
        } catch (Exception e) {
            return bytes;
        }
    }

    private static String oidName(String oid) {
        if (oid == null) return null;
        return switch (oid) {
            case "1.3.14.3.2.26"          -> "SHA-1 (" + oid + ")";
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256 (" + oid + ")";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384 (" + oid + ")";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512 (" + oid + ")";
            case "1.2.840.113549.1.1.1"   -> "RSA (" + oid + ")";
            case "1.2.840.113549.1.1.11"  -> "SHA256withRSA (" + oid + ")";
            case "1.2.840.113549.1.1.10"  -> "RSASSA-PSS (" + oid + ")";
            case "1.2.840.10045.2.1"      -> "ECDSA (" + oid + ")";
            case "1.2.840.10045.4.3.2"    -> "SHA256withECDSA (" + oid + ")";
            default -> oid;
        };
    }

    private static String oidHint(String oid) {
        if (oid == null) return "";
        return switch (oid) {
            case "1.2.840.113549.1.7.1" -> " (id-data — libovolná data)";
            case "1.2.840.113549.1.7.2" -> " (id-signedData)";
            case "1.2.840.113549.1.9.16.1.4" -> " (id-ct-TSTInfo — časové razítko)";
            default -> "";
        };
    }

    static String commonName(String dn) {
        if (dn == null) return "";
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) return p.substring(3).trim();
        }
        return "";
    }

    /** Strips characters Windows and the ZIP format disagree about, so extraction always succeeds. */
    public static String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned.isEmpty() ? "soubor" : cleaned;
    }

    private static void sortTree(Node node) {
        node.children.sort(Comparator
                .comparing((Node n) -> !n.directory)
                .thenComparing(n -> n.name, String.CASE_INSENSITIVE_ORDER));
        for (Node child : node.children) sortTree(child);
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        if (bytes.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes[i] != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }

    /** Skips a BOM and leading whitespace so {@code <?xml} preceded by either is still XML. */
    private static int skipTextPreamble(byte[] bytes) {
        int i = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) i = 3;
        while (i < bytes.length && Character.isWhitespace(bytes[i])) i++;
        return i;
    }

    private static byte[] readCapped(java.io.InputStream in, long cap) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int read;
        while ((read = in.read(buf)) > 0) {
            out.write(buf, 0, read);
            if (out.size() > cap) break;
        }
        return out.toByteArray();
    }

    private static void skip(java.io.InputStream in) throws java.io.IOException {
        byte[] buf = new byte[16384];
        while (in.read(buf) > 0) { /* drain */ }
    }

    static String describe(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
