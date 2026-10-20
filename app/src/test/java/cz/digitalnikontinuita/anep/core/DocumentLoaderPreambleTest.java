package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the narrow licence {@link DocumentLoader} takes with a PDF whose {@code %PDF-} header is
 * not at byte zero.
 *
 * <p>DSS picks the validator from the opening bytes, so such a file was reported as an unsupported
 * format although every reader opens it. Cutting the preamble fixes that — and would break three
 * other things if it were done on the header's position alone. A ZIP that is not an ASiC container
 * is a format DSS declines, and one in the sample corpus carries a stored PDF sixty bytes in; an
 * enveloping CAdES puts the signed PDF just past its DER header. Sixteen files in the two corpora
 * have the header away from zero and not one of them may be touched, so the rule is only allowed
 * to fire where nothing else accounts for the bytes.
 */
class DocumentLoaderPreambleTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Bajt před hlavičkou PDF se odřízne a je vykázán")
    void strayPrefixIsTrimmed() throws Exception {
        Path f = write("stray.pdf", concat(new byte[]{'\n'}, minimalPdf()));

        DocumentLoader.Loaded loaded = new DocumentLoader().loadWithDetail(f.toFile());

        assertEquals(1, loaded.strayPrefixBytes(),
                "Bajt před hlavičkou zůstal na místě — DSS soubor odmítne jako neznámý formát.");
        assertEquals('%', loaded.document().openStream().read(),
                "Dokument nezačíná hlavičkou PDF, ačkoli byl označen za oříznutý.");
    }

    @Test
    @DisplayName("Soubor s hlavičkou na nule zůstává beze změny")
    void wellFormedPdfIsUntouched() throws Exception {
        Path f = write("plain.pdf", minimalPdf());

        assertEquals(0, new DocumentLoader().loadWithDetail(f.toFile()).strayPrefixBytes());
    }

    @Test
    @DisplayName("ZIP, jehož první položkou je PDF, se neořezává")
    void zipCarryingPdfIsUntouched() throws Exception {
        // "PK\3\4" plus a local file header long enough to push the PDF into the search window —
        // the shape of zasilka-RCZS4WSYE6EYS8PP.zip, where the header sits at offset 60.
        byte[] zipish = concat("PK".getBytes(StandardCharsets.ISO_8859_1),
                concat(new byte[56], minimalPdf()));
        Path f = write("zasilka.zip", zipish);

        assertEquals(0, new DocumentLoader().loadWithDetail(f.toFile()).strayPrefixBytes(),
                "Archiv byl rozřezán na PDF — z nečitelného kontejneru se stal zmrzačený dokument.");
    }

    @Test
    @DisplayName("Obalující CAdES si ponechá svou DER hlavičku")
    void derStructureCarryingPdfIsUntouched() throws Exception {
        byte[] derish = concat(new byte[]{0x30, (byte) 0x82, 0x01, 0x00},
                concat(new byte[56], minimalPdf()));
        Path f = write("dokument.pdf.p7m", derish);

        assertEquals(0, new DocumentLoader().loadWithDetail(f.toFile()).strayPrefixBytes(),
                "Podepsaný obsah byl vyříznut z CAdES — podpis kolem něj by zmizel.");
    }

    // -------------------------------------------------------------------------

    /** Enough of a PDF to be recognised by its header; nothing here parses it. */
    private static byte[] minimalPdf() {
        return ("%PDF-1.4\n1 0 obj\n<</Type /Catalog>>\nendobj\ntrailer\n<</Root 1 0 R>>\n"
                + "startxref\n0\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private Path write(String name, byte[] content) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, content);
        return p;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }
}
