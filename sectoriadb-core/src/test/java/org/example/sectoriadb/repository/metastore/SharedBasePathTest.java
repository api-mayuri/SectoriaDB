package org.example.sectoriadb.repository.metastore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pools must not share a directory; and if legacy data does, the cleaners must not delete another pool's files. */
class SharedBasePathTest {
    @TempDir
    Path root;

    @Test
    void createRejectsDuplicateAndNestedBasePaths() throws Exception {
        try (StorageRig rig = new StorageRig(root)) {
            Path a = root.resolve("shared/a");
            rig.poolService.create("one", a.toString());
            assertThrows(IllegalArgumentException.class, () -> rig.poolService.create("two", a.toString()));
            assertThrows(IllegalArgumentException.class, () -> rig.poolService.create("three", a.resolve("inner").toString()));
            assertThrows(IllegalArgumentException.class, () -> rig.poolService.create("four", root.resolve("shared").toString()));
            rig.poolService.create("five", root.resolve("shared/b").toString());
        }
    }

    @Test
    void reaperAndCompactorKeepFilesOfAnotherPoolInTheSameDirectory() throws Exception {
        try (StorageRig rig = new StorageRig(root)) {
            PoolEntity p1 = rig.bucket("p1");
            // a legacy pool record that points at the same directory (cannot be created through PoolService any more)
            PoolEntity p2 = rig.pools.save(new PoolEntity("legacy-id", "p2", p1.getBasePath(), Instant.now()));
            BlobFileEntity cuckoo = rig.blobService.create(p2, 16, StorageRig.CHUNK);
            BlobFileEntity small = rig.blobService.createSmall(p2);
            rig.cache.evict(cuckoo.getId());
            rig.smallCache.evict(small.getId());
            Path blobFile = Path.of(cuckoo.getFilePath());
            Path smallFile = Path.of(small.getFilePath());
            assertEquals(Path.of(p1.getBasePath()), blobFile.getParent());

            assertEquals(0, rig.reaper.reconcileAtStartup());
            assertEquals(0, rig.reaper.deleteUnregistered(0));
            assertEquals(0, rig.compactor.deleteUnregisteredFiles(0));
            assertTrue(Files.exists(blobFile));
            assertTrue(Files.exists(smallFile));
        }
    }
}
