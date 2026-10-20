package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vloží ověřované soubory do hotového protokolu jako přílohy.
 *
 * <h2>Proč to znamená PDF/A-3, a ne PDF/A-2</h2>
 *
 * <p>PDF/A-2 přílohy připouští jen tehdy, když je příloha sama PDF/A (ISO 19005-2, klauzule 6.8).
 * Ověřovaný soubor tuhle podmínku skoro nikdy nesplní — bývá to podepsané PDF od cizího nástroje,
 * kontejner ASiC nebo odloučený podpis. Změřeno: protokol PDF/A-2b s přílohou veraPDF odmítá
 * pravidlem 6.8-5, týž protokol vysázený jako PDF/A-3b s toutéž přílohou projde.
 *
 * <p>Není to bez ceny a proto se to uživatele ptáme: PDF/A-3 je pro validátor státního SIP
 * <b>kontejnerový</b> formát (fmt/479–481) a nese s sebou pravidla o rozbalování, kterým se
 * PDF/A-2 (fmt/476–478) vyhýbá. Protokol bez přílohy proto zůstává PDF/A-2b.
 *
 * <h2>Co PDF/A-3 u přílohy žádá</h2>
 *
 * <p>Nestačí soubor vložit do stromu {@code /Names/EmbeddedFiles}: specifikace musí nést
 * {@code /AFRelationship} a katalog pole {@code /AF}, jinak veraPDF přílohu odmítne jako
 * nepřiřazenou. Vztah je {@code Source} — protokol vypovídá o tom souboru, ten soubor je jeho
 * předmětem.
 */
public final class PdfAttachments {

    private PdfAttachments() { }

    /**
     * @param pdf   hotový protokol vysázený jako PDF/A-3b
     * @param files soubory k vložení; neexistující se mlčky přeskočí
     * @return tytéž stránky s přílohami, nebo původní bajty, když nebylo co vložit
     */
    public static byte[] attach(byte[] pdf, List<Attachment> files) throws Exception {
        List<Attachment> usable = new ArrayList<>();
        for (Attachment a : files) {
            if (a != null && a.file() != null && a.file().isFile()) usable.add(a);
        }
        if (usable.isEmpty()) return pdf;

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            Map<String, PDComplexFileSpecification> tree = new LinkedHashMap<>();
            COSArray af = new COSArray();

            for (Attachment a : usable) {
                byte[] payload = Files.readAllBytes(a.file().toPath());
                Calendar when = new GregorianCalendar();
                when.setTimeInMillis(a.file().lastModified());

                PDEmbeddedFile embedded = new PDEmbeddedFile(doc, new ByteArrayInputStream(payload));
                embedded.setSubtype(mimeType(a.file().getName()));
                embedded.setSize(payload.length);
                embedded.setCreationDate(when);
                embedded.setModDate(when);

                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                // Obojí, a se stejnou hodnotou: čtečky sahají jednou po /F a jednou po /UF podle
                // toho, jak jsou staré, a soubor pojmenovaný jen v jednom z nich se v některých
                // z nich nabídne bez jména.
                spec.setFile(a.file().getName());
                spec.setFileUnicode(a.file().getName());
                spec.setFileDescription(a.description());
                spec.setEmbeddedFile(embedded);
                spec.setEmbeddedFileUnicode(embedded);
                spec.getCOSObject().setName("AFRelationship", "Source");

                tree.put(uniqueName(tree, a.file().getName()), spec);
                af.add(spec.getCOSObject());
            }

            PDEmbeddedFilesNameTreeNode node = new PDEmbeddedFilesNameTreeNode();
            node.setNames(tree);
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
            names.setEmbeddedFiles(node);
            catalog.setNames(names);
            catalog.getCOSObject().setItem(COSName.getPDFName("AF"), af);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Jeden vkládaný soubor.
     *
     * @param file        soubor na disku
     * @param description čím ten soubor v protokolu je — čtečka to ukazuje ve výpisu příloh, a bez
     *                    toho by u odloučeného podpisu stály dva soubory vedle sebe bez vysvětlení,
     *                    který z nich je podepsaný obsah a který podpis
     */
    public record Attachment(File file, String description) { }

    /** Dva soubory téhož jména se v jednom stromě přebijí; druhý dostane pořadové číslo. */
    private static String uniqueName(Map<String, ?> taken, String name) {
        if (!taken.containsKey(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            if (!taken.containsKey(candidate)) return candidate;
        }
    }

    /**
     * Typ obsahu podle přípony. Vlastní tabulka místo {@code Files.probeContentType}: ta se ptá
     * operačního systému a na jiném stroji vrátí jinou odpověď, kdežto v archivním formátu má být
     * výsledek stejný všude.
     */
    private static String mimeType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        return switch (ext) {
            case "pdf"  -> "application/pdf";
            case "xml"  -> "application/xml";
            case "asice", "sce", "scs", "asics" -> "application/vnd.etsi.asic-e+zip";
            case "zfo"  -> "application/vnd.software602.filler.form-xml-zip";
            case "p7s", "p7m" -> "application/pkcs7-signature";
            case "txt"  -> "text/plain";
            case "html", "htm" -> "text/html";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "zip"  -> "application/zip";
            default     -> "application/octet-stream";
        };
    }
}
