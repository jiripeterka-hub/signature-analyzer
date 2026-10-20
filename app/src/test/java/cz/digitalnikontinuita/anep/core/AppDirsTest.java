package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adresář dat aplikace (19. 10. 2026, linuxová verze). Na Windows se nesmí změnit — uživatelé
 * tam mají nastavení, úložiště certifikátů i mezipaměť seznamů.
 */
class AppDirsTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows: %APPDATA%\\SignatureAnalyzer jako dosud")
    void windowsUnchanged() {
        assertEquals(Path.of(System.getenv("APPDATA"), "SignatureAnalyzer"), AppDirs.dataDir());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux: ~/.config/SignatureAnalyzer nebo $XDG_CONFIG_HOME")
    void linuxXdg() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path expected = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg, "SignatureAnalyzer")
                : Path.of(System.getProperty("user.home"), ".config", "SignatureAnalyzer");
        assertEquals(expected, AppDirs.dataDir());
        assertTrue(AppDirs.dataDir().isAbsolute());
    }
}
