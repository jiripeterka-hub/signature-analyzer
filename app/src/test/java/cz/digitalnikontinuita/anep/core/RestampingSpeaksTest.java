package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pravidlo, podle kterého přerazítkování vybírá revokační údaje (17. 10. 2026): vkládá jen ty, které
 * o certifikátu něco vypovídají. Post-expirační „nerevokován“ bez závazku vydavatele se nevkládá —
 * měřením ho nevyužil CzechPOINT ani Acrobat, viz javadoc {@link RestampingService}.
 */
class RestampingSpeaksTest {

    private static final Date NOT_AFTER = d("2022-12-20T00:00:00Z");
    private static final Date BEFORE = d("2022-11-01T00:00:00Z");
    private static final Date AFTER = d("2026-10-17T00:00:00Z");

    private static Date d(String iso) {
        return Date.from(Instant.parse(iso));
    }

    @Test
    @DisplayName("Údaj vydaný za platnosti certifikátu se vloží")
    void withinValidity() {
        assertTrue(RestampingService.speaks(BEFORE, NOT_AFTER, null, null, false, false));
        assertTrue(RestampingService.speaks(NOT_AFTER, NOT_AFTER, null, null, false, false));
    }

    @Test
    @DisplayName("Post-expirační „nerevokován“ bez závazku vydavatele se nevloží")
    void postExpirySilence() {
        assertFalse(RestampingService.speaks(AFTER, NOT_AFTER, null, null, false, false));
    }

    @Test
    @DisplayName("archiveCutOff, který jen běží s dneškem, závazek není")
    void rollingArchiveCutOff() {
        // Tvar změřený na OCSP vydavatele NCA: archiveCutOff dva dny před vydáním odpovědi.
        assertFalse(RestampingService.speaks(AFTER, NOT_AFTER, null,
                d("2026-10-15T00:00:00Z"), false, false));
    }

    @Test
    @DisplayName("expiredCertsOnCRL nebo archiveCutOff do doby platnosti údaj zachrání")
    void issuerCommitment() {
        assertTrue(RestampingService.speaks(AFTER, NOT_AFTER, d("2020-01-01T00:00:00Z"),
                null, false, false));
        assertTrue(RestampingService.speaks(AFTER, NOT_AFTER, null,
                d("2018-12-01T00:00:00Z"), false, false));
        assertFalse(RestampingService.speaks(AFTER, NOT_AFTER, d("2023-06-01T00:00:00Z"),
                null, false, false));
    }

    @Test
    @DisplayName("Shodný CertHash údaj zachrání")
    void certHash() {
        assertTrue(RestampingService.speaks(AFTER, NOT_AFTER, null, null, true, false));
    }

    @Test
    @DisplayName("Revokace se vloží vždy — je to tvrzení, ne mlčení")
    void revokedAlwaysSpeaks() {
        assertTrue(RestampingService.speaks(AFTER, NOT_AFTER, null, null, false, true));
    }

    @Test
    @DisplayName("Bez dat se rozhodnout nedá — údaj se vloží")
    void unknownDates() {
        assertTrue(RestampingService.speaks(null, NOT_AFTER, null, null, false, false));
        assertTrue(RestampingService.speaks(AFTER, null, null, null, false, false));
    }
}
