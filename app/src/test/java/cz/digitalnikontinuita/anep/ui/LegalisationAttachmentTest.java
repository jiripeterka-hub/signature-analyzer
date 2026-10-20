package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Check;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Digest;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Link;
import cz.digitalnikontinuita.anep.core.LegalisationLinkInspector.Outcome;
import cz.digitalnikontinuita.anep.core.LegalisationLinkLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Co všechno se přiloží do protokolu, když si uživatel vyžádá vložení ověřovaného souboru.
 *
 * <p>Legalizační záznam sám o sobě není vším: odkazuje otisky na dokument, který v něm není.
 * Protokol s ním má proto nést i ten odkazovaný dokument — ale jen tehdy, když ho někdo předložil
 * a otisky souhlasily. Dokument za neshodou do řetězu nepatří: ten, kdo na něj odkazoval, se
 * neprokázal jako ten, na koho odkazoval ověřovaný soubor.
 */
class LegalisationAttachmentTest {

    private static final Link LINK = new Link("legalisation-metadata.xml", "guid", "ORIGINAL",
            null, List.of(new Digest("SHA-256", "abcd")));

    private static Check vysledek(Outcome outcome) {
        return new Check(outcome, List.of(), null);
    }

    @BeforeEach
    void clean() {
        LegalisationLinkLog.clear();
    }

    @Test
    @DisplayName("Bez kontroly se přiloží jen ověřovaný soubor")
    void withoutAnyCheckOnlyTheVerifiedFile(@TempDir Path dir) throws Exception {
        File source = soubor(dir, "zaznam.pdf");
        var files = PrintUtil.attachments(source, null);
        assertEquals(1, files.size(), files.toString());
    }

    @Test
    @DisplayName("Potvrzený řetěz se přiloží celý, v pořadí odkazů")
    void theConfirmedChainIsAttached(@TempDir Path dir) throws Exception {
        File source = soubor(dir, "zaznam.pdf");
        File prvni = soubor(dir, "legalizovany.pdf");
        File druhy = soubor(dir, "dalsi.pdf");

        LegalisationLinkLog.record(source, LINK, prvni, vysledek(Outcome.MATCH), LINK);
        LegalisationLinkLog.record(prvni, LINK, druhy, vysledek(Outcome.MATCH), null);

        var files = PrintUtil.attachments(source, null);
        assertEquals(3, files.size(), files.toString());
        assertEquals("zaznam.pdf", files.get(0).file().getName());
        assertEquals("legalizovany.pdf", files.get(1).file().getName());
        assertEquals("dalsi.pdf", files.get(2).file().getName());
        assertTrue(files.get(1).description().contains("ověřovaný soubor"),
                files.get(1).description());
        assertTrue(files.get(2).description().contains("legalizovany.pdf"),
                "druhý článek má říct, odkud se na něj odkazuje: " + files.get(2).description());
    }

    @Test
    @DisplayName("Za neshodou se řetěz už nepřikládá")
    void aMismatchEndsTheChain(@TempDir Path dir) throws Exception {
        File source = soubor(dir, "zaznam.pdf");
        File jiny = soubor(dir, "jiny.pdf");
        File dalsi = soubor(dir, "dalsi.pdf");

        LegalisationLinkLog.record(source, LINK, jiny, vysledek(Outcome.MISMATCH), LINK);
        LegalisationLinkLog.record(jiny, LINK, dalsi, vysledek(Outcome.MATCH), null);

        var files = PrintUtil.attachments(source, null);
        assertEquals(1, files.size(),
                "vazba za neshodou nebyla prokázána, do protokolu nepatří: " + files);
    }

    @Test
    @DisplayName("Odloučený podpis a řetěz se do protokolu vejdou vedle sebe")
    void detachedContentAndChainCoexist(@TempDir Path dir) throws Exception {
        File source = soubor(dir, "podpis.p7s");
        File obsah = soubor(dir, "obsah.txt");
        File odkazovany = soubor(dir, "legalizovany.pdf");

        LegalisationLinkLog.record(source, LINK, odkazovany, vysledek(Outcome.MATCH), null);

        var files = PrintUtil.attachments(source, obsah);
        assertEquals(3, files.size(), files.toString());
        assertEquals("obsah.txt", files.get(1).file().getName(),
                "podepsaný obsah patří hned za podpis, teprve pak odkazované dokumenty");
        assertEquals("legalizovany.pdf", files.get(2).file().getName());
    }

    private static File soubor(Path dir, String name) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, name);
        return p.toFile();
    }
}
