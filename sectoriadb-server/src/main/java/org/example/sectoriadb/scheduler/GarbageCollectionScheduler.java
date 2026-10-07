package org.example.sectoriadb.scheduler;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.service.gc.GarbageCollector;
import org.example.sectoriadb.service.gc.GcReports.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background garbage collection (doc 10), enabled by {@code sectoriadb.gc.enabled=true}:
 * <ul>
 *   <li>every {@code sectoriadb.gc.interval} one pass over the chunk queue, the orphan records and the tombstones
 *       (rate limited by {@code max-chunks-per-run}, honoring the grace period {@code sectoriadb.gc.grace});</li>
 *   <li>every {@code sectoriadb.gc.sweep-interval} a sweep of every pool for stray copies (default 6 h);</li>
 *   <li>every {@code sectoriadb.gc.compact-interval} compaction of small-object blobs over the dead-bytes threshold.</li>
 * </ul>
 * The intervals are read once at startup from {@link StorageProperties.Gc} (any {@code Duration} format works, the
 * delays are computed by {@link Schedule}). Passes never overlap: a pass that is still running makes the next
 * trigger of the same kind wait (fixed delay).
 */
@Component
@ConditionalOnProperty(prefix = "sectoriadb.gc", name = "enabled", havingValue = "true")
public class GarbageCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(GarbageCollectionScheduler.class);

    private final GarbageCollector gc;

    public GarbageCollectionScheduler(GarbageCollector gc) {
        this.gc = gc;
    }

    @Scheduled(fixedDelayString = "#{@gcSchedule.intervalMillis()}", initialDelayString = "#{@gcSchedule.initialDelayMillis()}")
    public void collect() {
        try {
            gc.run(Options.defaults());
        } catch (RuntimeException e) {
            log.error("Scheduled garbage collection failed: {}", e.toString(), e);
        }
    }

    @Scheduled(fixedDelayString = "#{@gcSchedule.sweepIntervalMillis()}", initialDelayString = "#{@gcSchedule.sweepInitialDelayMillis()}")
    public void sweep() {
        try {
            gc.sweepAll();
        } catch (RuntimeException e) {
            log.error("Scheduled sweep for stray chunk copies failed: {}", e.toString(), e);
        }
    }

    @Scheduled(fixedDelayString = "#{@gcSchedule.compactIntervalMillis()}", initialDelayString = "#{@gcSchedule.compactInitialDelayMillis()}")
    public void compact() {
        try {
            gc.compact(null, false);
        } catch (RuntimeException e) {
            log.error("Scheduled small-object compaction failed: {}", e.toString(), e);
        }
    }

    /** The delays of the three tasks, from {@link StorageProperties.Gc}; a bean of its own so that SpEL can name it. */
    @Component("gcSchedule")
    public static class Schedule {

        private final StorageProperties.Gc gc;

        public Schedule(StorageProperties props) {
            this.gc = props.getGc();
        }

        private static long ms(java.time.Duration d, long min) {
            return Math.max(min, d == null ? min : d.toMillis());
        }

        public long intervalMillis() { return ms(gc.getInterval(), 100); }
        public long initialDelayMillis() { return Math.min(intervalMillis(), 30_000); }
        public long sweepIntervalMillis() { return ms(gc.getSweepInterval(), 1_000); }
        public long sweepInitialDelayMillis() { return Math.min(sweepIntervalMillis(), 10 * 60_000L); }
        public long compactIntervalMillis() { return ms(gc.getCompactInterval(), 1_000); }
        public long compactInitialDelayMillis() { return Math.min(compactIntervalMillis(), 5 * 60_000L); }
    }
}
