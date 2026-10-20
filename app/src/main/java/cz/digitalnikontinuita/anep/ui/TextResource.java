package cz.digitalnikontinuita.anep.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Soubor s texty, které aplikace píše uživateli, a jeho čtení.
 *
 * <p>Znění se od 14. 9. 2026 neupravují v Javě, nýbrž v souborech pod {@code texty/}, aby je
 * mohl přepisovat i ten, kdo kód nepíše. Tahle třída umí jeden takový soubor přečíst; co které
 * znění znamená a kdy platí, ví až {@link ReasonTexts} a {@link SituationTexts}.
 *
 * <h2>Tvar souboru</h2>
 * <pre>
 *   [7]                  začátek oddílu
 *   kratce = krátký text        hodnota na jednom řádku
 *   problem =                   hodnota v odsazeném bloku pod klíčem
 *     první řádek odstavce
 *     druhý řádek téhož odstavce
 *
 *     druhý odstavec
 *   # pomoc =            zakomentovaný klíč: na tom místě se nic neukazuje
 * </pre>
 *
 * <p>Odsazené řádky se spojují mezerou, prázdný řádek uvnitř bloku dělá odstavec, křížek
 * <b>na začátku řádku</b> je poznámka. Hodnoty jsou vždy odsazené, takže se s poznámkou
 * nesplete ani text, který sám křížek obsahuje.
 *
 * <p>Soubor se veze v archivu aplikace; na disku vedle programu se po něm nepátrá. Kdyby
 * chyběl, je to vada sestavení a třída to řekne rovnou — tiché pokračování s prázdnými texty
 * by čtenáři ukázalo prázdné karty a nikdo by nevěděl proč.
 */
final class TextResource {

    private final String path;
    private final Map<String, Map<String, String>> sections;
    private final List<String> errors;

    TextResource(String resourcePath) {
        this.path = resourcePath;
        Parsed parsed = parse(read(resourcePath));
        this.sections = parsed.sections();
        this.errors = parsed.errors();
    }

    /** Výsledek čtení: co se přečetlo a co ne. */
    record Parsed(Map<String, Map<String, String>> sections, List<String> errors) {}

    private static String read(String path) {
        try (InputStream in = TextResource.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("V archivu aplikace chybí " + path
                        + " — bez něj nelze vypsat texty. Jde o vadu sestavení.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Soubor " + path + " se nepodařilo přečíst", e);
        }
    }

    // ---------------------------------------------------------------- čtení

    static Parsed parse(String text) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Set<String> opened = new LinkedHashSet<>();
        String section = null;
        String key = null;
        int keyLine = 0;
        StringBuilder value = new StringBuilder();
        boolean blankPending = false;
        String lastComment = null;
        int lineNumber = 0;

