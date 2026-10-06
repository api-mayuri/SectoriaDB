package org.example.sectoriadb.observability;

import java.time.Duration;

/**
 * Explicit histogram bucket boundaries (10-15 per histogram). See docs/architecture/08-observability-bench.md for the
 * reasoning behind each set.
 */
public final class Buckets {

    private Buckets() {
    }

    /** S3 request latency and GetObject time-to-first-byte: 1 ms ... 60 s, roughly 2.5x steps, 15 buckets. */
    public static final Duration[] REQUEST = ms(1, 2.5, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10_000, 30_000, 60_000);

    /** One disk read / write syscall (page cache hit ... slow disk): 10 us ... 1 s, 15 buckets. */
    public static final Duration[] DISK_IO = ms(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100, 250, 1000);

    /** fsync / metastore commit: 100 us ... 5 s, 15 buckets (SSD ~0.1-2 ms, HDD ~5-30 ms, trouble above 100 ms). */
    public static final Duration[] FSYNC = ms(0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000);

    /** Waiting for an in-process lock: 10 us ... 5 s, 12 buckets. */
    public static final Duration[] LOCK_WAIT = ms(0.01, 0.05, 0.1, 0.5, 1, 5, 10, 50, 100, 500, 1000, 5000);

    /** Blob resize (rewrites a whole blob): 100 ms ... 30 min, 11 buckets. */
    public static final Duration[] RESIZE = ms(100, 500, 1000, 5000, 10_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000);

    /** JVM GC pauses: 1 ms ... 5 s, 11 buckets (configured through management.metrics.distribution.slo). */
    public static final String GC_PAUSE_PROPERTY_VALUE = "1ms,5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s";

    /** Cuckoo eviction path length (moved chunks); max-evictions defaults to 32. 14 buckets; le="0.5" means "no move at all" (Micrometer needs boundaries > 0). */
    public static final double[] EVICTION_PATH = {0.5, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32};

    private static Duration[] ms(double... millis) {
        Duration[] d = new Duration[millis.length];
        for (int i = 0; i < millis.length; i++) d[i] = Duration.ofNanos((long) (millis[i] * 1_000_000));
        return d;
    }
}
