package cz.digitalnikontinuita.anep;

/**
 * Vstupní bod aplikace. Záměrně nedědí z {@code Application} a sám na JavaFX nesahá.
 *
 * <p>Když hlavní třída z {@code Application} dědí, spouštěč Javy to pozná a předá ji spouštěči
 * JavaFX. Ten nejdřív nastartuje grafickou vrstvu a teprve pak zavolá {@code main()}, takže co
 * {@code main()} nastaví pro start JavaFX, přijde pozdě. Naměřeno 5. 11. 2026 na velikosti
 * rozhraní: při spuštění přes {@code Main} byla vlastnost {@code glass.win.uiScale=175%} nastavená,
 * a přesto měřítko zůstalo 1,0. Teprve s touto třídou se {@code Main.main()} provede před startem.
 *
 * <p>Tuto třídu proto musí mít jako hlavní všechny způsoby spuštění: {@code javafx:run}, manifest
 * balíku, {@code dist.ps1} i {@code dist.sh}. Sonda volající {@code Main.main()} přímo ten rozdíl
 * nepozná, tak se tahle chyba napoprvé nezachytila.
 */
public final class Launcher {

    private Launcher() { }

    public static void main(String[] args) {
        Main.main(args);
    }
}
