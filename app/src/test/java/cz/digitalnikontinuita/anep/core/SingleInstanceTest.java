package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Předání souboru běžící instanci (22. 11. 2026): druhé spuštění předá cestu a běžící instance
 * ji vyzvedne; bez běžící instance se nepředá nic; starý požadavek se při startu zahodí.
 *
 * <p>V jedné JVM: „běžící instance“ je zámek, který drží tenhle proces — tryLock z téže JVM
 * hlásí OverlappingFileLockException, a to {@link SingleInstance#running} bere jako obsazeno.
 */
class SingleInstanceTest {

    @Test
    @DisplayName("Cesta s diakritikou a mezerami dojde běžící instanci a ta ji vyzvedne")
    void pathReachesRunningInstance(@TempDir Path dir) throws Exception {
        var received = new LinkedBlockingQueue<String>();
        assertFalse(SingleInstance.running(dir));
        assertTrue(SingleInstance.serve(dir, received::add));
        assertTrue(SingleInstance.running(dir));

        String path = "D:\\Smlouvy 2026\\Příloha č. 1 — podepsaná.pdf";
        assertTrue(SingleInstance.send(dir, path));
        assertEquals(path, received.poll(5, TimeUnit.SECONDS));

        // Druhá instance běžící instanci nepřebere.
        assertFalse(SingleInstance.serve(dir, s -> { }));
        assertTrue(SingleInstance.send(dir, "druhý"));
        assertEquals("druhý", received.poll(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Bez běžící instance se nepředá nic a požadavek nezůstane ležet")
    void noInstance(@TempDir Path dir) throws Exception {
        assertFalse(SingleInstance.send(dir, "x"));
        // Zámkový soubor po skončené instanci: zámek nikdo nedrží.
        Files.writeString(dir.resolve("instance.lock"), "");
        assertFalse(SingleInstance.send(dir, "x"));
        Path inbox = dir.resolve("inbox");
        assertTrue(!Files.exists(inbox) || Files.list(inbox).findAny().isEmpty());
    }

    @Test
    @DisplayName("Požadavek zbylý po dávném spuštění se při startu zahodí, čerstvý se vyzvedne")
    void staleRequestIsDropped(@TempDir Path dir) throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        Path old = inbox.resolve("stary.req");
        Files.writeString(old, "C:\\stary.pdf", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(3600)));
        Files.writeString(inbox.resolve("cerstvy.req"), "C:\\cerstvy.pdf", StandardCharsets.UTF_8);

        var received = new LinkedBlockingQueue<String>();
        assertTrue(SingleInstance.serve(dir, received::add));
        assertEquals("C:\\cerstvy.pdf", received.poll(5, TimeUnit.SECONDS));
        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
        assertFalse(Files.exists(old));
    }

    @Test
    @DisplayName("Za soubor se nebere přepínač; bez argumentu se nic nepředává")
    void fileArgument() {
        assertEquals("C:\\a.pdf", SingleInstance.fileArgument(List.of("--x", " C:\\a.pdf ")));
        assertNull(SingleInstance.fileArgument(List.of()));
        assertNull(SingleInstance.fileArgument(List.of("-Dfoo")));
        assertFalse(SingleInstance.forward(List.of()));
    }
}
