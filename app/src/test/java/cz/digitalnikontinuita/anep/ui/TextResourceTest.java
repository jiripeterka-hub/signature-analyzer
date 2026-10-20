package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tvar souborů s texty — to, co je společné souborům {@code duvody.txt} a {@code situace.txt}.
 *
 * <p>Zkouší se tu čtení samo, ne obsah těch dvou souborů: že se hodnota dá napsat za rovnítko
 * i pod ně, že prázdný řádek dělá odstavec, že poznámka se přeskočí, a hlavně že se ohlásí obě
 * nedopatření, po kterých se text tiše ztrácí — týž klíč dvakrát a týž oddíl dvakrát.
 *
 * <p>To druhé není domyšlená možnost: {@code [21]} v {@code duvody.txt} opravdu vzniklo dvakrát
 * a oddíly se slily, protože se klíče nepřekrývaly. Úprava v jednom z nich by se tvářila,
 * že se neprojevila.
 */
class TextResourceTest {

    @Test
    @DisplayName("hodnota se píše za rovnítko i pod ně, odstavce dělí prázdný řádek")
    void parsesBothShapes() {
        TextResource.Parsed p = TextResource.parse(String.join("\n",
                "# poznámka",
                "[7]",
                "kratce = krátce",
                "problem =",
                "  první řádek",
                "  druhý řádek",
                "",
                "  druhý odstavec",
                "",
                "[8]",
                "kratce = jiný"));

        Map<String, String> seven = p.sections().get("7");
        assertAll(
                () -> assertEquals(List.of(), p.errors()),
                () -> assertEquals("krátce", seven.get("kratce")),
                () -> assertEquals("první řádek druhý řádek\n\ndruhý odstavec", seven.get("problem")),
                () -> assertEquals("jiný", p.sections().get("8").get("kratce")));
    }

    @Test
    @DisplayName("text smí začít za rovnítkem a pokračovat pod ním")
    void valueMayStartOnTheKeyLine() {
        // Kdo napsal krátkou větu za rovnítko a dopsal ji na dalším řádku, myslel jeden text.
        // Dokud se to bralo jako dvě věci, pokračování se tiše zahazovalo a věta končila
        // uprostřed — narazil na to autor textů hned při první úpravě.
        TextResource.Parsed p = TextResource.parse(String.join("\n",
                "[7]", "pomoc = Zadat správnou dvojici:", "  dokument a jeho externí podpis."));
        assertAll(
                () -> assertEquals(List.of(), p.errors()),
                () -> assertEquals("Zadat správnou dvojici: dokument a jeho externí podpis.",
                        p.sections().get("7").get("pomoc")));
    }

    @Test
    @DisplayName("poznámka hodnotu uzavírá, text pod ní se nepřilepí ke klíči nad ní")
    void commentClosesTheValue() {
        // Nejde o úhlednost. Text psaný pod zakomentovaný klíč by se jinak připojil k hodnotě
        // nad poznámkou — tedy ke zcela jinému poli — a nikdo by si toho nevšiml.
        TextResource.Parsed p = TextResource.parse(String.join("\n",
                "[7]", "problem = Krátká věta.", "# pomoc =", "  Rada, která zůstala schovaná."));
        assertAll(
                () -> assertEquals("Krátká věta.", p.sections().get("7").get("problem")),
                () -> assertEquals(1, p.errors().size(), p.errors().toString()),
                () -> assertTrue(p.errors().get(0).contains("zakomentovaným klíčem „pomoc“"),
                        p.errors().toString()));
    }

    @Test
    @DisplayName("prázdný řádek na konci hodnoty nedělá odstavec navíc")
    void trailingBlankIsNotAParagraph() {
        TextResource.Parsed p = TextResource.parse("[7]\npomoc =\n  text\n\n[8]\nkratce = x");
        assertEquals("text", p.sections().get("7").get("pomoc"));
    }

    @Test
    @DisplayName("dvakrát týž klíč se ohlásí, aby se druhý tiše nepřebil")
    void duplicateKeyIsReported() {
        TextResource.Parsed p = TextResource.parse("[7]\nkratce = první\nkratce = druhý");
        assertEquals(1, p.errors().size(), p.errors().toString());
        assertTrue(p.errors().get(0).contains("podruhé"), p.errors().toString());
    }

    @Test
    @DisplayName("týž oddíl dvakrát se ohlásí, jinak by se tiše slil")
    void duplicateSectionIsReported() {
        TextResource.Parsed p = TextResource.parse(String.join("\n",
                "[7]", "kratce = x", "[8]", "kratce = y", "[7]", "pomoc = z"));
        assertEquals(1, p.errors().size(), p.errors().toString());
        assertTrue(p.errors().get(0).contains("podruhé"), p.errors().toString());
    }

    @Test
    @DisplayName("zakomentovaný klíč znamená, že se na tom místě nic neukáže")
    void commentedKeyIsAbsent() {
        TextResource.Parsed p = TextResource.parse("[7]\n# pomoc = nic\nkratce = x");
        assertAll(
                () -> assertEquals(List.of(), p.errors()),
                () -> assertNull(p.sections().get("7").get("pomoc")),
                () -> assertNotNull(p.sections().get("7").get("kratce")));
    }

    @Test
    @DisplayName("text s křížkem uvnitř se za poznámku nepovažuje")
    void hashInsideAValueSurvives() {
        // Poznámkou je jen křížek na začátku řádku; hodnoty jsou vždy odsazené. Bez toho
        // pravidla by se ztrácely věty o otiscích typu „x5t#S256".
        TextResource.Parsed p = TextResource.parse("[7]\ntechnicky =\n  otisk x5t#S256 v hlavičce");
        assertAll(
                () -> assertEquals(List.of(), p.errors()),
                () -> assertEquals("otisk x5t#S256 v hlavičce", p.sections().get("7").get("technicky")));
    }
}
