package org.example.sectoriadb.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.example.sectoriadb.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCredentialRepositoryTest {
    @TempDir
    Path dir;

    static class FailingMapper extends ObjectMapper {
        volatile boolean fail;

        @Override
        public ObjectWriter writerWithDefaultPrettyPrinter() {
            if (fail) throw new IllegalStateException("disk full");
            return super.writerWithDefaultPrettyPrinter();
        }
    }

    static CredentialEntity cred(String id) {
        CredentialEntity c = new CredentialEntity();
        c.setAccessKeyId(id);
        c.setSecretKey("secret-" + id);
        return c;
    }

    @Test
    void aFailedWriteLeavesTheCachedViewUnchanged() {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        FailingMapper mapper = new FailingMapper();
        JsonCredentialRepository repo = new JsonCredentialRepository(mapper, props);
        repo.save(cred("a"));
        repo.save(cred("b"));

        mapper.fail = true;
        assertThrows(RuntimeException.class, () -> repo.save(cred("c")));
        assertThrows(RuntimeException.class, () -> repo.deleteByAccessKeyId("a"));
        mapper.fail = false;

        assertTrue(repo.findByAccessKeyId("a").isPresent(), "the delete that failed to persist did not happen");
        assertFalse(repo.findByAccessKeyId("c").isPresent(), "the save that failed to persist is not usable");
        assertEquals(2, repo.findAll().size());
    }

    @Test
    void findAllReturnsAnImmutableSnapshotThatLaterWritesDoNotChange() {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        JsonCredentialRepository repo = new JsonCredentialRepository(new ObjectMapper(), props);
        repo.save(cred("a"));
        List<CredentialEntity> before = repo.findAll();
        assertThrows(UnsupportedOperationException.class, () -> before.add(cred("x")));
        repo.save(cred("b"));
        assertEquals(1, before.size());
        assertEquals(2, repo.findAll().size());
        repo.deleteByAccessKeyId("a");
        assertEquals(1, repo.findAll().size());
        assertEquals(2, before.size() + 1);
    }

    @Test
    void aSameSizeChangeByAnotherProcessIsSeenEvenWithinOneTimestampTick() throws Exception {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        JsonCredentialRepository server = new JsonCredentialRepository(new ObjectMapper(), props);
        JsonCredentialRepository cli = new JsonCredentialRepository(new ObjectMapper(), props);
        CredentialEntity c = cred("a");
        c.setDescription("aaaa");
        server.save(c);
        assertEquals("aaaa", server.findByAccessKeyId("a").orElseThrow().getDescription());

        Path file = dir.resolve("credentials.json");
        FileTime before = Files.getLastModifiedTime(file);
        long sizeBefore = Files.size(file);
        CredentialEntity changed = cli.findByAccessKeyId("a").orElseThrow();
        changed.setDescription("bbbb");   // same length: the file size does not change
        cli.save(changed);
        // make the replacement indistinguishable by time as well (a coarse-timestamp file system, or the same tick)
        Files.setLastModifiedTime(file, before);
        assertEquals(sizeBefore, Files.size(file));

        assertEquals("bbbb", server.findByAccessKeyId("a").orElseThrow().getDescription(),
                "the server must see the other process's change (a missed disable or policy keeps old rights)");
    }
}
