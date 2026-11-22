package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Položka „Otevřít v AnEP“ v nabídce Průzkumníka (22. 11. 2026): soubor .reg, který tlačítko
 * přenosné verze importuje, a přečtení cesty k programu z exportu klíče {@code command}.
 * Cesta s diakritikou a mezerami je ten případ, kvůli kterému se nejde přes {@code reg query}.
 */
class ExplorerContextMenuTest {

    private static final Path EXE = Path.of("D:\\Přenosné programy\\AnEP 1.5\\AnEP.exe");

    @Test
    @DisplayName("Soubor .reg nese text položky, ikonu a příkaz s cestou v uvozovkách")
    void regFileCarriesLabelIconAndCommand() {
        String reg = ExplorerContextMenu.regFile(EXE);
        assertTrue(reg.startsWith("\uFEFFWindows Registry Editor Version 5.00"), reg);
        assertTrue(reg.contains("[HKEY_CURRENT_USER\\Software\\Classes\\*\\shell\\AnEP]"), reg);
        assertTrue(reg.contains("@=\"Otevřít v AnEP\""), reg);
        assertTrue(reg.contains("\"Icon\"=\"\\\"D:\\\\Přenosné programy\\\\AnEP 1.5\\\\AnEP.exe\\\",0\""), reg);
        assertTrue(reg.contains("[HKEY_CURRENT_USER\\Software\\Classes\\*\\shell\\AnEP\\command]"), reg);
        assertTrue(reg.contains(
                "@=\"\\\"D:\\\\Přenosné programy\\\\AnEP 1.5\\\\AnEP.exe\\\" \\\"%1\\\"\""), reg);
    }

    @Test
    @DisplayName("Z exportu klíče command se přečte cesta k programu")
    void exeIsReadBackFromExport() {
        String export = ExplorerContextMenu.regFile(EXE);
        // Export klíče command má jen jeho oddíl — vezme se ten druhý.
        String commandOnly = export.substring(export.indexOf("[HKEY_CURRENT_USER\\Software\\Classes\\*\\shell\\AnEP\\command]"));
        assertEquals(EXE, ExplorerContextMenu.exeFromExport(commandOnly));
    }

    @Test
    @DisplayName("Příkaz bez uvozovek i prázdný export")
    void unquotedAndEmpty() {
        assertEquals(Path.of("C:\\AnEP\\AnEP.exe"),
                ExplorerContextMenu.exeFromExport("[X]\r\n@=\"C:\\\\AnEP\\\\AnEP.exe %1\"\r\n"));
        assertNull(ExplorerContextMenu.exeFromExport("Windows Registry Editor Version 5.00\r\n"));
    }
}
