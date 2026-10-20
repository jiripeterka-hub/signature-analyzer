package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CzechPluralTest {

    @Test
    @DisplayName("Počet objektů se skloňuje 1 / 2–4 / 5 a více")
    void objectsAgree() {
        assertEquals("0 objektů", CzechPlural.objects(0));
        assertEquals("1 objekt",  CzechPlural.objects(1));
        assertEquals("2 objekty", CzechPlural.objects(2));
        assertEquals("3 objekty", CzechPlural.objects(3));
        assertEquals("4 objekty", CzechPlural.objects(4));
        assertEquals("5 objektů", CzechPlural.objects(5));
        assertEquals("11 objektů", CzechPlural.objects(11));
        assertEquals("21 objektů", CzechPlural.objects(21));
    }

    @Test
    @DisplayName("Sloveso se řídí stejným pravidlem jako podstatné jméno")
    void verbsAgree() {
        assertEquals("byl zkontrolován",
                CzechPlural.form(1, "byl zkontrolován", "byly zkontrolovány", "bylo zkontrolováno"));
        assertEquals("byly zkontrolovány",
                CzechPlural.form(3, "byl zkontrolován", "byly zkontrolovány", "bylo zkontrolováno"));
        assertEquals("bylo zkontrolováno",
                CzechPlural.form(7, "byl zkontrolován", "byly zkontrolovány", "bylo zkontrolováno"));
        assertEquals("bylo zkontrolováno",
                CzechPlural.form(0, "byl zkontrolován", "byly zkontrolovány", "bylo zkontrolováno"));
    }

    @Test
    @DisplayName("Záporný počet nevyrobí nesmyslný tvar")
    void negativeFallsBackToGenitive() {
        assertEquals("-1 objektů", CzechPlural.objects(-1));
    }
}
