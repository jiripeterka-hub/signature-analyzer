package cz.digitalnikontinuita.anep.core;

import cz.digitalnikontinuita.anep.core.ContainerInspector.Kind;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Node;
import cz.digitalnikontinuita.anep.core.ContainerInspector.Result;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the files embedded in a PDF and presents them the same way {@link ContainerInspector}
 * presents an ASiC — because that is what they are: a PDF carrying attachments is a container,
 * whatever its extension says.
 * <p>
 * Attachments reach a PDF by three different routes and users do not distinguish between them:
 * the {@code /EmbeddedFiles} name tree (the "paperclip" list), file-attachment annotations
 * placed on a page, and the {@code /AF} associated-file arrays that PDF/A-3 and factur-x style
 * documents use to carry machine-readable data. All three are collected here, deduplicated by
 * content, and each node records how it got in.
 * <p>
 * Attachment bytes are extracted decrypted, so a nested inspection never needs the password again.
 */
public final class PdfAttachmentInspector {

    private PdfAttachmentInspector() {}

    /** Attachment count, or 0 when the file is not a PDF, is unreadable, or the password is wrong. */
    public static int countAttachments(PdfSource source, char[] password) {
        if (source == null || !source.isPdf()) return 0;
        try (PDDocument doc = source.open(password)) {
            return collect(doc).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** One embedded file as name and content. */
    public record Embedded(String name, byte[] data) { }

    /**
     * The embedded files as name and content, in the order the three routes below find them —
     * for callers looking for one particular attachment rather than browsing them all. Entries
     * whose data could not be read are left out: there is nothing to look at in them.
     */
    public static List<Embedded> embeddedFiles(PdfSource source, char[] password) {
        if (source == null || !source.isPdf()) return List.of();
        try (PDDocument doc = source.open(password)) {
            List<Embedded> out = new ArrayList<>();
            for (Attachment a : collect(doc)) {
                if (a.data != null && a.data.length > 0) out.add(new Embedded(a.name, a.data));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Convenience for bytes already extracted from another container — those are never encrypted. */
    public static boolean hasAttachments(byte[] pdfBytes) {
        return pdfBytes != null && ContainerInspector.detectKind(pdfBytes) == Kind.PDF
                && countAttachments(PdfSource.of(pdfBytes), null) > 0;
    }

    /**
     * Builds the browsable tree. Never throws — a PDF that cannot be opened comes back as a
     * {@link Result} carrying {@code error}, because the caller is a UI action.
     */
    public static Result inspect(byte[] pdfBytes, String fileName, char[] password) {
        Node root = new Node(fileName, "", true, null);
        Map<String, String> summary = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        try (PDDocument doc = PdfSource.of(pdfBytes).open(password)) {
            List<Attachment> attachments = collect(doc);

            summary.put("Formát", "dokument PDF s přílohami");
            summary.put("Verze PDF", String.valueOf(doc.getVersion()));
            summary.put("Počet stran", String.valueOf(doc.getNumberOfPages()));
            summary.put("Velikost souboru", ContainerInspector.formatSize(pdfBytes.length));
            summary.put("Počet příloh", String.valueOf(attachments.size()));
            if (doc.isEncrypted()) summary.put("Dokument je šifrovaný", "ano");
            if (isPortfolio(doc)) {
                summary.put("Portfolio (kolekce)", "ano — přílohy jsou hlavním obsahem dokumentu");
            }

            Node folder = root.add(new Node("Přílohy (" + attachments.size() + ")",
                    "prilohy", true, "vložené soubory"));
            int i = 0;
            for (Attachment a : attachments) {
                i++;
                String name = ContainerInspector.safeFileName(
                        a.name == null || a.name.isBlank() ? "priloha-" + i : a.name);
                Node n = folder.add(new Node(name, "prilohy/" + name, false, "příloha PDF"));
                n.data(a.data);
                n.modified(a.modified);
                n.put("Role", "příloha dokumentu PDF");
                n.put("Vloženo jako", String.join("; ", a.origins));
                n.put("Popis", a.description);
                n.put("Deklarovaný typ obsahu", a.mimeType);
                n.put("Vztah k dokumentu (AFRelationship)", relationshipHint(a.relationship));
                if (a.declaredSize > 0 && a.data != null && a.declaredSize != a.data.length) {
                    n.put("Deklarovaná velikost", ContainerInspector.formatSize(a.declaredSize)
                            + " — neodpovídá skutečné délce dat");
                }
                if (a.created != null) n.put("Vytvořeno", ContainerInspector.formatDate(a.created));
                if (a.data == null || a.data.length == 0) {
                    warnings.add("Přílohu " + name + " se nepodařilo přečíst; v dokumentu je "
                            + "uvedena, ale její data chybí nebo jsou poškozená.");
                    continue;
                }
                describeNested(n, a.data);
            }

            if (attachments.isEmpty()) {
                warnings.add("Dokument neobsahuje žádné přílohy.");
            }
            return new Result(Kind.PDF, "dokument PDF s přílohami", root, summary, warnings, null);

        } catch (Exception e) {
            summary.put("Velikost souboru", ContainerInspector.formatSize(pdfBytes.length));
            String msg = e.getMessage();
            return new Result(Kind.PDF, "dokument PDF", root, summary, warnings,
                    msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg);
        }
    }

    /** Notes what an attachment turned out to be, which is what enables opening it recursively. */
    private static void describeNested(Node node, byte[] data) {
        Kind kind = ContainerInspector.detectKind(data);
        if (kind == Kind.PDF) {
            int nested = countAttachments(PdfSource.of(data), null);
            node.put("Vlastní přílohy", nested > 0 ? "ano — " + nested : "ne");
        } else if (ContainerInspector.isContainerKind(kind)) {
            node.put("Příloha je kontejner", switch (kind) {
                case ZIP  -> "ano — archiv ZIP / ASiC";
                case CMS  -> "ano — objekt CMS / PKCS #7";
                case XML  -> "ano — dokument XML";
                case JSON -> "ano — dokument JSON";
                default   -> "ano";
            });
        }
    }

    // ---------------------------------------------------------------- collection

    /** One embedded file, merged across the routes it may have arrived by. */
    private static final class Attachment {
        String name;
        String description;
        String mimeType;
        String relationship;
        long declaredSize;
        java.util.Date created;
        java.util.Date modified;
        byte[] data;
        final Set<String> origins = new LinkedHashSet<>();
    }

    private static List<Attachment> collect(PDDocument doc) {
        Map<String, Attachment> byIdentity = new LinkedHashMap<>();
        PDDocumentCatalog catalog = doc.getDocumentCatalog();

        // 1. The /EmbeddedFiles name tree — the list a PDF reader shows in its attachment pane.
        try {
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
            PDNameTreeNode<PDComplexFileSpecification> tree = names.getEmbeddedFiles();
            if (tree != null) walkNameTree(tree, byIdentity, "seznam příloh dokumentu");
        } catch (Exception ignored) {
            // A broken name tree must not hide the attachments found by the other routes.
        }

        // 2. File-attachment annotations, which live on a page rather than in the catalog.
        for (int page = 0; page < doc.getNumberOfPages(); page++) {
            try {
                PDPage pdPage = doc.getPage(page);
                for (PDAnnotation annotation : pdPage.getAnnotations()) {
                    if (annotation instanceof PDAnnotationFileAttachment fileAnnotation
                            && fileAnnotation.getFile() instanceof PDComplexFileSpecification spec) {
                        add(byIdentity, spec, "anotace na straně " + (page + 1));
                    }
                }
            } catch (Exception ignored) {
                // Skip an unreadable page; the rest of the document is still worth listing.
            }
        }

        // 3. Associated files (/AF) — how PDF/A-3 and e-invoice profiles carry their data part.
        collectAssociatedFiles(catalog.getCOSObject(), byIdentity, "přidružený soubor dokumentu (/AF)");
        for (int page = 0; page < doc.getNumberOfPages(); page++) {
            try {
                collectAssociatedFiles(doc.getPage(page).getCOSObject(), byIdentity,
                        "přidružený soubor strany " + (page + 1) + " (/AF)");
            } catch (Exception ignored) {
                // As above.
            }
        }

        return new ArrayList<>(byIdentity.values());
    }

    private static void walkNameTree(PDNameTreeNode<PDComplexFileSpecification> node,
                                     Map<String, Attachment> out, String origin) throws Exception {
        Map<String, PDComplexFileSpecification> names = node.getNames();
        if (names != null) {
            for (Map.Entry<String, PDComplexFileSpecification> e : names.entrySet()) {
                Attachment a = add(out, e.getValue(), origin);
                if (a != null && (a.name == null || a.name.isBlank())) a.name = e.getKey();
            }
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
        if (kids != null) {
            // Kids are pages of a balanced tree, not folders — the flat list is the real structure.
            for (PDNameTreeNode<PDComplexFileSpecification> kid : kids) walkNameTree(kid, out, origin);
        }
    }

    private static void collectAssociatedFiles(COSDictionary dict, Map<String, Attachment> out,
                                               String origin) {
        if (dict == null) return;
        COSBase af = dict.getDictionaryObject(COSName.getPDFName("AF"));
        if (!(af instanceof COSArray array)) return;
        for (int i = 0; i < array.size(); i++) {
            if (array.getObject(i) instanceof COSDictionary specDict) {
                try {
                    add(out, new PDComplexFileSpecification(specDict), origin);
                } catch (Exception ignored) {
                    // Malformed entry — nothing to show for it.
                }
            }
        }
    }

    /**
     * Adds a file specification, merging it with an identical one already found by another route
     * so the same attachment is listed once, with every origin recorded.
     */
    private static Attachment add(Map<String, Attachment> out, PDComplexFileSpecification spec,
                                  String origin) {
        if (spec == null) return null;
        PDEmbeddedFile embedded = embeddedFile(spec);
        byte[] data = null;
        if (embedded != null) {
            try {
                data = embedded.toByteArray();
            } catch (Exception ignored) {
                // Keep the entry: its metadata still tells the user something is meant to be here.
            }
        }
        String name = firstNonBlank(spec.getFileUnicode(), spec.getFile(),
                spec.getFileDos(), spec.getFileMac(), spec.getFileUnix());
        String key = data != null && data.length > 0
                ? ContainerInspector.sha256Hex(data)
                : "bez-dat:" + name + ":" + System.identityHashCode(spec.getCOSObject());

        Attachment existing = out.get(key);
        if (existing != null) {
            existing.origins.add(origin);
            if (existing.name == null || existing.name.isBlank()) existing.name = name;
            return existing;
        }

        Attachment a = new Attachment();
        a.name = name;
        a.data = data;
        a.description = firstNonBlank(spec.getFileDescription());
        a.origins.add(origin);
        a.relationship = spec.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship"));
        if (embedded != null) {
            a.mimeType = firstNonBlank(embedded.getSubtype());
            a.declaredSize = embedded.getSize();
            try {
                a.created = toDate(embedded.getCreationDate());
                a.modified = toDate(embedded.getModDate());
            } catch (Exception ignored) {
                // Malformed date strings are common; they are not worth losing the attachment over.
            }
        }
        out.put(key, a);
        return a;
    }

    private static PDEmbeddedFile embeddedFile(PDComplexFileSpecification spec) {
        PDEmbeddedFile file = spec.getEmbeddedFileUnicode();
        if (file == null) file = spec.getEmbeddedFile();
        if (file == null) file = spec.getEmbeddedFileDos();
        if (file == null) file = spec.getEmbeddedFileMac();
        if (file == null) file = spec.getEmbeddedFileUnix();
        return file;
    }

    private static java.util.Date toDate(Calendar calendar) {
        return calendar == null ? null : calendar.getTime();
    }

    private static boolean isPortfolio(PDDocument doc) {
        try {
            return doc.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.getPDFName("Collection")) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Spells out what the PDF/A-3 relationship names mean; users rarely know them. */
    private static String relationshipHint(String relationship) {
        if (relationship == null || relationship.isBlank()) return null;
        String hint = switch (relationship.toLowerCase(Locale.ROOT)) {
            case "source"      -> "zdrojová data, ze kterých byl dokument vytvořen";
            case "data"        -> "strojově čitelná data odpovídající obsahu dokumentu";
            case "alternative" -> "alternativní podoba téhož obsahu";
            case "supplement"  -> "doplněk k obsahu dokumentu";
            case "encryptedpayload" -> "šifrovaný obsah dokumentu";
            case "formdata"    -> "data formuláře";
            case "schema"      -> "schéma popisující přiložená data";
            case "unspecified" -> "vztah neurčen";
            default -> null;
        };
        return hint == null ? relationship : relationship + " — " + hint;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
