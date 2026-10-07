package org.example.sectoriadb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The reference-counted LRU behind the table cache and the small-blob cache (doc 10, part B). */
class ResidentCacheTest {

    /** A resource that fails every use after it was closed (like a closed file channel). */
    static final class Res {
        final String id;
        volatile boolean closed;
        volatile boolean evictable = true;
        final long weight;

        Res(String id, long weight) {
            this.id = id;
            this.weight = weight;
        }

        void use() throws IOException {
            if (closed) throw new java.nio.channels.ClosedChannelException();
        }
    }

    static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    final List<Res> closedOnes = new ArrayList<>();

    ResidentCache<Res> cache(int max, long maxWeight, AtomicInteger loads) {
        return new ResidentCache<>("test", max, maxWeight, r -> r.weight, r -> r.evictable, r -> {
            r.closed = true;
            synchronized (closedOnes) {
                closedOnes.add(r);
            }
        }, null);
    }

    ResidentCache.Loader<Res> loader(AtomicInteger loads, long weight) {
        return id -> {
            loads.incrementAndGet();
            return new Res(id, weight);
        };
    }

    @Test
    void theLeastRecentlyUsedIdleResourceIsEvictedFirst() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(2, 0, loads);
        c.acquire("a", loader(loads, 1)).close();
        c.acquire("b", loader(loads, 1)).close();
        c.acquire("a", loader(loads, 1)).close();   // a is now the most recently used
        c.acquire("c", loader(loads, 1)).close();   // b goes
        assertEquals(2, c.size());
        assertNotNull(c.peek("a"));
        assertNull(c.peek("b"));
        assertNotNull(c.peek("c"));
        assertEquals(1, c.evictions());
        assertEquals(1, closedOnes.size());
        assertEquals("b", closedOnes.get(0).id);
        assertEquals(3, loads.get());
    }

    @Test
    void aPinnedResourceIsNeverClosedWhateverTheEvictionPressure() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(1, 0, loads);
        ResidentHandle<Res> pinned = c.acquire("a", loader(loads, 1));
        for (int i = 0; i < 20; i++) c.acquire("x" + i, loader(loads, 1)).close();   // pressure: limit is 1
        pinned.get().use();                                                          // still open
        assertFalse(pinned.get().closed);
        assertSame(pinned.get(), c.peek("a"), "still resident: the limit is soft while it is in use");
        pinned.close();
        pinned.close();                                                              // idempotent
        assertEquals(1, c.size(), "after the release the cache is back within its limit");
        assertFalse(pinned.get().closed, "within the limit it simply stays resident");
        c.acquire("y", loader(loads, 1)).close();   // now a is the least recently used idle one
        assertTrue(pinned.get().closed, "closed once it was out of the cache and idle");
    }

    @Test
    void explicitEvictionClosesWhenTheLastHandleIsReleased() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(8, 0, loads);
        ResidentHandle<Res> h1 = c.acquire("a", loader(loads, 1));
        ResidentHandle<Res> h2 = c.acquire("a", loader(loads, 1));
        assertSame(h1.get(), h2.get());
        assertEquals(1, loads.get());
        c.evict("a");
        assertNull(c.peek("a"));
        assertFalse(h1.get().closed);
        h1.close();
        assertFalse(h2.get().closed, "one handle is still open");
        h2.close();
        assertTrue(h2.get().closed);
        ResidentHandle<Res> again = c.acquire("a", loader(loads, 1));
        assertEquals(2, loads.get(), "an evicted resource is loaded afresh");
        assertFalse(again.get().closed);
        again.close();
    }

    @Test
    void aResourceThatIsNotEvictableStays() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(1, 0, loads);
        ResidentHandle<Res> a = c.acquire("a", loader(loads, 1));
        a.get().evictable = false;   // like a table with unforced writes
        a.close();
        c.acquire("b", loader(loads, 1)).close();   // over the limit: a is skipped, b (idle, evictable) goes
        assertNotNull(c.peek("a"));
        assertNull(c.peek("b"));
        assertFalse(a.get().closed);
        a.get().evictable = true;
        c.acquire("c", loader(loads, 1)).close();   // pressure again: now a, the eldest, can go
        assertNull(c.peek("a"));
        assertTrue(a.get().closed);
    }

    @Test
    void theWeightBudgetEvictsToo() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(100, 25, loads);
        for (int i = 0; i < 10; i++) c.acquire("w" + i, loader(loads, 10)).close();
        assertEquals(2, c.size());
        assertEquals(20, c.weight());
    }

    @Test
    void aFailedLoadIsReportedToEveryWaiterAndTheNextAcquireTriesAgain() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ResidentCache<Res> c = cache(4, 0, new AtomicInteger());
        CountDownLatch inLoad = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        ResidentCache.Loader<Res> failing = id -> {
            attempts.incrementAndGet();
            inLoad.countDown();
            await(go);
            throw new IOException("disk says no");
        };
        ExecutorService ex = Executors.newFixedThreadPool(2);
        Future<?> first = ex.submit(() -> assertThrows(IOException.class, () -> c.acquire("a", failing)));
        inLoad.await();
        Future<?> second = ex.submit(() -> assertThrows(IOException.class, () -> c.acquire("a", failing)));
        Thread.sleep(100);
        go.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertEquals(0, c.size());
        ex.shutdown();
        try (ResidentHandle<Res> ok = c.acquire("a", loader(new AtomicInteger(), 1))) {
            assertNotNull(ok.get());
        }
    }

    @Test
    void concurrentAcquiresOfOneIdLoadItOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(4, 0, loads);
        ResidentCache.Loader<Res> slow = id -> {
            loads.incrementAndGet();
            sleepQuietly(100);
            return new Res(id, 1);
        };
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<Res>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(ex.submit(() -> {
            try (ResidentHandle<Res> h = c.acquire("a", slow)) {
                return h.get();
            }
        }));
        Res first = fs.get(0).get();
        for (Future<Res> f : fs) assertSame(first, f.get());
        assertEquals(1, loads.get());
        ex.shutdown();
    }

    /** The stage-01 race: threads keep using resources while others force constant eviction; nobody sees a closed one. */
    @Test
    void usersNeverSeeAClosedResourceUnderEvictionChurn() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(2, 0, loads);
        int ids = 6, threads = 12;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        Set<Throwable> failures = ConcurrentHashMap.newKeySet();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            fs.add(ex.submit(() -> {
                java.util.Random r = new java.util.Random(seed);
                for (int i = 0; i < 20_000; i++) {
                    try (ResidentHandle<Res> h = c.acquire("r" + r.nextInt(ids), loader(loads, 1))) {
                        h.get().use();
                        if ((i & 63) == 0) Thread.yield();
                        h.get().use();
                    } catch (Throwable e) {
                        failures.add(e);
                        return;
                    }
                }
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        assertTrue(failures.isEmpty(), "no thread saw a closed resource: " + failures);
        assertTrue(c.evictions() > 100, "there was churn: " + c.evictions());
        assertTrue(c.size() <= 2);
        assertEquals(loads.get() - c.size(), closedOnes.size(), "everything that left the cache was closed exactly once");
    }

    @Test
    void closeAllClosesEverythingEvenPinned() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        ResidentCache<Res> c = cache(4, 0, loads);
        ResidentHandle<Res> a = c.acquire("a", loader(loads, 1));
        c.acquire("b", loader(loads, 1)).close();
        c.closeAll();
        assertTrue(a.get().closed);
        assertEquals(0, c.size());
        a.close();   // releasing after closeAll is harmless
    }
}
