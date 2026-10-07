package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupCommitTest {
    @TempDir
    Path dir;
    MetaStore store;

    static final MetaStoreOptions OPTS = MetaStoreOptions.defaults().pageSize(512).fsync(false);

    MetaStore open(MetaStoreOptions o) {
        store = MetaStore.open(dir.resolve("g.db"), o);
        return store;
    }

    @AfterEach
    void close() {
        if (store != null) store.close();
    }

    static byte[] k(String s) {
        return s.getBytes();
    }

    static byte[] counter(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    static long counterOf(Optional<byte[]> v) {
        return v.map(b -> ByteBuffer.wrap(b).getLong()).orElse(0L);
    }

    static boolean committerAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("metastore-committer") && t.isAlive());
    }

    /** Holds the writer slot so that everything submitted meanwhile ends up in one batch. */
    static List<CompletableFuture<Object>> submitWhileBlocked(MetaStore s, List<java.util.function.Function<WriteTxn, Object>> bodies) throws Exception {
        List<CompletableFuture<Object>> fs = new ArrayList<>();
        try (WriteTxn hold = s.beginWrite()) {
            for (var b : bodies) fs.add(s.submit(b));
            Thread.sleep(100);   // let the committer take them all into one batch
        }
        return fs;
    }

    @Test
    void concurrentIncrementsFrom32ThreadsAreExact() throws Exception {
        MetaStore s = open(OPTS);
        int threads = 32, per = 500;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(ex.submit(() -> {
                for (int i = 0; i < per; i++) {
                    s.writeGrouped(tx -> {
                        BTree tr = tx.tree("c");
                        tr.put(k("n"), counter(counterOf(tr.get(k("n"))) + 1));
                        return null;
                    });
                }
            }));
        }
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals((long) threads * per, counterOf(s.read(tx -> tx.tree("c").get(k("n")))));
        long commits = s.stats().lastTxId();
        assertTrue(commits < (long) threads * per, "bodies must share commits: " + commits);
        s.verify();
    }

    @Test
    void failingBodyInTheMiddleIsRolledBackAloneAndOthersCommitInOneTxn() throws Exception {
        MetaStore s = open(OPTS);
        s.writeVoid(tx -> tx.tree("t").put(k("pre"), k("p")));
        long before = s.stats().lastTxId();
        List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
        bodies.add(tx -> {
            tx.tree("t").put(k("a"), k("1"));
            return "a";
        });
        bodies.add(tx -> {
            BTree t = tx.tree("t");
            for (int i = 0; i < 60; i++) t.put(k("bad" + i), new byte[300]);     // splits, overflow pages
            t.put(k("big"), new byte[5000]);
            t.delete(k("a"));                                                    // touches a page of body 1
            t.put(k("pre"), k("overwritten"));
            tx.tree("newtree").put(k("x"), k("y"));
            tx.dropTree("t2");
            throw new IllegalArgumentException("boom");
        });
        bodies.add(tx -> {
            BTree t = tx.tree("t");
            assertEquals("1", new String(t.get(k("a")).orElseThrow()));          // sees body 1, not body 2
            t.put(k("c"), k("3"));
            return "c";
        });
        List<CompletableFuture<Object>> fs = submitWhileBlocked(s, bodies);
        assertEquals("a", fs.get(0).get(10, TimeUnit.SECONDS));
        ExecutionException e = assertThrows(ExecutionException.class, () -> fs.get(1).get(10, TimeUnit.SECONDS));
        assertEquals("boom", e.getCause().getMessage());
        assertEquals("c", fs.get(2).get(10, TimeUnit.SECONDS));
        assertEquals(before + 1, s.stats().lastTxId(), "one shared commit");
        s.read(tx -> {
            BTree t = tx.tree("t");
            assertEquals("1", new String(t.get(k("a")).orElseThrow()));
            assertEquals("3", new String(t.get(k("c")).orElseThrow()));
            assertEquals("p", new String(t.get(k("pre")).orElseThrow()));
            assertEquals(3, t.size());
            assertFalse(t.containsKey(k("big")));
            for (int i = 0; i < 60; i++) assertFalse(t.containsKey(k("bad" + i)));
            assertFalse(tx.hasTree("newtree"));
            return null;
        });
        s.verify();
    }

    @Test
    void rollbackDoesNotLetOverflowPagesOfEarlierBodiesBeOverwritten() throws Exception {
        MetaStore s = open(OPTS);
        byte[] big = new byte[3000];
        new Random(1).nextBytes(big);
        List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
        bodies.add(tx -> {
            tx.tree("t").put(k("big"), big);
            return null;
        });
        bodies.add(tx -> {
            // frees body 1's overflow chain, then writes new overflow data (it may be tempted to reuse the freed pages)
            tx.tree("t").put(k("big"), new byte[3000]);
            tx.tree("t").put(k("big2"), new byte[3000]);
            throw new IllegalStateException("fail");
        });
        List<CompletableFuture<Object>> fs = submitWhileBlocked(s, bodies);
        fs.get(0).get(10, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> fs.get(1).get(10, TimeUnit.SECONDS));
        assertArrayEquals(big, s.read(tx -> tx.tree("t").get(k("big"))).orElseThrow());
        s.verify();
    }

    @Test
    void randomBodiesWithRandomFailuresMatchAModel() throws Exception {
        MetaStore s = open(OPTS.groupCommit(16, 64, 0, 64));
        Random rnd = new Random(7);
        TreeMap<Integer, byte[]> model = new TreeMap<>();
        for (int round = 0; round < 60; round++) {
            List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
            List<TreeMap<Integer, byte[]>> effects = new ArrayList<>();
            List<Boolean> fail = new ArrayList<>();
            TreeMap<Integer, byte[]> expect = new TreeMap<>(model);
            for (int b = 0; b < 6; b++) {
                boolean f = rnd.nextInt(3) == 0;
                long seed = rnd.nextLong();
                TreeMap<Integer, byte[]> next = new TreeMap<>(expect);
                Random r = new Random(seed);
                for (int i = 0; i < 12; i++) {
                    int key = r.nextInt(120);
                    if (r.nextInt(3) == 0) next.remove(key);
                    else {
                        byte[] v = new byte[r.nextInt(5) == 0 ? 1500 : r.nextInt(100)];
                        r.nextBytes(v);
                        next.put(key, v);
                    }
                }
                bodies.add(tx -> {
                    Random rr = new Random(seed);
                    BTree t = tx.tree("t");
                    for (int i = 0; i < 12; i++) {
                        int key = rr.nextInt(120);
                        if (rr.nextInt(3) == 0) t.delete(k("" + key));
                        else {
                            byte[] v = new byte[rr.nextInt(5) == 0 ? 1500 : rr.nextInt(100)];
                            rr.nextBytes(v);
                            t.put(k("" + key), v);
                        }
                    }
                    if (f) throw new IllegalStateException("fail");
                    return null;
                });
                fail.add(f);
                if (!f) expect = next;
            }
            List<CompletableFuture<Object>> fs = submitWhileBlocked(s, bodies);
            for (int b = 0; b < fs.size(); b++) {
                final int bb = b;
                if (fail.get(b)) assertThrows(ExecutionException.class, () -> fs.get(bb).get(10, TimeUnit.SECONDS));
                else fs.get(b).get(10, TimeUnit.SECONDS);
            }
            model = expect;
            TreeMap<Integer, byte[]> m = model;
            s.read(tx -> {
                BTree t = tx.tree("t");
                assertEquals(m.size(), t.size());
                for (var e : m.entrySet()) assertArrayEquals(e.getValue(), t.get(k("" + e.getKey())).orElseThrow());
                return null;
            });
            if (round % 10 == 0) s.verify();
        }
        s.verify();
    }

    @Test
    void readersNeverSeeAPartiallyAppliedBatch() throws Exception {
        MetaStore s = open(OPTS);
        int keys = 40;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> bad = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                while (!stop.get()) {
                    s.read(tx -> {
                        BTree t = tx.tree("t");
                        Long first = null;
                        for (int i = 0; i < keys; i++) {
                            Optional<byte[]> v = t.get(k("k" + i));
                            if (v.isEmpty()) {
                                if (first != null) throw new AssertionError("key " + i + " missing, round " + first);
                                continue;
                            }
                            long r = counterOf(v);
                            if (first == null) first = r;
                            if (first != r) throw new AssertionError("mixed rounds " + first + " vs " + r + " at key " + i);
                        }
                        return null;
                    });
                }
            } catch (Throwable t) {
                bad.set(t);
            }
        });
        reader.start();
        for (int round = 1; round <= 80; round++) {
            final long r = round;
            List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
            for (int i = 0; i < keys; i++) {
                final int key = i;
                bodies.add(tx -> {
                    tx.tree("t").put(k("k" + key), counter(r));
                    return null;
                });
            }
            for (var f : submitWhileBlocked(s, bodies)) f.get(10, TimeUnit.SECONDS);
        }
        stop.set(true);
        reader.join();
        assertNull(bad.get());
    }

    @Test
    void futuresCompleteOnlyAfterTheMetaPageIsDurable() throws Exception {
        MetaStore s = open(OPTS);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<CompletableFuture<?>> fut = new AtomicReference<>();
        s.commitHook = stage -> {
            events.add(stage + ":done=" + fut.get().isDone());
        };
        // hold the writer so that the future is registered before the commit runs
        CompletableFuture<Object> f;
        try (WriteTxn hold = s.beginWrite()) {
            f = s.submit(tx -> {
                tx.tree("t").put(k("a"), k("1"));
                return 42;
            });
            fut.set(f);
        }
        assertEquals(42, f.get(10, TimeUnit.SECONDS));
        assertTrue(f.isDone());
        assertEquals(List.of("before-commit:done=false", "after-data:done=false", "after-meta:done=false"), events);
        assertEquals("1", new String(s.read(tx -> tx.tree("t").get(k("a"))).orElseThrow()));
    }

    @Test
    void crashBeforeTheMetaPageLeavesNoneOfTheBatchAfterReopen() throws Exception {
        MetaStore s = open(OPTS);
        s.writeVoid(tx -> tx.tree("t").put(k("pre"), k("p")));
        s.commitHook = stage -> {
            if (stage.equals("after-data")) throw new IllegalStateException("simulated crash");
        };
        List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            bodies.add(tx -> {
                tx.tree("t").put(k("b" + n), k("v"));
                return null;
            });
        }
        List<CompletableFuture<Object>> fs = submitWhileBlocked(s, bodies);
        for (var f : fs) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
            assertEquals("simulated crash", e.getCause().getMessage());
        }
        s.close();
        store = null;
        try (MetaStore r = MetaStore.open(dir.resolve("g.db"), OPTS)) {
            assertEquals(1L, (long) r.read(tx -> tx.tree("t").size()));
            r.verify();
        }
    }

    @Test
    void poisonedStoreFailsLaterSubmitsFastAndTheCommitterSurvivesUntilClose() throws Exception {
        MetaStore s = open(OPTS);
        s.commitHook = stage -> {
            if (stage.equals("after-data")) throw new IllegalStateException("disk gone");
        };
        ExecutionException first = assertThrows(ExecutionException.class,
                () -> s.submit(tx -> { tx.tree("t").put(k("a"), k("1")); return null; }).get(10, TimeUnit.SECONDS));
        assertEquals("disk gone", first.getCause().getMessage());
        s.commitHook = null;
        CompletableFuture<Object> late = s.submit(tx -> null);
        assertTrue(late.isCompletedExceptionally(), "fails fast, without queueing");
        ExecutionException e = assertThrows(ExecutionException.class, late::get);
        assertEquals("disk gone", e.getCause().getCause().getMessage());
        IllegalStateException ise = assertThrows(IllegalStateException.class, () -> s.writeGrouped(tx -> null));
        assertEquals("disk gone", ise.getCause().getMessage());
        assertTrue(committerAlive(), "the committer thread is still there");
        s.close();
        store = null;
        assertFalse(committerAlive());
    }

    @Test
    void submitsQueuedBeforePoisoningFailWithTheCause() throws Exception {
        MetaStore s = open(OPTS);
        s.commitHook = stage -> {
            if (stage.equals("after-data")) throw new IllegalStateException("disk gone");
        };
        List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
        for (int i = 0; i < 5; i++) bodies.add(tx -> {
            tx.tree("t").put(k("a"), k("1"));
            return null;
        });
        for (var f : submitWhileBlocked(s, bodies)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause().getMessage().contains("disk gone") || e.getCause().getCause().getMessage().contains("disk gone"));
        }
    }

    @Test
    void closeCommitsPendingBodiesAndStopsTheThread() throws Exception {
        MetaStore s = open(OPTS);
        List<CompletableFuture<Object>> fs = new ArrayList<>();
        Thread closer;
        WriteTxn hold = s.beginWrite();
        for (int i = 0; i < 20; i++) {
            final int n = i;
            fs.add(s.submit(tx -> {
                tx.tree("t").put(k("k" + n), k("v"));
                return n;
            }));
        }
        closer = new Thread(s::close);
        closer.start();
        Thread.sleep(200);
        assertTrue(closer.isAlive(), "close waits for the running transaction");
        hold.close();
        closer.join(10_000);
        assertFalse(closer.isAlive());
        for (int i = 0; i < fs.size(); i++) assertEquals(i, fs.get(i).get(1, TimeUnit.SECONDS));
        assertFalse(committerAlive(), "no leaked committer thread");
        CompletableFuture<Object> after = s.submit(tx -> null);
        assertThrows(ExecutionException.class, after::get);
        store = null;
        try (MetaStore r = MetaStore.open(dir.resolve("g.db"), OPTS)) {
            assertEquals(20L, (long) r.read(tx -> tx.tree("t").size()));
        }
    }

    @Test
    void submitsRacingWithCloseAlwaysEndUpCompleted() throws Exception {
        for (int round = 0; round < 20; round++) {
            Path f = dir.resolve("race" + round + ".db");
            MetaStore s = MetaStore.open(f, OPTS.groupCommit(8, 64, 0, 4));
            List<CompletableFuture<Object>> fs = new CopyOnWriteArrayList<>();
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Thread th = new Thread(() -> {
                    for (int i = 0; i < 50; i++) fs.add(s.submit(tx -> {
                        tx.tree("t").put(k("a"), k("1"));
                        return 1;
                    }));
                });
                ts.add(th);
                th.start();
            }
            Thread.sleep(round % 3);
            s.close();
            for (Thread th : ts) th.join(10_000);
            for (var fu : fs) {
                try {
                    fu.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof IllegalStateException);
                }
            }
        }
        assertFalse(committerAlive());
    }

    @Test
    void beginWriteAndGroupedWritesInterleaveCorrectly() throws Exception {
        MetaStore s = open(OPTS);
        int threads = 8, per = 200;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final boolean grouped = t % 2 == 0;
            fs.add(ex.submit(() -> {
                for (int i = 0; i < per; i++) {
                    if (grouped) {
                        s.writeGrouped(tx -> inc(tx));
                    } else {
                        try (WriteTxn tx = s.beginWrite()) {
                            inc(tx);
                            tx.commit();
                        }
                    }
                }
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals((long) threads * per, counterOf(s.read(tx -> tx.tree("c").get(k("n")))));
        s.verify();
    }

    private static Object inc(WriteTxn tx) {
        BTree tr = tx.tree("c");
        tr.put(k("n"), counter(counterOf(tr.get(k("n"))) + 1));
        return null;
    }

    @Test
    void batchSizeLimitSplitsTheQueueIntoSeveralCommits() throws Exception {
        MetaStore s = open(OPTS.groupCommit(4, 1024, 0, 64));
        long before = s.stats().lastTxId();
        List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            bodies.add(tx -> {
                tx.tree("t").put(k("k" + n), k("v"));
                return null;
            });
        }
        for (var f : submitWhileBlocked(s, bodies)) f.get(10, TimeUnit.SECONDS);
        assertEquals(before + 3, s.stats().lastTxId());   // 4 + 4 + 2
    }

    @Test
    void pageLimitEndsTheBatchAfterTheBodyThatCrossedIt() throws Exception {
        MetaStore s = open(OPTS.groupCommit(100, 3, 0, 64));
        long before = s.stats().lastTxId();
        List<java.util.function.Function<WriteTxn, Object>> bodies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            bodies.add(tx -> {
                tx.tree("t").put(k("k" + n), new byte[2000]);   // overflow: several pages each
                return null;
            });
        }
        for (var f : submitWhileBlocked(s, bodies)) f.get(10, TimeUnit.SECONDS);
        assertEquals(before + 6, s.stats().lastTxId());
    }

    @Test
    void fullQueueBlocksSubmitters() throws Exception {
        MetaStore s = open(OPTS.groupCommit(8, 64, 0, 2));
        AtomicInteger submitted = new AtomicInteger();
        Thread t;
        try (WriteTxn hold = s.beginWrite()) {
            t = new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    s.submit(tx -> null);
                    submitted.incrementAndGet();
                }
            });
            t.start();
            Thread.sleep(300);
            // 1 taken by the committer (waiting for the writer slot) + a batch drained + 2 in the queue at most
            assertTrue(submitted.get() < 20, "submit must block when the queue is full: " + submitted.get());
            assertTrue(t.isAlive());
        }
        t.join(10_000);
        assertEquals(20, submitted.get());
    }

    @Test
    void bodyMustNotCommitOrNest() throws Exception {
        MetaStore s = open(OPTS);
        assertThrows(IllegalStateException.class, () -> s.writeGrouped(tx -> {
            tx.tree("t").put(k("a"), k("1"));
            tx.commit();
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> s.writeGrouped(tx -> s.writeGrouped(t2 -> null)));
        s.writeGrouped(tx -> {
            tx.close();   // no-op inside a body
            tx.tree("t").put(k("b"), k("2"));
            return null;
        });
        assertEquals(1L, (long) s.read(tx -> tx.tree("t").size()));
    }

    @Test
    void bodyExceptionIsRethrownAsIs() {
        MetaStore s = open(OPTS);
        IllegalArgumentException boom = new IllegalArgumentException("x");
        assertSame(boom, assertThrows(IllegalArgumentException.class, () -> s.writeGrouped(tx -> {
            throw boom;
        })));
        assertEquals(0L, s.stats().lastTxId(), "a batch of failures does not commit anything");
    }
}
