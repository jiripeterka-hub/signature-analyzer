package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiScaleTest {

    @Test
    @DisplayName("Nové nastavení měřítko neurčuje")
    void freshSettingsLeaveScaleToSystem() {
        assertEquals(UiScale.SYSTEM, new AppSettings().getUiScalePercent());
    }

    @Test
    @DisplayName("Ze souboru se přijme jen nabízená hodnota")
    void parseAcceptsOnlyOfferedValues() {
        assertEquals(150, UiScale.parse("150"));
        assertEquals(175, UiScale.parse(" 175 "));
        // Okno 50× větší než obrazovka by se už nedalo ovládat, ani aby se volba vrátila.
        assertEquals(UiScale.SYSTEM, UiScale.parse("5000"));
        assertEquals(UiScale.SYSTEM, UiScale.parse("-100"));
        assertEquals(UiScale.SYSTEM, UiScale.parse("180%"));
        assertEquals(UiScale.SYSTEM, UiScale.parse(""));
        assertEquals(UiScale.SYSTEM, UiScale.parse(null));
    }

    @Test
    @DisplayName("Volba „podle systému“ nenastaví nic")
    void systemChoiceSetsNothing() {
        Properties target = new Properties();
        UiScale.apply(UiScale.SYSTEM, target);
        assertTrue(target.isEmpty());
    }

    @Test
    @DisplayName("Zvolená hodnota se nastaví pro Linux i Windows")
    void choiceIsSetForBothPlatforms() {
        Properties target = new Properties();
        UiScale.apply(175, target);
        assertEquals("175%", target.getProperty("glass.gtk.uiScale"));
        assertEquals("175%", target.getProperty("glass.win.uiScale"));
    }

    @Test
    @DisplayName("Ručně zadaná hodnota má přednost před nastavením")
    void explicitPropertyWins() {
        Properties target = new Properties();
        target.setProperty("glass.gtk.uiScale", "180%");
        UiScale.apply(125, target);
        assertEquals("180%", target.getProperty("glass.gtk.uiScale"));
    }
}
