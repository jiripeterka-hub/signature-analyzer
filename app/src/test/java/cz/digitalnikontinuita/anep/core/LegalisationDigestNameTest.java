package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Název hašovací funkce z eLegalizace převedený na jméno JDK. „SHA384“ bez pomlčky začíná
 * na „SHA3“ a dotaz na prefix z něj dělal „SHA3-84“ (opraveno 5. 10. 2026).
 */
class LegalisationDigestNameTest {

    @Test
    @DisplayName("SHA384 bez pomlčky je SHA-384, ne SHA3-84")
    void names() {
        assertEquals("SHA-384", LegalisationLinkInspector.normalise("SHA384"));
        assertEquals("SHA-256", LegalisationLinkInspector.normalise("sha256"));
        assertEquals("SHA-1", LegalisationLinkInspector.normalise("SHA1"));
        assertEquals("SHA3-384", LegalisationLinkInspector.normalise("SHA3384"));
        assertEquals("SHA3-256", LegalisationLinkInspector.normalise("SHA3_256"));
        assertEquals("SHA3-512", LegalisationLinkInspector.normalise("SHA3-512"));
        assertEquals("SHA-512", LegalisationLinkInspector.normalise("SHA-512"));
    }
}
