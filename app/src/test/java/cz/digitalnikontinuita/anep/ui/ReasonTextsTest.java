package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soubor {@code texty/duvody.txt} a jeho čtení.
 *
 * <p>Znění důvodů se od 14. 9. 2026 neupravují v Javě, nýbrž v souboru, který se veze
 * s aplikací. Tím se otevřela cesta, jakou dřív nebylo možné se splést: <b>překlep v souboru
 * se nepozná při překladu</b>. Chybějící oddíl, přejmenovaný klíč nebo zástupné slovo napsané
 * jinak než je kód očekává se dřív projeví až prázdnou kartou u uživatele. Tenhle test stojí
 * přesně tam.
 *
 * <p>Nezkouší, <i>co</i> je v textech napsáno — to je věc autora textů a mění se to. Zkouší,
 * že se soubor dá přečíst, že je v něm všechno, na co si kód sáhne, a že v něm není nic
 * navíc, co by se tvářilo jako funkční a nikdy se nepoužilo.
 */
class ReasonTextsTest {

    /** Zástupná slova, která aplikace umí doplnit. Cokoli jiného by ve větě zůstalo viset. */
    private static final Set<String> PLACEHOLDERS =
            Set.of("{autorita}", "{vydavatel}", "{certifikat}", "{konec}", "{po-pripnuti}");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    /**
     * Všechna jména klíčů, která aplikace v souboru čte — katalogová i ta z náhradních znění.
     * Co tu není, to se při čtení tiše přeskočí.
     */
    private static final List<String> KNOWN_KEYS = List.of(
            "kratce", "problem", "technicky", "pomoc", "zkusenejsi",
            "problem-bez-data", "pomoc-s-duvodem-15", "pricina", "pricina-bez-data",
            "problem-uvod", "problem-zaver", "dodatek-s-duvodem-21", "dodatek-bez-duvodu-21",
            "s-duvodem-15", "bez-duvodu-15",
            // Znění pro razítko, které kryje pozdější razítko v témže dokumentu: věty
            // o chybějícím dokladu jsou tam nepravdivé. Viz AdvisorView.laterTimestampProvesIt.
            "pricina-s-dokladem", "technicky-s-dokladem", "pomoc-s-dokladem");

    @Test
    @DisplayName("soubor se přečte celý a bez výhrad")
    void fileParses() {
        assertEquals(List.of(), ReasonTexts.errors(),
                "v souboru texty/duvody.txt jsou řádky, kterým čtení nerozumí");
    }

    @Test
    @DisplayName("každý důvod má oddíl, krátký popisek i větu o problému")
    void everyReasonIsComplete() {
        List<String> missing = new ArrayList<>();
        for (int n = 1; n <= 35; n++) {
            if (ReasonTexts.get(String.valueOf(n), "kratce") == null) missing.add(n + ".kratce");
            if (ReasonTexts.get(String.valueOf(n), "problem") == null) missing.add(n + ".problem");
        }
        assertEquals(List.of(), missing, "chybí v souboru texty/duvody.txt");
        assertEquals(35, ResultView.REASONS.size());
        assertEquals(35, ResultView.REASON_WORKING_LABELS.size());
    }

    @Test
    @DisplayName("rada pro zkušenější se bere z vyplněných klíčů, ne z výčtu v kódu")
    void expertTipComesFromTheFile() {
        // Zmrazit tu konkrétní čísla by popřelo smysl změny: dopsat radu k dalšímu důvodu se má
        // obejít bez zásahu do kódu <i>i bez zásahu do testu</i>. Hlídá se proto jen to, co by
        // opravdu bylo špatně: že řádek z karet zmizí úplně, nebo že se slíbí a nemá čím naplnit.
        assertFalse(ResultView.EXPERT_CRL_TIP_REASONS.isEmpty(),
                "žádný důvod nemá vyplněný klíč „zkusenejsi“ — řádek by z karet zmizel");
        for (int rn : ResultView.EXPERT_CRL_TIP_REASONS) {
            assertNotNull(ReasonTexts.get(String.valueOf(rn), "zkusenejsi"), "důvod " + rn);
        }
    }

    @Test
    @DisplayName("odkaz na okno „kde vzít CRL“ patří přesně k radě o CRL")
    void theCrlWindowLinkMatchesTheCrlAdvice() {
        // Od chvíle, kdy radu dostane každé znění zvlášť, se musí vědět, ke které z nich ten
        // odkaz patří. Výčet v kódu se proto srovnává s tím, co je opravdu v souboru: rada
        // o úložišti vlastních CRL je u osmi důvodů a je to pokaždé táž věta.
        String crlTip = ReasonTexts.get("12", "zkusenejsi");
        assertNotNull(crlTip, "referenční znění rady o CRL chybí u důvodu 12");

        Set<String> expected = new LinkedHashSet<>();
        for (int n = 1; n <= 35; n++) {
            if (crlTip.equals(ReasonTexts.get(String.valueOf(n), "zkusenejsi"))) {
                expected.add(String.valueOf(n));
            }
        }
        // Důvod 21 tu radu nese jen v jednom náhradním znění — u nezjištěné revokace.
        if (crlTip.equals(ReasonTexts.variant(21, "NO_REVOCATION", "zkusenejsi"))) {
            expected.add("21");
        }

        Set<String> inCode = new LinkedHashSet<>();
        for (int rn : AdvisorView.CRL_STORE_TIP_REASONS) inCode.add(String.valueOf(rn));
        assertEquals(expected, inCode,
                "AdvisorView.CRL_STORE_TIP_REASONS se rozešel s tím, u kterých důvodů ta rada"
                + " v souboru stojí");
    }

