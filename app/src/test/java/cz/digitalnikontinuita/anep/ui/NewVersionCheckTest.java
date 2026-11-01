package cz.digitalnikontinuita.anep.ui;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrola nové verze (1. 11. 2026): čtení souboru {@code verze.json} a porovnání čísel.
 * Síť se tu nepoužívá; stránku zastupuje místní server.
 */
class NewVersionCheckTest {

    @Test
    @DisplayName("Jméno vydání „1.0“ je totéž co verze 1.0.0")
    void shortReleaseNameEqualsFullVersion() {
        // Okno „O aplikaci“ ukazuje u 1.0.0 jméno vydání „1.0“; kdyby se to nerovnalo,
        // nabízela by aplikace „novou“ verzi, kterou už uživatel má.
        assertEquals(0, NewVersionCheck.compare("1.0.0", "1.0"));
        assertTrue(NewVersionCheck.compare("1.1.0", "1.0") > 0);
        assertTrue(NewVersionCheck.compare("1.0", "1.1.0") < 0);
    }

    @Test
    @DisplayName("Složky se porovnávají číselně, ne jako text")
    void componentsAreNumeric() {
        assertTrue(NewVersionCheck.compare("1.10.0", "1.9.3") > 0);
        assertTrue(NewVersionCheck.compare("2.0", "1.99.99") > 0);
        assertEquals(0, NewVersionCheck.compare("1.1.0-rc1", "1.1.0"));
    }

    @Test
    @DisplayName("Ze souboru se čte číslo a nepovinné datum")
    void parsesVersionAndDate() {
        NewVersionCheck.Latest l = NewVersionCheck.parse(
                "{\n  \"verze\": \"1.1.0\",\n  \"datum\": \"2026-11-15\"\n}");
        assertEquals("1.1.0", l.version());
        assertEquals(LocalDate.of(2026, 11, 15), l.date());

        NewVersionCheck.Latest bare = NewVersionCheck.parse("{\"verze\":\"1.1.0\"}");
        assertEquals("1.1.0", bare.version());
        assertNull(bare.date());

        // Špatné datum nesmí shodit ohlášení verze.
        assertEquals("1.1.0", NewVersionCheck.parse(
                "{\"verze\":\"1.1.0\",\"datum\":\"15. 11.\"}").version());
    }

    @Test
    @DisplayName("Bez čísla verze (třeba chybová stránka) není výsledek")
    void rejectsBodyWithoutVersion() {
        assertNull(NewVersionCheck.parse("<html>404 Not Found</html>"));
        assertNull(NewVersionCheck.parse("{\"verze\":\"nová\"}"));
        assertNull(NewVersionCheck.parse(null));
    }

    @Test
    @DisplayName("Stažení: 200 s údajem projde, jiný kód je chyba")
    void fetchesFromServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verze.json", ex -> {
            byte[] b = "{\"verze\":\"1.1.0\",\"datum\":\"2026-11-15\"}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/chybi.json", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        URI saved = NewVersionCheck.INFO_URL;
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            NewVersionCheck.INFO_URL = URI.create(base + "/verze.json");
            assertEquals("1.1.0", NewVersionCheck.fetch("1.0").version());

            NewVersionCheck.INFO_URL = URI.create(base + "/chybi.json");
            assertThrows(java.io.IOException.class, () -> NewVersionCheck.fetch("1.0"));
        } finally {
            NewVersionCheck.INFO_URL = saved;
            server.stop(0);
        }
    }
}
