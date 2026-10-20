package cz.digitalnikontinuita.anep.ui;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vysvětlivky situací, načtené ze souboru {@code /texty/situace.txt}.
 *
 * <p>Situace je to, co ověření o prvku zjistilo; důvod je to, proč výsledek není kladný. Seznam
 * situací se ukazuje v Analýze a jednotlivé vysvětlivky i pod tlačítky ⓘ a ⚠ v přehledech.
 * Znění se od 15. 9. 2026 upravují v souboru, ne v Javě — stejně jako u důvodů, viz
 * {@link ReasonTexts}.
 *
 * <h2>Upozornění, do kterých se doplňuje datum</h2>
 *
 * <p>Situace 2 a 48 mluví o revokovaném certifikátu a ověření někdy zná i den, kdy k odvolání
 * došlo. Ten se vkládá doprostřed věty, na místo {@code {datum}}. Vysvětlivka situace 48 tutéž
 * větu obsahuje celou, a aby nebyla v souboru dvakrát, bere si ji přes {@code {upozorneni}};
 * dvě znění téhož by se dřív nebo později rozešla.
 */
final class SituationTexts {

    private SituationTexts() {}

    static final String RESOURCE = "/texty/situace.txt";

    private static final TextResource FILE = new TextResource(RESOURCE);

    /** Text, nebo {@code null}, není-li v souboru vyplněný. */
    static String get(String section, String key) {
        return FILE.get(section, key);
    }

    /**
     * Upozornění u situace, do kterého se doplní den revokace, je-li znám.
     *
     * @param situation 2 (revokace před rozhodným okamžikem) nebo 48 (revokace bez důkazu o čase)
     * @param revokedAt den odvolání, nebo {@code null}
     */
    static String warning(int situation, java.time.Instant revokedAt) {
        return TextResource.fill(get(situation + "/upozorneni", "text"),
                "{datum}", revokedAt == null ? "" : " dne " + TimeUtil.format(revokedAt));
    }

    /** Vysvětlivky situací: oddíly pojmenované samotným číslem. */
    static Map<Integer, String> labels() {
        Map<Integer, String> out = new TreeMap<>();
        for (String section : FILE.sectionNames()) {
            Integer number = plainNumber(section);
            if (number == null) continue;
            String text = get(section, "text");
            if (text == null) continue;
            // Vysvětlivka situace 48 začíná týmž upozorněním, jaké nese tlačítko ⚠. Bez data:
            // seznam situací mluví o tom, co se zjistilo, ne o konkrétním dokumentu.
            String warning = text.contains("{upozorneni}") ? warning(number, null) : null;
            if (warning != null) text = text.replace("{upozorneni}", warning);
            out.put(number, text);
        }
        return Map.copyOf(out);
    }

    private static Integer plainNumber(String section) {
        try {
            return Integer.valueOf(section);
        } catch (NumberFormatException notAPlainNumber) {
            return null;
        }
    }

    /** Pro test: co se v souboru nepodařilo přečíst. */
    static List<String> errors() {
        return FILE.errors();
    }

    /** Pro test: názvy všech oddílů, v pořadí souboru. */
    static List<String> sections() {
        return FILE.sectionNames();
    }

    /** Pro test: které klíče oddíl má. Klíč s překlepem se jinak tiše ignoruje. */
    static List<String> keys(String section) {
        return FILE.keyNames(section);
    }
}
