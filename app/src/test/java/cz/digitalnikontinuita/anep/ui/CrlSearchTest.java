package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hledání v prohlížeči seznamu CRL.
 *
 * <p>Uživatel je 15. 9. 2026 hledal a nenašel — pole tam do té chvíle nebylo. Přitom právě od
 * toho dne se seznam otevírá i přímo vloženým souborem, a takové okno nemá {@code highlight},
 * na kterém by se otevřelo: čtenář zůstane u tisíců řádků bez záchytného bodu.
 *
 * <p>Sériové číslo si čtenář odněkud opíše a každý nástroj je píše jinak. Test drží to podstatné:
 * že na oddělovačích, velikosti písmen ani úvodních nulách nezáleží, a že se dá hledat i podle
 * konce čísla.
 */
class CrlSearchTest {

    private static final CrlView.Entry E =
            new CrlView.Entry(new BigInteger("B3140E", 16), "00 B3 14 0E", "1. 1. 2020", "—");

    private static boolean matches(String needle) {
        return CrlView.predicateFor(needle).test(E);
    }

    @Test
    @DisplayName("na zápisu šestnáctkového čísla nezáleží")
    void hexSpellingIrrelevant() {
        assertTrue(matches("00 B3 14 0E"));
        assertTrue(matches("b3140e"));
        assertTrue(matches("B3:14:0E"));
        assertTrue(matches("B3140E"));
        assertTrue(matches("  b3 14 0e  "));
    }

    @Test
    @DisplayName("úvodní nuly se ignorují z obou stran")
    void leadingZerosIgnored() {
        assertTrue(matches("0000B3140E"));
        assertTrue(matches("B3140E"));
    }

    @Test
    @DisplayName("hledá se podřetězcem, takže stačí konec čísla")
    void substringMatch() {
        assertTrue(matches("140E"));
        assertTrue(matches("0E"));
    }

    @Test
    @DisplayName("jde hledat i desítkově")
    void decimalMatch() {
        assertTrue(matches(E.serial().toString()));
    }

    @Test
    @DisplayName("co tam není, se nenajde")
    void noFalsePositives() {
        assertFalse(matches("FFFFFF"));
        assertFalse(matches("B3140F"));
    }

    @Test
    @DisplayName("prázdné hledání pustí všechno")
    void emptyShowsAll() {
        assertTrue(matches(""));
        assertTrue(matches("   "));
        assertTrue(matches(null));
    }
}
