package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.PdfFieldVisibility;

/**
 * Slova pro to, co {@link PdfFieldVisibility} spočítá.
 *
 * <p>Dvě situace, jedno pravidlo: u prázdného pole je to <b>předpověď</b>, jak bude budoucí podpis
 * vypadat, u podepsaného pole <b>upozornění</b>, že obrázek, který aplikace zrovna ukazuje, čtenář
 * v dokumentu takhle neuvidí. Znění je tady, aby se čtyři místa, která to píšou, nerozešla.
 */
final class FieldVisibilityText {

    private FieldVisibilityText() {}

    /** Čtyři možné kombinace zobrazení a tisku — pro pole, které se teprve bude podepisovat. */
    static String forecast(PdfFieldVisibility v) {
        if (v.zeroArea()) {
            return "neviditelný — pole nemá žádnou plochu, na obrazovce ani na výtisku"
                 + " se neprojeví";
        }
        boolean sees = v.visible();
        boolean prints = v.printed();
        if (sees && prints) return "viditelný na obrazovce i na výtisku";
        if (sees)           return "viditelný na obrazovce, ale netisknutelný";
        if (prints)         return "na obrazovce neviditelný, ale tisknutelný";
        return "neviditelný na obrazovce i na výtisku";
    }

    /**
     * Věta do bloku o vizualizaci, nebo {@code null}, když není co dodat.
     *
     * <p>{@code null} vrací pro běžný případ „vidět i vytisknout" — tam by upozornění jen
     * přidalo šum. Píše se jen tehdy, když se to, co aplikace ukazuje, liší od toho, co uvidí
     * čtenář v prohlížeči nebo na papíře; právě tenhle rozdíl je jinak neviditelný, protože
     * aplikace obrázek vykreslí i u pole, které si prohlížeč odepře nakreslit.
     */
    static String appearanceCaveat(cz.digitalnikontinuita.anep.model.SignatureSummary sig) {
        PdfFieldVisibility v = sig == null ? null : sig.getAppearanceVisibility();
        if (v == null || v.plainlyVisibleAndPrinted()) return null;

        // Neviditelná a netisknutelná vizualizace vzniká **s vědomím podepisující osoby**, která
        // si volí i její podobu — stejně jako u každé jiné. Text tady dřív tvrdil opak („podpis
        // byl vytvořen bez vizualizace"), a byl to nadinterpretovaný závěr z jednoho měření:
        // všech 46 takových v korpusu sedí v poli 150 × 32 bodu, tedy ve výchozí velikosti
        // podpisového pole Acrobatu, kdežto 458 ostatních se rozprostírá do šedesáti různých
        // rozměrů. Z toho, že rámečkem nikdo nehýbal, ale neplyne, že o vizualizaci nikdo
        // nerozhodl — velikost se u něčeho neviditelného měnit nemá proč. Opraveno podle
        // uživatele 4. 9. 2026; samotné měření platí dál, jen ten závěr z něj ne.
        if (v.neverShown()) {
            return "Tato konkrétní vizualizace byla vytvořena jako neviditelná"
                 + " a netisknutelná. To znamená, že v těle PDF dokumentu se neprojeví ani při"
                 + " zobrazování běžnými nástroji či službami, ani při tisku. Aplikace ji ukazuje"
                 + " proto, že v dokumentu je — i když by neměla být nikdy vidět.";
        }

        // Zbývají dvě smíšené kombinace — tady vizualizace opravdu je a jedno z obou platí.
        if (v.visible()) {
            return "Tato konkrétní vizualizace je tzv. viditelná, ale netisknutelná. To znamená,"
                 + " že při zobrazování běžnými nástroji či službami je v těle PDF dokumentu"
                 + " vidět, ale při tisku se netiskne.";
        }
        return "Tato konkrétní vizualizace je tzv. neviditelná, ale tisknutelná. To znamená,"
             + " že při zobrazování běžnými nástroji či službami není v těle PDF dokumentu"
             + " vidět, ale při tisku se tiskne.";
    }
}
