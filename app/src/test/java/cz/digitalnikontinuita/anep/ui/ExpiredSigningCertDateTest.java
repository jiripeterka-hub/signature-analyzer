package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Důvod 4 řekne, kdy ten podpisový certifikát expiroval.
 *
 * <p>Přání uživatele z 22. 9. 2026, totéž jako u důvodů 2 a 6 den předtím: věta tvrdila, že prvek
 * stojí na certifikátu, který už expiroval, ale den expirace si čtenář musel najít v jiné záložce —
 * a přitom je to přesně ten údaj, podle kterého pozná, zda mu rada pod větou (ověřování k zadanému
 * datu) může být co platná.
 *
 * <p>Na rozdíl od důvodů 2 a 6 je tu certifikát jen jeden a je vždy podpisový: situace 3, ze které
 * důvod 4 vzniká, se hlásí jen tehdy, když prvek nemá jediné razítko a expiroval právě podpisový
 * certifikát. Datum se proto bere z {@code getCertNotAfter()}, tedy z téhož údaje, jakým tu situaci
 * našlo jádro.
 */
class ExpiredSigningCertDateTest {

    private static final Instant EXPIRY = Instant.parse("2019-08-14T09:12:33Z");

    private static String problem(SignatureSummary sig) {
        return AdvisorView.reasonProblem(sig, 4, ResultView.REASONS.get(4));
    }

    @Test
    @DisplayName("Datum expirace se dosadí do věty")
    void theExpiryIsFilledIn() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(4)
                .certNotAfter(EXPIRY)
                .build();

        String text = problem(sig);
        assertTrue(text.contains("K tomu došlo"), text);
        assertTrue(text.contains("2019"), text);
        assertFalse(text.contains("{"), "zástupné slovo zůstalo nedoplněné: " + text);
    }

    /** Věta stojí mezi tou o expiraci a tou o ztrátě kontinuity — tak ji chtěl uživatel. */
    @Test
    @DisplayName("Stojí mezi první a druhou větou")
    void theSentenceStandsBetweenTheOtherTwo() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(4)
                .certNotAfter(EXPIRY)
                .build();

        String text = problem(sig);
        int expired = text.indexOf("řádná doba jeho platnosti");
        int when = text.indexOf("K tomu došlo");
        int loss = text.indexOf("To znamená ztrátu");
        assertTrue(expired >= 0 && when > expired && loss > when, text);
    }

    @Test
    @DisplayName("Bez data platí znění bez té věty")
    void withoutTheDateTheBareWordingApplies() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(4)
                .build();

        String text = problem(sig);
        assertEquals(ReasonTexts.get("4/bez-data", "problem"), text, text);
        assertFalse(text.contains("{"), text);
        assertFalse(text.contains("K tomu došlo"), "bez data se ta věta vynechá celá: " + text);
    }

    @Test
    @DisplayName("Katalogové znění zástupné slovo opravdu nese")
    void theCatalogueTextCarriesThePlaceholder() {
        assertTrue(ResultView.REASONS.get(4).problem().contains("{konec}"));
    }

    /**
     * Prvek, který kryje pozdější prvek s platným razítkem, má dřívější existenci doloženou —
     * katalogové znění o něm tvrdí opak („není opatřen časovým razítkem … ztrátu digitální
     * kontinuity, kterou již nejde obnovit"). Nález uživatele 22. 9. 2026.
     */
    @Test
    @DisplayName("S krycím důkazem platí znění, které netvrdí nenávratnou ztrátu")
    void withACoveringProofTheWordingChanges() {
        SignatureSummary sig = SigFixture.sig()
                .indication("INDETERMINATE")
                .reasons(4)
                .certNotAfter(EXPIRY)
                .coveringProof("Prvek č. 2 v témže dokumentu kryje tento podpis…")
                .build();

        String text = problem(sig);
        assertTrue(text.contains("Dřívější existenci"), text);
        assertTrue(text.contains("2019"), "datum expirace se dosadilo i sem: " + text);
        assertFalse(text.contains("nejde obnovit"), "tvrdí nenávratnou ztrátu: " + text);
        assertFalse(text.contains("{"), text);
    }
}
