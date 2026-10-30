package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Položka ZIPu nad limitem prohlížeče, jejíž velikost hlavička neuvádí.
 *
 * <p>Nález uživatele z 30. 10. 2026: program o 230 MB zabalený Průzkumníkem Windows se
 * v prohlížeči ukázal jako 128 MB a „Ověřit v AnEP“ v něm nenašlo podpis — položka byla useknutá
 * na limitu, ale vydávala se za celou. Průzkumník (stejně jako {@link ZipOutputStream} s kompresí)
 * píše velikost až do deskriptoru za data, takže se na limit přijde teprve při čtení.
 */
class ContainerZipLimitTest {

    @Test
    @DisplayName("useknutá položka nenese bajty, nese skutečnou velikost a upozornění")
    void oversizedEntryWithoutDeclaredSize() throws Exception {
        long size = 129L * 1024 * 1024;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("velky.bin"));
            byte[] chunk = new byte[1 << 20];
            for (long w = 0; w < size; w += chunk.length) zip.write(chunk);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("maly.txt"));
            zip.write("ahoj".getBytes());
            zip.closeEntry();
        }
        ContainerInspector.Result r = ContainerInspector.inspect(out.toByteArray(), "archiv.zip");
        ContainerInspector.Node big = r.root().getChildren().stream()
                .filter(n -> n.getName().equals("velky.bin")).findFirst().orElseThrow();
        assertNull(big.getData(), "useknutý začátek se nesmí vydávat za položku");
        assertEquals(size, big.getSize());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("Položka velky.bin je větší")),
                r.warnings().toString());
        ContainerInspector.Node small = r.root().getChildren().stream()
                .filter(n -> n.getName().equals("maly.txt")).findFirst().orElseThrow();
        assertEquals(4, small.getData().length, "další položky se čtou dál");
    }
}
