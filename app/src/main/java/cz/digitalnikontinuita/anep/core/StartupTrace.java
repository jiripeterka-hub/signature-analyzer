package cz.digitalnikontinuita.anep.core;

import java.time.Duration;
import java.time.Instant;

/**
 * Lightweight startup profiler. Prints milestones with the elapsed time since the
 * JVM process actually started (not since class loading), so the JVM's own boot
 * cost is visible too.
 *
 * <p>Enabled only with {@code -Danep.timing=true}; otherwise every call is a
 * single volatile read and returns immediately.
 */
public final class StartupTrace {

    private static final boolean ENABLED = Boolean.getBoolean("anep.timing");

    /** Process start; falls back to class-init time when the OS doesn't report it. */
    private static final Instant PROCESS_START = ProcessHandle.current().info().startInstant()
            .orElseGet(Instant::now);

    private static volatile long lastMillis = 0L;

    private StartupTrace() { }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Prints {@code mark} with time since process start and since the previous mark. */
    public static void mark(String label) {
        if (!ENABLED) return;
        long ms = Duration.between(PROCESS_START, Instant.now()).toMillis();
        long delta = ms - lastMillis;
        lastMillis = ms;
        System.err.printf("[timing] %6d ms (+%5d) %s%n", ms, delta, label);
    }

    /** Times {@code body} and prints how long it took. */
    public static void time(String label, Runnable body) {
        if (!ENABLED) {
            body.run();
            return;
        }
        long t0 = System.currentTimeMillis();
        body.run();
        System.err.printf("[timing] %6d ms (took %5d) %s%n",
                Duration.between(PROCESS_START, Instant.now()).toMillis(),
                System.currentTimeMillis() - t0, label);
    }
}
