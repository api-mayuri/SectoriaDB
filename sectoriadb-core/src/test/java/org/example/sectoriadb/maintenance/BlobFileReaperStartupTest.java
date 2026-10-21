package org.example.sectoriadb.maintenance;

import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.storage.StorageRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An unregistered blob file younger than the grace may be newer than the metadata store: startup must not delete it. */
class BlobFileReaperStartupTest {

    @TempDir
    Path root;

    @Test
    void startupKeepsUnregisteredBlobFilesYoungerThanTheGrace() throws Exception {
        try (StorageRig rig = new StorageRig(root)) {
            PoolEntity pool = rig.bucket("bkt");
            Path dir = Path.of(pool.getBasePath());
            Path young = Files.write(dir.resolve("blob_young.raw"), new byte[16]);
            Path old = Files.write(dir.resolve("blob_old.raw"), new byte[16]);
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

            // e.g. sectoria.db was restored from yesterday's backup: young.raw was written after it
            assertEquals(1, rig.reaper.reconcileAtStartup(Duration.ofMinutes(15).toMillis()));
            assertTrue(Files.exists(young), "younger than the grace: kept");
            assertFalse(Files.exists(old), "older than the grace and unregistered: garbage");

            // the plain variant stays unconditional (tests, offline tools)
            assertEquals(1, rig.reaper.reconcileAtStartup());
            assertFalse(Files.exists(young));
        }
    }
}
