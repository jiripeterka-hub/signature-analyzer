package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oddíl popisu forenzního balíčku o souborech přiložených k ověřenému dokumentu (10. 11. 2026).
 * Hlídá to podstatné: že popis o přílohách mlčí, když žádné nejsou, a že u nich výslovně říká,
 * že je aplikace neověřovala — balíček nese ověřovací materiál k dokumentu a bez té věty by
 * se dal číst i na přílohy.
 */
class FixationAttachmentsTest {

    @Test
    void bezPrilohPopisNicNepridava() {
        assertEquals("", FixationService.attachmentsNote(List.of()));
        assertEquals("", FixationService.attachmentsNote(null));
    }

    @Test
    void prilohyJsouVyjmenovanyAOznacenyJakoNeoverene() {
        String note = FixationService.attachmentsNote(
                List.of("prilohy/příloha č. 1.pdf", "prilohy/zpráva.eml"));
        assertTrue(note.contains("PŘILOŽENÉ SOUBORY"), note);
        assertTrue(note.contains("2 další soubory"), note);
        assertTrue(note.contains("  prilohy/příloha č. 1.pdf\n"), note);
        assertTrue(note.contains("  prilohy/zpráva.eml\n"), note);
        assertTrue(note.contains("aplikace neověřovala"), note);
    }

    @Test
    void jednaPrilohaMaJednotneCislo() {
        String note = FixationService.attachmentsNote(List.of("prilohy/a.txt"));
        assertTrue(note.contains("1 další soubor. V balíčku je beze změny"), note);
    }
}
