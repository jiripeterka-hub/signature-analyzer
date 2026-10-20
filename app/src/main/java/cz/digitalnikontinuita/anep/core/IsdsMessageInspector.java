package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents a ZFO — the file a Czech data box hands out — as what it is: a data message.
 *
 * <p>Structurally such a file is a CMS/PKCS #7 object sealed by the ISDS operator, and until now
 * the application showed it as one: an envelope with 11 kB of XML inside. That is true and
 * useless. What the file actually holds is a message — who sent it to whom, under what reference
 * number, when it was delivered and when it was accepted — together with the písemnosti attached
 * to it. This class reads that structure (ISDS WSDL 3.10, {@code dmBaseTypes.xsd}) and hands the
 * container browser a tree in those terms, with every attachment carrying its own bytes so it can
 * be previewed, saved or verified in its own right.
 *
 * <p>The seal itself is not re-interpreted here — {@link ContainerInspector#addCmsSignatureNodes}
 * contributes the same signer, certificate and revocation nodes any other CMS object gets, and
 * DSS validates the file exactly as before. What is added is one check nothing else performs:
 * the message envelope carries a hash of itself, and the qualified time-stamp of submission
 * carries that hash too. Recomputing it from the bytes present ties the message the reader sees
 * to the moment ISDS accepted it.
 */
public final class IsdsMessageInspector {

    private IsdsMessageInspector() {}

    /** Namespace of the message payload; the response wrapper uses this plus {@code /message} or {@code /delivery}. */
    private static final String NS_ISDS = "isds.czechpoint.cz/v20";

    /** Opening tag of the envelope element, whatever namespace prefix the sender chose for it. */
    private static final Pattern DM_DM =
            Pattern.compile("<([A-Za-z_][\\w.\\-]*:)?dmDm(\\s|>)");

    /**
     * Above this, the envelope is not duplicated into its own node. It nests the attachments, so
     * it is the entire payload a second time — worth holding for an ordinary message, which ISDS
     * caps at 20 MB, but not for a velkoobjemová zpráva, which may run to a gigabyte.
     * <p>
     * Holding it is worth something: the node's SHA-256 <em>is</em> the message hash, so the
     * reader can copy it straight out and hold it against anything ISDS quotes.
     */
    private static final int MAX_ENVELOPE_COPY = 32 * 1024 * 1024;


    // ---------------------------------------------------------------- detection

    /**
     * Cheap pre-check on the CMS payload, so a plain P7M is never parsed as a message.
     * The definitive answer comes from {@link #inspect}, which returns {@code null} when the XML
     * turns out not to be an ISDS response after all.
     */
    static boolean looksLikeIsdsMessage(byte[] content) {
        if (content == null || content.length < 64) return false;
        String head = new String(content, 0, Math.min(content.length, 4096), StandardCharsets.ISO_8859_1);
        return head.contains(NS_ISDS) && head.contains("dmDm");
    }

    // ---------------------------------------------------------------- entry point

    /**
     * Builds the message view, or returns {@code null} to let the caller fall back to the plain
     * CMS view — which is the right outcome for anything that is not, in fact, a data message.
     *
     * @param raw       the ZFO file as stored
     * @param cms       the parsed CMS object, for the seal on it
     * @param content   the signed payload (the message XML), exactly as the CMS carries it
     * @param fallback  warnings of the plain CMS view, added to when falling back is itself a
     *                  finding — a message whose XML will not parse is a damaged message, and
     *                  quietly showing it as a CMS envelope would hide that
     */
    static Result inspect(byte[] raw, CMSSignedData cms, byte[] content, String fileName,
                          List<String> fallback) {
        Document doc;
        try {
            doc = ContainerInspector.parseXml(content);
        } catch (Exception e) {
            fallback.add("Soubor nese datovou zprávu ISDS, ale její XML se nepodařilo přečíst ("
                    + ContainerInspector.describe(e) + "). Zobrazen je proto jen objekt CMS, "
                    + "ve kterém je zpráva uložena — obsah zprávy byl po zapečetění poškozen "
                    + "nebo upraven.");
            return null;
        }
        Element dmDm = firstDescendant(doc.getDocumentElement(), "dmDm");
        if (dmDm == null) return null;
        Element wrapper = dmDm.getParentNode() instanceof Element p ? p : null;
        if (wrapper == null) return null;

        String wrapperName = ContainerInspector.localName(wrapper);
        Flavour flavour = switch (wrapperName) {
            case "dmDelivery"                -> Flavour.DELIVERY;
            case "dmReturnedMessageEnvelope" -> Flavour.ENVELOPE;
            case "dmReturnedMessage"         -> Flavour.MESSAGE;
            default -> null;
        };
        if (flavour == null) return null;

        // Sender's copy and recipient's copy of one message hold the identical envelope and differ
        // only in the namespace of the response that delivered them, because the two sides fetch
        // their copy through different ISDS services. That namespace is therefore the only thing
        // saying whose file this is — worth knowing, since it is also whose evidence it is.
        ContainerInspector.IsdsDirection direction =
                ContainerInspector.isdsDirection(doc.getDocumentElement().getNamespaceURI());
        ContainerInspector.IsdsArtefact artefact = new ContainerInspector.IsdsArtefact(
                switch (flavour) {
                    case MESSAGE  -> ContainerInspector.IsdsKind.MESSAGE;
                    case DELIVERY -> ContainerInspector.IsdsKind.DELIVERY;
                    case ENVELOPE -> ContainerInspector.IsdsKind.ENVELOPE;
                }, direction);

        List<String> warnings = new ArrayList<>();
        Node root = new Node(fileName, "", true, null);

        // The message's own parts — envelope, time-stamp, signed XML — go in a folder of their
        // own, added shut. They are what the message is made of rather than what it says, and
        // leaving them open would put three technical files above the documents every time.
        // Their paths stay flat, so extracting the container still writes them at its top level.
        Node messageFolder = root.add(new Node("Datová zpráva", "zprava", true,
                "součásti datové zprávy")).collapsed();

        // 1. The envelope: every field the sender or ISDS filled in, in schema order.
        Map<String, String> envelope = readEnvelope(dmDm, wrapper, doc.getDocumentElement(), artefact);
        int[] dmDmRange = locateElement(content, DM_DM, "dmDm");
        Node envelopeNode = messageFolder.add(new Node("obalka-zpravy.xml", "obalka-zpravy.xml", false,
                "obálka datové zprávy (dmDm) — údaje o zprávě")).fragment();
        envelopeNode.put("Role", "obálka datové zprávy (dmDm) — údaje o zprávě");
        // Přímo do mapy, ne přes put(): značky oddílů nesou jen zástupnou hodnotu.
        envelopeNode.getInfo().putAll(envelope);
        // The envelope element contains the attachments, so on a large message it is the whole
        // file over again. Its value here is the fields above, not another copy of the bytes —
        // and datova-zprava.xml below carries those in full for anyone who wants them.
        if (dmDmRange != null) {
            int length = dmDmRange[1] - dmDmRange[0];
            if (length <= MAX_ENVELOPE_COPY) {
                envelopeNode.data(Arrays.copyOfRange(content, dmDmRange[0], dmDmRange[1]));
            } else {
                envelopeNode.put("Rozsah obálky",
                        ContainerInspector.formatSize(length) + " — pro samostatné zobrazení "
                        + "příliš rozsáhlá; celé podepsané XML je v prvku datova-zprava.xml");
            }
        }

        // 2. The písemnosti. A delivery receipt and a bare envelope carry none by definition.
        List<Node> attachments = addAttachments(root, dmDm);

        // 3. How the message travelled — only a delivery receipt records this.
        addEvents(root, wrapper);

        // 4. The qualified time-stamp of submission, and the hash check it makes possible.
        String timestampSummary = null;
        String hashVerdict;
        Node submissionTs = null;
        TimeStampTokenInfo tsInfo = null;
        byte[] tokenBytes = decodeBase64(textOf(wrapper, "dmQTimestamp"));
        // ISDS ukládá razítko často s nulovou výplní za strukturou (změřeno: 395 z ~1 100 zpráv
        // ve sbírce, skoro vždy 9 bajtů). BouncyCastle pak odmítne celé razítko („Extra data
        // detected in stream") a do 27. 9. 2026 tu stálo, že razítko podání nejde přečíst
        // (nález uživatele). Výplň k razítku nepatří — otisk v něm kryje dmHash, ne tyhle
        // bajty — takže se odřízne i v tom, co jde uložit a ověřit zvlášť.
        int padding = 0;
        if (tokenBytes != null) {
            int end = asn1Length(tokenBytes);
            if (end > 0 && end < tokenBytes.length && allZero(tokenBytes, end)) {
                padding = tokenBytes.length - end;
                tokenBytes = Arrays.copyOf(tokenBytes, end);
            }
        }
        if (tokenBytes != null && tokenBytes.length > 0) {
            Node ts = messageFolder.add(new Node("casove-razitko-isds.tst", "casove-razitko-isds.tst",
                    false, "kvalifikované elektronické časové razítko podání zprávy (dmQTimestamp)"));
            ts.data(tokenBytes);
            submissionTs = ts;
            ts.put("Role", "kvalifikované elektronické časové razítko podání zprávy (dmQTimestamp)");
            if (padding > 0) {
                ts.put("Poznámka", "v datové zprávě za razítkem následuje " + padding
                        + " B nulové výplně, která k razítku nepatří; tady je bez ní");
            }
            try {
                TimeStampToken token = new TimeStampToken(new CMSSignedData(tokenBytes));
                tsInfo = token.getTimeStampInfo();
                ts.put("Čas podání zprávy dle razítka",
                        ContainerInspector.formatDate(tsInfo.getGenTime()));
                ts.put("Otisková funkce", digestName(tsInfo.getMessageImprintAlgOID().getId()));
                ts.put("Otisk zprávy v razítku", hex(tsInfo.getMessageImprintDigest()));
                if (tsInfo.getPolicy() != null) ts.put("Politika razítka", tsInfo.getPolicy().getId());
                if (tsInfo.getSerialNumber() != null) {
                    ts.put("Sériové číslo razítka",
                            ContainerInspector.hexNumber(tsInfo.getSerialNumber()));
                }
                if (tsInfo.getTsa() != null) ts.put("Autorita razítka", tsaName(tsInfo));
                timestampSummary = ContainerInspector.formatDate(tsInfo.getGenTime());
            } catch (Exception e) {
                warnings.add("Kvalifikované elektronické časové razítko podání se nepodařilo přečíst ("
                        + ContainerInspector.describe(e) + "); jeho obsah lze uložit a ověřit zvlášť.");
            }
        } else {
            warnings.add("Zpráva neobsahuje kvalifikované elektronické časové razítko podání"
                    + " (dmQTimestamp).");
        }
        hashVerdict = checkHash(wrapper, content, dmDmRange, tsInfo, flavour, warnings);
        // Do 16. 10. 2026 stál verdikt v záhlaví zprávy; uživatel ho tam nechtěl. Patří k razítku
        // podání, jehož otisk se přepočítává — nesouhlas navíc dál hlásí varování.
        if (submissionTs != null) submissionTs.put("Kontrola otisku zprávy", hashVerdict);

        // 5. The signed XML as a whole — the bytes the seal actually covers.
        Node xml = messageFolder.add(new Node("datova-zprava.xml", "datova-zprava.xml", false,
                "podepsaný obsah — XML datové zprávy"));
        xml.data(content);
        xml.put("Role", "podepsaný obsah — XML datové zprávy (eContent objektu CMS)");

        // 6. The seal on the file, shown exactly as on any other CMS object — but shut.
        try {
            ContainerInspector.addCmsSignatureNodes(root, cms, true, true);
        } catch (Exception e) {
            warnings.add("Pečeť souboru se nepodařilo rozebrat: " + ContainerInspector.describe(e));
        }

        String typeLabel = artefact.typeLabel();

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Formát", typeLabel);
        putIfSet(summary, "Druh zprávy", messageCategory(dmDm, wrapper));
        putIfSet(summary, "ID datové zprávy", envelope.get("ID datové zprávy"));
        putIfSet(summary, "Věc", envelope.get("Věc"));
        putIfSet(summary, "Odesláno z datové schránky",
                boxWithHolder(textOf(dmDm, "dbIDSender"), textOf(dmDm, "dmSender")));
        putIfSet(summary, "Dodáno do datové schránky",
                boxWithHolder(textOf(dmDm, "dbIDRecipient"), textOf(dmDm, "dmRecipient")));
        putIfSet(summary, "Staženo ze schránky", downloadedFrom(artefact, dmDm));
        putIfSet(summary, "Čas podání", timestampSummary);
        putIfSet(summary, "Čas dodání", envelope.get("Čas dodání"));
        putIfSet(summary, "Čas doručení", envelope.get("Čas doručení"));
        putIfSet(summary, "Čas stažení", downloadTime(cms));
        putIfSet(summary, "Stav zprávy", envelope.get("Stav zprávy"));
        if (flavour == Flavour.MESSAGE) {
            summary.put("Počet příloh", attachments.isEmpty() ? "žádné"
                    : attachments.size() + " (" + ContainerInspector.formatSize(
                            attachments.stream().mapToLong(Node::getSize).sum()) + ")");
        }
        summary.put("Velikost souboru", ContainerInspector.formatSize(raw.length));

        if (flavour == Flavour.MESSAGE && attachments.isEmpty()) {
            warnings.add("Zpráva neobsahuje žádnou písemnost — v elementu dmFiles není nic, "
                    + "ačkoli podle schématu ISDS má být alespoň jedna.");
        }

        return new Result(Kind.ZFO, typeLabel, root, summary, warnings, null);
    }

    /**
     * Čas stažení zprávy ze schránky: čas výstupního časového razítka, které ISDS přidává
     * k pečeti v okamžiku stažení (atribut signature-time-stamp, OID 1.2.840.113549.1.9.16.2.14).
     * Zavedeno 16. 10. 2026 na pokyn uživatele. {@code null}, když pečeť razítko nenese nebo
     * nejde přečíst — pak řádek v záhlaví chybí.
     */
    private static String downloadTime(CMSSignedData cms) {
        try {
            for (org.bouncycastle.cms.SignerInformation signer : cms.getSignerInfos().getSigners()) {
                org.bouncycastle.asn1.cms.AttributeTable unsigned = signer.getUnsignedAttributes();
                if (unsigned == null) continue;
                org.bouncycastle.asn1.cms.Attribute attr = unsigned.get(
                        org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
                if (attr == null || attr.getAttrValues().size() == 0) continue;
                byte[] enc = attr.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded("DER");
                TimeStampToken token = new TimeStampToken(new CMSSignedData(enc));
                return ContainerInspector.formatDate(token.getTimeStampInfo().getGenTime());
            }
        } catch (Exception ignored) {
            // Nečitelné razítko popisuje uzel razítka pod pečetí; záhlaví jen vynechá řádek.
        }
        return null;
    }

    /**
     * „ej9ixnq (držitelem je: Jiří Peterka)“ — schránka a v závorce její držitel. Zavedeno
     * 16. 10. 2026 na pokyn uživatele: zpráva jde ze schránky do schránky, držitel je údaj o ní.
     */
    private static String boxWithHolder(String boxId, String holder) {
        if (boxId == null) return holder;
        return holder == null ? boxId : boxId + " (držitelem je: " + holder + ")";
    }

    /** Which of the three ISDS structures the file turned out to carry. */
    private enum Flavour { MESSAGE, DELIVERY, ENVELOPE }

    /**
     * The kind of message, always named.
     *
     * <p>The two kinds that can be recognised outright — the one ISDS sent itself and the one
     * somebody paid for — exhaust the exceptions, so what remains is the public-law message that
     * the Act provides for. Saying so is worth a line: an absent label would leave the reader to
     * work out from the absence of two others what kind of message they are holding, and the
     * public-law kind is the one whose legal effects the whole system is built around.
     *
     * <p>The two exceptions are mutually exclusive — ISDS neither charges for its own messages
     * nor marks them — so the order below is a formality rather than a precedence.
     */
    private static String messageCategory(Element dmDm, Element wrapper) {
        if (isSystemMessage(dmDm)) return "systémová datová zpráva";
        if (isPostalMessage(attr(wrapper, "dmType"))) return "poštovní datová zpráva (PDZ)";
        return "(veřejnoprávní) datová zpráva";
    }

    /** ID of the box ISDS sends from on its own initiative — antivirus, undeliverability, admin. */
    private static final String BOX_ISDS_ADMINISTRATOR = "aaaaaaa";
    /** ID of the operator's box — credit, data vault, certificate expiry and other paid services. */
    private static final String BOX_ISDS_OPERATOR = "zzzzzzq";

    /**
     * Whether ISDS itself sent the message rather than a person or an authority.
     *
     * <p>Two properties say so, and the operating rules state both: the sender is one of the two
     * system boxes, and the sender's box type is 0 — a value the data-box code book does not
     * otherwise use. Either is checked, because a message that has one and not the other would be
     * odd enough that calling it systémová is still the more useful answer.
     */
    private static boolean isSystemMessage(Element dmDm) {
        String box = textOf(dmDm, "dbIDSender");
        if (BOX_ISDS_ADMINISTRATOR.equalsIgnoreCase(box) || BOX_ISDS_OPERATOR.equalsIgnoreCase(box)) {
            return true;
        }
        String senderType = textOf(dmDm, "dmSenderType");
        return senderType != null && "0".equals(senderType.trim());
    }

    /**
     * Whether the message is a poštovní datová zpráva — one somebody paid for, as against the
     * public kind that flows to and from public authorities free of charge.
     *
     * <p>Nothing in the envelope says so in words. The operating rules put it in the presence of
     * the {@code dmType} attribute alone: "u veřejné zprávy nebude z důvodu zpětné kompatibility
     * atribut použit". Every value it takes on a downloaded message names a flavour of PDZ — paid
     * from a contract (K) or from credit (E), initiating a free reply (I, and A when subsidised),
     * such an initiator once used up (Y, B) or expired (X, C), the reply itself (O), or subsidised
     * (G, Z, D). Only "V" means the public kind, and only on the input side of CreateMessage —
     * excluded here anyway, since reading it as a PDZ would be the one mistake worth avoiding.
     */
    private static boolean isPostalMessage(String dmType) {
        return dmType != null && !dmType.isBlank() && !"V".equalsIgnoreCase(dmType.trim());
    }

    /**
     * Which data box this copy came out of, spelled out.
     *
     * <p>Follows from the direction and nothing else: the sender's copy can only have been
     * downloaded by the sender. It answers a question the envelope never does — the envelope
     * names both parties, but says nothing about whose file this is, and whose file it is
     * decides what it proves.
     */
    private static String downloadedFrom(ContainerInspector.IsdsArtefact artefact, Element dmDm) {
        String role = artefact.downloadedByRole();
        if (role == null) return null;
        String boxId = textOf(dmDm, artefact.direction() == ContainerInspector.IsdsDirection.SENT
                ? "dbIDSender" : "dbIDRecipient");
        return boxId == null ? role : role + " — " + boxId;
    }

    // ---------------------------------------------------------------- envelope

    /**
     * Reads the envelope into label→value pairs, keeping only what is filled in: the schema
     * marks nearly every field nillable and ISDS emits all of them, so a message that names no
     * organisational unit and quotes no law would otherwise be twenty lines of "nevyplněno".
     */
    private static Map<String, String> readEnvelope(Element dmDm, Element wrapper,
                                                    Element responseRoot,
                                                    ContainerInspector.IsdsArtefact artefact) {
        Map<String, String> out = new LinkedHashMap<>();

        put(out, "ID datové zprávy", textOf(dmDm, "dmID"));
        String dmType = attr(wrapper, "dmType");
        if (dmType != null) put(out, "Druh zprávy", dmType + " — " + messageType(dmType));
        if ("1".equals(attr(wrapper, "specMessFlag"))) {
            put(out, "Příznak ISDS", "zpráva je označena jako podezřelá (spam)");
        }
        if ("true".equalsIgnoreCase(attr(wrapper, "dmVODZ"))) {
            put(out, "Velkoobjemová zpráva (VoDZ)", "ano");
        }

        put(out, "Věc", textOf(dmDm, "dmAnnotation"));

        // Odesílatel a příjemce jako dva oddíly s nadpisem (16. 10. 2026, pokyn uživatele):
        // zpráva se posílá z datové schránky do datové schránky a jejich držitel je údaj o ní,
        // ne totéž co schránka. Typ schránky obálka uvádí jen u odesílatele (dmSenderType);
        // pro příjemce žádný takový prvek nemá.
        int section = 0;
        out.put(Node.separatorKey(++section), "-");
        out.put(Node.headingKey("Odesláno z datové schránky"), "-");
        put(out, "ID schránky" + Node.KEY_MARK + "odesílatel", textOf(dmDm, "dbIDSender"));
        String senderType = textOf(dmDm, "dmSenderType");
        if (senderType != null) put(out, "Typ schránky odesílatele",
                senderType + " — " + boxType(senderType));
        put(out, "Držitel datové schránky" + Node.KEY_MARK + "odesílatel", textOf(dmDm, "dmSender"));
        put(out, "Adresa držitele" + Node.KEY_MARK + "odesílatel", textOf(dmDm, "dmSenderAddress"));
        put(out, "Organizační jednotka odesílatele", textOf(dmDm, "dmSenderOrgUnit"));
        put(out, "Organizační jednotka odesílatele (kód)", nonZero(textOf(dmDm, "dmSenderOrgUnitNum")));

        out.put(Node.separatorKey(++section), "-");
        out.put(Node.headingKey("Dodáno do datové schránky"), "-");
        put(out, "ID schránky" + Node.KEY_MARK + "příjemce", textOf(dmDm, "dbIDRecipient"));
        put(out, "Držitel datové schránky" + Node.KEY_MARK + "příjemce", textOf(dmDm, "dmRecipient"));
        put(out, "Adresa držitele" + Node.KEY_MARK + "příjemce", textOf(dmDm, "dmRecipientAddress"));
        put(out, "Organizační jednotka příjemce", textOf(dmDm, "dmRecipientOrgUnit"));
        put(out, "Organizační jednotka příjemce (kód)", nonZero(textOf(dmDm, "dmRecipientOrgUnitNum")));
        put(out, "K rukám", textOf(dmDm, "dmToHands"));
        put(out, "Adresát je ne-OVM povýšené na OVM", yesNoIfTrue(textOf(dmDm, "dmAmbiguousRecipient")));
        out.put(Node.separatorKey(++section), "-");

        put(out, "Číslo jednací odesílatele", textOf(dmDm, "dmSenderRefNumber"));
        put(out, "Číslo jednací příjemce", textOf(dmDm, "dmRecipientRefNumber"));
        put(out, "Spisová značka odesílatele", textOf(dmDm, "dmSenderIdent"));
        put(out, "Spisová značka příjemce", textOf(dmDm, "dmRecipientIdent"));
        put(out, "Zmocnění", legalTitle(dmDm));

        put(out, "Do vlastních rukou", yesNo(textOf(dmDm, "dmPersonalDelivery")));
        put(out, "Náhradní doručení (fikcí)", allowSubst(textOf(dmDm, "dmAllowSubstDelivery")));
        put(out, "Odesláno v režimu OVM", yesNoIfTrue(textOf(dmDm, "dmOVM")));
        put(out, "Zveřejnění ID odesílatele", yesNoIfTrue(textOf(dmDm, "dmPublishOwnID")));

        // Fields ISDS adds around the envelope as the message travels.
        put(out, "Čas dodání", dateTime(textOf(wrapper, "dmDeliveryTime")));
        put(out, "Čas doručení", dateTime(textOf(wrapper, "dmAcceptanceTime")));
        put(out, "Stav zprávy", messageStatusToShow(textOf(wrapper, "dmMessageStatus"), artefact));
        String attSize = textOf(wrapper, "dmAttachmentSize");
        if (attSize != null) put(out, "Velikost příloh dle ISDS", attSize + " kB");

        Element hash = firstDescendant(wrapper, "dmHash");
        if (hash != null) {
            String alg = attr(hash, "algorithm");
            String value = text(hash);
            byte[] decoded = decodeBase64(value);
            put(out, "Otisk zprávy (dmHash)",
                    (alg != null ? alg + ": " : "") + (decoded != null ? hex(decoded) : value));
        }

        String source = attr(responseRoot, "isds_send");
        put(out, "Zdroj souboru (isds_send)", source);

        Element dmStatus = firstDescendant(responseRoot, "dmStatus");
        if (dmStatus != null) {
            String code = textOf(dmStatus, "dmStatusCode");
            String message = textOf(dmStatus, "dmStatusMessage");
            if (code != null || message != null) {
                put(out, "Stav operace ISDS",
                        (code != null ? code : "") + (message != null ? " — " + message : ""));
            }
        }
        return out;
    }

    /** Assembles the five separate {@code dmLegalTitle*} fields into the citation they spell out. */
    private static String legalTitle(Element dmDm) {
        String law = nonZero(textOf(dmDm, "dmLegalTitleLaw"));
        String year = nonZero(textOf(dmDm, "dmLegalTitleYear"));
        String sect = textOf(dmDm, "dmLegalTitleSect");
        String par = textOf(dmDm, "dmLegalTitlePar");
        String point = textOf(dmDm, "dmLegalTitlePoint");
        if (law == null && year == null && sect == null && par == null && point == null) return null;
        StringBuilder sb = new StringBuilder();
        if (sect != null) sb.append("§ ").append(sect);
        if (par != null) sb.append(sb.length() > 0 ? " odst. " : "odst. ").append(par);
        if (point != null) sb.append(sb.length() > 0 ? " písm. " : "písm. ").append(point);
        if (law != null || year != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("zákona č. ").append(law != null ? law : "?")
              .append('/').append(year != null ? year : "?").append(" Sb.");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- attachments

    /**
     * Takes every písemnost out of {@code dmFiles} with its own bytes and metadata. The name is
     * the one the sender gave ({@code dmFileDescr}); duplicates are numbered so that extracting
     * the whole message to a directory cannot silently overwrite one attachment with another.
     */
    private static List<Node> addAttachments(Node root, Element dmDm) {
        Element dmFiles = firstDescendant(dmDm, "dmFiles");
        if (dmFiles == null) return List.of();
        List<Element> files = childrenNamed(dmFiles, "dmFile");
        if (files.isEmpty()) return List.of();

        Node folder = root.add(new Node("Písemnosti (" + files.size() + ")", "pisemnosti", true,
                "písemnosti datové zprávy (dmFiles)"));
        List<Node> out = new ArrayList<>();
        List<String> used = new ArrayList<>();
        int index = 0;
        for (Element file : files) {
            index++;
            byte[] data = attachmentBytes(file);
            String descr = attr(file, "dmFileDescr");
            String name = uniqueName(descr == null || descr.isBlank()
                    ? "pisemnost-" + index + (data != null ? ContainerInspector.guessExtension(data) : ".bin")
                    : ContainerInspector.safeFileName(descr), used);

            String metaType = attr(file, "dmFileMetaType");
            Node node = folder.add(new Node(name, "pisemnosti/" + name, false, fileRole(metaType)));
            node.attachment();
            if (data != null) node.data(data);
            node.put("Role", fileRole(metaType));
            String mime = attr(file, "dmMimeType");
            node.put("MIME typ dle odesílatele",
                    mime == null || mime.isBlank() ? "neuveden" : mime);
            node.put("Název dle odesílatele", descr);
            node.put("Identifikátor písemnosti (dmFileGuid)", blankToNull(attr(file, "dmFileGuid")));
            node.put("Nadřízená písemnost (dmUpFileGuid)", blankToNull(attr(file, "dmUpFileGuid")));
            node.put("dmFormat", blankToNull(attr(file, "dmFormat")));
            if (data != null) {
                String detected = detectedContent(data);
                if (detected != null) node.put("Zjištěný obsah", detected);
            }
            out.add(node);
        }
        return out;
    }

    /**
     * The bytes of one písemnost. A file arrives base64-encoded in {@code dmEncodedContent};
     * XML sent as XML arrives as a live element inside {@code dmXMLContent} and is serialised
     * back out, because that is the only form in which it can be saved or opened.
     */
    private static byte[] attachmentBytes(Element file) {
        Element encoded = firstDescendant(file, "dmEncodedContent");
        if (encoded != null) {
            byte[] decoded = decodeBase64(text(encoded));
            if (decoded != null) return decoded;
        }
        Element xmlContent = firstDescendant(file, "dmXMLContent");
        if (xmlContent != null) {
            NodeList kids = xmlContent.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element payload) {
                    try {
                        return ContainerInspector.serializeElement(payload);
                    } catch (Exception ignored) {
                        // Unserialisable subtree — the metadata are still worth showing.
                    }
                }
            }
        }
        return null;
    }

    private static String fileRole(String metaType) {
        if (metaType == null) return "písemnost datové zprávy";
        return switch (metaType) {
            case "main"      -> "hlavní písemnost (main)";
            case "enclosure" -> "příloha (enclosure)";
            case "signature" -> "podpis k jiné písemnosti (signature)";
            case "meta"      -> "metadata (meta)";
            default          -> "písemnost datové zprávy (" + metaType + ")";
        };
    }

    /** Names what the bytes really are, which is worth saying when the declared MIME type is empty or wrong. */
    private static String detectedContent(byte[] data) {
        return switch (ContainerInspector.detectKind(data)) {
            case PDF  -> "dokument PDF";
            case ZIP  -> "archiv ZIP (ASiC, ODF, OOXML…)";
            case CMS, ZFO -> "objekt CMS / PKCS #7";
            // Detection keys on the leading '<', which HTML shares with XML — and ISDS sends
            // plenty of HTML (DetailZpravy.html and the like), so the distinction is worth making.
            case XML  -> looksLikeHtml(data) ? "dokument HTML" : "dokument XML";
            case JSON -> "dokument JSON";
            // Certificates and CRLs land here, since neither is a container; naming them is
            // worth doing, because ISDS carries them as attachments more often than not.
            case OTHER -> {
                String der = ContainerInspector.describeDer(data);
                yield der != null ? der : ContainerInspector.looksTextual(data) ? "text" : null;
            }
        };
    }

    private static boolean looksLikeHtml(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 1024), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return head.contains("<!doctype html") || head.contains("<html");
    }

    // ---------------------------------------------------------------- delivery events

    /**
     * The delivery history a receipt carries. Rendered as one text element rather than as a
     * folder of empty nodes: the events are a chronological narrative, and reading them as one
     * is the point — while still being saveable as evidence of how the message was delivered.
     */
    private static void addEvents(Node root, Element wrapper) {
        Element events = firstDescendant(wrapper, "dmEvents");
        if (events == null) return;
        List<Element> list = childrenNamed(events, "dmEvent");
        if (list.isEmpty()) return;

        Node node = root.add(new Node("prubeh-dorucovani.txt", "prubeh-dorucovani.txt", false,
                "průběh doručování zprávy (dmEvents)"));
        node.put("Role", "průběh doručování zprávy (dmEvents)");
        StringBuilder text = new StringBuilder();
        int i = 0;
        for (Element event : list) {
            i++;
            String when = dateTime(textOf(event, "dmEventTime"));
            String what = textOf(event, "dmEventDescr");
            node.put(i + ". " + (when != null ? when : "bez času"), what != null ? what : "—");
            text.append(when != null ? when : "(bez času)").append("  ")
                .append(what != null ? what : "").append('\n');
        }
        node.data(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- hash check

    /**
     * Recomputes the envelope's hash and compares it with the one inside the qualified time-stamp.
     *
     * <p>The XML also quotes the hash in {@code dmHash}, but the ISDS operating rules say plainly
     * that this text is only a copy of the value sealed in the time-stamp and cannot itself serve
     * as proof of integrity. So the time-stamp is what the recomputed value is held against, and
     * the copy is checked separately — a copy that disagrees with the time-stamp is worth saying
     * out loud even though it proves nothing on its own.
     *
     * <p>The rules also state the hash input is <em>not</em> canonicalised in the XML-signature
     * sense: it is the raw bytes of the {@code dmDm} element. Line endings are the one exception
     * observed in practice — messages whose base64 arrived CRLF-wrapped hash to the value that
     * XML line-ending normalisation produces, so that form is tried as well and the reader is
     * told which of the two matched.
     */
    private static String checkHash(Element wrapper, byte[] content, int[] dmDmRange,
                                    TimeStampTokenInfo tsInfo,
                                    Flavour flavour, List<String> warnings) {
        Element hashElement = firstDescendant(wrapper, "dmHash");
        byte[] quoted = hashElement == null ? null : decodeBase64(text(hashElement));
        byte[] sealed = tsInfo == null ? null : tsInfo.getMessageImprintDigest();

        if (quoted != null && sealed != null && !MessageDigest.isEqual(quoted, sealed)) {
            warnings.add("Otisk uvedený v obálce zprávy (dmHash) se liší od otisku zapečetěného "
                    + "v kvalifikovaném elektronickém časovém razítku. Průkazná je hodnota v razítku.");
        }
        if (sealed == null) {
            return quoted == null ? null : "nelze provést — chybí čitelné časové razítko";
        }
        if (flavour != Flavour.MESSAGE) {
            return "nelze přepočítat — otisk se vztahuje k celé zprávě včetně písemností, "
                    + "které tento soubor neobsahuje";
        }
        if (dmDmRange == null) {
            return "nelze provést — obálku zprávy se nepodařilo v podepsaném XML vymezit";
        }

        String algorithm = digestName(tsInfo.getMessageImprintAlgOID().getId());
        byte[] direct = digest(content, dmDmRange[0], dmDmRange[1] - dmDmRange[0], algorithm);
        if (direct != null && MessageDigest.isEqual(direct, sealed)) {
            return algorithm + " — souhlasí s otiskem v kvalifikovaném elektronickém časovém razítku ✔";
        }
        byte[] relaxed = normalizeLineEndings(content, dmDmRange[0], dmDmRange[1]);
        byte[] normalized = digest(relaxed, 0, relaxed.length, algorithm);
        if (normalized != null && MessageDigest.isEqual(normalized, sealed)) {
            return algorithm + " — souhlasí s otiskem v kvalifikovaném elektronickém časovém razítku ✔ "
                    + "(po normalizaci konců řádků podle XML)";
        }
        warnings.add("Otisk přepočítaný z obálky zprávy se neshoduje s otiskem v kvalifikovaném "
                + "elektronickém časovém razítku — obsah zprávy neodpovídá tomu, co ISDS orazítkoval, "
                + "nebo byl soubor po stažení upraven.");
        return algorithm + " — NESOUHLASÍ s otiskem v kvalifikovaném elektronickém časovém razítku ✘";
    }

    /** CRLF and lone CR become LF, which is what an XML parser does before anything sees the text. */
    private static byte[] normalizeLineEndings(byte[] data, int from, int to) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(to - from);
        for (int i = from; i < to; i++) {
            if (data[i] == '\r') {
                out.write('\n');
                if (i + 1 < to && data[i + 1] == '\n') i++;
            } else {
                out.write(data[i]);
            }
        }
        return out.toByteArray();
    }

    /**
     * Finds where one element starts and ends in the document, as {@code {from, to}} byte offsets.
     *
     * <p>The hash is taken over the bytes as they stand, so re-serialising the parsed DOM would
     * not do: it would normalise attribute order, quoting and empty elements, and the result
     * would hash to something the time-stamp never saw. ISO-8859-1 maps every byte to exactly one
     * char, so offsets found in the decoded string are byte offsets in the original.
     */
    private static int[] locateElement(byte[] xml, Pattern open, String localName) {
        String text = new String(xml, StandardCharsets.ISO_8859_1);
        Matcher m = open.matcher(text);
        if (!m.find()) return null;
        String prefix = m.group(1) == null ? "" : m.group(1);
        String closeTag = "</" + prefix + localName + ">";
        int end = text.indexOf(closeTag, m.start());
        if (end < 0) return null;
        return new int[]{m.start(), end + closeTag.length()};
    }

    /** The TSA's name without the {@code GeneralName} tag number {@code toString()} prefixes it with. */
    private static String tsaName(TimeStampTokenInfo info) {
        org.bouncycastle.asn1.x509.GeneralName tsa = info.getTsa();
        if (tsa.getTagNo() == org.bouncycastle.asn1.x509.GeneralName.directoryName) {
            return String.valueOf(org.bouncycastle.asn1.x500.X500Name.getInstance(tsa.getName()));
        }
        return String.valueOf(tsa.getName());
    }

    // ---------------------------------------------------------------- code books

    /**
     * The message state, worded and filtered for the reader who has the file in front of them —
     * or {@code null} when it is better left unsaid.
     *
     * <p>A state is what ISDS currently records about the message, and most of what it records
     * is about the reader's own handling of it: on a received message it says the reader logged
     * in and downloaded it, which they know, and dresses that up as a conclusion about delivery.
     * That conclusion carries the same caveat as the delivery time — the system draws it from its
     * own records alone — so on a received message it is not shown at all.
     *
     * <p>On a sent message the question is a real one, because the sender cannot otherwise see
     * what became of the message, and three answers matter: delivered by login, delivered by the
     * statutory fiction after ten days, or merely deposited in the recipient's box so far. Those
     * are stated in words rather than as a code.
     *
     * <p>State 7 says the recipient opened the message, which by the course ISDS puts a message
     * through can only happen after state 6 — so it means delivered by login just as 6 does, and
     * reads the same. The remaining states say something other than "delivered": failed antivirus,
     * undeliverable, erased, moved to the vault. They are left out rather than invited to be read
     * as an answer to a question they do not address.
     *
     * <p>A delivery receipt keeps the full code book: it exists to document delivery, and it
     * spells the whole course out in its events besides.
     */
    private static String messageStatusToShow(String code, ContainerInspector.IsdsArtefact artefact) {
        if (code == null) return null;
        if (artefact.kind() != ContainerInspector.IsdsKind.MESSAGE) {
            return code + " — " + messageStatus(code);
        }
        if (artefact.direction() != ContainerInspector.IsdsDirection.SENT) return null;
        return switch (code.trim()) {
            case "6", "7" -> "Zpráva byla doručena přihlášením";
            case "5"      -> "Zpráva byla doručena fikcí";
            case "4"      -> "Zpráva byla dodána";
            default       -> null;
        };
    }

    /** Message states, ISDS operating rules ch. 1.5. */
    private static String messageStatus(String code) {
        return switch (code.trim()) {
            case "1" -> "Datová zpráva byla podána (vznikla v ISDS)";
            case "2" -> "Datová zpráva včetně písemností podepsána podacím časovým razítkem";
            case "3" -> "Datová zpráva neprošla antivirovou kontrolou — nebyla dodána";
            case "4" -> "Datová zpráva je dodána do schránky adresáta";
            case "5" -> "Uplynulo 10 dnů od dodání — doručena fikcí";
            case "6" -> "Doručena přihlášením oprávněné osoby";
            case "7" -> "Zpráva byla přečtena (stažena) příjemcem";
            case "8" -> "Zpráva označena jako nedoručitelná (schránka adresáta znepřístupněna)";
            case "9" -> "Obsah zprávy smazán, obálka přesunuta do archivu";
            case "10" -> "Zpráva přesunuta do Datového trezoru";
            default -> "neznámý stav";
        };
    }

    /** Data box types, ISDS service {@code FindDataBox2}. */
    private static String boxType(String code) {
        return switch (code.trim()) {
            case "0"  -> "systémová schránka ISDS";
            case "10" -> "OVM — orgán veřejné moci";
            case "13" -> "OVM_REQ — podřízená schránka OVM zřízená na žádost";
            case "14" -> "OVM_FO — fyzická osoba v roli orgánu veřejné moci";
            case "15" -> "OVM_PFO — podnikající fyzická osoba v roli orgánu veřejné moci";
            case "16" -> "OVM_PO — právnická osoba zapsaná do rejstříku OVM";
            case "20" -> "PO — právnická osoba";
            case "22" -> "PO_REQ — právnická osoba, schránka zřízená na žádost";
            case "30" -> "PFO — podnikající fyzická osoba";
            case "31" -> "PFO_ADVOK — advokát";
            case "32" -> "PFO_DANPOR — daňový poradce";
            case "34" -> "PFO_AUDITOR — statutární auditor";
            case "35" -> "PFO_ZNALEC — znalec";
            case "36" -> "PFO_TLUMOCNIK — soudní tlumočník nebo překladatel";
            case "37" -> "PFO_ARCH — architekt";
            case "38" -> "PFO_AIAT — autorizovaný inženýr nebo technik ve výstavbě";
            case "39" -> "PFO_AZI — autorizovaný zeměměřický inženýr";
            case "40" -> "FO — fyzická osoba";
            case "50" -> "PFO_REQ — podnikající fyzická osoba, schránka na žádost";
            default -> "neznámý typ schránky";
        };
    }

    /** Values of the {@code dmType} attribute as ISDS returns them (dmBaseTypes.xsd, tDmType). */
    private static String messageType(String code) {
        return switch (code.trim()) {
            case "K" -> "smluvní poštovní datová zpráva";
            case "O" -> "odpovědní poštovní datová zpráva";
            case "G" -> "globálně dotovaná poštovní datová zpráva";
            case "Z" -> "omezeně dotovaná poštovní datová zpráva";
            case "D" -> "externě dotovaná poštovní datová zpráva";
            case "E" -> "elektronická poštovní zpráva (EPZ)";
            case "I" -> "iniciační zpráva k odpovědní datové zprávě";
            case "Y" -> "iniciační zpráva k ODZ, již zkonzumovaná";
            case "X" -> "iniciační zpráva k ODZ, expirovaná";
            case "A" -> "dotovaná iniciační zpráva k ODZ";
            case "B" -> "dotovaná iniciační zpráva k ODZ, zkonzumovaná";
            case "C" -> "dotovaná iniciační zpráva k ODZ, expirovaná";
            case "V" -> "veřejná datová zpráva";
            default -> "neznámý druh";
        };
    }

    // ---------------------------------------------------------------- XML helpers

    /** First descendant with this local name, whatever namespace prefix the sender chose. */
    private static Element firstDescendant(Element from, String localName) {
        if (from == null) return null;
        if (localName.equals(ContainerInspector.localName(from))) return from;
        NodeList kids = from.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) {
                Element found = firstDescendant(child, localName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<Element> childrenNamed(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child
                    && localName.equals(ContainerInspector.localName(child))) {
                out.add(child);
            }
        }
        return out;
    }

    /** Text of the named descendant, or {@code null} when absent, nil or empty. */
    private static String textOf(Element from, String localName) {
        Element el = firstDescendant(from, localName);
        return el == null ? null : text(el);
    }

    private static String text(Element el) {
        if ("true".equals(el.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "nil"))) {
            return null;
        }
        String value = el.getTextContent();
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String attr(Element el, String name) {
        if (el == null || !el.hasAttribute(name)) return null;
        String value = el.getAttribute(name);
        return value.isEmpty() ? null : value;
    }

    // ---------------------------------------------------------------- value helpers

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }

    private static void putIfSet(Map<String, String> map, String key, String value) {
        put(map, key, value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** ISDS writes 0 where a numeric field is unused; that is not a value worth a line of its own. */
    private static String nonZero(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "0".equals(trimmed) ? null : trimmed;
    }

    private static String yesNo(String value) {
        if (value == null) return null;
        return "true".equalsIgnoreCase(value.trim()) ? "ano" : "ne";
    }

    /** For flags whose "false" is the ordinary case and says nothing worth a line. */
    private static String yesNoIfTrue(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim()) ? "ano" : null;
    }

    private static String allowSubst(String value) {
        if (value == null) return null;
        return "true".equalsIgnoreCase(value.trim()) ? "povoleno" : "vyloučeno";
    }

    private static String dateTime(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return cz.digitalnikontinuita.anep.core.TimeFormat.full(
                    OffsetDateTime.parse(iso.trim()).toInstant());
        } catch (Exception e) {
            return iso.trim();
        }
    }

    /**
     * Délka první struktury ASN.1 v bajtech, nebo -1, nedá-li se určit. U kódování BER
     * s neurčitou délkou ({@code 30 80}) se délka nedá odečíst z hlavičky a vrací se -1 —
     * takové razítko končí značkou konce obsahu a výplň za ním se nehledá.
     */
    private static int asn1Length(byte[] b) {
        if (b.length < 2) return -1;
        int first = b[1] & 0xff;
        if (first < 0x80) return 2 + first;
        int n = first & 0x7f;
        if (n == 0 || n > 4 || b.length < 2 + n) return -1;
        long len = 0;
        for (int i = 0; i < n; i++) len = (len << 8) | (b[2 + i] & 0xff);
        long total = 2L + n + len;
        return total <= Integer.MAX_VALUE ? (int) total : -1;
    }

    private static boolean allZero(byte[] b, int from) {
        for (int i = from; i < b.length; i++) if (b[i] != 0) return false;
        return true;
    }

    private static byte[] decodeBase64(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Base64.getMimeDecoder().decode(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] digest(byte[] data, int from, int length, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            md.update(data, from, length);
            return md.digest();
        } catch (Exception e) {
            return null;
        }
    }

    private static String digestName(String oid) {
        return switch (oid) {
            case "1.3.14.3.2.26"          -> "SHA-1";
            case "2.16.840.1.101.3.4.2.4" -> "SHA-224";
            case "2.16.840.1.101.3.4.2.1" -> "SHA-256";
            case "2.16.840.1.101.3.4.2.2" -> "SHA-384";
            case "2.16.840.1.101.3.4.2.3" -> "SHA-512";
            default -> oid;
        };
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Keeps the sender's file name but never lets two attachments claim the same one. */
    private static String uniqueName(String name, List<String> used) {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int suffix = 1;
        while (used.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = stem + " (" + (++suffix) + ")" + extension;
        }
        used.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }
}
