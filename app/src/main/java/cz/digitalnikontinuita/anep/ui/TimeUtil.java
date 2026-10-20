package cz.digitalnikontinuita.anep.ui;

import cz.digitalnikontinuita.anep.core.TimeFormat;

import java.time.Instant;
import java.util.Date;

/**
 * Okamžik pro okna — jen průchod na {@link TimeFormat}, kde je jediný zápis pro celou aplikaci.
 *
 * <p>Zůstává jako samostatná třída, protože ji volají desítky míst v {@code ui} a přejmenovat je
 * všechny by změnu formátu utopilo v přesunech. Formát sám je popsaný u {@link TimeFormat}.
 */
final class TimeUtil {

    private TimeUtil() { }

    static String format(Instant instant) {
        return TimeFormat.full(instant);
    }

    static String format(Date date) {
        return TimeFormat.full(date);
    }

    static String format(long epochMs) {
        return TimeFormat.full(epochMs);
    }
}
