package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tlačítko „Otevřít v systémovém prohlížeči“ v prohlížeči certifikátů a seznamů CRL
 * (přání uživatele, 20. 9. 2026).
 *
 * <p>Měří se ta půlka, která na prostředí nezávisí: komu se tlačítko nabídne a jaký soubor se pro
 * systém připraví. Že systém ten soubor opravdu otevře, závisí na tom, co má přidružené, a to se
 * dá jen zkusit — od toho je hláška, která místo „chyba“ radí prvek uložit.
 */
class SystemViewerTest {

    /**
     * Výčet, ne domněnka. Odpověď OCSP systém nezná a zeptal by se, čím ji otevřít; tlačítko,
     * které vede na tuhle otázku, je horší než žádné.
     */
    @Test
    @DisplayName("Nabízí se u certifikátu a seznamu CRL, ne u odpovědi OCSP")
    void onlyForTypesTheSystemKnows() {
        assertTrue(UiDialogs.hasSystemViewer("*.cer"));
        assertTrue(UiDialogs.hasSystemViewer("*.crl"));
        assertFalse(UiDialogs.hasSystemViewer("*.ors"), "Odpověď OCSP systém neumí otevřít");
        assertFalse(UiDialogs.hasSystemViewer("*.*"));
    }

    /**
     * Systém pozná typ jedině podle přípony, takže se jménem z dialogu „Uložit…“ vystačit nedá:
     * u certifikátu vloženého jako soubor je to jméno toho souboru, klidně {@code podpis.p7s}.
     */
    @Test
    @DisplayName("Příponu dává typ, ne jméno, se kterým se prvek uložil")
    void theExtensionComesFromTheType() throws Exception {
        byte[] data = { 0x30, (byte) 0x82, 0x01, 0x02 };

        File cert = UiDialogs.writeTempFor(data, "podpis.p7s", "*.cer");
        assertTrue(cert.getName().endsWith(".cer"), cert.getName());
        assertEquals("podpis.cer", cert.getName(), "Základ jména zůstává, přípona se vymění");
        assertArrayEquals(data, Files.readAllBytes(cert.toPath()));

        File crl = UiDialogs.writeTempFor(data, "seznam", "*.crl");
        assertEquals("seznam.crl", crl.getName(), "Jméno bez přípony ji dostane");
    }

    @Test
    @DisplayName("Co by v cestě vadilo, se do jména souboru nedostane")
    void theNameIsSanitised() throws Exception {
        byte[] data = { 0x30, 0x00 };
        assertEquals("a_b_c.cer",
                UiDialogs.writeTempFor(data, "a:b*c.pdf", "*.cer").getName());
        assertEquals("prvek.cer",
                UiDialogs.writeTempFor(data, null, "*.cer").getName(),
                "Bez jména se použije náhradní, ne prázdné");
        assertEquals("prvek.cer",
                UiDialogs.writeTempFor(data, "   ", "*.cer").getName());
    }

    /**
     * Soubor je dočasný a maže se s aplikací: kdo ho chce mít, má vedle tlačítko „Uložit…“.
     * Nechávat po sobě certifikáty v systémovém odkladišti je horší než je uklidit.
     */
    @Test
    @DisplayName("Připravuje se do odkladiště, ne vedle dokumentu")
    void theFileIsTemporary() throws Exception {
        File f = UiDialogs.writeTempFor(new byte[] { 1 }, "certifikat", "*.cer");
        assertTrue(f.toPath().startsWith(System.getProperty("java.io.tmpdir")), f.getPath());
        assertTrue(f.getParentFile().getName().startsWith("anep-prvek-"), f.getPath());
    }
}
