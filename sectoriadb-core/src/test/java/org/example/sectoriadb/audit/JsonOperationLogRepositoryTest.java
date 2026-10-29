package org.example.sectoriadb.audit;

import org.example.sectoriadb.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonOperationLogRepositoryTest {

    @TempDir
    Path meta;

    private JsonOperationLogRepository repo() {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(meta.toString());
        return new JsonOperationLogRepository(new ObjectMapper(), props);
    }

    private OperationLogEntity entry(String id) {
        return entry(id, "STORE", Instant.now());
    }

    private OperationLogEntity entry(String id, String type, Instant at) {
        OperationLogEntity e = new OperationLogEntity();
        e.setId(id);
        e.setTimestamp(at);
        e.setOperationType(type);
        e.setEntityId("obj-" + id);
        return e;
    }

    @Test
    void queriesReturnTheNewestMatchesNewestFirst() {
        try (JsonOperationLogRepository repo = repo()) {
            Instant t0 = Instant.parse("2026-10-29T10:00:00Z");
            for (int i = 0; i < 500; i++) repo.save(entry("e" + i, i % 2 == 0 ? "STORE" : "DELETE", t0.plusSeconds(i)));
            List<OperationLogEntity> stores = repo.findByOperationTypeOrderByTimestampDesc("STORE", 3);
            assertEquals(List.of("e498", "e496", "e494"), stores.stream().map(OperationLogEntity::getId).toList());
            assertEquals(List.of("e499", "e498"), repo.findTopNByOrderByTimestampDesc(2).stream().map(OperationLogEntity::getId).toList());
            assertEquals("e7", repo.findById("e7").orElseThrow().getId());
            assertEquals(List.of("e7"), repo.findByEntityIdOrderByTimestampDesc("obj-e7", 10).stream().map(OperationLogEntity::getId).toList());
            assertEquals(2, repo.findByTimestampAfterOrderByTimestampDesc(t0.plusSeconds(497), 10).size());
            assertEquals(List.of(), repo.findTopNByOrderByTimestampDesc(0));
        }
    }

    @Test
    void aBrokenOrHalfWrittenLineIsSkippedAndAReopenedLogKeepsAppending() throws IOException {
        try (JsonOperationLogRepository repo = repo()) {
            repo.save(entry("first"));
        }
        Files.writeString(meta.resolve("oplogs.jsonl"), "{\"id\":\"cut-off", java.nio.file.StandardOpenOption.APPEND);
        try (JsonOperationLogRepository repo = repo()) {
            repo.save(entry("second"));   // starts after the cut-off text on the same line: that line is unreadable
            repo.save(entry("third"));
            List<String> ids = repo.findAll().stream().map(OperationLogEntity::getId).toList();
            assertTrue(ids.contains("first") && ids.contains("third"), ids.toString());
        }
    }

    @Test
    void concurrentWritersNeverInterleaveLines() throws Exception {
        try (JsonOperationLogRepository repo = repo()) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<java.util.concurrent.Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                done.add(pool.submit(() -> {
                    for (int i = 0; i < 500; i++) repo.save(entry(thread + "-" + i));
                }));
            }
            for (var f : done) f.get();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(4000, repo.findAll().size(), "every line parses: none was torn by a concurrent write");
        }
    }

    @Test
    void anEntryAfterCloseIsDroppedNotAnError() {
        JsonOperationLogRepository repo = repo();
        repo.save(entry("before"));
        repo.close();
        repo.save(entry("after"));
        assertEquals(1, repo().findAll().size());
    }

    @Test
    void rolledFilesAreLimitedSoTheLogCannotFillTheDisk() throws IOException {
        for (int i = 1; i <= 8; i++) {   // eight rolled files from earlier runs, oldest first
            Files.writeString(meta.resolve(String.format("oplogs-2026-01-0%dT00-00-00.jsonl", i)), "{}\n");
        }
        try (RandomAccessFile f = new RandomAccessFile(meta.resolve("oplogs.jsonl").toFile(), "rw")) {
            f.setLength(100L * 1024 * 1024);   // sparse: at the roll limit
        }
        JsonOperationLogRepository repo = repo();
        repo.save(entry("after-roll"));

        List<String> archives;
        try (var files = Files.list(meta)) {
            archives = files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("oplogs-")).sorted().toList();
        }
        assertEquals(JsonOperationLogRepository.MAX_ARCHIVES, archives.size(), archives.toString());
        assertFalse(archives.contains("oplogs-2026-01-01T00-00-00.jsonl"), "the oldest archive is deleted");
        assertTrue(archives.contains("oplogs-2026-01-08T00-00-00.jsonl"));
        assertEquals(1, repo.findAll().size(), "the new active file holds only the new entry");
    }
}
