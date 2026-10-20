package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodina SHA3 podle celého názvu, ne podle začátku. Nález uživatele z 5. 10. 2026: podpis
 * SHA384 (rodina SHA-2) vyšel v Digitální kontinuitě jako „z rodiny SHA3“, protože název
 * „SHA384“ začíná na „SHA3“.
 */
class Sha3FamilyTest {

    @Test
    @DisplayName("SHA-2 není SHA3, ani když název začíná na „SHA3“")
    void sha2IsNotSha3() {
        for (String d : new String[] { "SHA384", "SHA256", "SHA512", "SHA224", "SHA1",
                "SHA-384", "sha384" }) {
            assertFalse(ContinuityHorizon.isSha3Family(d), d);
        }
        assertFalse(ContinuityHorizon.isSha3Family(null));
    }

    @Test
    @DisplayName("SHA3 ve všech podobách zápisu je SHA3")
    void sha3IsSha3() {
        for (String d : new String[] { "SHA3-224", "SHA3-256", "SHA3-384", "SHA3-512",
                "SHA3_256", "SHA3512", "sha3-384", "SHAKE256" }) {
            assertTrue(ContinuityHorizon.isSha3Family(d), d);
        }
    }
}
