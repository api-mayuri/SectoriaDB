package org.example.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.StorageProperties;
import org.example.entity.OperationLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Repository
public class JsonOperationLogRepository implements OperationLogRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonOperationLogRepository.class);
    private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;

    private final ObjectMapper mapper;
    private final Path dataFile;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonOperationLogRepository(ObjectMapper mapper, StorageProperties props) {
        this.mapper   = mapper;
        this.dataFile = Path.of(props.getMetaDir(), "oplogs.jsonl");
    }

    @Override
    public OperationLogEntity save(OperationLogEntity entity) {
        lock.writeLock().lock();
        try {
            Files.createDirectories(dataFile.getParent());
            rollIfNeeded();
            String line = mapper.writeValueAsString(entity) + "\n";
            Files.writeString(dataFile, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return entity;
        } catch (IOException e) {
            log.error("Failed to append to oplogs.jsonl: {}", e.getMessage());
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<OperationLogEntity> findById(String id) {
        return findAll().stream().filter(e -> id.equals(e.getId())).findFirst();
    }

    @Override
    public List<OperationLogEntity> findAll() {
        lock.readLock().lock();
        try {
            return readAll(dataFile);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<OperationLogEntity> findByOperationTypeOrderByTimestampDesc(String type, int limit) {
        return findAll().stream()
                .filter(e -> type.equals(e.getOperationType()))
                .sorted(Comparator.comparing(OperationLogEntity::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<OperationLogEntity> findByEntityIdOrderByTimestampDesc(String entityId, int limit) {
        return findAll().stream()
                .filter(e -> entityId.equals(e.getEntityId()))
                .sorted(Comparator.comparing(OperationLogEntity::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<OperationLogEntity> findByTimestampAfterOrderByTimestampDesc(Instant since, int limit) {
        return findAll().stream()
                .filter(e -> e.getTimestamp() != null && e.getTimestamp().isAfter(since))
                .sorted(Comparator.comparing(OperationLogEntity::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<OperationLogEntity> findByOperationTypeAndEntityIdOrderByTimestampDesc(
            String type, String entityId, int limit) {
        return findAll().stream()
                .filter(e -> type.equals(e.getOperationType()) && entityId.equals(e.getEntityId()))
                .sorted(Comparator.comparing(OperationLogEntity::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<OperationLogEntity> findTopNByOrderByTimestampDesc(int n) {
        return findAll().stream()
                .sorted(Comparator.comparing(OperationLogEntity::getTimestamp).reversed())
                .limit(n)
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<OperationLogEntity> readAll(Path file) {
        if (!Files.exists(file)) return new ArrayList<>();
        List<OperationLogEntity> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.strip();
                if (!line.isEmpty()) {
                    try {
                        entries.add(mapper.readValue(line, OperationLogEntity.class));
                    } catch (Exception ex) {
                        log.warn("Skipping corrupt log line: {}", ex.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to read oplogs.jsonl: {}", e.getMessage());
        }
        return entries;
    }

    /** Archive current file if it exceeds MAX_FILE_BYTES. */
    private void rollIfNeeded() throws IOException {
        if (!Files.exists(dataFile)) return;
        if (Files.size(dataFile) < MAX_FILE_BYTES) return;

        String suffix = "-" + Instant.now().toString().replace(":", "-").substring(0, 19);
        Path archive  = dataFile.resolveSibling("oplogs" + suffix + ".jsonl");
        Files.move(dataFile, archive);
        log.info("Rolled operation log: archived to {}", archive);
    }
}
