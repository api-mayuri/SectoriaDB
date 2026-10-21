package org.example.sectoriadb.storage;

/** A small growable set of longs (open addressing); a hold records the chunk keys it relied on, one entry per chunk of an upload. */
final class LongSet {
    private long[] table = new long[16];
    private boolean[] used = new boolean[16];
    private int size;

    boolean add(long v) {
        if ((size + 1) * 2 > table.length) grow();
        int i = slot(table, used, v);
        if (used[i]) return false;
        used[i] = true;
        table[i] = v;
        size++;
        return true;
    }

    boolean contains(long v) {
        return used[slot(table, used, v)];
    }

    int size() {
        return size;
    }

    private static int slot(long[] t, boolean[] u, long v) {
        long h = v * 0x9E3779B97F4A7C15L;
        int mask = t.length - 1;
        int i = (int) (h ^ (h >>> 32)) & mask;
        while (u[i] && t[i] != v) i = (i + 1) & mask;
        return i;
    }

    private void grow() {
        long[] old = table;
        boolean[] oldUsed = used;
        table = new long[old.length * 2];
        used = new boolean[old.length * 2];
        for (int j = 0; j < old.length; j++) {
            if (oldUsed[j]) {
                int i = slot(table, used, old[j]);
                used[i] = true;
                table[i] = old[j];
            }
        }
    }
}
