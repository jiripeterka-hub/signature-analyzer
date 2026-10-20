package cz.digitalnikontinuita.anep.core;

/**
 * Zda se podpis v podpisovém poli ukáže na obrazovce a zda se vytiskne.
 *
 * <p>Jedno pravidlo pro dvě různá místa: pro pole, které je teprve připravené
 * ({@link PdfEmptySignatureFieldDetector}), i pro pole už podepsané
 * ({@link PdfSignatureAppearanceExtractor}). U prázdného pole je to předpověď, u podepsaného
 * popis — ale počítá se stejně, protože podepisovací nástroj příznaky ani obdélník nemění.
 * Změřeno na referenční rodině: {@code /F 36} → {@code /F 164} a {@code /F 0} → {@code /F 128},
 * tedy přibyl jen {@code Locked} a {@code /Rect} zůstal.
 *
 * <p>Příznaky podle ISO 32000-1, tabulky 165.
 *
 * @param annotationFlags hodnota {@code /F} widgetu; 0 pro pole bez anotace
 * @param width           šířka {@code /Rect} v bodech
 * @param height          výška {@code /Rect} v bodech
 */
public record PdfFieldVisibility(int annotationFlags, float width, float height) {

    private static final int FLAG_HIDDEN = 1 << 1; //  2
    private static final int FLAG_PRINT  = 1 << 2; //  4
    private static final int FLAG_NOVIEW = 1 << 5; // 32

    /** Pole bez anotace, tedy bez místa i bez příznaků. */
    public static PdfFieldVisibility none() {
        return new PdfFieldVisibility(0, 0f, 0f);
    }

    /**
     * Bez kreslicí plochy se nedá ukázat nic. Takhle se obvykle zakládá záměrně neviditelný
     * podpis ({@code /Rect [0 0 0 0]}) a přebíjí to příznaky, které klidně můžou říkat „tisknout"
     * nad plochou nulové velikosti.
     */
    public boolean zeroArea() { return width <= 0f || height <= 0f; }

    /** {@code Hidden}: nezobrazí se ani nevytiskne, ať ostatní příznaky říkají cokoli. */
    public boolean hidden() { return (annotationFlags & FLAG_HIDDEN) != 0; }

    /** {@code NoView}: nejde na obrazovku, na papír ano, je-li zároveň {@code Print}. */
    public boolean noView() { return (annotationFlags & FLAG_NOVIEW) != 0; }

    /** {@code Print}: bez něj se anotace netiskne nikdy. */
    public boolean printFlag() { return (annotationFlags & FLAG_PRINT) != 0; }

    /** Uvidí ho čtenář na obrazovce? */
    public boolean visible() {
        return !zeroArea() && !hidden() && !noView();
    }

    /**
     * Vyjde na papíře? {@code Hidden} tisk potlačuje bez ohledu na {@code Print} — a ty dva bývají
     * nastavené společně dost často (volba „Skryté" v Acrobatu nechává {@code Print} na místě,
     * takže vzniká {@code /F 6}), takže čtení samotného {@code Print} by takové pole prohlásilo
     * za tisknutelné.
     */
    public boolean printed() {
        return !zeroArea() && !hidden() && printFlag();
    }

    /** Běžný případ — vidět i vytisknout. Jen tehdy není o čem psát. */
    public boolean plainlyVisibleAndPrinted() {
        return visible() && printed();
    }

    /**
     * Podpis se v dokumentu neprojeví nijak — ani na obrazovce, ani na papíře.
     *
     * <p>Vedou sem dvě cesty a je důležité, že je to <b>jedna</b> otázka: pole nulové velikosti
     * (podpis bez vizualizace, v korpusu většina) a pole s příznakem {@code Hidden} (vizualizace,
     * kterou podepisující osoba vědomě zvolila jako neviditelnou, včetně její podoby). Na to, co
     * uvidí čtenář dokumentu, nemá vliv ani jeden; liší se jen tím, jestli v souboru zůstal
     * obrázek, který nikdo nikdy nenakreslí.
     */
    public boolean neverShown() {
        return !visible() && !printed();
    }

    /** „/F 36 — Print, NoView" — surová hodnota i její rozklad, pro čtenáře, který chce zdroj. */
    public String describeFlags() {
        StringBuilder names = new StringBuilder();
        int v = annotationFlags;
        appendFlag(names, (v & 1) != 0,   "Invisible");
        appendFlag(names, hidden(),       "Hidden");
        appendFlag(names, printFlag(),    "Print");
        appendFlag(names, (v & 8) != 0,   "NoZoom");
        appendFlag(names, (v & 16) != 0,  "NoRotate");
        appendFlag(names, noView(),       "NoView");
        appendFlag(names, (v & 64) != 0,  "ReadOnly");
        appendFlag(names, (v & 128) != 0, "Locked");
        appendFlag(names, (v & 256) != 0, "ToggleNoView");
        appendFlag(names, (v & 512) != 0, "LockedContents");
        String decoded = names.length() == 0 ? "žádný příznak" : names.toString();
        String base = "/F " + v + " — " + decoded;
        // Hidden a Print vedle sebe si podle tabulky 165 neodporují, jen Hidden vyhrává. Nechat
        // to bez vysvětlení znamená, že čtenář vidí „Print" a o řádek výš „netiskne se".
        if (hidden() && printFlag()) {
            base += " (Hidden má přednost před Print, pole se tedy netiskne)";
        }
        return base;
    }

    private static void appendFlag(StringBuilder sb, boolean set, String name) {
        if (!set) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(name);
    }
}
