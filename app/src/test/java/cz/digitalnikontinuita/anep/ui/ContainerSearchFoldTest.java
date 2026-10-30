package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hledání v prohlížeči kontejnerů nezávisle na velikosti písmen a diakritice (30. 10. 2026). */
class ContainerSearchFoldTest {

    @Test
    @DisplayName("bez diakritiky a malými písmeny")
    void fold() {
        assertEquals("pecet", ContainerView.fold("Pečeť"));
        assertEquals("podepisujici (1)", ContainerView.fold("Podepisující (1)"));
        assertTrue(ContainerView.fold("Časové razítko podpisu").contains("razitko"));
    }
}
