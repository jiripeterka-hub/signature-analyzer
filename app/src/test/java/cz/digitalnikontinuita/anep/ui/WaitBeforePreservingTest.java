package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rada „počkejte s uchováním, až vydavatel zveřejní čerstvější revokační údaj".
 *
 * <p>Od chvíle, kdy situace 51 přestala brát nabídku, se uchování nabídne i vteřinu po podpisu —
 * a tehdy vloží do dokumentu údaj starší než podpis a archivní razítko ho zafixuje natrvalo.
 * Doloženo na {@code T 2020 10 15 po revokaci Signer s revinfo_preserved_….pdf}, který situaci 51
 * nese dál i po uchování.
 *
 * <p>Podstatné jsou případy, kdy rada <b>nepadne</b>: u staršího dokumentu s expirovaným
 * certifikátem už žádný novější údaj nevznikne a „počkejte" by bylo bez konce.
 */
class WaitBeforePreservingTest {

    private static SigFixture.Builder cerstvyPlatny() {
        return SigFixture.sig()
                .indication("TOTAL_PASSED")
                .situations(31, 32, 33, 34, 51)
                .bestSignatureTime(Instant.now().minus(2, ChronoUnit.MINUTES));
    }

    @Test
    void radiSePockatUCerstvehoPlatnehoPodpisu() {
        assertTrue(AdvisorView.fresherRevocationLikelySoon(cerstvyPlatny().build(), true));
    }

    /** Přesně ten hlášený případ: pečeť z roku 2019, certifikát dávno prošlý. */
    @Test
    void mlciUStarehoDokumentu() {
        SignatureSummary sig = cerstvyPlatny()
                .bestSignatureTime(Instant.now().minus(2000, ChronoUnit.DAYS)).build();
        assertFalse(AdvisorView.fresherRevocationLikelySoon(sig, true),
                "u expirovaného certifikátu už novější údaj nevznikne, čekání nepomůže");
    }

    @Test
    void mlciTesneZaHranici() {
        SignatureSummary sig = cerstvyPlatny()
                .bestSignatureTime(Instant.now().minus(25, ChronoUnit.HOURS)).build();
        assertFalse(AdvisorView.fresherRevocationLikelySoon(sig, true));
    }

    @Test
    void mlciBezSituace51() {
        SignatureSummary sig = cerstvyPlatny().situations(31, 32, 33, 34).build();
        assertFalse(AdvisorView.fresherRevocationLikelySoon(sig, true),
                "bez situace 51 není co dohánět");
    }

    @Test
    void mlciUNeplatnehoPrvku() {
        SignatureSummary sig = cerstvyPlatny().indication("INDETERMINATE").build();
        assertFalse(AdvisorView.fresherRevocationLikelySoon(sig, false),
                "u nekladného verdiktu se neřeší načasování úkonu, ale verdikt sám");
    }

    /**
     * Bez razítka rozhoduje deklarovaný čas, a ten fixtura nastavuje 900 dní zpátky. Hlídá to,
     * že se chybějící razítko nečte jako „stáří neznámé, tedy čerstvé".
     */
    @Test
    void mlciKdyzJeZnamJenStaryDeklarovanyCas() {
        SignatureSummary sig = SigFixture.sig()
                .indication("TOTAL_PASSED").situations(51).build();
        assertFalse(AdvisorView.fresherRevocationLikelySoon(sig, true));
    }
}
