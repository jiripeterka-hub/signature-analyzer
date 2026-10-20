package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static cz.digitalnikontinuita.anep.ui.AdvisorView.OwnTimestampStatus;
import static cz.digitalnikontinuita.anep.ui.AdvisorView.earliestValidOwnTimestamp;
import static cz.digitalnikontinuita.anep.ui.AdvisorView.ownTimestampStatus;
import static cz.digitalnikontinuita.anep.ui.SigFixture.FUTURE;
import static cz.digitalnikontinuita.anep.ui.SigFixture.PAST;
import static cz.digitalnikontinuita.anep.ui.SigFixture.STAMPED;
import static cz.digitalnikontinuita.anep.ui.SigFixture.sig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Odstavec „Důkaz existence v čase" nesmí tvrdit, že se razítko nepodařilo ověřit, když obstálo.
 *
 * <p>Nález uživatele z 13. 9. 2026 na protipodpisu: Rozšířený přehled hlásil razítko jako platné
 * a Analýza u téhož razítka tvrdila opak. Příčinou bylo jediné větvení, které slilo dvě různé
 * věci — „žádné razítko neobstálo" a „razítko obstálo, ale rozhodný okamžik doložil někdo jiný".
 */
class OwnTimestampStatusTest {

    private static TimestampEntry stamp(Instant when, boolean passed) {
        return new TimestampEntry(when, passed, "TSA", FUTURE, "sig", true);
    }

    @Test
    @DisplayName("Bez razítek není co ověřovat")
    void noTimestamps() {
        SignatureSummary s = sig().build();
        assertEquals(OwnTimestampStatus.NONE_VALID, ownTimestampStatus(s, null));
        assertNull(earliestValidOwnTimestamp(s));
    }

    @Test
    @DisplayName("Razítko, které neobstálo, se za platné vydávat nesmí")
    void onlyFailedTimestamp() {
        SignatureSummary s = sig().timestamps(List.of(stamp(STAMPED, false))).build();
        assertEquals(OwnTimestampStatus.NONE_VALID, ownTimestampStatus(s, null));
        assertNull(earliestValidOwnTimestamp(s));
    }

    @Test
    @DisplayName("Platné razítko a rozhodný okamžik od jiného prvku: situace 54")
    void provedByCoveringElement() {
        SignatureSummary s = sig()
                .timestamps(List.of(stamp(STAMPED, true)))
                .situations(54)
                .coveringProof("Prvek č. 2 … je protipodpisem nad tímto podpisem …")
                .build();
        // Rozhodný okamžik je pozdější než vlastní razítko prvku — právě tak vypadá protipodpis,
        // který z povahy věci vzniká až po podpisu, nad kterým stojí.
        assertEquals(OwnTimestampStatus.PROVED_BY_OTHER_ELEMENT,
                ownTimestampStatus(s, STAMPED.plusSeconds(1_100)));
    }

    @Test
    @DisplayName("Situace 54 bez věty o krycím prvku na tu větev nestačí")
    void situation54WithoutDetail() {
        SignatureSummary s = sig().timestamps(List.of(stamp(STAMPED, true))).situations(54).build();
        assertEquals(OwnTimestampStatus.PROVES_NOTHING, ownTimestampStatus(s, null));
    }

    @Test
    @DisplayName("Platné razítko, které přesto nic nedokládá — razítko ve VRI, situace 52")
    void validButProvesNothing() {
        SignatureSummary s = sig().timestamps(List.of(stamp(STAMPED, true))).situations(52).build();
        assertEquals(OwnTimestampStatus.PROVES_NOTHING, ownTimestampStatus(s, null));
    }

    @Test
    @DisplayName("Prokázaný okamžik je, ale razítka prvku za ním nestojí")
    void provenElsewhere() {
        SignatureSummary s = sig().timestamps(List.of(stamp(STAMPED, true))).build();
        assertEquals(OwnTimestampStatus.OTHER_SOURCE, ownTimestampStatus(s, PAST));
    }

    @Test
    @DisplayName("Vybírá se nejstarší razítko, které obstálo — ne nejstarší ze všech")
    void earliestAmongValid() {
        Instant older = STAMPED.minusSeconds(3_600);
        SignatureSummary s = sig()
                .timestamps(List.of(stamp(older, false), stamp(STAMPED, true)))
                .build();
        TimestampEntry picked = earliestValidOwnTimestamp(s);
        assertEquals(STAMPED, picked.time());
    }
}
