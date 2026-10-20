package cz.digitalnikontinuita.anep.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pořadí, v jakém Analýza vypisuje důvody.
 *
 * <p>Tři skupiny: napřed důvody, které žádný ústupek nespraví (mezi nimi 22 první), pak ty, které
 * ústupek spraví, a nakonec důvod 22, přišlo-li odvolání až po konci platnosti certifikátu.
 * Rozdělení je změřené na sbírce (ReasonOrderScan, 25. 9. 2026) — viz
 * {@code AdvisorView.RELAXABLE_REASONS}.
 *
 * <p>Původní nález z „po revokaci QES.pdf“ (pořadí 15, 22 — čtenář četl o SHA-1 dřív než
 * o odvolaném certifikátu) platí dál: odvolání za platnosti jde první.
 */
class ReasonOrderTest {

    private static final Instant NOT_AFTER = Instant.parse("2019-12-07T09:38:35Z");

    @Test
    @DisplayName("Důvod 22 (odvolání za platnosti) jde na první místo")
    void reason22GoesFirst() {
        assertEquals(List.of(22, 15), AdvisorView.reasonsInDisplayOrder(List.of(15, 22)));
    }

    @Test
    @DisplayName("Nespravitelné důvody předcházejí spravitelným, uvnitř skupin pořadí zůstává")
    void decisiveBeforeRelaxable() {
        assertEquals(List.of(22, 34, 21, 6, 15),
                AdvisorView.reasonsInDisplayOrder(List.of(6, 15, 34, 21, 22)));
    }

    @Test
    @DisplayName("Když pořadí sedí, vrací se seznam tak, jak přišel")
    void alreadyInOrder() {
        List<Integer> reasons = List.of(22, 6);
        assertSame(reasons, AdvisorView.reasonsInDisplayOrder(reasons));
        List<Integer> decisiveFirst = List.of(4, 15);
        assertSame(decisiveFirst, AdvisorView.reasonsInDisplayOrder(decisiveFirst));
    }

    /**
     * Nález uživatele 25. 9. 2026 nad „2result.pdf“: certifikát platný do 7. 12. 2019, odvolaný
     * 30. 11. 2020. Ověřování skončilo na důvodu 4; odvolání po konci platnosti jde na konec.
     */
    @Test
    @DisplayName("2result.pdf: 4, 1, 15, 22")
    void revocationAfterExpiryGoesLast() {
        Instant revoked = Instant.parse("2020-11-30T10:23:59Z");
        assertEquals(List.of(4, 1, 15, 22), AdvisorView.reasonsInDisplayOrder(
                List.of(1, 4, 15, 22), revoked, NOT_AFTER, false));
    }

    @Test
    @DisplayName("Odvolání za platnosti nechává důvod 22 na prvním místě")
    void revocationWithinValidityStaysFirst() {
        Instant revoked = Instant.parse("2019-06-01T00:00:00Z");
        assertEquals(List.of(22, 4, 1, 15), AdvisorView.reasonsInDisplayOrder(
                List.of(1, 4, 15, 22), revoked, NOT_AFTER, false));
    }

    @Test
    @DisplayName("Chybějící podepsaný soubor jde před revokační výhrady")
    void missingDataFirst() {
        assertEquals(List.of(33, 2, 6),
                AdvisorView.reasonsInDisplayOrder(List.of(2, 6, 33)));
    }

    /**
     * Razítko, které neobstálo jen kvůli zastaralému algoritmu, spraví týž ústupek jako SHA-1
     * u podpisu — důvod 21 pak rozhodující není a pořadí [15, 21] zůstává. Jinak jde 21 napřed.
     */
    @Test
    @DisplayName("Důvod 21 se řadí podle příčiny selhání razítka")
    void reason21FollowsTheTimestampCause() {
        assertEquals(List.of(15, 21), AdvisorView.reasonsInDisplayOrder(
                List.of(15, 21), null, null, true));
        assertEquals(List.of(21, 15), AdvisorView.reasonsInDisplayOrder(
                List.of(15, 21), null, null, false));
    }

    @Test
    @DisplayName("Jediný důvod ani prázdný seznam nepotřebují nic dělat")
    void shortListsAreReturnedAsIs() {
        List<Integer> single = List.of(22);
        assertSame(single, AdvisorView.reasonsInDisplayOrder(single));
        List<Integer> none = List.of();
        assertSame(none, AdvisorView.reasonsInDisplayOrder(none));
    }
}
