package org.example.sectoriadb;

import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.service.impl.TableFullException;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CuckooHashTable#freeSlot}: slots go back to the pool, counters follow, and it all survives a reload. */
class CuckooFreeSlotTest {

    static final int BUCKETS = 16;          // 128 slots
    static final int CHUNK = 4096;

    @TempDir
    Path tmp;

    XxHash64BytesHasher hasher = new XxHash64BytesHasher();
    BlobFile blob;
    CuckooHashTable table;

    @BeforeEach
    void setUp() throws IOException {
        Path p = tmp.resolve("t.raw");
        BlobLayout.createFile(p, BUCKETS, CHUNK);
        blob = new BlobFile("t", p, CuckooHashTable.computeRequiredBlobSize(BUCKETS, CHUNK));
        table = open();
    }

    CuckooHashTable open() throws IOException {
        CuckooHashTable t = new CuckooHashTable(blob, new FileChannelStorageIOEngine(false), hasher, BUCKETS, CHUNK);
        t.loadMetadataFromDisk();
        return t;
    }

    static byte[] data(long seed) {
        byte[] b = new byte[CHUNK];
        new Random(seed).nextBytes(b);
        return b;
    }

    long put(CuckooHashTable t, long seed) throws IOException {
        byte[] d = data(seed);
        long key = hasher.hash64(d, 0L);
        t.insertPreservingKey(key, ByteBuffer.wrap(d));
        return key;
    }

    @Test
    void freedChunkIsGoneAndCountersDrop() throws IOException {
        long a = put(table, 1), b = put(table, 2);
        assertEquals(2, table.getFillStats().activeSlots());

        assertEquals(CHUNK, table.freeSlot(a));
        assertEquals(1, table.getFillStats().activeSlots(), "the fill statistics see the freed slot at once");
        assertFalse(table.lookup(a).isPresent());
        assertTrue(table.readChunkByKey(a, CHUNK).isEmpty());
        assertTrue(table.readChunkByKey(b, CHUNK).isPresent(), "neighbours are untouched");
        assertFalse(table.contains(a));
        assertTrue(table.contains(b));
        assertEquals((long) (table.getFillStats().totalSlots() - 1) * CHUNK, table.getFillStats().freeBytes(CHUNK),
                "free bytes: everything but the one live chunk");
    }

    @Test
    void freeingIsIdempotentAndIgnoresUnknownKeys() throws IOException {
        long a = put(table, 1);
        assertEquals(CHUNK, table.freeSlot(a));
        assertEquals(-1, table.freeSlot(a), "second free: nothing to do");
        assertEquals(-1, table.freeSlot(12345L), "unknown key");
        assertEquals(0, table.getFillStats().activeSlots());
    }

    @Test
    void freedStateSurvivesReload() throws IOException {
        long a = put(table, 1), b = put(table, 2);
        table.freeSlot(a);
        CuckooHashTable again = open();
        assertEquals(1, again.getFillStats().activeSlots());
        assertFalse(again.contains(a));
        assertTrue(again.contains(b));
        assertArrayEquals(data(2), bytesOf(again.readChunkByKey(b, CHUNK).orElseThrow()));
    }

    static byte[] bytesOf(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    @Test
    void freedSlotsAreReusedByInserts() throws IOException {
        // fill the table until it refuses, free half, and the same number of new chunks fit again
        List<Long> keys = new ArrayList<>();
        int seed = 0;
        try {
            while (true) keys.add(put(table, seed++));
        } catch (TableFullException full) {
            // expected
        }
        int stored = keys.size();
        assertTrue(stored > 100, "a 128-slot table holds well over 100 chunks: " + stored);
        assertEquals(stored, table.getFillStats().activeSlots());

        int toFree = stored / 2;
        for (int i = 0; i < toFree; i++) assertEquals(CHUNK, table.freeSlot(keys.get(i)));
        assertEquals(stored - toFree, table.getFillStats().activeSlots());

        int added = 0;
        try {
            for (int i = 0; i < toFree; i++) {
                keys.add(put(table, 10_000 + i));
                added++;
            }
        } catch (TableFullException full) {
            // may stop slightly short of the previous peak: cuckoo tables near the limit refuse before they are full
        }
        assertTrue(added >= toFree * 9 / 10, "freed slots take new chunks: " + added + " of " + toFree);
        // everything that should be there is, with intact bytes; everything freed is not
        for (int i = 0; i < toFree; i++) assertFalse(table.contains(keys.get(i)), "freed #" + i);
        for (int i = toFree; i < keys.size(); i++) {
            assertTrue(table.readChunkByKey(keys.get(i), CHUNK).isPresent(), "live #" + i);
        }
        assertEquals(keys.size() - toFree, table.getFillStats().activeSlots());

        CuckooHashTable again = open();
        assertEquals(table.getFillStats().activeSlots(), again.getFillStats().activeSlots());
        for (int i = toFree; i < keys.size(); i++) assertTrue(again.contains(keys.get(i)));
    }

    @Test
    void reinsertingAFreedKeyWorks() throws IOException {
        long a = put(table, 1);
        table.freeSlot(a);
        long again = put(table, 1);
        assertEquals(a, again);
        assertTrue(table.contains(a));
        assertEquals(1, table.getFillStats().activeSlots());
    }

    @Test
    void activeKeysPagesThroughTheTable() throws IOException {
        Set<Long> keys = new HashSet<>();
        for (int i = 0; i < 40; i++) keys.add(put(table, i));
        Set<Long> seen = new HashSet<>();
        int from = 0, pages = 0;
        while (from >= 0) {
            CuckooHashTable.KeyPage page = table.activeKeys(from, 7);
            for (long k : page.keys()) assertTrue(seen.add(k), "no key twice");
            assertTrue(page.keys().length <= 7);
            from = page.next();
            pages++;
        }
        assertEquals(keys, seen);
        assertTrue(pages >= 6);
        table.freeSlot(keys.iterator().next());
        Set<Long> after = new HashSet<>();
        for (int from2 = 0; from2 >= 0; ) {
            var page = table.activeKeys(from2, 1000);
            for (long k : page.keys()) after.add(k);
            from2 = page.next();
        }
        assertEquals(39, after.size());
    }

    @Test
    void concurrentFreesAndInsertsKeepTheCountersExact() throws Exception {
        List<Long> keys = new ArrayList<>();
        for (int i = 0; i < 60; i++) keys.add(put(table, i));
        Thread freer = new Thread(() -> {
            try {
                for (int i = 0; i < 30; i++) table.freeSlot(keys.get(i));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread inserter = new Thread(() -> {
            try {
                for (int i = 0; i < 30; i++) put(table, 5000 + i);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        freer.start();
        inserter.start();
        freer.join();
        inserter.join();
        assertEquals(60, table.getFillStats().activeSlots());
        for (int i = 30; i < 60; i++) assertTrue(table.contains(keys.get(i)));
        CuckooHashTable again = open();
        assertEquals(60, again.getFillStats().activeSlots());
    }
}
