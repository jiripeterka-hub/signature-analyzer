package cz.digitalnikontinuita.anep.core;

import java.util.List;
import java.util.Properties;

/**
 * Velikost rozhraní, kterou si uživatel volí v Nastavení (5. 11. 2026, pokyn uživatele).
 *
 * <p>Vzniklo z ohlasu uživatele na Linuxu, kterému bylo rozhraní na jeho monitoru špatně čitelné
 * a pomohlo až spuštění s {@code _JAVA_OPTIONS=-Dglass.gtk.uiScale=180%}. To se ale nedá chtít po
 * každém. Volba nastavuje tutéž vlastnost, jen sama, před startem JavaFX.
 *
 * <p>Měřítko celého rozhraní, ne větší písmo přes CSS: velikost písma je v kódu napevno asi na
 * 300 místech a rozměry prvků na dalších asi 240, takže by část textů zůstala malá a rozložení by
 * se rozjelo. Cenou je, že se změna projeví až po novém spuštění: JavaFX měřítko čte jen při startu.
 *
 * <p>Třída nesmí sahat na JavaFX: běží dřív, než se spustí.
 */
public final class UiScale {

    /** Bez zásahu: měřítko určí JavaFX podle systému. */
    public static final int SYSTEM = 0;

    /** Nabízené hodnoty v procentech. Jiná hodnota se nepřijímá, viz {@link #parse}. */
    public static final List<Integer> CHOICES = List.of(SYSTEM, 100, 125, 150, 175, 200);

    /** Každá platforma čte jen svou, nastavit obě je neškodné. Na macOS taková vlastnost není. */
    private static final List<String> PROPERTIES = List.of("glass.gtk.uiScale", "glass.win.uiScale");

    /** Hodnota z nastavení při tomto spuštění; podle ní dialog pozná, že je potřeba restart. */
    private static volatile int atStart = SYSTEM;

    private UiScale() { }

    /**
     * Hodnota ze souboru nastavení. Co není mezi nabízenými, znamená „podle systému“: ručně
     * přepsaná hodnota jako 5000 by jinak vytvořila okno, ve kterém se volba nedá ani vrátit.
     */
    public static int parse(String stored) {
        if (stored == null) return SYSTEM;
        try {
            int v = Integer.parseInt(stored.trim());
            return CHOICES.contains(v) ? v : SYSTEM;
        } catch (NumberFormatException e) {
            return SYSTEM;
        }
    }

    /**
     * Volá se z {@code main()} před {@code launch()}: později by to JavaFX už nepřečetl. Hlavní
     * třídou proto musí být {@code Launcher}, viz tam.
     */
    public static void applyBeforeToolkit(int percent) {
        atStart = percent;
        apply(percent, System.getProperties());
    }

    /**
     * Hodnota zadaná ručně (třeba přes {@code _JAVA_OPTIONS}) má přednost před nastavením. Kdo ji
     * zadal, chce právě ji, a jde to i k záchraně, když volba v nastavení nevyhovuje.
     */
    static void apply(int percent, Properties target) {
        if (percent == SYSTEM) return;
        for (String property : PROPERTIES) {
            if (target.getProperty(property) == null) {
                target.setProperty(property, percent + "%");
            }
        }
    }

    public static int atStart() {
        return atStart;
    }
}
