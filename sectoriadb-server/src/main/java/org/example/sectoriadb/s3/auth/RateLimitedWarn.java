package org.example.sectoriadb.s3.auth;

import org.slf4j.Logger;

import java.util.function.LongSupplier;

/**
 * WARN lines for events an unauthenticated client can produce at will (failed signatures): at most {@code perWindow}
 * lines per window, the rest is only counted and reported by one summary line when the next window starts. The metric
 * {@code sectoriadb_s3_auth_failures_total} still counts every failure.
 */
final class RateLimitedWarn {

    private final Logger log;
    private final int perWindow;
    private final long windowNanos;
    private final LongSupplier clock;
    private long windowStart;
    private int logged;
    private long suppressed;

    RateLimitedWarn(Logger log, int perWindow, long windowNanos) {
        this(log, perWindow, windowNanos, System::nanoTime);
    }

    RateLimitedWarn(Logger log, int perWindow, long windowNanos, LongSupplier clock) {
        this.log = log;
        this.perWindow = perWindow;
        this.windowNanos = windowNanos;
        this.clock = clock;
        this.windowStart = clock.getAsLong();
    }

    synchronized void warn(String format, Object arg) {
        long now = clock.getAsLong();
        if (now - windowStart >= windowNanos) {
            if (suppressed > 0) {
                log.warn("{} more failed authentication message(s) suppressed in the last window (see sectoriadb_s3_auth_failures_total)",
                        suppressed);
            }
            windowStart = now;
            logged = 0;
            suppressed = 0;
        }
        if (logged < perWindow) {
            logged++;
            log.warn(format, arg);
        } else {
            suppressed++;
        }
    }
}
