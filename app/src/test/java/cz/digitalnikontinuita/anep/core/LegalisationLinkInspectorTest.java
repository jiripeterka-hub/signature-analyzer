package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference eLegalizace leaves in a document points outside it: the attachment carries only
 * digests, so the link is confirmed by hashing another file and nothing else. What is asserted
 * here is the whole of that — the attachment is found and read, and the comparison says yes to
 * the referenced document and no to any other.
 *
 * <p>The digests are over the file as it lies on disk, with no canonicalisation of any kind. That
 * was measured on the sample documents before it was written down: the plain SHA-256 of
 * {@code legalizovany dokument.pdf} is the value its legalisation
 * record and both signature sheets declare.
 */
class LegalisationLinkInspectorTest {

    @TempDir
    Path dir;

    /** Test vector for SHA-256 of the four bytes {@code test}, so one check stands on its own. */
    private static final byte[] TEST_BYTES = "test".getBytes(StandardCharsets.UTF_8);
    private static final String TEST_SHA256 =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Test
    @DisplayName("Odkaz se přečte z přílohy legalisation-metadata.xml")
    void linkIsRead() throws Exception {
        byte[] target = minimalPdf();
        Path pdf = write("odkazujici.pdf", pdfWithAttachment("legalisation-metadata.xml",
                metadata("162057366-10781-240110154715", "ORIGINAL",
                        "legalizovany dokument.pdf", target)));

        LegalisationLinkInspector.Link link =
                LegalisationLinkInspector.read(pdf.toFile(), null);

        assertNotNull(link, "Příloha s odkazem nebyla v dokumentu nalezena.");
        assertEquals("legalisation-metadata.xml", link.attachmentName());
        assertEquals("162057366-10781-240110154715", link.guid());
        assertEquals("ORIGINAL", link.type());
        assertEquals("legalizovany dokument.pdf", link.targetName());
        assertEquals(4, link.digests().size(), "Nepřečetly se všechny uvedené otisky.");
    }

    @Test
    @DisplayName("Odkazovaný dokument otisky potvrdí, jiný ne")
    void referencedDocumentMatches() throws Exception {
        byte[] target = minimalPdf();
        Path pdf = write("odkazujici.pdf", pdfWithAttachment("legalisation-metadata.xml",
                metadata("1347834414-240119153736", "ARCH", null, target)));
        Path referenced = write("odkazovany.pdf", target);
        // Jiný dokument: stejný tvar, jiný obsah — otisky se musejí rozejít u všech funkcí.
        Path other = write("jiny.pdf", minimalPdf("Jiny obsah"));

        LegalisationLinkInspector.Link link = LegalisationLinkInspector.read(pdf.toFile(), null);
        LegalisationLinkInspector.Check ok =
                LegalisationLinkInspector.verify(link, referenced.toFile());
        LegalisationLinkInspector.Check no =
                LegalisationLinkInspector.verify(link, other.toFile());

        assertEquals(LegalisationLinkInspector.Outcome.MATCH, ok.outcome());
        assertEquals(4, ok.digests().size());
        assertTrue(ok.digests().stream().allMatch(d -> d.supported() && d.matches()),
                "Některý otisk odkazovaného dokumentu nesouhlasil.");
        assertEquals(LegalisationLinkInspector.Outcome.MISMATCH, no.outcome());
        assertTrue(no.digests().stream().noneMatch(
                LegalisationLinkInspector.DigestCheck::matches),
                "Jiný dokument prošel na některý z otisků.");
    }