        for (String raw : text.split("\r?\n", -1)) {
            lineNumber++;
            if (raw.startsWith("#")) {
                // Poznámka hodnotu uzavírá. Bez toho by se text napsaný pod zakomentovaným
                // klíčem tiše přilepil ke klíči nad poznámkou — a to je horší než chyba,
                // protože by si toho nikdo nevšiml.
                key = flush(sections, section, key, value, errors, keyLine);
                blankPending = false;
                lastComment = raw;
                continue;
            }
            String line = raw.stripTrailing();
            if (line.isEmpty()) {
                if (key != null) blankPending = true;
                continue;
            }
            if (Character.isWhitespace(line.charAt(0))) {
                if (key == null) {
                    // Nejčastější příčina: klíč nad textem zůstal zakomentovaný. Samotné
                    // „odsazený text mimo hodnotu" by poslalo čtenáře hledat, co je špatně,
                    // ačkoli chybí jediný znak.
                    String commented = commentedKey(lastComment);
                    errors.add("řádek " + lineNumber + ": " + (commented != null
                            ? "text pod zakomentovaným klíčem „" + commented + "“ — smažte"
                              + " křížek, jinak se neukáže"
                            : "odsazený text mimo hodnotu"));
                    continue;
                }
                if (value.length() > 0) value.append(blankPending ? "\n\n" : " ");
                blankPending = false;
                value.append(line.strip());
                continue;
            }
            // Řádek od kraje uzavírá rozepsanou hodnotu, ať už je to nový oddíl, nový klíč,
            // nebo nesrozumitelný řádek.
            key = flush(sections, section, key, value, errors, keyLine);
            blankPending = false;
            lastComment = null;

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip();
                if (section.isEmpty()) {
                    errors.add("řádek " + lineNumber + ": prázdný název oddílu");
                    section = null;
                } else if (!opened.add(section)) {
                    // Dva oddíly téhož jména by se tiše slily a ten, kdo text upravil v jednom
                    // z nich, by hledal, proč se to neprojevilo. Stalo se to při zavádění.
                    errors.add("řádek " + lineNumber + ": oddíl [" + section + "] je v souboru"
                            + " podruhé");
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                errors.add("řádek " + lineNumber + ": ani oddíl, ani klíč — „" + line + "“");
                continue;
            }
            if (section == null) {
                errors.add("řádek " + lineNumber + ": klíč mimo oddíl");
                continue;
            }
            // Text smí stát za rovnítkem i pod ním, a obojí naráz: kdo napsal krátkou větu
            // za rovnítko a dopsal ji na dalším řádku, myslel jeden text. Dokud se to bralo
            // jako dvě věci, pokračování se tiše zahazovalo a věta končila uprostřed.
            key = line.substring(0, equals).strip();
            keyLine = lineNumber;
            String rest = line.substring(equals + 1).strip();
            if (!rest.isEmpty()) value.append(rest);
        }
        flush(sections, section, key, value, errors, keyLine);
        return new Parsed(sections, List.copyOf(errors));
    }

    /** Jméno klíče v zakomentovaném řádku {@code # pomoc =}, nebo {@code null}. */
    private static String commentedKey(String comment) {
        if (comment == null) return null;
        String body = comment.substring(1).strip();
        int equals = body.indexOf('=');
        if (equals <= 0) return null;
        String name = body.substring(0, equals).strip();
        return name.isEmpty() || name.contains(" ") ? null : name;
    }

    /** Uloží rozepsanou hodnotu a vrátí {@code null} jako „žádný klíč není otevřený“. */
    private static String flush(Map<String, Map<String, String>> sections, String section,
                                String key, StringBuilder value, List<String> errors, int line) {
        if (key != null) {
            if (section == null) {
                errors.add("řádek " + line + ": hodnota „" + key + "“ mimo oddíl");
            } else if (value.length() > 0) {
                put(sections, section, key, value.toString(), errors, line);
            }
        }
        value.setLength(0);
        return null;
    }

    private static void put(Map<String, Map<String, String>> sections, String section, String key,
                            String value, List<String> errors, int line) {
        Map<String, String> target = sections.computeIfAbsent(section, s -> new LinkedHashMap<>());
        if (target.put(key, value) != null) {
            errors.add("řádek " + line + ": klíč „" + key + "“ je v oddílu [" + section
                    + "] podruhé");
        }
    }

    // ---------------------------------------------------------------- co z něj kdo bere

    /** Text, nebo {@code null}, není-li v souboru vyplněný. */
    String get(String section, String key) {
        Map<String, String> values = sections.get(section);
        if (values == null) return null;
        String text = values.get(key);
        return text == null || text.isBlank() ? null : text;
    }

    /** Doplní do textu údaje z dokumentu: {@code fill(text, "{autorita}", "I.CA")}. */
    static String fill(String text, String... pairs) {
        if (text == null) return null;
        String out = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) out = out.replace(pairs[i], pairs[i + 1]);
        return out;
    }

    /** Názvy oddílů, v pořadí souboru. */
    List<String> sectionNames() {
        return List.copyOf(sections.keySet());
    }

    /**
     * Názvy klíčů v oddílu. Pro test: klíč, jehož jméno se nikam netrefí, se jinak <b>tiše
     * ignoruje</b> a text pod ním se u uživatele neukáže, aniž by se co stalo.
     */
    List<String> keyNames(String section) {
        Map<String, String> values = sections.get(section);
        return values == null ? List.of() : List.copyOf(values.keySet());
    }

    /** Co se v souboru nepodařilo přečíst. Prázdné u souboru, který se veze s aplikací. */
    List<String> errors() {
        return errors;
    }

    /** Odkud se čte — do hlášky testu, aby bylo jasné, který soubor se má opravit. */
    String path() {
        return path;
    }
}