    @Test
    @DisplayName("v souboru není klíč, který by aplikace nečetla")
    void noUnknownKeys() {
        // Klíč s překlepem se tiše ignoruje: text pod ním se u uživatele neukáže a nic to
        // neohlásí. Ze všech způsobů, jak si při úpravě souboru uškodit, je tenhle nejšerednější,
        // protože vypadá jako hotová práce.
        List<String> unknown = new ArrayList<>();
        for (String section : ReasonTexts.sections()) {
            for (String key : ReasonTexts.keys(section)) {
                if (!KNOWN_KEYS.contains(key)) unknown.add("[" + section + "] " + key);
            }
        }
        assertEquals(List.of(), unknown, "klíč, který aplikace nikdy nepřečte");
    }

    @Test
    @DisplayName("náhradní znění, na která se kód ptá, v souboru jsou")
    void everyVariantTheCodeAsksForExists() {
        List<String> missing = new ArrayList<>();
        require(missing, "2/bez-data", "problem");
        require(missing, "4/bez-data", "problem");
        require(missing, "4/kryto", "problem");
        require(missing, "6/bez-data", "problem");
        require(missing, "3/vlastni-podpis", "problem");
        require(missing, "24/bez-certifikatu", "problem");
        require(missing, "25/bez-certifikatu", "problem");
        require(missing, "3/bez-vydavatele", "problem");
        require(missing, "9/bez-dukazu", "problem");
        require(missing, "15/vyrazena-sluzba", "dodatek-s-duvodem-21", "dodatek-bez-duvodu-21");
        require(missing, "21/ramec", "problem-uvod", "problem-zaver");
        require(missing, "21/po-pripnuti", "s-duvodem-15", "bez-duvodu-15");
        for (TimestampFailure cause : TimestampFailure.values()) {
            // OTHER je „příčinu se nepodařilo zařadit"; pro ni se vlastní znění schválně nepíše
            // a zůstane obecná věta z katalogu.
            if (cause == TimestampFailure.OTHER) continue;
            require(missing, "21/" + cause, "pricina", "technicky");
            // Vlastní „pomoc" povinná není: chybí-li, ukáže se obecná rada z katalogu důvodu 21,
            // a u NO_REVOCATION je to tak schválně — tamní rada patří mezi rady pro zkušenější.
            // Co povinné je: aby příčina měla aspoň jednu vlastní radu, jinak o ní kromě popisu
            // problému nikdo nic neřekne.
            requireAny(missing, "21/" + cause, "pomoc", "zkusenejsi");
            require(missing, "31/" + cause, "problem", "pomoc");
        }
        for (int situation : new int[]{37, 39, 40, 38}) {
            require(missing, "32/s" + situation, "problem", "pomoc");
        }
        assertEquals(List.of(), missing, "chybí v souboru texty/duvody.txt");
    }

    @Test
    @DisplayName("v souboru není oddíl, na který se nikdo neptá")
    void noStrandedSections() {
        Set<String> expected = new LinkedHashSet<>();
        for (int n = 1; n <= 35; n++) expected.add(String.valueOf(n));
        expected.add("2/bez-data");
        expected.add("4/bez-data");
        expected.add("4/kryto");
        expected.add("6/bez-data");
        expected.add("3/vlastni-podpis");
        expected.add("24/bez-certifikatu");
        expected.add("25/bez-certifikatu");
        expected.add("3/bez-vydavatele");
        expected.add("9/bez-dukazu");
        expected.add("15/vyrazena-sluzba");
        expected.add("21/ramec");
        expected.add("21/po-pripnuti");
        for (TimestampFailure cause : TimestampFailure.values()) {
            if (cause == TimestampFailure.OTHER) continue;
            expected.add("21/" + cause);
            expected.add("31/" + cause);
        }
        for (int situation : new int[]{37, 39, 40, 38}) expected.add("32/s" + situation);

        List<String> stranded = new ArrayList<>(ReasonTexts.sections());
        stranded.removeAll(expected);
        assertEquals(List.of(), stranded,
                "oddíl v souboru, který kód nikdy nepřečte — buď překlep v názvu, nebo zbytek");
    }

    @Test
    @DisplayName("zástupná slova jsou jen ta, která aplikace umí doplnit")
    void onlyKnownPlaceholders() {
        List<String> unknown = new ArrayList<>();
        for (String section : ReasonTexts.sections()) {
            for (String key : KNOWN_KEYS) {
                String text = ReasonTexts.get(section, key);
                if (text == null) continue;
                Matcher m = PLACEHOLDER.matcher(text);
                while (m.find()) {
                    if (!PLACEHOLDERS.contains(m.group())) {
                        unknown.add("[" + section + "] " + key + ": " + m.group());
                    }
                }
            }
        }
        assertEquals(List.of(), unknown, "aplikace tohle zástupné slovo nedoplní a zůstane ve větě");
    }

    private static void require(List<String> missing, String section, String... keys) {
        for (String key : keys) {
            if (ReasonTexts.get(section, key) == null) missing.add("[" + section + "] " + key);
        }
    }

    /** Aspoň jeden z klíčů; který, na tom nezáleží. */
    private static void requireAny(List<String> missing, String section, String... keys) {
        for (String key : keys) {
            if (ReasonTexts.get(section, key) != null) return;
        }
        missing.add("[" + section + "] ani jedno z " + String.join(", ", keys));
    }
}
