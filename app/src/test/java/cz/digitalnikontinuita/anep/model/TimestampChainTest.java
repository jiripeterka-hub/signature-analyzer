package cz.digitalnikontinuita.anep.model;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pravidlo „kam sahá řada razítek“ — na něm stojí situace 31 a 32, čtení pro okruh CzechPOINTu
 * i řádek o posloupnosti v Rozšířeném přehledu.
 *
 * <p>Zkouší se tady, a ne na sbírce: tvar, ve kterém se nové pravidlo liší od starého, je
 * v korpusu vzácný (135 souborů z 1 629 s dokumentovým razítkem) a jeho okrajové varianty —
 * neznámý konec platnosti, dvě mezery za sebou — se v něm nevyskytují vůbec.
 */
class TimestampChainTest {

    private static final Instant T0 = Instant.parse("2020-01-01T10:00:00Z");

    @Test
    @DisplayName("Řada sahá tam, kam dosáhne nejzazší z razítek, ne to poslední v pořadí")
    void reachIsTheFurthestNotTheLast() {
        // Tvar z „podpis s razitkem-extended-pades-baseline-lta.pdf": podpisové razítko na
        // dlouhém certifikátu, dokumentové o něco později na kratším.
        List<TimestampEntry> chain = List.of(ts(0, 2000), ts(100, 300));

        assertEquals(day(2000), TimestampChain.reachFromStart(chain));
        assertTrue(TimestampChain.unbroken(chain));
    }

    @Test
    @DisplayName("Razítko přibylé za platnosti staršího článku mezeru netvoří")
    void anOlderStampCarriesTheChain() {
        List<TimestampEntry> chain = List.of(ts(0, 2000), ts(100, 50), ts(400, 365));

        assertTrue(TimestampChain.gaps(chain).isEmpty());
        // Nejzazší je pořád ten první článek (2000. den), ne poslední přírůstek (765. den).
        assertEquals(day(2000), TimestampChain.reachFromStart(chain));
    }

    @Test
    @DisplayName("Mezera tam, kde nové razítko nestihlo žádný ze starších certifikátů")
    void trueGapIsStillAGap() {
        List<TimestampEntry> chain = List.of(ts(0, 100), ts(500, 365));

        List<TimestampChain.Gap> gaps = TimestampChain.gaps(chain);
        assertEquals(1, gaps.size());
        assertEquals(day(100), gaps.get(0).from());
        assertEquals(day(500), gaps.get(0).to());
        assertFalse(TimestampChain.unbroken(chain));
    }

    @Test
    @DisplayName("Za mezerou už krytí podpisu nepokračuje, i když razítka pokračují")
    void reachFromStartStopsAtTheGap() {
        List<TimestampEntry> chain = List.of(ts(0, 100), ts(500, 365));

        // Co vzniklo za mezerou, se k podpisu neváže — proto krytí končí 100. dnem…
        assertEquals(day(100), TimestampChain.reachFromStart(chain));
        // …kdežto výpis řady se ptá, kam sahá to, co za mezerou přibylo.
        assertEquals(day(865), TimestampChain.reachOfLastRun(chain));
    }

    @Test
    @DisplayName("Neznámý konec platnosti nesmaže krytí doložené starším razítkem")
    void unknownExpiryDoesNotShortenTheReach() {
        List<TimestampEntry> chain = List.of(
                ts(0, 365),
                new TimestampEntry(day(300), true, "TSA", null, "doc", true));

        assertEquals(day(365), TimestampChain.reachFromStart(chain));
        assertTrue(TimestampChain.unbroken(chain));
    }

    @Test
    @DisplayName("Bez jediného známého konce platnosti se krytí nedá změřit")
    void nothingToMeasure() {
        List<TimestampEntry> chain = List.of(
                new TimestampEntry(T0, true, "TSA", null, "sig", true),
                new TimestampEntry(day(10), true, "TSA", null, "doc", true));

        assertNull(TimestampChain.reachFromStart(chain));
        assertFalse(TimestampChain.unbroken(chain),
                "Bez konce platnosti nelze ukázat, že další razítko přibylo včas.");
        assertNull(TimestampChain.reachFromStart(List.of()));
    }

    private static TimestampEntry ts(int createdDay, int certLivesDays) {
        return new TimestampEntry(day(createdDay), true, "TSA", day(createdDay + certLivesDays),
                "sig", true);
    }

    private static Instant day(int n) {
        return T0.plus(n, ChronoUnit.DAYS);
    }
}
