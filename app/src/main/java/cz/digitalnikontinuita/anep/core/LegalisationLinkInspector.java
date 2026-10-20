package cz.digitalnikontinuita.anep.core;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the reference to another document that eLegalizace at CzechPOINT leaves in a PDF, and
 * checks a candidate file against it.
 * <p>
 * The reference is an attachment named {@code legalisation-metadata.xml} carrying nothing but
 * digests — the referenced document itself is not in the file and never is. So the link can only
 * be confirmed by putting the other document next to it, which is what {@link #verify} does.
 * <p>
 * Several digests are declared, one per hash function (SHA-256, SHA-512, SHA3-256, SHA3-512 in
 * the documents seen so far). All of them are checked, not the first that can be computed: they
 * cover the same bytes, so any disagreement between them is itself worth showing. The digests are
 * taken over the whole file as it lies on disk — measured on a legalised document and its
 * signature sheet, where the plain SHA-256 of the file matched the declared value exactly.
 */
public final class LegalisationLinkInspector {

    private LegalisationLinkInspector() { }

    /** One declared digest: the function as the XML spells it, and the expected value in hex. */
    public record Digest(String function, String value) { }

    /**
     * What the attachment says. {@code targetName} and {@code type} are optional — the signature
     * sheets carry a type but no name, the legalisation record carries both.
     */
    public record Link(String attachmentName, String guid, String type, String targetName,
                       List<Digest> digests) { }

    /** How one declared digest came out. {@code actual} is null when the function is unknown. */
    public record DigestCheck(String function, String expected, String actual,
                              boolean supported, boolean matches) { }

    public enum Outcome {
        /** Every digest that could be computed agrees with the declared value. */
        MATCH,
        /** At least one computed digest differs — this is not the referenced document. */
        MISMATCH,
        /** None of the declared functions is available, so nothing was actually compared. */
        UNSUPPORTED,
        /** The candidate could not be read. */
        ERROR
    }

    public record Check(Outcome outcome, List<DigestCheck> digests, String error) { }

    // ---------------------------------------------------------------- reading

    /** The link in this PDF, or null when it carries none. Never throws. */
    public static Link read(File pdf, char[] password) {
        if (pdf == null || !pdf.isFile()) return null;
        return read(PdfSource.of(pdf), password);
    }

    /** The link in this PDF, or null when it carries none. Never throws. */
    public static Link read(PdfSource source, char[] password) {
        if (source == null || !source.isPdf()) return null;
        List<PdfAttachmentInspector.Embedded> attachments;
        try {
            attachments = PdfAttachmentInspector.embeddedFiles(source, password);
        } catch (Exception e) {
            return null;
        }
        // The name first: it is what the format prescribes and what tells one attachment from
        // another when a document carries several.
        for (PdfAttachmentInspector.Embedded a : attachments) {
            if (isLegalisationName(a.name())) {
                Link link = parse(a.name(), a.data());
                if (link != null) return link;
            }
        }
        // A renamed attachment is still the same reference. The shape is distinctive enough to
        // recognise on its own — an <info> root with a <guid> and digests keyed by function —
        // so a document whose attachment lost its name is not left unread.
        for (PdfAttachmentInspector.Embedded a : attachments) {
            if (isLegalisationName(a.name())) continue;
            Link link = parse(a.name(), a.data());
            if (link != null && link.guid() != null) return link;
        }
        return null;
    }

    /** True for {@code legalisation-metadata.xml} and the name-tree key {@code legalisation metadata}. */
    private static boolean isLegalisationName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        // Both spellings: the Czech implementation writes the British one, but a name is no place
        // to be strict about that.
        return (n.contains("legalisationmetadata") || n.contains("legalizationmetadata"));
    }

    /** Parses the attachment. Returns null for anything that is not this kind of reference. */
    private static Link parse(String attachmentName, byte[] xml) {
        if (xml == null || xml.length == 0) return null;
        try {
            org.w3c.dom.Document doc = ContainerInspector.parseXml(xml);
            Element root = doc.getDocumentElement();
            if (root == null || !"info".equalsIgnoreCase(root.getLocalName() != null
                    ? root.getLocalName() : root.getNodeName())) {
                return null;
            }
            List<Digest> digests = new ArrayList<>();
            NodeList hashes = root.getElementsByTagNameNS("*", "hash");
            for (int i = 0; i < hashes.getLength(); i++) {
                Node n = hashes.item(i);
                if (!(n instanceof Element e)) continue;
                String function = e.getAttribute("function");
                String value = text(e);
                if (function == null || function.isBlank() || value == null || value.isBlank()) continue;
                digests.add(new Digest(function.trim(), value.trim()));
            }
            if (digests.isEmpty()) return null;
            return new Link(attachmentName, child(root, "guid"), child(root, "type"),
                    child(root, "name"), List.copyOf(digests));
        } catch (Exception e) {
            // Not our XML, or not XML at all — either way there is no reference to report.
            return null;
        }
    }

    private static String child(Element root, String name) {
        NodeList list = root.getElementsByTagNameNS("*", name);
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e) {
                String value = text(e);
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    private static String text(Element e) {
        String value = e.getTextContent();
        return value == null ? null : value.trim();
    }

    // ---------------------------------------------------------------- checking

    /**
     * Hashes {@code candidate} with every function the link declares and compares the results.
     * <p>
     * One pass over the file feeds all the digests, because these documents live in synced
     * folders where reading one four times over is four times the wait.
     */
    public static Check verify(Link link, File candidate) {
        if (link == null || candidate == null || !candidate.isFile()) {
            return new Check(Outcome.ERROR, List.of(), "Soubor se nepodařilo přečíst.");
        }
        Map<String, MessageDigest> engines = new LinkedHashMap<>();
        for (Digest d : link.digests()) {
            String key = normalise(d.function());
            if (engines.containsKey(key)) continue;
            MessageDigest md = engine(key);
            if (md != null) engines.put(key, md);
        }
        if (engines.isEmpty()) {
            List<DigestCheck> unsupported = new ArrayList<>();
            for (Digest d : link.digests()) {
                unsupported.add(new DigestCheck(d.function(), d.value(), null, false, false));
            }
            return new Check(Outcome.UNSUPPORTED, List.copyOf(unsupported), null);
        }

        try (InputStream in = new BufferedInputStream(new FileInputStream(candidate), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (MessageDigest md : engines.values()) md.update(buffer, 0, read);
            }
        } catch (Exception e) {
            String message = e.getMessage();
            return new Check(Outcome.ERROR, List.of(),
                    message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
        }

        Map<String, String> computed = new LinkedHashMap<>();
        for (Map.Entry<String, MessageDigest> e : engines.entrySet()) {
            computed.put(e.getKey(), hex(e.getValue().digest()));
        }

        List<DigestCheck> checks = new ArrayList<>();
        boolean anyChecked = false;
        boolean allMatch = true;
        for (Digest d : link.digests()) {
            String actual = computed.get(normalise(d.function()));
            if (actual == null) {
                checks.add(new DigestCheck(d.function(), d.value(), null, false, false));
                continue;
            }
            boolean matches = actual.equalsIgnoreCase(d.value().replaceAll("\\s", ""));
            anyChecked = true;
            allMatch &= matches;
            checks.add(new DigestCheck(d.function(), d.value(), actual, true, matches));
        }
        Outcome outcome = !anyChecked ? Outcome.UNSUPPORTED
                : allMatch ? Outcome.MATCH : Outcome.MISMATCH;
        return new Check(outcome, List.copyOf(checks), null);
    }

    /**
     * The JDK's name for the function the XML names. The values seen are already the JDK's own
     * ({@code SHA-256}, {@code SHA3-512}), but a missing hyphen is the obvious way for another
     * implementation to write the same thing, and it costs nothing to accept it.
     */
    static String normalise(String function) {
        if (function == null) return "";
        String f = function.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_]", "");
        // Podle celého názvu, ne podle začátku: „SHA384“ začíná na „SHA3“ a dotaz na prefix
        // z něj dělal „SHA3-84“, funkci, kterou JDK nezná (opraveno 5. 10. 2026 spolu se
        // zařazením do rodiny SHA3 v Digitální kontinuitě, kde měl týž dotaz týž důsledek).
        if (f.matches("SHA3(224|256|384|512)")) f = "SHA3-" + f.substring(4);
        else if (f.matches("SHA(1|224|256|384|512)")) f = "SHA-" + f.substring(3);
        return f;
    }

    private static MessageDigest engine(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
