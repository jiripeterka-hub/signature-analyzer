package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecentFilesTest {

    private static RecentFiles.Entry e(String doc, String ext) {
        return new RecentFiles.Entry(new File(doc), ext == null ? null : new File(ext));
    }

    @Test
    void newestFirstAndRepeatedPairMovesToTop() {
        List<RecentFiles.Entry> list = new ArrayList<>();
        list = RecentFiles.add(list, e("a.pdf", null));
        list = RecentFiles.add(list, e("b.pdf", null));
        list = RecentFiles.add(list, e("a.pdf", null));
        assertEquals(List.of(e("a.pdf", null), e("b.pdf", null)), list);
    }

    @Test
    void sameDocumentWithDifferentExternalFileIsAnotherEntry() {
        List<RecentFiles.Entry> list = new ArrayList<>();
        list = RecentFiles.add(list, e("obsah.txt", "podpis1.p7s"));
        list = RecentFiles.add(list, e("obsah.txt", "podpis2.p7s"));
        list = RecentFiles.add(list, e("obsah.txt", null));
        assertEquals(3, list.size());
    }

    @Test
    void keepsAtMostTen() {
        List<RecentFiles.Entry> list = new ArrayList<>();
        for (int i = 0; i < 15; i++) list = RecentFiles.add(list, e(i + ".pdf", null));
        assertEquals(RecentFiles.LIMIT, list.size());
        assertEquals(e("14.pdf", null), list.get(0));
        assertEquals(e("5.pdf", null), list.get(RecentFiles.LIMIT - 1));
    }

    @Test
    void roundTripThroughFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("recent-files.properties");
        List<RecentFiles.Entry> list = List.of(
                e("C:\\data\\dokument s mezerou.pdf", null),
                e("C:\\data\\obsah.txt", "C:\\data\\obsah.txt.p7s"));
        RecentFiles.save(file, list);
        List<RecentFiles.Entry> back = RecentFiles.load(file);
        assertEquals(list, back);
        assertNull(back.get(0).external());
    }

    @Test
    void missingFileGivesEmptyList(@TempDir Path dir) {
        assertEquals(List.of(), RecentFiles.load(dir.resolve("nic.properties")));
    }

    @Test
    void onlyOwnTemporaryFilesAreSkipped(@TempDir Path tmp) {
        assertEquals(true, RecentFiles.isTemporary(
                tmp.resolve("anep-prvek-123").resolve("priloha.pdf").toFile(), tmp));
        assertEquals(false, RecentFiles.isTemporary(
                tmp.resolve("OneNote").resolve("16.0").resolve("Exported").resolve("x.pdf").toFile(), tmp));
        assertEquals(false, RecentFiles.isTemporary(new File("D:\\data\\anep-prvek-1\\a.pdf"), tmp));
    }
}
