package org.example.sectoriadb.shell;

import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.service.PoolService;
import org.example.sectoriadb.service.gc.GarbageCollector;
import org.example.sectoriadb.service.gc.GcReports;
import org.example.sectoriadb.service.gc.GcReports.CompactionReport;
import org.example.sectoriadb.service.gc.GcReports.Options;
import org.example.sectoriadb.service.gc.GcReports.RunReport;
import org.example.sectoriadb.service.gc.GcReports.SweepReport;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Garbage collection from the shell (doc 10): {@code gc status}, {@code gc run}, {@code gc sweep}, {@code gc compact}. */
@ShellComponent
public class GcCommands {

    private final GarbageCollector gc;
    private final PoolService poolService;

    public GcCommands(GarbageCollector gc, PoolService poolService) {
        this.gc = gc;
        this.poolService = poolService;
    }

    @ShellMethod(key = "gc status", value = "Garbage collection: queue lengths and last results  |  gc status")
    public String gcStatus() {
        GarbageCollector.Status s = gc.status();
        StringBuilder sb = new StringBuilder("Garbage collection\n");
        sb.append(String.format("  Grace period (G):     %s%n", Duration.ofMillis(s.graceMillis())));
        sb.append(String.format("  Chunk queue:          %d chunk(s) with no reference, %d past the grace period%n", s.chunkQueue(), s.chunkQueueDue()));
        sb.append(String.format("  Orphan copies:        %d%n", s.orphans()));
        sb.append(String.format("  Tombstones:           %d retired manifest(s), %d past the grace period%n", s.tombstones(), s.tombstonesDue()));
        sb.append(String.format("  Uploads in flight:    %d (they keep sweeps away from stray copies)%n", s.uploadsInFlight()));
        sb.append(String.format("  Old small files:      %d waiting for the grace period%n", s.oldFilesPending()));
        if (s.lastRun() != null) {
            sb.append(String.format("  Last pass (%s): %s%n", Instant.ofEpochMilli(s.lastRunAtMillis()), s.lastRun()));
            s.lastRun().messages.forEach(m -> sb.append("    ! ").append(m).append('\n'));
        } else {
            sb.append("  Last pass:            none since start\n");
        }
        if (s.lastSweep() != null) sb.append("  Last sweep:           ").append(s.lastSweep()).append('\n');
        if (s.lastCompaction() != null) sb.append("  Last compaction:      ").append(s.lastCompaction()).append('\n');
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "gc run", value = "One collector pass: free unreferenced chunks, orphans, tombstones  |  gc run [--pool NAME] [--grace 15m|0s] [--max N]")
    public String gcRun(
            @ShellOption(defaultValue = ShellOption.NULL, help = "Only this pool") String pool,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Grace period for this pass (default: sectoriadb.gc.grace); 0s ignores it") String grace,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Most chunks to free in this pass") Integer max) {
        Options o = Options.defaults();
        if (pool != null) o = o.withPool(poolService.getByName(pool).getId());
        if (grace != null) o = o.withGrace(parseDuration(grace).toMillis());
        if (max != null) o = o.withMaxChunks(max);
        RunReport r = gc.run(o);
        StringBuilder sb = new StringBuilder("Pass finished: ").append(r);
        r.messages.forEach(m -> sb.append("\n  ! ").append(m));
        return sb.toString();
    }

    @ShellMethod(key = "gc sweep", value = "Walk the blobs of a pool for stray copies (crashed or aborted uploads) and free them  |  gc sweep --pool NAME")
    public String gcSweep(@ShellOption(help = "Pool name") String pool) {
        PoolEntity p = poolService.getByName(pool);
        SweepReport r = gc.sweep(p);
        StringBuilder sb = new StringBuilder("Sweep of pool '" + pool + "': ").append(r);
        if (r.deferred) sb.append("\n  uploads were in flight longer than sectoriadb.gc.sweep-gate-wait: the rest is left for the next sweep");
        r.messages.forEach(m -> sb.append("\n  ! ").append(m));
        return sb.toString();
    }

    @ShellMethod(key = "gc compact", value = "Compact small-object blobs whose dead bytes exceed the threshold  |  gc compact --pool NAME [--force]")
    public String gcCompact(
            @ShellOption(help = "Pool name") String pool,
            @ShellOption(defaultValue = "false", help = "Compact regardless of the dead-bytes threshold (never the append target)") boolean force) {
        PoolEntity p = poolService.getByName(pool);
        List<CompactionReport> reports = gc.compact(p, force);
        if (reports.isEmpty()) return "Nothing to compact in pool '" + pool + "'.";
        StringBuilder sb = new StringBuilder();
        for (CompactionReport r : reports) sb.append(r).append('\n');
        return sb.toString().stripTrailing();
    }

    /** {@code 0}, {@code 0s}, {@code 30s}, {@code 5m}, {@code 2h}, {@code 1d} or ISO-8601 ({@code PT5M}). */
    static Duration parseDuration(String text) {
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (t.startsWith("p")) return Duration.parse(t.toUpperCase(java.util.Locale.ROOT));
            if (t.matches("\\d+")) return Duration.ofMillis(Long.parseLong(t));
            long n = Long.parseLong(t.substring(0, t.length() - (t.endsWith("ms") ? 2 : 1)));
            return switch (t.endsWith("ms") ? "ms" : t.substring(t.length() - 1)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot parse the duration '" + text + "': use 0s, 30s, 5m, 2h, 1d or PT5M");
        }
    }
}
