package org.example.sectoriadb.metastore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page cache must never change what a reader sees: pages are reused after the free-list release, so a stale cached
 * copy of a reused page would show another tree's data. A cache far smaller than the file forces constant eviction.
 */
class PageCacheTest {

    @TempDir
    Path dir;

    static byte[] key(long i) {
        return Keys.builder().longUnsigned(i).build();
    }

    static byte[] value(long i, long version) {
        return ByteBuffer.allocate(24).putLong(i).putLong(version).putLong(i ^ version).array();
    }

    /** A tiny cache (constant eviction) and one that holds the whole file (stale copies would survive until reuse). */
    @ParameterizedTest
    @ValueSource(longs = {32 * 512, 16 << 20})
    void readersOnSnapshotsSeeExactlyTheirVersionWhilePagesAreRewrittenAndReused(long cacheBytes) throws Exception {
        AtomicLong hits = new AtomicLong(), misses = new AtomicLong();
        MetaStoreListener counting = new MetaStoreListener() {
            @Override
            public void onPageCacheHit() {
                hits.incrementAndGet();
            }

            @Override
            public void onPageRead(long nanos, long bytes) {
                misses.incrementAndGet();
            }
        };
        MetaStoreOptions opts = MetaStoreOptions.defaults().pageSize(512).fsync(false).listener(counting).pageCacheBytes(cacheBytes);
        int keys = 3000;
        try (MetaStore s = MetaStore.open(dir.resolve("c.db"), opts)) {
            try (WriteTxn tx = s.beginWrite()) {
                for (int i = 0; i < keys; i++) tx.tree("t").put(key(i), value(i, 0));
                tx.commit();
            }
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // version -> all keys carry this version (or were deleted, for the odd keys of odd versions)
            ConcurrentHashMap<Long, Boolean> committed = new ConcurrentHashMap<>();
            committed.put(0L, true);
            Thread writer = new Thread(() -> {
                try {
                    for (long v = 1; v <= 60; v++) {
                        try (WriteTxn tx = s.beginWrite()) {
                            BTree t = tx.tree("t");
                            for (int i = 0; i < keys; i++) {
                                if (v % 2 == 1 && i % 2 == 1) t.delete(key(i));
                                else t.put(key(i), value(i, v));
                            }
                            tx.commit();
                        }
                        committed.put(v, true);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    stop.set(true);
                }
            });
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                long seed = r;
                readers.add(new Thread(() -> {
                    Random rnd = new Random(seed);
                    try {
                        while (!stop.get()) {
                            try (ReadTxn tx = s.beginRead()) {
                                BTree t = tx.tree("t");
                                // the version of this snapshot is the one of key 0 (always present)
                                long v = ByteBuffer.wrap(t.get(key(0)).orElseThrow()).getLong(8);
                                for (int n = 0; n < 400; n++) {
                                    int i = rnd.nextInt(keys);
                                    Optional<byte[]> got = t.get(key(i));
                                    if (v % 2 == 1 && i % 2 == 1) {
                                        assertTrue(got.isEmpty(), "deleted in version " + v + ": key " + i);
                                    } else {
                                        assertArrayEquals(value(i, v), got.orElseThrow(), "key " + i + " of version " + v);
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }));
            }
            readers.forEach(Thread::start);
            writer.start();
            writer.join();
            for (Thread t : readers) t.join();
            assertNull(failure.get(), () -> "a reader saw a wrong page: " + failure.get());
            assertEquals(61, committed.size());
            assertTrue(hits.get() > 0, "hits " + hits);
            // the small cache evicts constantly, the large one holds every page written (no file reads at all)
            if (cacheBytes < (1 << 20)) assertTrue(misses.get() > 0, "misses " + misses);
            assertTrue(s.verify().entries() > 0, "the file verifies (the verifier reads around the cache)");
        }
    }

    @Test
    void aCachedStoreAndAnUncachedOneAgreeAndTheCacheSavesFileReads() throws Exception {
        long[] reads = new long[2];
        for (int run = 0; run < 2; run++) {
            int r = run;
            MetaStoreListener l = new MetaStoreListener() {
                @Override
                public void onPageRead(long nanos, long bytes) {
                    reads[r]++;
                }
            };
            MetaStoreOptions o = MetaStoreOptions.defaults().pageSize(512).fsync(false).listener(l)
                    .pageCacheBytes(run == 0 ? 0 : 8 << 20);
            try (MetaStore s = MetaStore.open(dir.resolve("a" + run + ".db"), o)) {
                try (WriteTxn tx = s.beginWrite()) {
                    for (int i = 0; i < 2000; i++) tx.tree("t").put(key(i), value(i, 1));
                    tx.commit();
                }
                for (int pass = 0; pass < 5; pass++) {
                    try (ReadTxn tx = s.beginRead()) {
                        for (int i = 0; i < 2000; i++) assertArrayEquals(value(i, 1), tx.tree("t").get(key(i)).orElseThrow());
                    }
                }
            }
        }
        // written pages enter the cache, so the cached store reads (almost) nothing back from the file
        assertTrue(reads[1] * 10 < reads[0], "uncached " + reads[0] + " reads, cached " + reads[1]);
    }
}
