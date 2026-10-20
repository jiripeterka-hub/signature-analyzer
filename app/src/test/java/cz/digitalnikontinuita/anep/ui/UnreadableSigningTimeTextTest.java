package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Situace 22 — podpis bez čitelného atributu signing-time — jako poznámka k výsledku.
 *
 * <p>Nález z 3. 10. 2026 na {@code T uznávaný_preserved_…asics}: AnEP o něm tvrdilo „Podpis
 * neobsahuje atribut 'signing-time'", ačkoli v podpisu stojí — zapsaný jako GeneralizedTime místo
 * UTCTime, které pro léta 1950–2049 chce RFC 5652. Týž den přestal AnEP atribut vyžadovat; situace
 * 22 zůstala jako poznámka, že validátory na výchozí politice DSS podpis odmítnout mohou.
 */
class UnreadableSigningTimeTextTest {

    @Test
    @DisplayName("Nečitelný signing-time se za chybějící nevydává")
    void unreadableIsNotCalledMissing() {
        SignatureSummary sig = SigFixture.sig().situations(22).signingTimeUnreadable().build();
        String situation = ResultView.situationLabel(22, sig);
        assertFalse(situation.contains("nenese"), situation);
        assertTrue(situation.contains("GeneralizedTime"), situation);
        assertTrue(situation.contains("AnEP jej nevyžaduje"), situation);
    }

    @Test
    @DisplayName("Chybějící signing-time: poznámka, ne překážka")
    void missingIsANote() {
        SignatureSummary sig = SigFixture.sig().situations(22).build();
        String situation = ResultView.situationLabel(22, sig);
        assertTrue(situation.contains("nenese"), situation);
        assertTrue(situation.contains("AnEP jej"), situation);
        assertTrue(situation.contains("mohou podpis kvůli tomu"), situation);
    }
}
