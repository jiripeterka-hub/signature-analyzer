package cz.digitalnikontinuita.anep.ui;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Znění, která aplikace píše o důvodech, načtená ze souboru {@code /texty/duvody.txt}.
 *
 * <p>Do 14. 9. 2026 stály tyhle texty v Javě, rozsekané na pokračování řetězců přes desítky
 * řádků. Přeformulovat větu proto znamenalo zásah do zdrojového kódu, a autor textů není nutně
 * ten, kdo kód píše. Teď jsou v souboru, který se dá otevřít v textovém editoru: číslo důvodu
 * v hranaté závorce, pod ním čtyři pojmenovaná pole. Kód si nechává jen rozhodování o tom,
 * které znění kdy platí.
 *
 * <p>Tvar souboru popisuje {@link TextResource}; oddíl s lomítkem
 * ({@code [21/EXPIRED_NO_POE]}) nese náhradní znění pro tutéž věc a vybírá se v kódu.
 */
final class ReasonTexts {

    private ReasonTexts() {}

    static final String RESOURCE = "/texty/duvody.txt";

    private static final TextResource FILE = new TextResource(RESOURCE);

    /** Text, nebo {@code null}, není-li v souboru vyplněný. */
    static String get(String section, String key) {
        return FILE.get(section, key);
    }

    /**
     * Náhradní znění vybrané okolností — {@code variant(21, "REVOKED", "pomoc")} sáhne do oddílu
     * {@code [21/REVOKED]}. Chybí-li, vrací {@code null} a volající zůstane u základního znění.
     */
    static String variant(int reason, String name, String key) {
        return get(reason + "/" + name, key);
    }

    /** Doplní do textu údaje z dokumentu: {@code fill(text, "{autorita}", "I.CA")}. */
    static String fill(String text, String... pairs) {
        return TextResource.fill(text, pairs);
    }

    /** Katalog důvodů: oddíly pojmenované samotným číslem, v pořadí čísel. */
    static Map<Integer, ResultView.ReasonEntry> catalogue() {
        Map<Integer, ResultView.ReasonEntry> out = new TreeMap<>();
        for (String section : FILE.sectionNames()) {
            Integer number = plainNumber(section);
            if (number == null) continue;
            // Chybějící „problem" se neschovává: karta by měla prázdné místo a nikdo by nevěděl,
            // že se má doplnit do souboru. Ostatní dvě pole prázdná být smějí a často jsou.
            String problem = get(section, "problem");
            out.put(number, new ResultView.ReasonEntry(
                    problem != null ? problem
                            : "(v souboru texty/duvody.txt chybí text „problem“ pro důvod "
                              + number + ")",
                    get(section, "technicky"),
                    get(section, "pomoc")));
        }
        return Map.copyOf(out);
    }

    /**
     * Důvody, které mají v souboru vyplněnou radu pro zkušenější uživatele.
     *
     * <p>Dřív to byl jeden text v Javě a pevný výčet sedmi čísel, u kterých se ukazoval. Teď
     * rozhoduje soubor: napíše-li se {@code zkusenejsi} k dalšímu důvodu, rada se u něj objeví
     * a v kódu se nemění nic.
     *
     * <p>Kolik řádků z toho na kartě bude, rozhoduje <b>znění</b>, ne počet důvodů: táž věta
     * u několika důvodů stojí jednou, pod spodním z nich, kdežto různé rady se neslučují. Dělá
     * to {@link AdvisorView#expertTipRows}.
     */
    static java.util.Set<Integer> expertTipReasons() {
        java.util.Set<Integer> out = new java.util.TreeSet<>();
        for (String section : FILE.sectionNames()) {
            Integer number = plainNumber(section);
            if (number != null && get(section, "zkusenejsi") != null) out.add(number);
        }
        return java.util.Set.copyOf(out);
    }

    /** Krátké popisky do seznamu „Zjištěné důvody“. */
    static Map<Integer, String> shortLabels() {
        Map<Integer, String> out = new TreeMap<>();
        for (String section : FILE.sectionNames()) {
            Integer number = plainNumber(section);
            if (number == null) continue;
            String label = get(section, "kratce");
            if (label != null) out.put(number, label);
        }
        return Map.copyOf(out);
    }

    /** Číslo oddílu bez lomítka, nebo {@code null} u náhradního znění a u čehokoli jiného. */
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
