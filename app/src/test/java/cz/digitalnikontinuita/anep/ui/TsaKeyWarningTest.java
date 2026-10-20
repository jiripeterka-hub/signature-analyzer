package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.AppSettings;
import cz.digitalnikontinuita.anep.core.TsaProfile;
import cz.digitalnikontinuita.anep.core.TsaSigningKey;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upozornění u zdroje časových razítek, že klíč autority přestane být podle ETSI TS 119 312
 * spolehlivý dřív, než skončí její certifikát (5. 10. 2026). Případy jsou ze skenu sbírek:
 * PostSignum TSA (RSA-2048, certifikát do 2032) a Sectigo Qualified (RSA-4096, do 2034).
 */
class TsaKeyWarningTest {

    private static TsaProfile profile(SignatureAlgorithm alg, int size, String certEnd) {
        TsaProfile p = new TsaProfile("Zkušební", "http://tsa.example", "", "",
                TsaProfile.HASH_SHA256);
        if (alg != null) p.setSigningKey(new TsaSigningKey(alg, size));
        if (certEnd != null) p.setCertNotAfter(Date.from(Instant.parse(certEnd)));
        return p;
    }

    @Test
    @DisplayName("RSA-2048 s certifikátem do 2032: upozornění s datem a jen varováním v AnEP")
    void rsa2048Warns() {
        String text = TsaManagerDialog.keyWarning(
                profile(SignatureAlgorithm.RSA_SHA256, 2048, "2032-10-10T10:00:00Z"),
                new AppSettings());
        assertTrue(text != null && text.contains("1. 1. 2027"), String.valueOf(text));
        assertTrue(text.contains("RSA, klíč 2 048 bitů"), text);
        assertTrue(text.contains("10. 10. 2032"), text);
        assertTrue(text.contains("jen varováním"), text);
    }

    @Test
    @DisplayName("RSA-4096 s certifikátem do 2034: o konci rozhoduje certifikát, upozornění není")
    void rsa4096Silent() {
        assertNull(TsaManagerDialog.keyWarning(
                profile(SignatureAlgorithm.RSA_SHA256, 4096, "2034-08-02T10:00:00Z"),
                new AppSettings()));
    }

    @Test
    @DisplayName("ECDSA-256 a nezjištěný klíč: upozornění není")
    void ecdsaAndUnknownSilent() {
        assertNull(TsaManagerDialog.keyWarning(
                profile(SignatureAlgorithm.ECDSA_SHA256, 256, "2032-10-10T10:00:00Z"),
                new AppSettings()));
        assertNull(TsaManagerDialog.keyWarning(
                profile(null, 0, "2032-10-10T10:00:00Z"), new AppSettings()));
    }

    @Test
    @DisplayName("klíč se zapíše do nastavení a zase přečte")
    void persistRoundTrip() {
        TsaSigningKey key = new TsaSigningKey(SignatureAlgorithm.RSA_SSA_PSS_SHA256_MGF1, 3072);
        assertEquals(key, TsaSigningKey.parse(key.persist()));
        assertNull(TsaSigningKey.parse(""));
        assertNull(TsaSigningKey.parse("NESMYSL:12"));
    }
}
