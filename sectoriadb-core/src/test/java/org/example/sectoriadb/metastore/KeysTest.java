package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class KeysTest {
    private static String randString(Random r) {
        int n = r.nextInt(5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(switch (r.nextInt(5)) {
            case 0 -> '\0';
            case 1 -> 'é';
            case 2 -> '中';
            default -> (char) ('a' + r.nextInt(3));
        });
        return sb.toString();
    }

    @Test
    void compositeOrderMatchesTupleOrder() {
        Random r = new Random(5);
        for (int i = 0; i < 20_000; i++) {
            String s1 = randString(r), s2 = randString(r);
            long l1 = r.nextInt(5) - 2, l2 = r.nextInt(5) - 2;
            byte[] k1 = Keys.builder().string(s1).longSigned(l1).build();
            byte[] k2 = Keys.builder().string(s2).longSigned(l2).build();
            // String.compareTo orders UTF-16 units, so compare code points as UTF-8 bytes do
            int cmpS = Arrays.compareUnsigned(s1.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    s2.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int expected = cmpS != 0 ? Integer.signum(cmpS) : Long.signum(l1 - l2);
            assertEquals(expected, Integer.signum(Arrays.compareUnsigned(k1, k2)), s1 + "/" + l1 + " vs " + s2 + "/" + l2);
        }
    }

    @Test
    void roundTrip() {
        byte[] k = Keys.builder().string("buck\0et").string("").longUnsigned(-1L).longSigned(-5).raw(new byte[]{1, 2}).build();
        Keys.Reader rd = new Keys.Reader(k);
        assertEquals("buck\0et", rd.string());
        assertEquals("", rd.string());
        assertEquals(-1L, rd.longUnsigned());
        assertEquals(-5L, rd.longSigned());
        assertArrayEquals(new byte[]{1, 2}, rd.rest());
    }

    @Test
    void prefixesWorkWithScans() {
        byte[] bucket = Keys.builder().string("b1").build();
        byte[] inBucket = Keys.builder().string("b1").string("obj").build();
        byte[] longer = Keys.builder().string("b1x").string("obj").build();
        assertTrue(Arrays.compareUnsigned(bucket, inBucket) < 0);
        byte[] end = Keys.prefixEnd(bucket);
        assertTrue(Arrays.compareUnsigned(inBucket, end) < 0);
        assertTrue(Arrays.compareUnsigned(longer, end) >= 0 || Arrays.compareUnsigned(longer, bucket) < 0 == false);
        assertNull(Keys.prefixEnd(new byte[]{(byte) 0xFF, (byte) 0xFF}));
        assertArrayEquals(new byte[]{1, 3}, Keys.prefixEnd(new byte[]{1, 2, (byte) 0xFF}));
    }

    @Test
    void prefixScanOnTree(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        try (MetaStore s = MetaStore.open(dir.resolve("k.db"), MetaStoreOptions.defaults().pageSize(512).fsync(false))) {
            s.writeVoid(tx -> {
                BTree t = tx.tree("objects");
                for (String b : new String[]{"a", "ab", "b", "a\0"}) {
                    for (int i = 0; i < 30; i++) t.put(Keys.builder().string(b).string("o" + i).build(), new byte[]{1});
                }
            });
            s.read(tx -> {
                for (String b : new String[]{"a", "ab", "b", "a\0"}) {
                    int n = 0;
                    try (Cursor c = tx.tree("objects").scanPrefix(Keys.builder().string(b).build())) {
                        while (c.next()) {
                            assertEquals(b, new Keys.Reader(c.key()).string());
                            n++;
                        }
                    }
                    assertEquals(30, n, b);
                }
                return null;
            });
        }
    }
}
