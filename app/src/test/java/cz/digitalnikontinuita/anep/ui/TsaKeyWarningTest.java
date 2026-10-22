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
 * Upozornění u zdroje časových razítek, že klíč autority přestane být podle knihovny DSS
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
        // Datum přisuzuje knihovně DSS, ne ETSI — to ho míní pro nové certifikáty TSA.
        assertTrue(text.contains("Knihovna DSS, nad kterou AnEP ověřuje, ho od 1. 1. 2027"), text);
        assertTrue(!text.contains("ETSI"), text);
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
    @DisplayName("seznam zdrojů vypíše přijímané a odmítnuté funkce i podpis razítka")
    void hashesAndSigningLines() {
        TsaProfile p = profile(SignatureAlgorithm.RSA_SHA384, 4096, null);
        assertEquals("Hashovací funkce: přijímané zatím nezjištěny; aplikace žádá s: SHA-256",
                TsaManagerDialog.hashesLine(p));
        // I.CA: SHA-384 odmítá, ostatní bere (měření v TimestampingService.probe)
        p.setSupportedHashes(java.util.List.of(TsaProfile.HASH_SHA256, TsaProfile.HASH_SHA512));
        assertEquals("Přijímá hashovací funkce: SHA-512, SHA-256 (odmítá SHA-384);"
                + " aplikace žádá s: SHA-256", TsaManagerDialog.hashesLine(p));
        assertEquals("Razítka podepisuje: RSA, klíč 4 096 bitů, hash SHA-384",
                TsaManagerDialog.signingLine(p));
        assertNull(TsaManagerDialog.signingLine(profile(null, 0, null)));
        p.setCertNotAfter(Date.from(Instant.parse("2034-08-02T10:00:00Z")));
        assertEquals("Razítka podepisuje: RSA, klíč 4 096 bitů, hash SHA-384;"
                + " Platnost certifikátu do: 2. 8. 2034", TsaManagerDialog.signingLine(p));
        assertEquals("Platnost certifikátu do: 10. 10. 2032", TsaManagerDialog.signingLine(
                profile(null, 0, "2032-10-10T10:00:00Z")));
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
