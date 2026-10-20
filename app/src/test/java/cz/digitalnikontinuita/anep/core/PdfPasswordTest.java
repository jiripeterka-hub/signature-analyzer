package cz.digitalnikontinuita.anep.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kdy si AnEP smí říct o heslo k dokumentu PDF.
 *
 * <p>PDF zná dvě hesla: uživatelské otevírá dokument, vlastnické omezuje, co se s ním smí dělat.
 * Nejběžnější podoba ochrany — ta, kterou Acrobat hlásí jako „Zabezpečení heslem“ se zákazy tisku,
 * úprav a podepisování — má uživatelské heslo <b>prázdné</b>: soubor se otevře komukoli.
 *
 * <p>Do 15. 9. 2026 se AnEP ptal na přítomnost {@code /Encrypt} a nad každým takovým souborem
 * vyskočil dialogem dřív, než se ho pokusil otevřít. Nález uživatele nad třemi zprávami z ISDS:
 * všechny tři se otevřou prázdným heslem, žádný podpis nenesou, a přesto se čtenář k té odpovědi
 * nedostal — heslo, které po něm program chtěl, nezná nikdo kromě autora dokumentu.
 */
class PdfPasswordTest {

    /** Jednostránkové PDF, které se teprve zamkne. */
    private static PDDocument blank() {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage());
        return doc;
    }

    private static File protect(Path dir, String name, String owner, String user)
            throws Exception {
        AccessPermission permissions = new AccessPermission();
        permissions.setCanModify(false);
        File out = dir.resolve(name).toFile();
        try (PDDocument doc = blank()) {
            doc.protect(new StandardProtectionPolicy(owner, user, permissions));
            doc.save(out);
        }
        return out;
    }

    @Test
    @DisplayName("zámek jen proti změnám (prázdné uživatelské heslo) se na heslo neptá")
    void ownerPasswordOnlyOpensSilently(@TempDir Path dir) throws Exception {
        File f = protect(dir, "jen-vlastnicke.pdf", "vlastnik", "");
        assertFalse(PdfSource.needsPasswordToOpen(f),
                "dokument se otevře prázdným heslem, ptát se nemá co");
    }

    @Test
    @DisplayName("zámek proti otevření se na heslo zeptá")
    void userPasswordAsks(@TempDir Path dir) throws Exception {
        File f = protect(dir, "s-uzivatelskym.pdf", "vlastnik", "uzivatel");
        assertTrue(PdfSource.needsPasswordToOpen(f));
    }

    @Test
    @DisplayName("nešifrované PDF se na heslo neptá")
    void plainPdfDoesNotAsk(@TempDir Path dir) throws Exception {
        File out = dir.resolve("obycejne.pdf").toFile();
        try (PDDocument doc = blank()) {
            doc.save(out);
        }
        assertFalse(PdfSource.needsPasswordToOpen(out));
    }

    @Test
    @DisplayName("soubor, který PDF není, se na heslo neptá")
    void nonPdfDoesNotAsk(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("text.pdf");
        Files.write(f, "tohle PDF není, jen se tak jmenuje".getBytes(StandardCharsets.UTF_8));
        assertFalse(PdfSource.needsPasswordToOpen(f.toFile()));
    }

    @Test
    @DisplayName("poškozené PDF neposílá uživatele shánět heslo")
    void brokenPdfDoesNotAsk(@TempDir Path dir) throws Exception {
        // Jiná než heslová chyba při otevírání není důvod ptát se na heslo: poškozený soubor má
        // ohlásit svůj vlastní problém. Hlavička i /Encrypt v traileru tu jsou, obsah ne.
        Path f = dir.resolve("useknute.pdf");
        Files.write(f, ("%PDF-1.7\ntrailer\n<< /Encrypt 9 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        assertFalse(PdfSource.needsPasswordToOpen(f.toFile()));
    }

    @Test
    @DisplayName("chybějící soubor a null se na heslo neptají")
    void missingFileDoesNotAsk(@TempDir Path dir) {
        assertFalse(PdfSource.needsPasswordToOpen(null));
        assertFalse(PdfSource.needsPasswordToOpen(dir.resolve("neexistuje.pdf").toFile()));
    }
}
