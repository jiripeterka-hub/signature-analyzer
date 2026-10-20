package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nabídka otiskových funkcí je výčet toho, na co zdroj odpověděl razítkem — ne strop.
 *
 * <p>Souvislá být nemusí: I.CA vezme SHA-512, odmítne SHA-384 a SHA-256 zase vezme. Měřeno
 * 15. 8. 2026 proti {@code tsabase.ica.cz}, odmítnutí zní „Message digest algorithm is not
 * supported" s PKIFailureInfo 0x80.
 */
class TsaHashOfferTest {

    @Test
    @DisplayName("Nabídne se přesně to, co zdroj vzal — i s dírou uprostřed")
    void gapInTheMiddleSurvives() {
        TsaProfile t = profileWith(List.of(TsaProfile.HASH_SHA512, TsaProfile.HASH_SHA256));
        assertEquals(List.of(TsaProfile.HASH_SHA512, TsaProfile.HASH_SHA256),
                t.offeredHashAlgorithms());
    }

    @Test
    @DisplayName("Jediná podporovaná funkce zůstane jediná v nabídce")
    void singleSupportedHash() {
        assertEquals(List.of(TsaProfile.HASH_SHA256),
                profileWith(List.of(TsaProfile.HASH_SHA256)).offeredHashAlgorithms());
    }

    @Test
    @DisplayName("Výčet se řadí od nejsilnější, ať přijde v jakémkoli pořadí")
    void orderIsNormalised() {
        TsaProfile t = profileWith(List.of(TsaProfile.HASH_SHA256, TsaProfile.HASH_SHA512));
        assertEquals(List.of(TsaProfile.HASH_SHA512, TsaProfile.HASH_SHA256),
                t.offeredHashAlgorithms());
    }

    @Test
    @DisplayName("Bez zjištění se nabízí vše — neznalost není důvod omezovat")
    void unknownOffersAll() {
        assertEquals(TsaProfile.allHashAlgorithms(), new TsaProfile().offeredHashAlgorithms());
    }

    @Test
    @DisplayName("Nastavená funkce, kterou zdroj nebere, se přesadí na nejsilnější podporovanou")
    void unsupportedSettingIsMoved() {
        TsaProfile t = new TsaProfile("x", "http://tsa", "", "", TsaProfile.HASH_SHA384);
        t.setSupportedHashes(List.of(TsaProfile.HASH_SHA512, TsaProfile.HASH_SHA256));
        assertEquals(TsaProfile.HASH_SHA512, t.getHashAlgorithm());
    }

    @Test
    @DisplayName("Podporovanou nastavenou funkci nikdo nepřesazuje")
    void supportedSettingIsKept() {
        TsaProfile t = new TsaProfile("x", "http://tsa", "", "", TsaProfile.HASH_SHA256);
        t.setSupportedHashes(List.of(TsaProfile.HASH_SHA512, TsaProfile.HASH_SHA256));
        assertEquals(TsaProfile.HASH_SHA256, t.getHashAlgorithm());
    }

    @Test
    @DisplayName("Prázdné zjištění nastavení nemění — o zdroji se nic neví")
    void emptyProbeChangesNothing() {
        TsaProfile t = new TsaProfile("x", "http://tsa", "", "", TsaProfile.HASH_SHA512);
        t.setSupportedHashes(List.of());
        assertEquals(TsaProfile.HASH_SHA512, t.getHashAlgorithm());
        assertEquals(TsaProfile.allHashAlgorithms(), t.offeredHashAlgorithms());
    }

    @Test
    @DisplayName("Kopie profilu si nese i zjištěné údaje")
    void copyCarriesProbeResults() {
        TsaProfile t = profileWith(List.of(TsaProfile.HASH_SHA384));
        t.setCertNotAfter(new java.util.Date(1_800_000_000_000L));
        TsaProfile copy = new TsaProfile(t);
        assertEquals(List.of(TsaProfile.HASH_SHA384), copy.getSupportedHashes());
        assertEquals(t.getCertNotAfter(), copy.getCertNotAfter());
    }

    private static TsaProfile profileWith(List<String> supported) {
        TsaProfile t = new TsaProfile("x", "http://tsa", "", "", supported.get(0));
        t.setSupportedHashes(supported);
        return t;
    }
}
