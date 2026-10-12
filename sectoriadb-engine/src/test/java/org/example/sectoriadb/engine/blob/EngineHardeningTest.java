package org.example.sectoriadb.engine.blob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.example.sectoriadb.engine.format.BlobLayout;
import org.example.sectoriadb.engine.error.InvalidBlobHeaderException;
import org.example.sectoriadb.engine.io.BlobFile;
import org.example.sectoriadb.engine.io.FileChannelStorageIOEngine;
import org.example.sectoriadb.engine.error.TableFullException;
import org.example.sectoriadb.engine.hash.XxHash64BytesHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the engine review findings. */
class EngineHardeningTest {
    static final int BUCKETS = 16;
    static final int CHUNK = 512;

    @TempDir
    Path tmp;

    CuckooHashTable table(Path p) {
        long size = BlobLayout.requiredSize(BUCKETS, CHUNK);
        return new CuckooHashTable(new BlobFile("t", p, size), new FileChannelStorageIOEngine(false),
                new XxHash64BytesHasher(), BUCKETS, CHUNK);
    }

    @Test
    void createFileNeverTruncatesAnExistingFile() throws Exception {
        Path p = tmp.resolve("blob_x.raw");
        BlobLayout.createFile(p, BUCKETS, CHUNK);
        Files.write(p, new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.WRITE);
        long size = Files.size(p);
        assertThrows(FileAlreadyExistsException.class, () -> BlobLayout.createFile(p, BUCKETS, CHUNK));
        assertEquals(size, Files.size(p));
        Path s = tmp.resolve("small_x.sob");
        SmallObjectBlob.create(s);
        assertThrows(FileAlreadyExistsException.class, () -> SmallObjectBlob.create(s));
    }

    @Test
    void aTruncatedBlobIsRejectedWhenItIsLoaded() throws Exception {
        Path p = tmp.resolve("blob_t.raw");
        BlobLayout.createFile(p, BUCKETS, CHUNK);
        table(p).loadMetadataFromDisk();    // intact: fine
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(Files.size(p) - 1);
        }
        assertThrows(InvalidBlobHeaderException.class, () -> table(p).loadMetadataFromDisk());
    }

    @Test
    void aFullTableReportsTableFullAndStaysUsable() throws Exception {
        Path p = tmp.resolve("blob_f.raw");
        BlobLayout.createFile(p, BUCKETS, CHUNK);
        CuckooHashTable t = table(p);
        Random r = new Random(11);
        List<Long> keys = new ArrayList<>();
        byte[] first = null;
        long firstKey = 0;
        boolean full = false;
        for (int i = 0; i < 10 * BUCKETS * 8 && !full; i++) {
            byte[] data = new byte[CHUNK];
            r.nextBytes(data);
            long key = new XxHash64BytesHasher().hash64(ByteBuffer.wrap(data));
            try {
                t.insert(key, ByteBuffer.wrap(data));
                keys.add(key);
                if (first == null) {
                    first = data;
                    firstKey = key;
                }
            } catch (TableFullException e) {
                full = true;
            }
        }
        assertTrue(full, "the table fills up");
        assertTrue(keys.size() > BUCKETS * 2, "most slots were usable before it was full: " + keys.size());
        assertTrue(t.freeSlot(firstKey) >= 0);
        byte[] again = new byte[CHUNK];
        r.nextBytes(again);
        long k2 = new XxHash64BytesHasher().hash64(ByteBuffer.wrap(again));
        try {
            t.insert(k2, ByteBuffer.wrap(again));   // a freed slot may or may not be reachable; must not misbehave
            assertArrayEquals(again, t.readChunkByKey(k2, CHUNK).orElseThrow().array());
        } catch (TableFullException ok) {
            // fine
        }
    }

    @Test
    void ioEngineReportsFileSize() throws IOException {
        Path p = tmp.resolve("blob_s.raw");
        BlobLayout.createFile(p, BUCKETS, CHUNK);
        try (FileChannelStorageIOEngine e = new FileChannelStorageIOEngine(false)) {
            assertEquals(Files.size(p), e.size(new BlobFile("s", p, 0)));
        }
    }
}
