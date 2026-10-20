package cz.digitalnikontinuita.anep.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the trusted-list maintenance that runs after startup from competing with what the
 * user actually asked for.
 *
 * <p>The freshness check downloads and parses ~40 national trusted lists; measured against a
 * cold start, a validation overlapping it took ~4 s instead of ~0.3 s, purely from contention.
 * Background tasks call {@link #awaitIdle()} between units of work and pause while a
 * validation is running.
 */
public final class BackgroundWork {

    private static final AtomicInteger FOREGROUND = new AtomicInteger();
    private static final Object LOCK = new Object();

    private BackgroundWork() { }

    /** Marks the start of user-initiated work that background tasks must yield to. */
    public static void beginForeground() {
        FOREGROUND.incrementAndGet();
    }

    /** Marks the end of user-initiated work; wakes any background task waiting on it. */
    public static void endForeground() {
        if (FOREGROUND.decrementAndGet() <= 0) {
            synchronized (LOCK) {
                LOCK.notifyAll();
            }
        }
    }

    public static boolean isForegroundBusy() {
        return FOREGROUND.get() > 0;
    }

    /**
     * Blocks the calling background thread while a validation is running. Returns immediately
     * when nothing is in the foreground. Interruption is treated as "carry on".
     */
    public static void awaitIdle() {
        while (FOREGROUND.get() > 0) {
            synchronized (LOCK) {
                if (FOREGROUND.get() <= 0) return;
                try {
                    LOCK.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
