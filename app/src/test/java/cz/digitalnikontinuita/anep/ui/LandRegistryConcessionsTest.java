package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.ReportMapper.Relaxation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ústupky podatelny katastru v bloku o vkladové listině: všechny kromě SHA-1, kterou podatelna
 * nepřijímá ({@link LandRegistryDeed}).
 */
class LandRegistryConcessionsTest {

    @Test
    @DisplayName("podatelna připouští tři ústupky, SHA-1 mezi nimi není")
    void concessionSet() {
        assertEquals(Set.of(Relaxation.NEWER_OCSP, Relaxation.EXPIRED_REVOCATION_SOURCES,
                Relaxation.PDF_NOT_ETSI), LandRegistryDeed.LAND_REGISTRY_CONCESSIONS);
    }

    @Test
    @DisplayName("platný jen díky SHA-1 podatelna odmítne")
    void validOnlyThanksToSha1() {
        assertTrue(LandRegistryDeed.sha1Rejected("TOTAL_PASSED",
                List.of(Relaxation.SHA1), false, List.of()));
        assertTrue(LandRegistryDeed.sha1Rejected("TOTAL_PASSED",
                List.of(Relaxation.SHA1, Relaxation.NEWER_OCSP), false, List.of()));
    }

    @Test
    @DisplayName("zastupuje-li SHA-1 jiný ústupek, verdikt nese ten druhý")
    void sha1Interchangeable() {
        assertFalse(LandRegistryDeed.sha1Rejected("TOTAL_PASSED",
                List.of(Relaxation.NEWER_OCSP, Relaxation.SHA1), true, List.of()));
        assertEquals(Set.of(Relaxation.NEWER_OCSP), LandRegistryDeed.landRegistryConcessions(
                List.of(Relaxation.NEWER_OCSP, Relaxation.SHA1), true));
    }

    @Test
    @DisplayName("neznámá platnost kvůli SHA-1 (důvod 15) podatelna odmítne, jiná ne")
    void undeterminedSha1() {
        assertTrue(LandRegistryDeed.sha1Rejected("INDETERMINATE", List.of(), false, List.of(15, 12)));
        assertFalse(LandRegistryDeed.sha1Rejected("INDETERMINATE", List.of(), false, List.of(12)));
    }

    @Test
    @DisplayName("platný podle standardů žádné ústupky nevykazuje")
    void standardsValid() {
        assertFalse(LandRegistryDeed.sha1Rejected("TOTAL_PASSED", List.of(), false, List.of()));
        assertTrue(LandRegistryDeed.landRegistryConcessions(List.of(), false).isEmpty());
    }

    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-08T12:00:00Z");

    private static cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry razitko(
            String tsaNotAfter, boolean anchor) {
        return new cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry(
                java.time.Instant.parse("2020-01-01T00:00:00Z"), true, "TSA",
                java.time.Instant.parse(tsaNotAfter), "sig", true, true, null, null, false,
                null, anchor);
    }

    @Test
    @DisplayName("s ústupky a jen s razítkem po expiraci certifikátu TSA: přes SecuSign neobstojí")
    void timestampDoubt() {
        var expiredTs = List.of(razitko("2025-01-01T00:00:00Z", false));
        // poslední podpis — o jeho razítko se opírá požadavek na kvalifikované razítko
        assertTrue(LandRegistryDeed.timestampDoubt(true, true,
                java.time.Instant.parse("2027-01-01T00:00:00Z"), expiredTs, NOW));
        // dřívější podpis s expirovaným certifikátem potřebuje razítko jako důkaz existence
        assertTrue(LandRegistryDeed.timestampDoubt(true, false,
                java.time.Instant.parse("2024-01-01T00:00:00Z"), expiredTs, NOW));
    }

    @Test
    @DisplayName("bez pochybnosti: podle standardů, platné razítko, kotva nebo razítko netřeba")
    void noTimestampDoubt() {
        var expiredTs = List.of(razitko("2025-01-01T00:00:00Z", false));
        assertFalse(LandRegistryDeed.timestampDoubt(false, true, null, expiredTs, NOW));
        assertFalse(LandRegistryDeed.timestampDoubt(true, true, null,
                List.of(razitko("2030-01-01T00:00:00Z", false)), NOW));
        assertFalse(LandRegistryDeed.timestampDoubt(true, true, null,
                List.of(razitko("2025-01-01T00:00:00Z", true)), NOW));
        assertFalse(LandRegistryDeed.timestampDoubt(true, false,
                java.time.Instant.parse("2027-01-01T00:00:00Z"), expiredTs, NOW));
    }

    @Test
    @DisplayName("nezastupitelné ústupky podatelny se vypíšou všechny")
    void allRequiredListed() {
        assertEquals(Set.of(Relaxation.NEWER_OCSP, Relaxation.PDF_NOT_ETSI),
                LandRegistryDeed.landRegistryConcessions(
                        List.of(Relaxation.PDF_NOT_ETSI, Relaxation.NEWER_OCSP), false));
    }
}
