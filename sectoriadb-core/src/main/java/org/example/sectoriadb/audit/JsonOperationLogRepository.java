package org.example.sectoriadb.audit;

import org.example.sectoriadb.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The operation log as JSON lines in {@code oplogs.jsonl}, rolled at {@value #MAX_FILE_BYTES} bytes with
 * {@value #MAX_ARCHIVES} rolled files kept.
 *
 * <p>Writes go to one channel opened once in append mode: a single {@code write} per entry under a short lock (opening,
 * appending and closing the file for every operation cost three system calls and a path lookup on the request path).
 * Queries stream the active file line by line and keep only the newest matches they return, so a query needs memory for
 * its result, not for the file (the file reaches 100 MB under load and the heap is about 1 GB).
 */
public class JsonOperationLogRepository implements OperationLogRepository, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JsonOperationLogRepository.class);
    static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    /** Rolled files kept next to the active one: the log must not grow without bound (a busy server rolls it every few minutes). */
    static final int MAX_ARCHIVES = 5;
    private static final Comparator<OperationLogEntity> NEWEST_FIRST = Comparator.comparing(OperationLogEntity::getTimestamp,
            Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed();

    private final ObjectMapper mapper;
    private final Path dataFile;
    private final Object writeLock = new Object();
    /** Guarded by {@link #writeLock}; opened on the first write. */
    private FileChannel out;
    private long size;
    private boolean closed;

    public JsonOperationLogRepository(ObjectMapper mapper, StorageProperties props) {
        this.mapper   = mapper;
        this.dataFile = Path.of(props.getMetaDir(), "oplogs.jsonl");
    }

    @Override
    public OperationLogEntity save(OperationLogEntity entity) {
        byte[] line;
        try {
            line = (mapper.writeValueAsString(entity) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot encode an operation log entry: " + e.getMessage(), e);
        }
        synchronized (writeLock) {
            try {
                if (closed) {
                    // a request finishing during shutdown: the log is an audit trail, not a reason to fail the request
                    log.debug("Operation log closed, entry dropped: {}", entity.getOperationType());
                    return entity;
                }
                if (out == null) open();
                if (size > 0 && size + line.length > MAX_FILE_BYTES) roll();
                ByteBuffer b = ByteBuffer.wrap(line);
                while (b.hasRemaining()) out.write(b);
                size += line.length;
                return entity;
            } catch (IOException e) {
                log.error("Failed to append to oplogs.jsonl: {}", e.getMessage());
                closeQuietly();   // reopened on the next write
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (writeLock) {
            closed = true;
            closeQuietly();
        }
    }

    @Override
    public Optional<OperationLogEntity> findById(String id) {
        return newest(e -> id.equals(e.getId()), 1).stream().findFirst();
    }

    /** Every entry of the active file. For tests and small files: the whole file is held in memory. */
    @Override
    public List<OperationLogEntity> findAll() {
        return newest(e -> true, Integer.MAX_VALUE);
    }

    @Override
    public List<OperationLogEntity> findByOperationTypeOrderByTimestampDesc(String type, int limit) {
        return newest(e -> type.equals(e.getOperationType()), limit);
    }

    @Override
    public List<OperationLogEntity> findByEntityIdOrderByTimestampDesc(String entityId, int limit) {
        return newest(e -> entityId.equals(e.getEntityId()), limit);
    }

    @Override
    public List<OperationLogEntity> findByTimestampAfterOrderByTimestampDesc(Instant since, int limit) {
        return newest(e -> e.getTimestamp() != null && e.getTimestamp().isAfter(since), limit);
    }

    @Override
    public List<OperationLogEntity> findByOperationTypeAndEntityIdOrderByTimestampDesc(
            String type, String entityId, int limit) {
        return newest(e -> type.equals(e.getOperationType()) && entityId.equals(e.getEntityId()), limit);
    }

    @Override
    public List<OperationLogEntity> findTopNByOrderByTimestampDesc(int n) {
        return newest(e -> true, n);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * The newest {@code limit} matching entries of the active file, newest first. The file is in append order, so the
     * last matches are the newest; only they are kept while streaming. A line being appended right now may be cut off:
     * it is skipped like any unreadable line.
     */
    private List<OperationLogEntity> newest(Predicate<OperationLogEntity> filter, int limit) {
        if (limit <= 0) return List.of();
        ArrayDeque<OperationLogEntity> last = new ArrayDeque<>(Math.min(limit, 1024));
        try (BufferedReader r = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                OperationLogEntity e;
                try {
                    e = mapper.readValue(line, OperationLogEntity.class);
                } catch (RuntimeException ex) {   // JacksonException is unchecked in Jackson 3
                    log.debug("Skipping unreadable operation log line: {}", ex.getMessage());
                    continue;
                }
                if (!filter.test(e)) continue;
                if (last.size() == limit) last.removeFirst();
                last.addLast(e);
            }
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            log.error("Failed to read oplogs.jsonl: {}", e.getMessage());
        }
        List<OperationLogEntity> out = new ArrayList<>(last);
        out.sort(NEWEST_FIRST);   // threads append in nearly but not exactly timestamp order
        return out;
    }

    private void open() throws IOException {
        Files.createDirectories(dataFile.getParent());
        out = FileChannel.open(dataFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        size = out.size();
    }

    private void closeQuietly() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) {
            log.debug("Closing oplogs.jsonl: {}", e.getMessage());
        }
        out = null;
    }

    /** Archives the active file and starts a new one. Called under {@link #writeLock}. */
    private void roll() throws IOException {
        closeQuietly();
        String stamp = Instant.now().toString().replace(":", "-");
        stamp = stamp.substring(0, Math.min(stamp.length(), 23)).replace("Z", "");
        Path archive = dataFile.resolveSibling("oplogs-" + stamp + ".jsonl");
        for (int n = 1; Files.exists(archive); n++) archive = dataFile.resolveSibling("oplogs-" + stamp + "-" + n + ".jsonl");
        Files.move(dataFile, archive);
        log.info("Rolled operation log: archived to {}", archive);
        pruneArchives();
        open();
    }

    /** Deletes the oldest rolled files beyond {@link #MAX_ARCHIVES} (the timestamp in the name sorts chronologically). */
    private void pruneArchives() throws IOException {
        List<Path> archives;
        try (var files = Files.list(dataFile.getParent())) {
            archives = files.filter(f -> f.getFileName().toString().matches("oplogs-.+\\.jsonl")).sorted().toList();
        }
        for (int i = 0; i < archives.size() - MAX_ARCHIVES; i++) {
            Files.deleteIfExists(archives.get(i));
        }
    }
}
