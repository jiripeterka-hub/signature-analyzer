package cz.digitalnikontinuita.anep.model;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Zařazení příčiny, proč razítko neobstálo.
 *
 * <p>Kombinace klíčů nejsou vymyšlené: pocházejí ze skenu 416 dokumentů ze 14. 9. 2026, který
 * našel 209 neúspěšných samostatných razítek. Počty u jednotlivých případů říkají, jak často se
 * ta kombinace ve sbírce vyskytla — a u dvou větví, že se nevyskytla vůbec.
 */
class TimestampFailureClassifierTest {

    /**
     * Nejpočetnější případ (135×) a zároveň ten, kvůli kterému se zařazení přestalo řídit dílčí
     * indikací: {@code ICTIVRSC} říká, že certifikátu skončila platnost.
     */
    @Test
    @DisplayName("expirace certifikátu — 135× ve sbírce")
    void expiredCertificate() {
        assertEquals(TimestampFailure.EXPIRED_NO_POE, TimestampFailure.of(
                List.of("BBB_XCV_SUB_ANS_2", "BBB_XCV_ICTIVRSC_ANS", "PSV_IPTVC_ANS",
                        "QUAL_HAS_GRANTED_AT_ANS"),
                "OUT_OF_BOUNDS_NO_POE"));
    }

    /**
     * <b>Tohle byl ten omyl.</b> Dílčí indikace zní „nenalezen řetěz certifikátů", takže zařazení
     * podle ní tvrdilo, že autorita není mezi důvěryhodnými. Klíč {@code IRDPFC} ale říká, že
     * chybí jen revokační údaj — autorita v seznamech je. Ve sbírce 58×, tedy víc než čtvrtina.
     */
    @Test
    @DisplayName("chybějící revokační údaj se nesmí číst jako chybějící kotva důvěry — 58×")
    void missingRevocationIsNotMissingChain() {
        assertEquals(TimestampFailure.NO_REVOCATION, TimestampFailure.of(
                List.of("BBB_XCV_SUB_ANS_2", "BBB_XCV_IRDPFC_ANS", "PSV_IPTVC_ANS",
                        "QUAL_HAS_GRANTED_AT_ANS"),
                "NO_CERTIFICATE_CHAIN_FOUND_NO_POE"));
    }

    @Test
    @DisplayName("řetěz opravdu nevede ke kotvě důvěry — 2×")
    void genuinelyNoChain() {
        assertEquals(TimestampFailure.NO_CHAIN, TimestampFailure.of(
                List.of("BBB_XCV_CCCBB_TSP_ANS", "ASCCM_PKSK_ANS",
                        "QUAL_CERT_TRUSTED_LIST_REACHED_ANS"),
                "NO_CERTIFICATE_CHAIN_FOUND"));
    }

    /**
     * {@code HPCCVVT} = „nenalezen řetěz platný k okamžiku ověření", tedy expirace. Dílčí
     * indikace ho také hlásí jako chybějící řetěz; ve sbírce 5×.
     */
    @Test
    @DisplayName("řetěz neplatný k dnešku patří k expiraci, ne k chybějící kotvě — 5×")
    void chainNotValidNowIsExpiry() {
        assertEquals(TimestampFailure.EXPIRED_NO_POE, TimestampFailure.of(
                List.of("BBB_XCV_HPCCVVT_ANS", "ASCCM_AR_ANS_AKSNR", "PSV_IPTVC_ANS",
                        "QUAL_HAS_GRANTED_AT_ANS"),
                "NO_CERTIFICATE_CHAIN_FOUND_NO_POE"));
    }

    /**
     * Expirace má přednost před zastaralým algoritmem, i když ten je v protokolu nápadnější.
     * Změřeno na „certifikacni_a_uznavany_s_razitky_s_revinfo.pdf": po zapnutí ústupku k SHA-1
     * chyby {@code ASCCM_*} zmizely a indikace se nezměnila, takže verdikt držela expirace.
     */
    @Test
    @DisplayName("expirace přebíjí zastaralý algoritmus")
    void expiryBeatsWeakAlgorithm() {
        assertEquals(TimestampFailure.EXPIRED_NO_POE, TimestampFailure.of(
                List.of("BBB_XCV_SUB_ANS", "BBB_XCV_ICTIVRSC_ANS", "ASCCM_AR_ANS_AKSNR",
                        "PSV_IPTVC_ANS", "QUAL_HAS_QTST_ANS"),
                "OUT_OF_BOUNDS_NOT_REVOKED"));
    }

    @Test
    @DisplayName("odvolání přebíjí všechno ostatní")
    void revocationBeatsEverything() {
        assertEquals(TimestampFailure.REVOKED, TimestampFailure.of(
                List.of("BBB_XCV_ISCR_ANS", "BBB_XCV_ICTIVRSC_ANS", "ASCCM_AR_ANS_AKSNR"),
                "OUT_OF_BOUNDS_NO_POE"));
    }

    /** Zastaralý algoritmus jako jediná výhrada — ve sbírce se nikdy neukázal. */
    @Test
    @DisplayName("samotný zastaralý algoritmus — znění do zásoby, ve sbírce 0×")
    void weakAlgorithmAlone() {
        assertEquals(TimestampFailure.CRYPTO, TimestampFailure.of(
                List.of("ASCCM_AR_ANS_ANR"), "CRYPTO_CONSTRAINTS_FAILURE_NO_POE"));
    }

    @Test
    @DisplayName("bez klíčů rozhoduje dílčí indikace")
    void fallsBackToSubIndication() {
        assertAll(
                () -> assertEquals(TimestampFailure.EXPIRED_NO_POE,
                        TimestampFailure.of(List.of(), "OUT_OF_BOUNDS_NO_POE")),
                () -> assertEquals(TimestampFailure.BROKEN,
                        TimestampFailure.of(null, "HASH_FAILURE")),
                () -> assertEquals(TimestampFailure.NO_DATA,
                        TimestampFailure.of(null, "SIGNED_DATA_NOT_FOUND")),
                () -> assertEquals(TimestampFailure.OTHER,
                        TimestampFailure.of(List.of(), "FORMAT_FAILURE")),
                () -> assertEquals(TimestampFailure.OTHER,
                        TimestampFailure.of(List.of(), null)));
    }

    /**
     * Klíče, které o příčině nevypovídají, nesmí zařazení strhnout. {@code QUAL_*} mluví
     * o kvalifikovanosti a {@code PSV_IPTVC_ANS} jen konstatuje, že ověření podle minulosti
     * nebylo průkazné — to je důsledek, ne příčina.
     */
    @Test
    @DisplayName("průvodní klíče zařazení neovlivní")
    void bystanderKeysDoNotDecide() {
        assertEquals(TimestampFailure.OTHER, TimestampFailure.of(
                List.of("PSV_IPTVC_ANS", "QUAL_HAS_GRANTED_AT_ANS"), "NO_POE"));
    }
}
