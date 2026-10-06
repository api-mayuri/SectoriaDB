package org.example.sectoriadb;

import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.ChunkLocation;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

        ChunkLocation loc = table.insert(key, chunk).location();

        assertNotNull(loc);
        Optional<ChunkLocation> found = table.lookup(key);
        assertTrue(found.isPresent());
        assertEquals(loc, found.get());

        ByteBuffer read = table.readChunk(loc, CHUNK_SIZE);
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
            assertTrue(table.lookup(entry.getKey()).isPresent(),
                    "Chunk not found for key " + Long.toHexString(entry.getKey()));
        }
    }

    @Test
    void deduplication_sameKeyReturnsSameLocation() throws IOException {
        byte[] data = randomBytes(CHUNK_SIZE);
        ByteBuffer chunk = ByteBuffer.wrap(data);
        long key = hasher.hash64(chunk.duplicate());

        ChunkLocation first  = table.insert(key, chunk.duplicate()).location();
        ChunkLocation second = table.insert(key, chunk.duplicate()).location();

        assertEquals(first, second, "Duplicate insert should return the same ChunkLocation");
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

        assertTrue(table2.lookup(key).isPresent(),
                "Chunk must be findable after metadata reload from disk");

        ByteBuffer read = table2.readChunk(table2.lookup(key).get(), CHUNK_SIZE);
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
