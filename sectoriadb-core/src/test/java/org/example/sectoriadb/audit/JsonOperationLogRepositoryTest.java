package org.example.sectoriadb.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonOperationLogRepositoryTest {

    @TempDir
    Path meta;

    private JsonOperationLogRepository repo() {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(meta.toString());
        return new JsonOperationLogRepository(new ObjectMapper().findAndRegisterModules(), props);
    }

    private OperationLogEntity entry(String id) {
        OperationLogEntity e = new OperationLogEntity();
        e.setId(id);
        e.setTimestamp(Instant.now());
        e.setOperationType("STORE");
        return e;
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
