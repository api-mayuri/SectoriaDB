package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository.BucketStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Object count / stored bytes are maintained inside the commit transactions, never by scanning on read. */
class ObjectTotalsTest {

    @TempDir
    Path dir;

    StorageRig rig;

    @BeforeEach
    void start() {
        rig = new StorageRig(dir);
    }

    @AfterEach
    void stop() {
        rig.close();
    }

    private void put(PoolEntity pool, String key, int size) throws Exception {
        rig.files.storeStream(new ByteArrayInputStream(new byte[size]), pool, key, "application/octet-stream");
    }

    @Test
    void totalsFollowPutOverwriteDeleteAndSurviveRestart() throws Exception {
        assertEquals(new BucketStats(0, 0), rig.manifests.totals());
        PoolEntity a = rig.bucket("a");
        PoolEntity b = rig.bucket("b");
        put(a, "k1", 100);
        put(a, "k2", 5000);          // chunked (> chunk size 4096)
        put(b, "k1", 0);             // empty object
        assertEquals(new BucketStats(3, 5100), rig.manifests.totals());

        put(a, "k1", 300);           // overwrite: still 3 objects, +200 bytes
        assertEquals(new BucketStats(3, 5300), rig.manifests.totals());

        rig.files.deleteObject("a", "k2");
        assertEquals(new BucketStats(2, 300), rig.manifests.totals());
        rig.files.deleteObject("a", "does-not-exist");
        assertEquals(new BucketStats(2, 300), rig.manifests.totals());

        rig.reopen();
        assertEquals(new BucketStats(2, 300), rig.manifests.totals());
        BucketStats scanned = rig.manifests.countAndSize("a");
        BucketStats scannedB = rig.manifests.countAndSize("b");
        assertEquals(2, scanned.objects() + scannedB.objects());
        assertEquals(300, scanned.bytes() + scannedB.bytes());
    }
}
