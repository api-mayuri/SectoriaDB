package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Randomized comparison of the store with java.util.TreeMap. */
class MetaStoreModelTest {
    @TempDir
    Path dir;

    static final String[] TREES = {"alpha", "beta", "gamma"};

    static TreeMap<byte[], byte[]> newMap() {
        return new TreeMap<>(Arrays::compareUnsigned);
    }

    static Map<String, TreeMap<byte[], byte[]>> copy(Map<String, TreeMap<byte[], byte[]>> m) {
        Map<String, TreeMap<byte[], byte[]>> c = new TreeMap<>();
        m.forEach((k, v) -> {
            TreeMap<byte[], byte[]> t = newMap();
            t.putAll(v);
            c.put(k, t);
        });
        return c;
    }

    static byte[] randKey(Random r) {
        int shape = r.nextInt(10);
        if (shape == 0) return new byte[]{(byte) r.nextInt(4)};
        int len = 1 + r.nextInt(shape < 3 ? 40 : 10);
        byte[] k = new byte[len];
        for (int i = 0; i < len; i++) {
            int c = r.nextInt(8);
            k[i] = switch (c) {
                case 0 -> 0;
                case 1 -> (byte) 0xFF;
                case 2 -> (byte) 0x80;
                default -> (byte) ('a' + r.nextInt(6));
            };
        }
        return k;
    }

    static byte[] randVal(Random r) {
        int x = r.nextInt(100);
        int len = x < 70 ? r.nextInt(30) : x < 95 ? r.nextInt(120) : 100 + r.nextInt(2500);
        byte[] v = new byte[len];
        r.nextBytes(v);
        return v;
    }

    static void assertSameTree(TreeMap<byte[], byte[]> exp, BTree t) {
        assertEquals(exp.size(), t.size());
        Cursor c = t.scan();
        for (Map.Entry<byte[], byte[]> e : exp.entrySet()) {
            assertTrue(c.next());
            assertArrayEquals(e.getKey(), c.key());
            assertArrayEquals(e.getValue(), c.value());
        }
        assertFalse(c.next());
    }

