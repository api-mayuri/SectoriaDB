package org.example.sectoriadb.engine.blob;

import org.example.sectoriadb.engine.format.BlobLayout;
import org.example.sectoriadb.engine.io.BlobFile;
import org.example.sectoriadb.engine.io.FileChannelStorageIOEngine;
import org.example.sectoriadb.engine.hash.XxHash64BytesHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CuckooHashTableTest {

    @TempDir
    Path tmpDir;

    private CuckooHashTable table;
    private XxHash64BytesHasher hasher;

    private static final int NUM_BUCKETS = 16;
    private static final int CHUNK_SIZE  = 4096;

    @BeforeEach
    void setUp() throws IOException {
        Path blobPath = tmpDir.resolve("test.raw");
        long blobSize = CuckooHashTable.computeRequiredBlobSize(NUM_BUCKETS, CHUNK_SIZE);
        BlobLayout.createFile(blobPath, NUM_BUCKETS, CHUNK_SIZE);
        BlobFile blobFile = new BlobFile("test", blobPath, blobSize);
        hasher = new XxHash64BytesHasher();
        table  = new CuckooHashTable(blobFile, new FileChannelStorageIOEngine(false), hasher, NUM_BUCKETS, CHUNK_SIZE);
    }

    @Test
    void insertAndReadback_dataMatchesOriginal() throws IOException {
        byte[] data = randomBytes(CHUNK_SIZE);
        ByteBuffer chunk = ByteBuffer.wrap(data);
        long key = hasher.hash64(chunk.duplicate());

        table.insert(key, chunk);

        assertTrue(table.contains(key));

        ByteBuffer read = table.readChunkByKey(key, CHUNK_SIZE).orElseThrow();
        byte[] readBytes = new byte[CHUNK_SIZE];
        read.get(readBytes);
        assertArrayEquals(data, readBytes);
    }

    @Test
    void fiftyChunks_allFindableAfterInsert() throws IOException {
        Map<Long, byte[]> inserted = new HashMap<>();
        Random rng = new Random(42);

        for (int i = 0; i < 50; i++) {
            byte[] data = randomBytes(CHUNK_SIZE, rng);
            // Ensure uniqueness by embedding the index
            data[0] = (byte)(i >> 8);
            data[1] = (byte) i;
            ByteBuffer chunk = ByteBuffer.wrap(data);
            long key = hasher.hash64(chunk.duplicate());
            table.insert(key, chunk);
            inserted.put(key, data);
        }

        for (Map.Entry<Long, byte[]> entry : inserted.entrySet()) {
            assertTrue(table.contains(entry.getKey()),
                    "Chunk not found for key " + Long.toHexString(entry.getKey()));
        }
    }

    @Test
    void deduplication_sameKeyStaysInTheSameSlot() throws IOException {
        byte[] data = randomBytes(CHUNK_SIZE);
        ByteBuffer chunk = ByteBuffer.wrap(data);
        long key = hasher.hash64(chunk.duplicate());

        assertFalse(table.insert(key, chunk.duplicate()).deduplicated());
        int first = table.slotOf(key);
        assertTrue(table.insert(key, chunk.duplicate()).deduplicated());

        assertEquals(first, table.slotOf(key), "A duplicate insert must not move the chunk");
    }

    @Test
    void metadataPersistedToDisk_survicesTableRestart() throws IOException {
        byte[] data = randomBytes(CHUNK_SIZE);
        ByteBuffer chunk = ByteBuffer.wrap(data);
        long key = hasher.hash64(chunk.duplicate());

        table.insert(key, chunk);

        // Create a brand-new table instance on the same file
        BlobFile blobFile = table.getBlobFile();
        CuckooHashTable table2 = new CuckooHashTable(
                blobFile, new FileChannelStorageIOEngine(false), hasher, NUM_BUCKETS, CHUNK_SIZE);
        table2.loadMetadataFromDisk();

        assertTrue(table2.contains(key),
                "Chunk must be findable after metadata reload from disk");

        ByteBuffer read = table2.readChunkByKey(key, CHUNK_SIZE).orElseThrow();
        byte[] readBytes = new byte[CHUNK_SIZE];
        read.get(readBytes);
        assertArrayEquals(data, readBytes);
    }

    private byte[] randomBytes(int size) {
        return randomBytes(size, new Random());
    }

    private byte[] randomBytes(int size, Random rng) {
        byte[] bytes = new byte[size];
        rng.nextBytes(bytes);
        return bytes;
    }
}