    @Test
    @DisplayName("Otisk se počítá z celého souboru, jak leží na disku")
    void digestIsOverTheWholeFile() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <info>
                    <guid>1-2</guid>
                    <hashes>
                        <hash function="SHA-256">%s</hash>
                    </hashes>
                    <type>ARCH</type>
                </info>
                """.formatted(TEST_SHA256);
        Path pdf = write("odkazujici.pdf",
                pdfWithAttachment("legalisation-metadata.xml", xml.getBytes(StandardCharsets.UTF_8)));
        Path referenced = write("ctyri-bajty.bin", TEST_BYTES);

        LegalisationLinkInspector.Check check = LegalisationLinkInspector.verify(
                LegalisationLinkInspector.read(pdf.toFile(), null), referenced.toFile());

        assertEquals(LegalisationLinkInspector.Outcome.MATCH, check.outcome(),
                "Otisk se nepočítá ze syrových bajtů souboru.");
    }

    @Test
    @DisplayName("Neznámá hashovací funkce se vykáže, ale ověření nezhatí")
    void unknownFunctionIsReportedNotFatal() throws Exception {
        byte[] target = minimalPdf();
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <info>
                    <guid>1-2</guid>
                    <hashes>
                        <hash function="SHA-256">%s</hash>
                        <hash function="SHA4-256">00</hash>
                    </hashes>
                    <type>ARCH</type>
                </info>
                """.formatted(hex("SHA-256", target));
        Path pdf = write("odkazujici.pdf",
                pdfWithAttachment("legalisation-metadata.xml", xml.getBytes(StandardCharsets.UTF_8)));
        Path referenced = write("odkazovany.pdf", target);

        LegalisationLinkInspector.Check check = LegalisationLinkInspector.verify(
                LegalisationLinkInspector.read(pdf.toFile(), null), referenced.toFile());

        assertEquals(LegalisationLinkInspector.Outcome.MATCH, check.outcome());
        assertEquals(1, check.digests().stream().filter(d -> !d.supported()).count(),
                "Neznámá funkce se v rozpisu neobjevila.");
    }

    @Test
    @DisplayName("Dokument bez přílohy žádný odkaz nehlásí")
    void documentWithoutAttachment() throws Exception {
        Path pdf = write("prosty.pdf", minimalPdf());
        assertNull(LegalisationLinkInspector.read(pdf.toFile(), null));
    }

    @Test
    @DisplayName("Jiná příloha než odkaz se za odkaz nevydává")
    void unrelatedAttachmentIsNotALink() throws Exception {
        Path pdf = write("s-prilohou.pdf", pdfWithAttachment("faktura.xml",
                "<invoice><total>100</total></invoice>".getBytes(StandardCharsets.UTF_8)));
        assertNull(LegalisationLinkInspector.read(pdf.toFile(), null));
    }

    // ---------------------------------------------------------------- fixtures

    /** The attachment as the CzechPOINT documents write it: four functions over the same bytes. */
    private static byte[] metadata(String guid, String type, String name, byte[] target)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<info>\n    <guid>").append(guid).append("</guid>\n    <hashes>\n");
        for (String function : new String[]{ "SHA-256", "SHA-512", "SHA3-256", "SHA3-512" }) {
            sb.append("        <hash function=\"").append(function).append("\">")
              .append(hex(function, target)).append("</hash>\n");
        }
        sb.append("    </hashes>\n");
        if (name != null) sb.append("    <name>").append(name).append("</name>\n");
        sb.append("    <type>").append(type).append("</type>\n</info>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(String function, byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance(function).digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] pdfWithAttachment(String name, byte[] content) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());

            PDEmbeddedFile embedded = new PDEmbeddedFile(doc, new ByteArrayInputStream(content));
            embedded.setSubtype("text/xml");
            embedded.setSize(content.length);

            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile(name);
            spec.setFileUnicode(name);
            spec.setEmbeddedFile(embedded);
            spec.setEmbeddedFileUnicode(embedded);

            Map<String, PDComplexFileSpecification> names = new LinkedHashMap<>();
            names.put(name, spec);
            org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode tree =
                    new org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode();
            tree.setNames(names);

            PDDocumentNameDictionary dictionary =
                    new PDDocumentNameDictionary(doc.getDocumentCatalog());
            dictionary.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(dictionary);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] minimalPdf() throws Exception {
        return minimalPdf(null);
    }

    /** A PDF whose bytes differ from another one's — the marker rides in the document title. */
    private static byte[] minimalPdf(String marker) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            if (marker != null) doc.getDocumentInformation().setTitle(marker);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private Path write(String name, byte[] bytes) throws Exception {
        Path path = dir.resolve(name);
        Files.write(path, bytes);
        return path;
    }
}
