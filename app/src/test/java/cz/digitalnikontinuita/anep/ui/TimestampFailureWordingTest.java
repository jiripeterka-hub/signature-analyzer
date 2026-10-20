package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampEntry;
import cz.digitalnikontinuita.anep.model.SignatureSummary.TimestampFailure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * „Bez důkazu o čase“ se smí říct jen tam, kde ten důkaz opravdu chybí.
 *
 * <p>Nález uživatele z 20. 9. 2026: podpisové časové razítko dostalo v Rozšířeném přehledu
 * popisek „prošlý certifikát autority bez důkazu o čase“, ačkoli v témže dokumentu leží
 * dokumentové razítko o měsíc mladší, které ho v čase fixuje — a fixuje ho <b>uvnitř</b>
 * platnosti certifikátu jeho autority. Že ten důkaz existuje, ví i DSS: v podrobném protokolu
 * u toho razítka stojí {@code ValidationTime="TIMESTAMP_POE_TIME"} právě s časem toho
 * dokumentového razítka.
 *
 * <p>Co platí dál: certifikát autority je opravdu prošlý a ověřit se razítko nepodařilo
 * ({@code PSV_IPTVC_ANS}, „past time-stamp validation is not conclusive“). Jen to není tím, že
 * by chybělo, čím dřívější existenci doložit.
 */
class TimestampFailureWordingTest {

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("Kryje-li razítko pozdější, popisek o chybějícím důkazu mlčí")
    void aLaterTimestampTakesTheMissingProofOutOfTheWording() {
        String withProof = ExtendedView.failureReason(TimestampFailure.EXPIRED_NO_POE, true);
        assertFalse(withProof.contains("bez důkazu o čase"),
                "Důkaz existuje, tvrdit opak je nepravda: " + withProof);
        assertTrue(withProof.startsWith("prošlý certifikát autority"),
                "Prošlý certifikát ale prošlý zůstává: " + withProof);
        assertTrue(withProof.contains("nepodařilo"),
                "A že se ověřit nepodařilo, musí zůstat taky: " + withProof);

        assertEquals("prošlý certifikát autority bez důkazu o čase",
                ExtendedView.failureReason(TimestampFailure.EXPIRED_NO_POE, false),
                "Kde důkaz opravdu chybí, znění zůstává beze změny");
    }

    @Test
    @DisplayName("Ostatní příčiny se pozdějším razítkem nemění")
    void theOtherCausesAreUntouched() {
        for (TimestampFailure cause : List.of(TimestampFailure.CRYPTO, TimestampFailure.REVOKED,
                TimestampFailure.NO_CHAIN, TimestampFailure.NO_REVOCATION,
                TimestampFailure.BROKEN, TimestampFailure.NO_DATA)) {
            assertEquals(ExtendedView.failureReason(cause, false),
                    ExtendedView.failureReason(cause, true), cause.name());
        }
    }

    /**
     * Rozhoduje čas <b>následujícího</b> razítka, a jen když samo obstálo — u posledního razítka
     * je „posuzovaný okamžik“ současnost, a ta důkaz není.
     */
    @Test
    @DisplayName("Důkaz dává jen pozdější razítko, které samo obstálo")
    void onlyAPassingLaterTimestampCounts() {
        Instant signed = NOW.minus(1200, ChronoUnit.DAYS);
        Instant stamped = NOW.minus(1170, ChronoUnit.DAYS);
        Instant tsaExpiry = NOW.minus(300, ChronoUnit.DAYS);

        TimestampEntry failing = ts(signed, false, tsaExpiry);
        TimestampEntry laterOk = ts(stamped, true, NOW.plus(900, ChronoUnit.DAYS));
        assertTrue(ExtendedView.provenInTime(List.of(failing, laterOk), 0),
                "Pozdější razítko vzniklo ještě za platnosti certifikátu toho prvního");

        TimestampEntry laterFailed = ts(stamped, false, NOW.plus(900, ChronoUnit.DAYS));
        assertFalse(ExtendedView.provenInTime(List.of(failing, laterFailed), 0),
                "Razítko, které samo neobstálo, důkazem není");

        assertFalse(ExtendedView.provenInTime(List.of(failing), 0),
                "U posledního razítka je posuzovaným okamžikem současnost, a to důkaz není");

        // Pozdější razítko až po expiraci certifikátu toho prvního: důkaz sice je, ale mimo
        // platnost — a pak „bez důkazu o čase“ sedí věcně i dál.
        TimestampEntry tooLate = ts(NOW.minus(100, ChronoUnit.DAYS), true,
                NOW.plus(900, ChronoUnit.DAYS));
        assertFalse(ExtendedView.provenInTime(List.of(failing, tooLate), 0));
    }

    private static TimestampEntry ts(Instant time, boolean passed, Instant notAfter) {
        return new TimestampEntry(time, passed, "TSA", notAfter, "sig", true, true);
    }
}
