package cz.digitalnikontinuita.anep.ui;

import java.util.List;

/**
 * Výklad k připomínkám knihovny DSS, načtený ze souboru {@code /texty/dss-pripominky.txt}.
 *
 * <p>Oddíl se jmenuje klíčem hlášení DSS ({@code QUAL_QSCD_AT_ST_ANS} a podobně), nese krátkou
 * českou větu ({@code kratce}) a odstavec pro čtenáře, který PKI nedělá ({@code vyklad}).
 * Znění se upravují v souboru, ne v Javě — stejně jako u důvodů a situací, viz {@link ReasonTexts}
 * a {@link SituationTexts}.
 *
 * <p><b>Klíč bez výkladu se nezamlčuje.</b> Karta v takovém případě ukáže původní anglickou větu
 * knihovny a poznámku, že zdejší výklad k tomu hlášení zatím není. Tichý výpadek by byl horší:
 * hlášení, ke kterému se nikdo nedostal napsat výklad, by z karty prostě zmizelo a nikdo by se
 * nedozvěděl, že tam mělo být. Klíčů zná DSS stovky a výčet v souboru stojí na tom, co se
 * naměřilo na sbírce, takže nový klíč je otázka času, ne výjimka.
 */
final class DssRemarkTexts {

    private DssRemarkTexts() {}

    static final String RESOURCE = "/texty/dss-pripominky.txt";

    private static final TextResource FILE = new TextResource(RESOURCE);

    /** Krátká česká věta k hlášení, nebo {@code null}, není-li v souboru. */
    static String shortText(String key) {
        return key == null ? null : FILE.get(key, "kratce");
    }

    /** Odstavec pro méně zkušeného čtenáře, nebo {@code null}, není-li v souboru. */
    static String explanation(String key) {
        return key == null ? null : FILE.get(key, "vyklad");
    }

    /** Zda soubor k tomu klíči něco má. */
    static boolean known(String key) {
        return shortText(key) != null || explanation(key) != null;
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
