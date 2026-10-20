package cz.digitalnikontinuita.anep.core;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Kde aplikace drží svá data: nastavení, vlastní úložiště certifikátů a CRL, seznam naposledy
 * otevřených souborů a mezipaměť důvěryhodných seznamů.
 *
 * <p>Do 19. 10. 2026 si tutéž cestu skládalo pět tříd každá zvlášť z {@code %APPDATA%}, a kde
 * proměnná nebyla, z domovského adresáře. Na Windows to dávalo
 * {@code %APPDATA%\SignatureAnalyzer} a to zůstává beze změny — mění se jen Linux, kde by data
 * skončila v {@code ~/SignatureAnalyzer}, tedy viditelném adresáři přímo v domově. Linux má pro
 * to {@code $XDG_CONFIG_HOME}, výchozí {@code ~/.config} (XDG Base Directory Specification).
 * Ostatní systémy (macOS) se drží dosavadního chování, dokud pro ně nevznikne vlastní balík.
 */
public final class AppDirs {

    private static final String FOLDER = "SignatureAnalyzer";

    private AppDirs() { }

    /** Kořenový adresář dat aplikace; nevytváří ho. */
    public static Path dataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("linux")) {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            // Specifikace žádá absolutní cestu; relativní se má ignorovat.
            Path base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                    ? Path.of(xdg) : Path.of(home, ".config");
            return base.resolve(FOLDER);
        }
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) appData = home;
        return Path.of(appData, FOLDER);
    }
}
