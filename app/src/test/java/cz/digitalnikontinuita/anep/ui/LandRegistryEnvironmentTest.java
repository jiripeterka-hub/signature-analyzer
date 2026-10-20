package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kdy za neznámou platnost podpisu může prostředí, takže blok o vkladové listině řekne
 * „nelze posoudit“ místo „nesplňuje“ ({@link LandRegistryDeed#environmental}).
 */
class LandRegistryEnvironmentTest {

    @Test
    @DisplayName("nedostupné revokační informace jsou vinou prostředí")
    void revocationUnavailable() {
        assertTrue(LandRegistryDeed.environmental(List.of(12), false));
    }

    @Test
    @DisplayName("neznámý certifikát je vinou prostředí jen bez důvěryhodných seznamů")
    void unknownCertificate() {
        assertTrue(LandRegistryDeed.environmental(List.of(3), true));
        assertFalse(LandRegistryDeed.environmental(List.of(3), false));
    }

    @Test
    @DisplayName("stačí jeden další důvod a o výsledku rozhoduje ten")
    void anotherReasonDecides() {
        assertFalse(LandRegistryDeed.environmental(List.of(12, 4), false));
        assertFalse(LandRegistryDeed.environmental(List.of(3, 12, 26), true));
    }

    @Test
    @DisplayName("bez důvodů se prostředí neviní")
    void noReasons() {
        assertFalse(LandRegistryDeed.environmental(List.of(), true));
        assertFalse(LandRegistryDeed.environmental(null, true));
    }
}
