package org.example.sectoriadb.shell;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.model.OperationLogEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.OperationLogRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.HashTableCache;
import org.example.sectoriadb.service.PoolService;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@ShellComponent
public class StatusCommands {

    private final PoolService poolService;
    private final BlobService blobService;
    private final HashTableCache cache;
    private final StorageProperties props;
    private final OperationLogRepository opLogRepo;

    public StatusCommands(PoolService poolService, BlobService blobService, HashTableCache cache,
                          StorageProperties props, OperationLogRepository opLogRepo) {
        this.poolService = poolService;
        this.blobService = blobService;
        this.cache       = cache;
        this.props       = props;
        this.opLogRepo   = opLogRepo;
    }

    @ShellMethod(key = "status", value = "Show system status — all pools with fill ratios  |  status")
    public String status() throws IOException {
        List<PoolEntity> pools = poolService.listAll();
        if (pools.isEmpty()) return "No pools. Create one with 'mkpool --name NAME --path DIR'";

        StringBuilder sb = new StringBuilder("SectoriaDB Status\n");
        sb.append("═".repeat(60)).append('\n');
        for (PoolEntity pool : pools) {
            List<BlobFileEntity> blobs = blobService.listByPool(pool);
            sb.append(String.format("Pool: %-20s  (%d blob(s))%n", pool.getName(), blobs.size()));
            if (!blobs.isEmpty()) {
                sb.append(ShellTable.blobTableHeader());
                for (BlobFileEntity b : blobs) {
                    sb.append(ShellTable.blobRow(blobService, b));
                    if (b.getKind() != BlobKind.CUCKOO) continue;
                    CuckooHashTable.FillStats s = cache.get(b).getFillStats();
                    if (s.fillPercent() >= props.getAutoResize().getThresholdPercent()) {
                        sb.append(String.format(
                                "    ⚠ Fill >= threshold (%d%%) — auto-resize will trigger%n",
                                props.getAutoResize().getThresholdPercent()));
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "config", value = "Display current storage configuration  |  config")
    public String settingsShow() {
        StorageProperties.AutoResize ar = props.getAutoResize();
        return String.format(
                "SectoriaDB Configuration%n" +
                "  default-chunk-size:             %s%n" +
                "  default-num-buckets:            %d%n" +
                "  max-evictions:                  %d%n" +
                "  small-object.max-file-bytes:    %s%n" +
                "  auto-resize.enabled:            %s%n" +
                "  auto-resize.threshold-percent:  %d%%%n" +
                "  auto-resize.expand-percent:     %d%%%n" +
                "  auto-resize.check-interval:     %s%n" +
                "%n" +
                "Edit application.properties and restart to change settings.",
                ShellTable.humanSize(props.getDefaultChunkSize()),
                props.getDefaultNumBuckets(),
                props.getMaxEvictions(),
                ShellTable.humanSize(props.getSmallObject().getMaxFileBytes()),
                ar.isEnabled(),
                ar.getThresholdPercent(),
                ar.getExpandPercent(),
                formatDuration(ar.getCheckIntervalMs()));
    }

    @ShellMethod(key = "logs", value = "Show the last N lines of the application log  |  logs [--n N]")
    public String logsTail(
            @ShellOption(defaultValue = "50", help = "Number of lines to show") int n) throws IOException {
        int lines = n;
        Path logFile = Path.of("./sectoriadb-meta/sectoriadb.log");
        if (!Files.exists(logFile)) {
            return "Log file not found: " + logFile.toAbsolutePath() +
                   "\nThe log file is created on first activity.";
        }
        List<String> tail = tailFile(logFile, lines);
        return tail.isEmpty() ? "Log file is empty." : String.join("\n", tail);
    }

    @ShellMethod(key = "audit",
            value = "Query the audit/recovery operation log  |  audit [--op TYPE] [--id ID] [--since TS] [--limit N]")
    public String logsQuery(
            @ShellOption(defaultValue = ShellOption.NULL,
                    help = "Operation: STORE | DELETE | RESTORE | RESTORE_RANGE | RESIZE | BLOB_CREATE | BLOB_DELETE | POOL_CREATE | POOL_DELETE")
                    String op,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Entity ID (file / blob / pool id)") String id,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Only entries after this ISO-8601 timestamp") String since,
            @ShellOption(defaultValue = "50", help = "Max rows to return") int limit) {
        String operation = op;
        String entityId  = id;

        List<OperationLogEntity> results;

        if (operation != null && entityId != null) {
            results = opLogRepo.findByOperationTypeAndEntityIdOrderByTimestampDesc(
                    operation.toUpperCase(), entityId, limit);
        } else if (operation != null) {
            results = opLogRepo.findByOperationTypeOrderByTimestampDesc(operation.toUpperCase(), limit);
        } else if (entityId != null) {
            results = opLogRepo.findByEntityIdOrderByTimestampDesc(entityId, limit);
        } else if (since != null) {
            Instant sinceInstant;
            try { sinceInstant = Instant.parse(since); }
            catch (DateTimeParseException e) {
                return "Invalid ISO-8601 timestamp: " + since + " (example: 2026-09-01T00:00:00Z)";
            }
            results = opLogRepo.findByTimestampAfterOrderByTimestampDesc(sinceInstant, limit);
        } else {
            results = opLogRepo.findTopNByOrderByTimestampDesc(limit);
        }

        if (results.isEmpty()) return "No log entries found.";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-22s  %-16s  %-7s  %-36s  %s%n",
                "Timestamp", "Operation", "Status", "Entity ID", "Name"));
        sb.append("─".repeat(110)).append('\n');
        for (OperationLogEntity e : results) {
            sb.append(String.format("%-22s  %-16s  %-7s  %-36s  %s%n",
                    e.getTimestamp() != null
                            ? e.getTimestamp().toString().replace("T", " ").substring(0, 19) : "",
                    e.getOperationType(),
                    e.getStatus(),
                    e.getEntityId() != null ? e.getEntityId() : "",
                    e.getEntityName() != null ? e.getEntityName() : ""));
        }
        return sb.toString().stripTrailing();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> tailFile(Path path, int maxLines) throws IOException {
        LinkedList<String> lines = new LinkedList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() > maxLines) lines.removeFirst();
            }
        }
        return new ArrayList<>(lines);
    }

    private String formatDuration(long ms) {
        if (ms < 1_000)     return ms + "ms";
        if (ms < 60_000)    return (ms / 1_000) + "s";
        if (ms < 3_600_000) return (ms / 60_000) + "m";
        return (ms / 3_600_000) + "h";
    }
}