    static void assertSameStore(Map<String, TreeMap<byte[], byte[]>> exp, ReadTxn tx) {
        assertEquals(new ArrayList<>(exp.keySet()), tx.treeNames());
        for (var e : exp.entrySet()) assertSameTree(e.getValue(), tx.tree(e.getKey()));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void randomOperationsMatchTreeMap(long seed) {
        Random r = new Random(seed);
        Path file = dir.resolve("m" + seed + ".db");
        MetaStoreOptions opts = MetaStoreOptions.defaults().pageSize(512).fsync(false);
        MetaStore store = MetaStore.open(file, opts);
        Map<String, TreeMap<byte[], byte[]>> model = new TreeMap<>();
        ReadTxn heldReader = null;
        Map<String, TreeMap<byte[], byte[]>> heldModel = null;
        int heldAge = 0;
        long totalOps = 0;
        int txCount = 0;

        while (totalOps < 25_000) {
            Map<String, TreeMap<byte[], byte[]>> work = copy(model);
            WriteTxn tx = store.beginWrite();
            int nOps = 1 + r.nextInt(r.nextInt(10) == 0 ? 200 : 25);
            for (int i = 0; i < nOps; i++, totalOps++) {
                String name = TREES[r.nextInt(TREES.length)];
                int op = r.nextInt(100);
                if (op < 2 && work.containsKey(name)) {
                    assertTrue(tx.dropTree(name));
                    work.remove(name);
                    continue;
                }
                TreeMap<byte[], byte[]> m = work.computeIfAbsent(name, k -> newMap());
                BTree t = tx.tree(name);
                if (op < 52) {
                    byte[] k = randKey(r), v = randVal(r);
                    t.put(k, v);
                    m.put(k, v);
                } else if (op < 78) {
                    byte[] k = m.isEmpty() || r.nextInt(3) == 0 ? randKey(r) : pick(m, r);
                    assertEquals(m.remove(k) != null, t.delete(k));
                } else if (op < 90) {
                    byte[] k = r.nextBoolean() || m.isEmpty() ? randKey(r) : pick(m, r);
                    byte[] exp = m.get(k);
                    var got = t.get(k);
                    assertEquals(exp != null, got.isPresent());
                    if (exp != null) assertArrayEquals(exp, got.get());
                    assertEquals(exp != null, t.containsKey(k));
                } else if (op < 96) {
                    byte[] a = randKey(r), b = randKey(r);
                    if (Arrays.compareUnsigned(a, b) > 0) {
                        byte[] x = a;
                        a = b;
                        b = x;
                    }
                    checkRange(m, t.scan(a, b), m.subMap(a, true, b, false));
                    checkRange(m, t.scan(a, null), m.tailMap(a, true));
                    checkRange(m, t.scan(null, b), m.headMap(b, false));
                } else {
                    byte[] p = m.isEmpty() || r.nextBoolean() ? new byte[]{(byte) ('a' + r.nextInt(6))} : Arrays.copyOf(pick(m, r), 1);
                    byte[] end = Keys.prefixEnd(p);
                    checkRange(m, t.scanPrefix(p), end == null ? m.tailMap(p, true) : m.subMap(p, true, end, false));
                }
            }
            assertSameStore(work, tx);   // a write txn sees its own writes
            if (r.nextInt(4) != 0) {
                tx.commit();
                model = work;
            } else {
                tx.close();
            }
            txCount++;

            if (heldReader != null && ++heldAge > 15) {
                assertSameStore(heldModel, heldReader);
                heldReader.close();
                heldReader = null;
            }
            if (heldReader == null && r.nextInt(8) == 0) {
                heldReader = store.beginRead();
                heldModel = copy(model);
                heldAge = 0;
            }
            if (txCount % 40 == 0) {
                if (heldReader != null) {
                    assertSameStore(heldModel, heldReader);
                    heldReader.close();
                    heldReader = null;
                }
                store.close();
                store = MetaStore.open(file, opts);
                try (ReadTxn rt = store.beginRead()) {
                    assertSameStore(model, rt);
                }
                store.verify();
            }
        }
        if (heldReader != null) {
            assertSameStore(heldModel, heldReader);
            heldReader.close();
        }
        try (ReadTxn rt = store.beginRead()) {
            assertSameStore(model, rt);
        }
        store.verify();
        store.close();
    }

    private static byte[] pick(TreeMap<byte[], byte[]> m, Random r) {
        int skip = r.nextInt(m.size());
        for (byte[] k : m.keySet()) if (skip-- == 0) return k;
        throw new AssertionError();
    }

    private static void checkRange(TreeMap<byte[], byte[]> all, Cursor c, Map<byte[], byte[]> exp) {
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> vals = new ArrayList<>();
        try (c) {
            while (c.next()) {
                keys.add(c.key());
                vals.add(c.value());
            }
        }
        assertEquals(exp.size(), keys.size());
        int i = 0;
        for (var e : exp.entrySet()) {
            assertArrayEquals(e.getKey(), keys.get(i));
            assertArrayEquals(e.getValue(), vals.get(i));
            i++;
        }
    }

    @Test
    void deepTreesSplitAndMergeBack() {
        MetaStore s = MetaStore.open(dir.resolve("deep.db"), MetaStoreOptions.defaults().pageSize(512).fsync(false));
        int n = 6000;
        s.writeVoid(tx -> {
            BTree t = tx.tree("t");
            for (int i = 0; i < n; i++) t.put(Keys.builder().longUnsigned(i).build(), new byte[]{(byte) i});
        });
        s.verify();
        // delete in a scattered order to exercise merging
        s.writeVoid(tx -> {
            BTree t = tx.tree("t");
            for (int i = 0; i < n; i += 2) assertTrue(t.delete(Keys.builder().longUnsigned(i).build()));
            for (int i = 1; i < n; i += 2) assertTrue(t.delete(Keys.builder().longUnsigned(i).build()));
            assertEquals(0, t.size());
        });
        var report = s.verify();
        assertEquals(1, report.trees());
        assertEquals(0, report.entries());
        assertEquals(1, report.treePages()); // only the catalog leaf is left
        s.close();
    }
}
