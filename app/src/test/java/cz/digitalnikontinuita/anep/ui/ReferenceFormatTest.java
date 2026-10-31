package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Referenční formát podle prováděcího nařízení (EU) 2026/248 (od 31. 10. 2026, předtím podle
 * zrušeného rozhodnutí 2015/1506). Analýza a Rozšířený přehled se ptají téže funkce; dřív si
 * Rozšířený přehled četl jen slovo BASELINE a CB-AdES by u něj vyšel referenční.
 */
class ReferenceFormatTest {

    @Test
    @DisplayName("CB-AdES zatím referenční není, JAdES a ostatní baseline ano")
    void whichFormatsAreReference() {
        assertFalse(AdvisorView.isReferenceFormat("CB-AdES-BASELINE-T"));
        assertTrue(AdvisorView.isReferenceFormat("JAdES-BASELINE-B"));
        assertTrue(AdvisorView.isReferenceFormat("PAdES-BASELINE-LTA"));
        assertFalse(AdvisorView.isReferenceFormat("PKCS7-LTA"));
    }

    @Test
    @DisplayName("Dovětek u JAdES platí jen do 23. 2. 2027 a jen u JAdES")
    void jadesNoteEndsOnTheDay() {
        LocalDate before = LocalDate.of(2027, 2, 22);
        LocalDate onTheDay = LocalDate.of(2027, 2, 23);
        assertTrue(AdvisorView.jadesNotYetFormal("JAdES-BASELINE-T", before));
        assertFalse(AdvisorView.jadesNotYetFormal("JAdES-BASELINE-T", onTheDay),
                "od toho dne už „formálně až od“ neplatí");
        assertFalse(AdvisorView.jadesNotYetFormal("XAdES-BASELINE-T", before));
    }

    @Test
    @DisplayName("Rozšířený přehled říká o CB-AdES „zatím nejde“")
    void extendedViewAgreesWithAnalysis() {
        assertEquals("CB-AdES Baseline-T (zatím nejde o referenční formát)",
                ExtendedView.formatSignatureLabel("CB-AdES-BASELINE-T"));
        assertTrue(ExtendedView.formatSignatureLabel("JAdES-BASELINE-B")
                .startsWith("JAdES Baseline-B (jde o referenční formát"));
    }
}
