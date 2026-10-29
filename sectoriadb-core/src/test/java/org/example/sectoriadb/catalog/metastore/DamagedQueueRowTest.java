package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.maintenance.gc.GcReports.Options;
import org.example.sectoriadb.storage.StorageRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Found by fuzzing: a damaged row of the GC queue made the decoder underflow, so every collector pass failed on it. */
class DamagedQueueRowTest {

    @TempDir
    Path root;

    @Test
    void aDamagedRowIsDroppedAndTheRestOfTheQueueIsCollected() throws Exception {
        try (StorageRig rig = new StorageRig(root)) {
            var pool = rig.bucket("bkt");
            for (int i = 0; i < 5; i++) {
                rig.writer.storeStream(new ByteArrayInputStream(new byte[100 + i]), pool, "k" + i, "application/octet-stream");
                rig.catalog.deleteObject("bkt", "k" + i);
            }
            // time, the marker of the new format, then a pool length far beyond the value
            byte[] damaged = ByteBuffer.allocate(12).putLong(0).put((byte) 0x01).putShort((short) 0x7fff).put((byte) 0).array();
            rig.store.writeVoid(tx -> tx.tree(Trees.DELETED_MANIFESTS).put(Trees.gcKey(1, "damaged-manifest"), damaged));
            assertEquals(6, rig.manifests.gcQueueSize());

            var report = rig.gc.run(Options.defaults().withGrace(0));
            assertEquals(0, rig.manifests.gcQueueSize(), report.toString());
            assertEquals(5L, report.smallRecordsMarked, "the intact rows were collected as usual");
        }
    }
}
