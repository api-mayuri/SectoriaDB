package org.example.sectoriadb.scheduler;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.maintenance.gc.GarbageCollector;
import org.example.sectoriadb.maintenance.gc.GcReports;
import org.example.sectoriadb.maintenance.gc.GcReports.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Background garbage collection, enabled by {@code sectoriadb.gc.enabled=true}:
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

    /** Passes in a row while the chunk cap keeps being hit; then the scheduler's pause applies, so other work gets its turn. */
    private static final int MAX_BACK_TO_BACK = 20;

    private final GarbageCollector gc;
    private final org.example.sectoriadb.maintenance.LifecycleService lifecycle;

    public GarbageCollectionScheduler(GarbageCollector gc, org.example.sectoriadb.maintenance.LifecycleService lifecycle) {
        this.gc = gc;
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "#{@gcSchedule.lifecycleIntervalMillis()}", initialDelayString = "#{@gcSchedule.lifecycleInitialDelayMillis()}")
    public void applyLifecycle() {
        try {
            lifecycle.run(System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.error("Scheduled lifecycle pass failed: {}", e.toString(), e);
        }
    }

    @Scheduled(fixedDelayString = "#{@gcSchedule.intervalMillis()}", initialDelayString = "#{@gcSchedule.initialDelayMillis()}")
    public void collect() {
        try {
            // a pass that stopped at its chunk cap with more due is followed at once: the interval is a pause for an idle
            // collector, not a ceiling on how much garbage can be reclaimed per minute
            for (int i = 0; i < MAX_BACK_TO_BACK; i++) {
                GcReports.RunReport r = gc.run(Options.defaults());
                if (!r.more || r.errors > 0 || Thread.currentThread().isInterrupted()) break;
            }
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

        private static long ms(Duration d, long min) {
            return Math.max(min, d == null ? min : d.toMillis());
        }

        public long intervalMillis() { return ms(gc.getInterval(), 100); }
        public long initialDelayMillis() { return Math.min(intervalMillis(), 30_000); }
        public long sweepIntervalMillis() { return ms(gc.getSweepInterval(), 1_000); }
        public long sweepInitialDelayMillis() { return Math.min(sweepIntervalMillis(), 10 * 60_000L); }
        public long compactIntervalMillis() { return ms(gc.getCompactInterval(), 1_000); }
        public long compactInitialDelayMillis() { return Math.min(compactIntervalMillis(), 5 * 60_000L); }
        public long lifecycleIntervalMillis() { return ms(gc.getLifecycleInterval(), 1_000); }
        public long lifecycleInitialDelayMillis() { return Math.min(lifecycleIntervalMillis(), 2 * 60_000L); }
    }
}
