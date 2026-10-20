package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Kind;
import cz.digitalnikontinuita.anep.ui.ContinuityHorizon.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Přenos horizontů po řadě razítek — {@link ContinuityElementBlocks#coveredBy}.
 *
 * <p>Předloha: dokument se čtyřmi razítky (nález uživatele 25. 9. 2026). Razítko č. 2 bylo
 * nekvalifikované s certifikátem do roku 2019, ale kryla ho souvislá řada až po razítko s
 * certifikátem do roku 2030 — a záložka o něm psala „již uplynulo“.
 */
class TimestampCoverageTest {

    private static final Run STRICT = new Run(true, List.of(), null);
    private static final Instant NOW = Instant.now();

    private static ContinuityElementBlocks.Element stamp(Instant certNotAfter, boolean qualified) {
        var b = SigFixture.sig().format("TimeStampToken").indication("TOTAL_PASSED")
                .certNotAfter(certNotAfter);
        if (qualified) b.qualified();
        SignatureSummary sig = b.build();
        return ContinuityElementBlocks.element(sig, "TOTAL_PASSED", STRICT, false, true, null);
    }

    @Test
    @DisplayName("Razítko kryté souvislou řadou převezme horizont posledního razítka")
    void aCoveredStampTakesTheChainHorizon() {
        Instant old = NOW.minus(2000, ChronoUnit.DAYS);
        Instant later = NOW.plus(1800, ChronoUnit.DAYS);
        var first = stamp(old, false);                  // vlastní horizont: uplynul
        var last = stamp(later, true);                  // kvalifikované, do budoucna
        var out = ContinuityElementBlocks.coveredBy(List.of(first, last),
                Map.of(0, List.of(1)),
                List.of(old.minus(400, ChronoUnit.DAYS), old.minus(100, ChronoUnit.DAYS)));

        assertEquals(last.anep(), out.get(0).anep(), "AnEP má převzít horizont kryjícího razítka");
        assertEquals(last.tolerant(), out.get(0).tolerant(),
                "CzechPOINT: kryjící razítko vzniklo za platnosti certifikátu, řada drží");
        assertEquals(2, out.get(0).anepVia().number());
        assertSame(last, out.get(1), "Poslední razítko se nemění");
    }

    @Test
    @DisplayName("Kryjící razítko vzniklé až po expiraci řadu pro CzechPOINT nepřenese")
    void aLateCoverDoesNotHelpTheStrictReading() {
        Instant old = NOW.minus(2000, ChronoUnit.DAYS);
        Instant later = NOW.plus(1800, ChronoUnit.DAYS);
        var first = stamp(old, false);
        var last = stamp(later, true);
        var out = ContinuityElementBlocks.coveredBy(List.of(first, last),
                Map.of(0, List.of(1)),
                List.of(old.minus(400, ChronoUnit.DAYS), old.plus(100, ChronoUnit.DAYS)));

        assertEquals(first.tolerant(), out.get(0).tolerant(),
                "Mezera v řadě: tenhle okruh razítko po expiraci neuzná");
        assertNull(out.get(0).tolerantVia());
        assertEquals(first.anep(), out.get(0).anep(),
                "Vlastní horizont uplynul dřív, než kryjící razítko vzniklo");
    }

    @Test
    @DisplayName("Spodní mez „nejméně do“ otevřený horizont nepřebije")
    void anOpenHorizonDoesNotReplaceALowerBound() {
        Instant soon = NOW.plus(300, ChronoUnit.DAYS);
        var first = stamp(soon, true);                              // nejméně do „soon“
        var last = stamp(NOW.minus(10, ChronoUnit.DAYS), true);     // expirované → OPEN
        assertEquals(Kind.OPEN, last.anep().kind());
        var out = ContinuityElementBlocks.coveredBy(List.of(first, last),
                Map.of(0, List.of(1)),
                List.of(NOW.minus(900, ChronoUnit.DAYS), NOW.minus(800, ChronoUnit.DAYS)));
        assertEquals(first.anep(), out.get(0).anep());
    }
}
