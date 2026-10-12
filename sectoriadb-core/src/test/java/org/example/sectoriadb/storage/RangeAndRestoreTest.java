package org.example.sectoriadb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.PoolEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Range validation and partial-output cleanup of the restore paths. */
class RangeAndRestoreTest {
    static final int CHUNK = StorageRig.CHUNK;

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        pool = rig.bucket("bkt");
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    ManifestEntity put(String key, byte[] data) throws Exception {
        return rig.files.storeStream(new ByteArrayInputStream(data), pool, key, "application/octet-stream");
    }

    static byte[] data(int n) {
        byte[] b = new byte[n];
        new Random(5).nextBytes(b);
        return b;
    }

    @Test
    void streamRangeOfAChunkedObjectIsValidated() throws Exception {
        byte[] d = data(CHUNK * 3 + 100);
        ManifestEntity m = put("big", d);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.files.streamRange(m, out, CHUNK - 10, 30);
        assertArrayEquals(Arrays.copyOfRange(d, CHUNK - 10, CHUNK + 20), out.toByteArray());

        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        rig.files.streamRange(m, empty, 5, 0);
        assertEquals(0, empty.size(), "a zero-length range yields nothing");

        assertThrows(IllegalArgumentException.class, () -> rig.files.streamRange(m, new ByteArrayOutputStream(), -1, 10));
        assertThrows(IllegalArgumentException.class, () -> rig.files.streamRange(m, new ByteArrayOutputStream(), 0, -1));
        assertThrows(IllegalArgumentException.class, () -> rig.files.streamRange(m, new ByteArrayOutputStream(), d.length - 5, 10));
        assertThrows(IllegalArgumentException.class, () -> rig.files.streamRange(m, new ByteArrayOutputStream(), d.length + 1, 1));
    }

    @Test
    void aFailedRangeRestoreLeavesNoPartialFile() throws Exception {
        byte[] d = data(CHUNK * 4);
        ManifestEntity m = put("big", d);
        Path ok = root.resolve("range.bin");
        rig.files.restoreRange(m.getId(), ok, CHUNK + 1, CHUNK * 2);
        assertArrayEquals(Arrays.copyOfRange(d, CHUNK + 1, CHUNK * 3 + 1), Files.readAllBytes(ok));

        // lose the third chunk: the restore fails after writing the first ones
        BlobFileEntity blob = rig.blobService.cuckooBlobsOf(pool).get(0);
        rig.table(blob).freeSlot(m.chunkKeyArray()[2]);
        Path bad = root.resolve("bad.bin");
        assertThrows(IOException.class, () -> rig.files.restoreRange(m.getId(), bad, 0, CHUNK * 4));
        assertFalse(Files.exists(bad), "no partial output");
    }
}
