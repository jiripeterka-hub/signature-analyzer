package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soubor {@code texty/situace.txt} a jeho obsah.
 *
 * <p>Vysvětlivky situací se od 15. 9. 2026 neupravují v Javě. Překlep v souboru se proto
 * nepozná při překladu — chybějící oddíl nebo přejmenovaný klíč by se projevil až prázdným
 * místem u uživatele. Tenhle test stojí přesně tam; co je v textech napsáno, nezkoumá.
 */
class SituationTextsTest {

    /** Zástupná slova, která aplikace umí doplnit. Cokoli jiného by ve větě zůstalo viset. */
    private static final Set<String> PLACEHOLDERS = Set.of("{datum}", "{upozorneni}");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    @Test
    @DisplayName("soubor se přečte celý a bez výhrad")
    void fileParses() {
        assertEquals(List.of(), SituationTexts.errors(),
                "v souboru texty/situace.txt jsou řádky, kterým čtení nerozumí");
    }

    @Test
    @DisplayName("každá situace, kterou kód zná, má vysvětlivku")
    void everySituationHasText() {
        // Čísla nejsou souvislá řada: 47 a 52 se dřív zrušily a 53, 54 přibyly později.
        // Proto se ptáme na to, co je v souboru, a hlídáme jen počet a to, že nic není prázdné.
        List<String> empty = new ArrayList<>();
        for (String section : SituationTexts.sections()) {
            if (section.contains("/")) continue;
            if (SituationTexts.get(section, "text") == null) empty.add(section);
        }
        assertEquals(List.of(), empty, "oddíl bez textu v texty/situace.txt");
        assertEquals(54, ResultView.SITUATION_LABELS.size());
    }

    @Test
    @DisplayName("situace, na které se kód ptá jménem, v souboru jsou")
    void situationsTheCodeNamesExist() {
        // Tahle čísla se v kódu píšou natvrdo — tlačítka ⓘ a ⚠ v přehledech a poznámky
        // na kartě. Kdyby jejich oddíl z souboru zmizel, zůstalo by u nich prázdno.
        for (int situation : new int[]{20, 21, 49, 50, 51, 54, 1, 53}) {
            assertNotNull(ResultView.SITUATION_LABELS.get(situation), "situace " + situation);
        }
    }

    @Test
    @DisplayName("upozornění u situací 2 a 48 umí přijmout i vynechat datum revokace")
    void warningsTakeTheDate() {
        Instant revoked = Instant.parse("2019-03-04T08:15:00Z");
        for (int situation : new int[]{2, 48}) {
            String without = SituationTexts.warning(situation, null);
            String with = SituationTexts.warning(situation, revoked);
            assertAll("situace " + situation,
                    () -> assertNotNull(without),
                    () -> assertFalse(without.contains("{datum}"), without),
                    () -> assertFalse(with.contains("{datum}"), with),
                    () -> assertTrue(with.contains("4. 3. 2019"), with),
                    () -> assertTrue(with.length() > without.length(),
                            "s datem má být věta delší"));
        }
    }

    @Test
    @DisplayName("vysvětlivka 48 nese totéž upozornění jako tlačítko, ne jeho druhý opis")
    void situation48ReusesTheWarning() {
        // Dvě znění téhož by se dřív nebo později rozešla; proto se do vysvětlivky dosazuje.
        assertTrue(ResultView.SITUATION_LABELS.get(48)
                        .startsWith(SituationTexts.warning(48, null)),
                ResultView.SITUATION_LABELS.get(48));
        assertFalse(ResultView.SITUATION_LABELS.get(48).contains("{upozorneni}"));
    }

    @Test
    @DisplayName("zástupná slova jsou jen ta, která aplikace umí doplnit")
    void onlyKnownPlaceholders() {
        List<String> unknown = new ArrayList<>();
        for (String section : SituationTexts.sections()) {
            String text = SituationTexts.get(section, "text");
            if (text == null) continue;
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) {
                if (!PLACEHOLDERS.contains(m.group())) {
                    unknown.add("[" + section + "]: " + m.group());
                }
            }
        }
        assertEquals(List.of(), unknown, "aplikace tohle zástupné slovo nedoplní a zůstane ve větě");
    }

    @Test
    @DisplayName("v souboru není klíč, který by aplikace nečetla")
    void noUnknownKeys() {
        // Klíč s překlepem se tiše ignoruje: text pod ním se u uživatele neukáže a nic to
        // neohlásí. U situací je ten výčet krátký — jediný „text".
        List<String> unknown = new ArrayList<>();
        for (String section : SituationTexts.sections()) {
            for (String key : SituationTexts.keys(section)) {
                if (!"text".equals(key)) unknown.add("[" + section + "] " + key);
            }
        }
        assertEquals(List.of(), unknown, "klíč, který aplikace nikdy nepřečte");
    }

    @Test
    @DisplayName("v souboru není oddíl, na který se nikdo neptá")
    void noStrandedSections() {
        Set<String> allowed = new LinkedHashSet<>(List.of("2/upozorneni", "48/upozorneni",
                "22/necitelny"));
        List<String> stranded = new ArrayList<>();
        for (String section : SituationTexts.sections()) {
            if (!section.contains("/")) continue;
            if (!allowed.contains(section)) stranded.add(section);
        }
        assertEquals(List.of(), stranded,
                "oddíl v souboru, který kód nikdy nepřečte — buď překlep v názvu, nebo zbytek");
    }
}
