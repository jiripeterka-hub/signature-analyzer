package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Jména výstupů se nesmějí vrstvit. Všechny čtyři akce jsou opakovatelné, takže dřív nebo později
 * dostanou na vstup svůj vlastní výstup — a jméno má říkat, dokdy artefakt platí, což se dvěma
 * datry za sebou nejde.
 */
class ActionBaseNameTest {

    @Test
    @DisplayName("Značka předchozí akce se nahradí, nevrší se")
    void previousMarkIsReplaced() {
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Do_03_08_2034"));
        // Značky, které aplikace píše od 7. 9. 2026 — jedna za každou variantu.
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Uchovano_do_03_08_2034"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Prodlouzeno_do_03_08_2034"));
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Prerazitkovano_do_03_08_2034"));
        // Značky, které aplikace už nepíše. Soubory s nimi ale existují a vracejí se na vstup,
        // takže je musí umět odstranit i nadále — jinak by se na ně nová značka navrstvila.
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Zachovano_do_03_08_2034"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Uchovano_do_03_08_2034"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Prodlouzeno_do_03_08_2034"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Razitko_do_03_08_2034"));
    }

    @Test
    @DisplayName("Značka bez data — certifikát se nepodařilo přečíst — odejde také")
    void markWithoutDateIsReplaced() {
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Zachovano"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Uchovano"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Prodlouzeno"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Prerazitkovano"));
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva_Razitko"));
    }

    @Test
    @DisplayName("Značka nese variantu a za ní datum")
    void markCarriesTheVariant() {
        java.util.Date until = java.util.Date.from(
                java.time.LocalDate.of(2034, 8, 3)
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        assertEquals("_Uchovano_do_03_08_2034",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.PRESERVE, until));
        assertEquals("_Prodlouzeno_do_03_08_2034",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.PROLONG, until));
        assertEquals("_Prerazitkovano_do_03_08_2034",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.RESTAMP, until));
    }

    @Test
    @DisplayName("Soubor se starou značkou dostane novou, ne druhou")
    void anOldMarkIsSwappedForTheNewOne() {
        // Skutečný soubor z korpusu: uchovaný ještě pod holou značkou _Do_, kterému aplikace
        // uchování nabízí znovu. Bez záměny by vyšlo dokument_Do_02_05_2032_Uchovano_do_…
        java.util.Date until = java.util.Date.from(
                java.time.LocalDate.of(2034, 8, 3)
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        String base = AdvisorView.actionBaseName("dokument signed_Do_02_05_2032");
        assertEquals("dokument signed", base);
        assertEquals("dokument signed_Uchovano_do_03_08_2034",
                base + AdvisorView.continuityMark(AdvisorView.ContinuityMode.PRESERVE, until));
    }

    /**
     * Soubor, který vznikl s testovacím razítkem, se má poznat ze jména.
     *
     * <p>Zadání uživatele z 18. 9. 2026, poté co přibyla předvolba PostSignum DEMO. Takový soubor
     * vzniká při zkoušení a od ostrého výsledku ho jinak nerozezná nic, co by bylo vidět ve
     * správci souborů — přitom datum ve značce slibuje ověřitelnost do roku 2034.
     */
    @Test
    @DisplayName("Nekvalifikované razítko se ve jméně přizná za datem")
    void anUnqualifiedStampIsMarkedInTheName() {
        java.util.Date until = java.util.Date.from(
                java.time.LocalDate.of(2034, 8, 3)
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        assertEquals("_Uchovano_do_03_08_2034 (DEMO)",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.PRESERVE, until, true));
        assertEquals("_Prerazitkovano_do_03_08_2034 (DEMO)",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.RESTAMP, until, true));
        // Bez data zbývá holé slovo — a značka i tak stojí za ním, ne místo něj.
        assertEquals("_Uchovano (DEMO)",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.PRESERVE, null, true));
        // Kvalifikované razítko jméno nemění; to je ta obvyklá cesta a nesmí se hnout.
        assertEquals("_Uchovano_do_03_08_2034",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.PRESERVE, until, false));
    }

    /**
     * Všechny úkony jsou opakovatelné, takže se jméno potkává samo se sebou. Kdyby značku „ (DEMO)“
     * neuměl {@code actionBaseName} odstranit, vyšlo by po druhém úkonu
     * {@code X_Uchovano_do_… (DEMO)_Uchovano_do_… (DEMO)} — a první datum v něm už neplatí.
     */
    @Test
    @DisplayName("Značka DEMO se při dalším úkonu nevrší")
    void theDemoMarkIsNotStacked() {
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Uchovano_do_03_08_2034 (DEMO)"));
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Do_03_08_2034 (DEMO)"));
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Prerazitkovano (DEMO)"));
        // Zkoušel se testovací zdroj, pak ostrý: značka po prvním úkonu musí zmizet celá.
        assertEquals("smlouva", AdvisorView.actionBaseName(
                "smlouva_Uchovano_do_01_01_2030 (DEMO)_Uchovano_do_03_08_2034"));
        // A obráceně — ostrý napřed, testovací potom.
        assertEquals("smlouva", AdvisorView.actionBaseName(
                "smlouva_Uchovano_do_01_01_2030_Uchovano_do_03_08_2034 (DEMO)"));
    }

    @Test
    @DisplayName("„(DEMO)“ uprostřed jména není značka a nesmí zmizet")
    void demoInTheMiddleIsNotAMark() {
        assertEquals("smlouva (DEMO) dodatek",
                AdvisorView.actionBaseName("smlouva (DEMO) dodatek"));
        assertEquals("smlouva (DEMO)", AdvisorView.actionBaseName("smlouva (DEMO)"));
    }

    @Test
    @DisplayName("Bez data zbývá holé slovo, jméno se ale od vstupního liší")
    void markWithoutDateIsStillAMark() {
        assertEquals("_Uchovano",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.PRESERVE, null));
        assertEquals("_Prerazitkovano",
                AdvisorView.continuityMark(AdvisorView.ContinuityMode.RESTAMP, null));
    }

    @Test
    @DisplayName("Každá varianta má vlastní jméno na tlačítku i ve značce")
    void everyVariantIsNamedDistinctly() {
        java.util.Set<String> verbs = new java.util.HashSet<>();
        java.util.Set<String> marks = new java.util.HashSet<>();
        for (AdvisorView.ContinuityMode m : AdvisorView.ContinuityMode.values()) {
            verbs.add(m.verb);
            marks.add(m.mark);
            // Značka jde do jména souboru, které putuje na cizí systémy.
            assertEquals(m.mark, java.text.Normalizer
                            .normalize(m.mark, java.text.Normalizer.Form.NFD)
                            .replaceAll("[^\\p{ASCII}]", ""),
                    "Značka musí být bez diakritiky: " + m.mark);
        }
        assertEquals(3, verbs.size(), "Dvě varianty se stejným tlačítkem nejsou rozlišené: " + verbs);
        assertEquals(3, marks.size(), "Dvě varianty se stejnou značkou: " + marks);
    }

    @Test
    @DisplayName("Uklidí i po jménech, která už dvě značky nesou")
    void stackedMarksAreAllRemoved() {
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Do_03_08_2034_Do_03_08_2034"));
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Razitko_do_01_01_2030_Uchovano_do_03_08_2034"));
        // Přesně ten případ, který nastane po přechodu na jednu značku: starý výstup se zachová
        // znovu a jméno by neslo dvě data, z nichž první už neplatí.
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Uchovano_do_01_01_2030_Zachovano_do_03_08_2034"));
        assertEquals("smlouva",
                AdvisorView.actionBaseName("smlouva_Zachovano_do_01_01_2030_Do_03_08_2034"));
    }

    @Test
    @DisplayName("Jméno bez značky zůstane, jak je")
    void plainNameSurvives() {
        assertEquals("smlouva", AdvisorView.actionBaseName("smlouva"));
        assertEquals("smlouva 2034", AdvisorView.actionBaseName("smlouva 2034"));
        // Datum uprostřed jména není značka a nesmí zmizet.
        assertEquals("smlouva_Do_03_08_2034_dodatek",
                AdvisorView.actionBaseName("smlouva_Do_03_08_2034_dodatek"));
    }

    /**
     * Balíček zafixovaný testovacím razítkem se pozná stejně jako uchovaný dokument.
     *
     * <p>Razítko je z téhož zdroje a datum ve jméně říká, dokdy balíček obnovit — u razítka,
     * které za důkaz existence v čase nikdo nevezme. Doplněno 18. 9. 2026 spolu se značkou
     * u augmentace, na pokyn uživatele.
     */
    @Test
    @DisplayName("Balíček s nekvalifikovaným razítkem nese značku za datem")
    void theFixationSuffixCarriesTheDemoMark() {
        java.util.Date until = java.util.Date.from(
                java.time.LocalDate.of(2034, 8, 3)
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        assertEquals("_Do_03_08_2034 (DEMO)", AdvisorView.fixationSuffix(until, true));
        assertEquals("_Do_03_08_2034", AdvisorView.fixationSuffix(until, false));
        // Bez data se značka nepřipisuje: neměla by se čeho chytit a při dalším zafixování by
        // se buď vršila, nebo by pravidlo muselo ukrajovat „(DEMO)“ z konce každého jména.
        assertEquals("", AdvisorView.fixationSuffix(null, true));
    }

    @Test
    @DisplayName("Značka DEMO nevrší ani u balíčku zabaleného podruhé")
    void theDemoMarkIsNotStackedOnPackages() {
        // „_pdf" je značka typu zabaleného dokumentu a zůstává — jméno má říkat, co je uvnitř.
        // Odstraňuje se předpona, datum a značka za ním.
        assertEquals("smlouva_pdf",
                AdvisorView.fixationBaseName("FB_smlouva_pdf_Do_03_08_2034 (DEMO)"));
        // Poprvé testovacím zdrojem, podruhé ostrým.
        assertEquals("smlouva_pdf", AdvisorView.fixationBaseName(
                "FB_FB_smlouva_pdf_Do_01_01_2030 (DEMO)_Do_03_08_2034"));
        // Typ obalu se podruhé nepřipisuje ani se značkou na konci.
        assertEquals("", AdvisorView.fixationTypeMark("FB_smlouva_pdf_Do_03_08_2034 (DEMO).asice"));
    }

    @Test
    @DisplayName("Předpona FB_ nepřibude podruhé")
    void fixationPrefixIsNotDoubled() {
        assertEquals("smlouva", AdvisorView.fixationBaseName("FB_smlouva_Do_03_08_2034"));
        assertEquals("smlouva", AdvisorView.fixationBaseName("FB_FB_smlouva_Do_03_08_2034_Do_03_08_2034"));
        assertEquals("smlouva", AdvisorView.fixationBaseName("smlouva"));
    }

    @Test
    @DisplayName("Typ zabaleného dokumentu jméno balíčku nese")
    void fixationNameCarriesTheInputType() {
        assertEquals("_pdf", AdvisorView.fixationTypeMark("smlouva.pdf"));
        assertEquals("_asice", AdvisorView.fixationTypeMark("smlouva.asice"));
        assertEquals("_zfo", AdvisorView.fixationTypeMark("zprava.zfo"));
        // Tečky uvnitř jména typem nejsou — rozhoduje ta poslední.
        assertEquals("_pdf", AdvisorView.fixationTypeMark("smlouva.v2.pdf"));
    }

    @Test
    @DisplayName("Typ nepřibude podruhé a nevymýšlí se, kde není")
    void fixationTypeIsNotDoubledNorInvented() {
        // Balíček, který jde na vstup podruhé: jméno typ původního dokumentu už nese a typ obalu
        // (_asice) by ho zakryl.
        assertEquals("", AdvisorView.fixationTypeMark("FB_smlouva_pdf_Do_03_08_2034.asice"));
        assertEquals("", AdvisorView.fixationTypeMark("smlouva"));
        assertEquals("", AdvisorView.fixationTypeMark("smlouva."));
        // Skrytý soubor bez přípony — tečka na začátku jména typ neoznačuje.
        assertEquals("", AdvisorView.fixationTypeMark(".smlouva"));
    }
}
