package org.example.sectoriadb.s3.http;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Throughput over the last {@code window} (not since the start): a client cannot buy idle time with a fast burst and
 * then trickle one byte per minute. After the grace period the bytes seen in the window (or in the whole stream while it
 * is younger than the window) must reach {@code minBytesPerSec} times the window length.
 *
 * Not thread-safe: one instance per stream, used by the thread that reads or writes it.
 */
final class SlidingRate {

    private static final int SLOTS = 32;

    private final long graceNanos;
    private final long minBytesPerSec;
    private final long windowNanos;
    private final long slotNanos;
    private final LongSupplier clock;
    private final long start;
    private final long[] bytes = new long[SLOTS];
    private final long[] slotIds = new long[SLOTS];

    SlidingRate(long graceNanos, long minBytesPerSec, long windowNanos, LongSupplier clock) {
        this.graceNanos = graceNanos;
        this.minBytesPerSec = minBytesPerSec;
        this.windowNanos = Math.max(windowNanos, SLOTS);
        this.slotNanos = Math.max(1, this.windowNanos / SLOTS);
        this.clock = clock;
        this.start = clock.getAsLong();
        Arrays.fill(slotIds, -1);
    }

    /** Accounts {@code n} bytes that just moved; true when the stream is below the minimum rate and must be cut. */
    boolean record(long n) {
        if (minBytesPerSec <= 0) return false;
        long elapsed = clock.getAsLong() - start;
        long slot = elapsed / slotNanos;
        int i = (int) (slot % SLOTS);
        if (slotIds[i] != slot) {
            slotIds[i] = slot;
            bytes[i] = 0;
        }
        bytes[i] += n;
        if (elapsed <= graceNanos) return false;
        long inWindow = 0;
        for (int s = 0; s < SLOTS; s++) {
            if (slotIds[s] >= 0 && slotIds[s] > slot - SLOTS) inWindow += bytes[s];
        }
        long observed = Math.min(elapsed, windowNanos);
        return inWindow * 1e9 / observed < minBytesPerSec;
    }
}
