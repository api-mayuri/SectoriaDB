package org.example.sectoriadb.metastore;

import java.util.Arrays;

/** Minimal growable stack of longs. */
final class LongList {
    private long[] a = new long[16];
    private int n;

    void push(long v) {
        if (n == a.length) a = Arrays.copyOf(a, n * 2);
        a[n++] = v;
    }

    long pop() {
        return a[--n];
    }

    boolean isEmpty() {
        return n == 0;
    }

    int size() {
        return n;
    }

    long get(int i) {
        return a[i];
    }
}
