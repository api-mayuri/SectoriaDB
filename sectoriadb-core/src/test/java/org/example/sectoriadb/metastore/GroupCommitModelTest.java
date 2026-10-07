package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Randomised model-based test of group commit with per-body savepoints. Bodies run strictly one after another on
 * the committer thread (or on the test thread for direct transactions that hold the writer slot), so a plain
 * in-memory model that is updated by the very same code that drives the store IS the model of the serial
 * execution order: a body snapshots the model when it starts, applies every operation to store and model, checks
 * every read against the model, and restores the snapshot when it throws. After each round the whole store is
 * compared with the model and {@link MetaStore#verify()} checks the exact page accounting.
 */
class GroupCommitModelTest {
    @TempDir
    Path dir;
    MetaStore store;

    @AfterEach
    void close() {
        if (store != null) store.close();
    }

    static final int PAGE = 512;

    MetaStore open(int maxBatch, int maxPages) {
        store = MetaStore.open(dir.resolve("m.db"),
                MetaStoreOptions.defaults().pageSize(PAGE).fsync(false).groupCommit(maxBatch, maxPages, 0, 4096));
        return store;
    }

    // ================================================================ model

    static final class Boom extends RuntimeException {
        Boom() {
            super("injected", null, false, false);
        }
    }

    static final class Model {
        TreeMap<String, TreeMap<byte[], byte[]>> trees = new TreeMap<>();

        static TreeMap<byte[], byte[]> newTree() {
            return new TreeMap<>(Arrays::compareUnsigned);
        }

        Model copy() {
            Model m = new Model();
            for (var e : trees.entrySet()) {
                TreeMap<byte[], byte[]> t = newTree();
                t.putAll(e.getValue());
                m.trees.put(e.getKey(), t);
            }
            return m;
        }

        long entries() {
            long n = 0;
            for (var t : trees.values()) n += t.size();
            return n;
        }
    }

    final Model model = new Model();                       // touched only while holding the writer slot
    final ArrayDeque<String> trace = new ArrayDeque<>();   // last operations, for failure messages (writer slot too)
    final List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

    void log(String s) {
        if (trace.size() > 150) trace.pollFirst();
        trace.addLast(s);
    }

    static final String[] TREES = {"t0", "t1", "t2", "t3", "t4"};

    static byte[] key(int i) {
        return String.format("k%03d", i).getBytes();
    }

    static byte[] value(Random r) {
        int c = r.nextInt(100);
        int len = c < 45 ? r.nextInt(60) : c < 70 ? 90 + r.nextInt(300) : c < 97 ? 500 + r.nextInt(3000) : 6000 + r.nextInt(4000);
        byte[] v = new byte[len];
        r.nextBytes(v);
        return v;
    }

    static String describe(byte[] v) {
        return v == null ? "null" : v.length + "B#" + Arrays.hashCode(v);
    }

    /**
     * One body: a random sequence of operations on store and model, optionally ending in an exception. Everything is
     * verified as it goes.
     */
    Object runBody(WriteTxn tx, long bodySeed, int id) {
        Random r = new Random(bodySeed);
        Model snapshot = model.copy();
        int ops = 1 + r.nextInt(14);
        int throwAt = r.nextInt(100) < 30 ? r.nextInt(ops + 1) : -1;
        boolean badKey = r.nextInt(100) < 4;
        log("body " + id + " ops=" + ops + " throwAt=" + throwAt);
        try {
            for (int i = 0; i < ops; i++) {
                if (i == throwAt) throw new Boom();
                op(tx, r);
            }
            if (throwAt == ops) throw new Boom();
            if (badKey) {
                String t = TREES[r.nextInt(TREES.length)];
                model.trees.computeIfAbsent(t, x -> Model.newTree());
                byte[] big = new byte[BTree.maxKeySize(PAGE) + 1];
                assertThrows(IllegalArgumentException.class, () -> tx.tree(t).put(big, new byte[1]));
                log("  bad key swallowed");
            }
            return id;
        } catch (Throwable e) {
            model.trees = snapshot.trees;
            log("  body " + id + " threw " + e);
            if (!(e instanceof Boom)) unexpected.add(e);
            throw e;
        }
    }

    void op(WriteTxn tx, Random r) {
        String tn = TREES[r.nextInt(TREES.length)];
        int c = r.nextInt(100);
        TreeMap<byte[], byte[]> m = model.trees.get(tn);
        if (c < 30) {                                              // put
            byte[] k = key(r.nextInt(70));
            byte[] v = value(r);
            log("  put " + tn + " " + new String(k) + " " + describe(v));
            tx.tree(tn).put(k, v);
            model.trees.computeIfAbsent(tn, x -> Model.newTree()).put(k, v);
        } else if (c < 45) {                                       // delete
            byte[] k = key(r.nextInt(70));
            log("  del " + tn + " " + new String(k));
            boolean had = m != null && m.containsKey(k);
            boolean got = tx.tree(tn).delete(k);
            assertEquals(had, got, "delete result");
            if (m == null) model.trees.put(tn, Model.newTree());
            else m.remove(k);
        } else if (c < 60) {                                       // get
            byte[] k = key(r.nextInt(70));
            log("  get " + tn + " " + new String(k));
            byte[] got = tx.tree(tn).get(k).orElse(null);
            byte[] exp = m == null ? null : m.get(k);
            assertArrayEqualsOrNull(exp, got, "get " + tn + " " + new String(k));
            model.trees.computeIfAbsent(tn, x -> Model.newTree());
        } else if (c < 70) {                                       // range scan + size
            int a = r.nextInt(70), b = a + r.nextInt(30);
            log("  scan " + tn + " " + a + ".." + b);
            BTree t = tx.tree(tn);
            m = model.trees.computeIfAbsent(tn, x -> Model.newTree());
            assertEquals(m.size(), t.size(), "size " + tn);
            var exp = m.subMap(key(a), true, key(b), false).entrySet().iterator();
            try (Cursor cur = t.scan(key(a), key(b))) {
                while (cur.next()) {
                    assertTrue(exp.hasNext(), "scan returned extra key " + new String(cur.key()));
                    var e = exp.next();
                    assertArrayEquals(e.getKey(), cur.key());
                    assertArrayEquals(e.getValue(), cur.value());
                }
            }
            assertFalse(exp.hasNext(), "scan missed keys");
        } else if (c < 78) {                                       // drop tree
            log("  drop " + tn);
            boolean had = model.trees.containsKey(tn);
            BTree h = had && r.nextBoolean() ? tx.tree(tn) : null;
            assertEquals(had, tx.dropTree(tn), "dropTree result");
            model.trees.remove(tn);
            if (h != null) assertThrows(IllegalStateException.class, () -> h.put(key(1), new byte[1]));
        } else if (c < 84) {                                       // create tree only
            log("  create " + tn);
            tx.tree(tn);
            model.trees.computeIfAbsent(tn, x -> Model.newTree());
        } else if (c < 90) {                                       // delete a run of keys (merges)
            int a = r.nextInt(70), n = 5 + r.nextInt(25);
            log("  delrun " + tn + " " + a + "+" + n);
            BTree t = tx.tree(tn);
            m = model.trees.computeIfAbsent(tn, x -> Model.newTree());
            for (int j = a; j < a + n; j++) assertEquals(m.remove(key(j)) != null, t.delete(key(j)));
        } else if (c < 96) {                                       // burst of puts (splits, overflow chains)
            int a = r.nextInt(70), n = 5 + r.nextInt(20);
            log("  putrun " + tn + " " + a + "+" + n);
            BTree t = tx.tree(tn);
            m = model.trees.computeIfAbsent(tn, x -> Model.newTree());
            for (int j = a; j < a + n; j++) {
                byte[] v = value(r);
                t.put(key(j), v);
                m.put(key(j), v);
            }
        } else {                                                   // tree list
            log("  names");
            assertEquals(new ArrayList<>(model.trees.keySet()), tx.treeNames());
            for (String t : TREES) assertEquals(model.trees.containsKey(t), tx.hasTree(t), "hasTree " + t);
        }
    }

    static void assertArrayEqualsOrNull(byte[] exp, byte[] got, String msg) {
        if (exp == null) {
            assertEquals(null, got, msg);
        } else {
            assertNotNull(got, msg);
            assertArrayEquals(exp, got, msg);
        }
    }

    static void checkEquals(Model m, ReadTxn tx, String what) {
        assertEquals(new ArrayList<>(m.trees.keySet()), tx.treeNames(), what + ": tree names");
        for (var e : m.trees.entrySet()) {
            BTree t = tx.tree(e.getKey());
            assertEquals(e.getValue().size(), t.size(), what + ": size of " + e.getKey());
            var it = e.getValue().entrySet().iterator();
            try (Cursor c = t.scan()) {
                while (c.next()) {
                    assertTrue(it.hasNext(), what + ": extra key in " + e.getKey());
                    var x = it.next();
                    assertArrayEquals(x.getKey(), c.key(), what + ": key order in " + e.getKey());
                    assertArrayEquals(x.getValue(), c.value(), what + ": value of " + new String(x.getKey()));
                }
            }
            assertFalse(it.hasNext(), what + ": missing keys in " + e.getKey());
        }
    }

    // ================================================================ randomized test

    @Test
    void randomizedGroupCommitAgainstModel() throws Exception {
        for (long seed = 1; seed <= Long.getLong("gcm.seeds", 8); seed++) runSeed(seed);
    }

    Thread scanner;
    volatile boolean scanning;

    /**
     * A concurrent reader that keeps opening short snapshots and reads everything in them: a page that a writer
     * overwrote although a snapshot still reaches it shows up as a checksum/type error or a count mismatch.
     */
    Thread startScanner(MetaStore s) {
        scanning = true;
        Thread t = new Thread(() -> {
            try {
                while (scanning) {
                    try (ReadTxn tx = s.beginRead()) {
                        for (String n : tx.treeNames()) {
                            BTree tree = tx.tree(n);
                            long cnt = 0;
                            try (Cursor c = tree.scan()) {
                                while (c.next()) {
                                    c.value();
                                    cnt++;
                                }
                            }
                            assertEquals(tree.size(), cnt, "snapshot " + tx.txId() + " tree " + n);
                        }
                    }
                    Thread.sleep(1);
                }
            } catch (Throwable e) {
                if (scanning) unexpected.add(e);
            }
        }, "model-scanner");
        t.setDaemon(true);
        t.start();
        return t;
    }

    void stopScanner() throws InterruptedException {
        scanning = false;
        if (scanner != null) scanner.join();
        scanner = null;
    }

    void runSeed(long seed) throws Exception {
        stopScanner();
        if (store != null) store.close();
        store = null;
        Files.deleteIfExists(dir.resolve("m.db"));
        model.trees.clear();
        trace.clear();
        unexpected.clear();
        Random r = new Random(seed * 7919);
        int threads = seed % 2 == 0 ? 4 : 1;       // odd seeds: one submitter => deterministic execution order
        open(1 + r.nextInt(40), 4 + r.nextInt(200));
        int rounds = Integer.getInteger("gcm.rounds", 14);
        List<ReadTxn> readers = new ArrayList<>();
        List<Model> readerModels = new ArrayList<>();
        List<Integer> readerRounds = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        scanner = startScanner(store);
        try {
            for (int round = 0; round < rounds; round++) {
                String ctx = "seed " + seed + " round " + round;
                // 1. optionally hold the writer slot: run a direct txn, and let everything queue into one batch
                boolean hold = r.nextInt(100) < 60;
                WriteTxn direct = hold ? store.beginWrite() : null;
                boolean directCommit = true;
                if (direct != null) {
                    Model snap = model.copy();
                    int n = r.nextInt(3);
                    directCommit = r.nextBoolean();
                    try {
                        for (int i = 0; i < n; i++) op(direct, r);
                    } catch (RuntimeException | AssertionError e) {
                        unexpected.add(e);
                    }
                    if (!directCommit) model.trees = snap.trees;
                    if (!unexpected.isEmpty()) {
                        direct.close();
                        fail(ctx + ": direct txn failed " + unexpected.get(0) + "\n" + String.join("\n", trace), unexpected.get(0));
                    }
                }
                // 2. submit bodies from several threads
                int per = 3 + r.nextInt(12);
                List<CompletableFuture<Object>> futures = Collections.synchronizedList(new ArrayList<>());
                List<Future<?>> subs = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final long base = seed * 1_000_003L + round * 1009L + t * 100_003L;
                    final int idBase = round * 10_000 + t * 1000;
                    subs.add(pool.submit(() -> {
                        for (int i = 0; i < per; i++) {
                            int id = idBase + i;
                            long bs = base + i * 31L;
                            futures.add(store.submit((Function<WriteTxn, Object>) tx -> runBody(tx, bs, id)));
                        }
                    }));
                }
                for (Future<?> f : subs) f.get(30, TimeUnit.SECONDS);
                if (direct != null) {
                    if (directCommit) direct.commit();
                    else direct.close();
                }
                // 3. wait for everything
                int ok = 0, failed = 0;
                for (var f : futures) {
                    try {
                        f.get(30, TimeUnit.SECONDS);
                        ok++;
                    } catch (ExecutionException e) {
                        if (!(e.getCause() instanceof Boom)) unexpected.add(e.getCause());
                        failed++;
                    }
                }
                if (!unexpected.isEmpty()) {
                    fail(ctx + ": unexpected failure " + unexpected.get(0) + "\n" + String.join("\n", trace), unexpected.get(0));
                }
                // 4. whole-store comparison, page accounting, long-lived readers
                try {
                    store.read(tx -> {
                        checkEquals(model, tx, ctx);
                        return null;
                    });
                    MetaStore.VerifyReport rep = store.verify();
                    assertEquals(model.trees.size(), rep.trees(), ctx + ": trees in verify");
                    assertEquals(model.entries(), rep.entries(), ctx + ": entries in verify");
                    if (Boolean.getBoolean("gcm.print")) System.out.println(ctx + " " + rep + " " + store.stats());
                    for (int i = 0; i < readers.size(); i++) checkEquals(readerModels.get(i), readers.get(i), ctx + ": reader " + i);
                } catch (Throwable e) {
                    throw new AssertionError(ctx + " (ok=" + ok + " failed=" + failed + "): " + e + "\n" + String.join("\n", trace), e);
                }
                // 5. keep readers across batches, occasionally reopen
                // (a reader pins the freed pages of every later commit, and the freelist chain is rewritten per commit,
                // so readers are kept for a few rounds only: this is a known cost of the freelist design, not under test)
                if (!readers.isEmpty() && round - readerRounds.get(0) >= 3) {
                    readers.remove(0).close();
                    readerModels.remove(0);
                    readerRounds.remove(0);
                }
                if (r.nextInt(100) < 35 && readers.size() < 3) {
                    readers.add(store.beginRead());
                    readerModels.add(model.copy());
                    readerRounds.add(round);
                }
                if (r.nextInt(100) < 15) {
                    for (ReadTxn t : readers) t.close();
                    readers.clear();
                    readerModels.clear();
                    readerRounds.clear();
                    stopScanner();
                    store.close();
                    open(1 + r.nextInt(40), 4 + r.nextInt(200));
                    scanner = startScanner(store);
                    try {
                        store.read(tx -> {
                            checkEquals(model, tx, ctx + " after reopen");
                            return null;
                        });
                        store.verify();
                    } catch (Throwable e) {
                        throw new AssertionError(ctx + " after reopen: " + e, e);
                    }
                }
            }
        } finally {
            stopScanner();
            pool.shutdownNow();
            for (ReadTxn t : readers) t.close();
        }
    }

    // ================================================================ targeted cases

    static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** All bodies are queued behind a held writer slot so that they land in one batch. */
    List<CompletableFuture<Object>> batch(List<Function<WriteTxn, Object>> bodies) {
        List<CompletableFuture<Object>> fs = new ArrayList<>();
        try (WriteTxn hold = store.beginWrite()) {
            for (var b : bodies) fs.add(store.submit(b));
        }
        return fs;
    }

    static void expectOk(CompletableFuture<Object> f) throws Exception {
        f.get(20, TimeUnit.SECONDS);
    }

    static void expectBoom(CompletableFuture<Object> f) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(20, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof Boom, "cause: " + e.getCause());
    }

    @Test
    void bodyFreesOverflowOfEarlierBodyThenThrows() throws Exception {
        open(64, 1024);
        byte[] a = bytes(3000, 1), b = bytes(3000, 2), big = bytes(5000, 3);
        var fs = batch(List.of(
                tx -> {
                    tx.tree("t").put(key(1), a);
                    tx.tree("t").put(key(2), b);
                    return null;
                },
                tx -> {                                   // frees (replaces and deletes) overflow chains of body 1, reuses pages, fails
                    tx.tree("t").put(key(1), big);
                    tx.tree("t").delete(key(2));
                    tx.tree("t").put(key(3), bytes(4000, 4));
                    throw new Boom();
                },
                tx -> {                                   // must still see body 1's intact data
                    assertArrayEquals(a, tx.tree("t").get(key(1)).orElseThrow());
                    assertArrayEquals(b, tx.tree("t").get(key(2)).orElseThrow());
                    assertFalse(tx.tree("t").containsKey(key(3)));
                    tx.tree("t").put(key(5), bytes(4000, 5));
                    return null;
                }));
        expectOk(fs.get(0));
        expectBoom(fs.get(1));
        expectOk(fs.get(2));
        store.read(tx -> {
            assertArrayEquals(a, tx.tree("t").get(key(1)).orElseThrow());
            assertArrayEquals(b, tx.tree("t").get(key(2)).orElseThrow());
            assertArrayEquals(bytes(4000, 5), tx.tree("t").get(key(5)).orElseThrow());
            assertEquals(3, tx.tree("t").size());
            return null;
        });
        store.verify();
    }

    @Test
    void bodyDropsTreeOfEarlierBodyThenThrows() throws Exception {
        open(64, 1024);
        byte[] a = bytes(2500, 1);
        var fs = batch(List.of(
                tx -> {
                    for (int i = 0; i < 40; i++) tx.tree("x").put(key(i), bytes(200 + i, i));
                    tx.tree("x").put(key(99), a);
                    return null;
                },
                tx -> {
                    assertTrue(tx.dropTree("x"));
                    tx.tree("x").put(key(1), bytes(3000, 9));   // recreate and reuse the dropped pages
                    throw new Boom();
                },
                tx -> {
                    assertEquals(41, tx.tree("x").size());
                    assertArrayEquals(a, tx.tree("x").get(key(99)).orElseThrow());
                    for (int i = 0; i < 40; i++) assertArrayEquals(bytes(200 + i, i), tx.tree("x").get(key(i)).orElseThrow());
                    return null;
                }));
        expectOk(fs.get(0));
        expectBoom(fs.get(1));
        expectOk(fs.get(2));
        store.read(tx -> {
            assertEquals(41, tx.tree("x").size());
            return null;
        });
        store.verify();
    }

    @Test
    void bodyCreatesTreeWritesAndThrows() throws Exception {
        open(64, 1024);
        var fs = batch(List.of(
                tx -> {
                    tx.tree("keep").put(key(1), bytes(100, 1));
                    return null;
                },
                tx -> {
                    for (int i = 0; i < 30; i++) tx.tree("ghost").put(key(i), bytes(1000, i));
                    tx.tree("ghost2");
                    throw new Boom();
                },
                tx -> {
                    assertEquals(List.of("keep"), tx.treeNames());
                    assertFalse(tx.hasTree("ghost"));
                    return null;
                }));
        expectOk(fs.get(0));
        expectBoom(fs.get(1));
        expectOk(fs.get(2));
        store.read(tx -> {
            assertEquals(List.of("keep"), tx.treeNames());
            return null;
        });
        store.verify();
    }

    @Test
    void brokenFlagOfFailedBodyDoesNotLeakIntoNextBody() throws Exception {
        open(64, 1024);
        var fs = batch(List.of(
                tx -> {
                    tx.tree("t").put(key(1), bytes(1500, 1));
                    return null;
                },
                tx -> {                                   // what a failure in the middle of a tree operation leaves behind
                    tx.tree("t").put(key(2), bytes(1500, 2));
                    tx.broken = true;
                    throw new Boom();
                },
                tx -> {
                    tx.tree("t").put(key(3), bytes(1500, 3));
                    return null;
                }));
        expectOk(fs.get(0));
        expectBoom(fs.get(1));
        expectOk(fs.get(2));
        store.read(tx -> {
            assertEquals(2, tx.tree("t").size());
            assertFalse(tx.tree("t").containsKey(key(2)));
            return null;
        });
        store.verify();
    }

    @Test
    void batchWhereAllBodiesFail() throws Exception {
        open(64, 1024);
        store.writeVoid(tx -> tx.tree("t").put(key(1), bytes(2000, 1)));
        long before = store.stats().lastTxId();
        var fs = batch(List.of(
                tx -> {
                    tx.tree("t").delete(key(1));
                    tx.tree("n").put(key(1), bytes(900, 2));
                    throw new Boom();
                },
                tx -> {
                    tx.dropTree("t");
                    throw new Boom();
                },
                tx -> {
                    tx.tree("t").put(key(2), bytes(3000, 3));
                    throw new Boom();
                }));
        for (var f : fs) expectBoom(f);
        assertEquals(before, store.stats().lastTxId(), "no commit for an all-failed batch");
        store.read(tx -> {
            assertEquals(List.of("t"), tx.treeNames());
            assertArrayEquals(bytes(2000, 1), tx.tree("t").get(key(1)).orElseThrow());
            return null;
        });
        store.verify();
        store.writeGroupedVoid(tx -> tx.tree("t").put(key(2), bytes(3000, 3)));   // still usable
        store.verify();
    }

    @Test
    void nestedFailureAfterPageReuseInsideSameBody() throws Exception {
        open(64, 1024);
        var fs = batch(List.of(
                tx -> {                                   // churn: leaves freed owned pages in the reusable list
                    for (int i = 0; i < 12; i++) tx.tree("t").put(key(i), bytes(2000, i));
                    for (int i = 0; i < 12; i += 2) tx.tree("t").delete(key(i));
                    return null;
                },
                tx -> {                                   // reuses them, frees again, reuses again, then fails
                    for (int i = 0; i < 12; i++) tx.tree("t").put(key(i), bytes(1800, 100 + i));
                    for (int i = 0; i < 12; i++) tx.tree("t").delete(key(i));
                    for (int i = 20; i < 30; i++) tx.tree("t").put(key(i), bytes(2500, i));
                    tx.dropTree("t");
                    for (int i = 0; i < 8; i++) tx.tree("u").put(key(i), bytes(3000, 200 + i));
                    throw new Boom();
                },
                tx -> {
                    for (int i = 1; i < 12; i += 2) assertArrayEquals(bytes(2000, i), tx.tree("t").get(key(i)).orElseThrow());
                    assertEquals(6, tx.tree("t").size());
                    for (int i = 0; i < 12; i++) tx.tree("t").put(key(i), bytes(2200, 300 + i));
                    return null;
                },
                tx -> {                                   // fail again after reuse of pages freed by the previous body
                    for (int i = 0; i < 12; i++) tx.tree("t").delete(key(i));
                    for (int i = 0; i < 12; i++) tx.tree("t").put(key(i), bytes(2600, 400 + i));
                    throw new Boom();
                }));
        expectOk(fs.get(0));
        expectBoom(fs.get(1));
        expectOk(fs.get(2));
        expectBoom(fs.get(3));
        store.read(tx -> {
            assertEquals(12, tx.tree("t").size());
            for (int i = 0; i < 12; i++) assertArrayEquals(bytes(2200, 300 + i), tx.tree("t").get(key(i)).orElseThrow());
            assertFalse(tx.hasTree("u"));
            return null;
        });
        store.verify();
    }
}
