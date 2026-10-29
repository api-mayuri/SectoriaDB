package org.example.sectoriadb.metastore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.zip.CRC32C;

/**
 * Fixed-size page I/O with CRC32C. Page layout: crc(4) type(1) flags(1) count(2) payload.
 *
 * <p>Verified pages are kept in a two-way set-associative cache. It is coherent because every page write goes through
 * {@link #write}, which replaces the cached copy, and because a page is rewritten only when no snapshot can reach it any
 * more (copy on write and the free-list release rule): a reader never loads a page while the writer rewrites it.
 * Cached arrays are shared and must never be modified by callers. {@link #readUncached} bypasses the cache (verification).
 */
final class Pager {
    private static final Logger log = LoggerFactory.getLogger(Pager.class);
    static final int HEADER = 8;
    static final byte T_LEAF = 1, T_BRANCH = 2, T_OVERFLOW = 3, T_FREELIST = 4;

    final FileChannel ch;
    final int pageSize;
    final boolean fsync;
    final MetaStoreListener listener;
    final FaultInjector faults = new FaultInjector();

    private static final class Entry {
        final long id;
        final byte[] page;
        volatile boolean hot = true;

        Entry(long id, byte[] page) {
            this.id = id;
            this.page = page;
        }
    }

    /** Null when the cache is disabled; the length is even, slots {@code 2k} and {@code 2k+1} form one set. */
    private final AtomicReferenceArray<Entry> cache;
    private final int setMask;

    Pager(FileChannel ch, int pageSize, boolean fsync, MetaStoreListener listener) {
        this(ch, pageSize, fsync, listener, 0);
    }

    Pager(FileChannel ch, int pageSize, boolean fsync, MetaStoreListener listener, long cacheBytes) {
        this.ch = ch;
        this.pageSize = pageSize;
        this.fsync = fsync;
        this.listener = listener;
        long pages = cacheBytes / pageSize;
        if (pages >= 2) {
            int sets = Integer.highestOneBit((int) Math.min(pages / 2, 1 << 24));
            this.cache = new AtomicReferenceArray<>(sets * 2);
            this.setMask = sets - 1;
        } else {
            this.cache = null;
            this.setMask = 0;
        }
    }

    private int set(long id) {
        return ((int) ((id * 0x9E3779B97F4A7C15L) >>> 32) & setMask) * 2;
    }

    private byte[] cached(long id) {
        int s = set(id);
        for (int i = 0; i < 2; i++) {
            Entry e = cache.get(s + i);
            if (e != null && e.id == id) {
                e.hot = true;
                return e.page;
            }
        }
        return null;
    }

    /** Puts a page into its set: replaces any copy of the same id, else fills a free way, else evicts the colder way. */
    private void remember(long id, byte[] page) {
        int s = set(id);
        Entry fresh = new Entry(id, page);
        Entry a = cache.get(s), b = cache.get(s + 1);
        boolean aSame = a != null && a.id == id, bSame = b != null && b.id == id;
        if (aSame || bSame) {
            if (aSame) cache.set(s, fresh);
            if (bSame) cache.set(s + 1, aSame ? null : fresh);
            return;
        }
        if (a == null) {
            cache.set(s, fresh);
        } else if (b == null) {
            cache.set(s + 1, fresh);
        } else {
            int victim = !a.hot ? 0 : !b.hot ? 1 : (int) (id & 1);
            a.hot = false;
            b.hot = false;
            cache.set(s + victim, fresh);
        }
    }

    /** Metrics are observers: a failing sink must never fail (or poison) a page operation that already happened. */
    private static void metricFailure(Throwable e) {
        log.warn("Metrics callback failed: {}", e.toString());
    }

    static int crc(byte[] p, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(p, off, len);
        return (int) c.getValue();
    }

    /** A verified page; the returned array may be shared with the cache and must not be modified. */
    byte[] read(long id) {
        if (cache != null) {
            byte[] hit = cached(id);
            if (hit != null) {
                try {
                    listener.onPageCacheHit();
                } catch (RuntimeException | Error e) {
                    metricFailure(e);
                }
                return hit;
            }
        }
        byte[] p = readUncached(id);
        if (cache != null) remember(id, p);
        return p;
    }

    /** Reads and verifies the page from the file, ignoring the cache. */
    byte[] readUncached(long id) {
        byte[] p = new byte[pageSize];
        long t0 = System.nanoTime();
        try {
            ByteBuffer bb = ByteBuffer.wrap(p);
            long pos = id * pageSize;
            while (bb.hasRemaining()) {
                int r = ch.read(bb, pos + bb.position());
                if (r < 0) throw new CorruptedPageException(id, "beyond end of file");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            listener.onPageRead(System.nanoTime() - t0, pageSize);
        } catch (RuntimeException | Error e) {
            metricFailure(e);
        }
        int stored = ByteBuffer.wrap(p).getInt(0);
        if (stored != crc(p, 4, pageSize - 4)) {
            try {
                listener.onChecksumFailure();
            } catch (RuntimeException | Error e) {
                metricFailure(e);
            }
            throw new CorruptedPageException(id, "checksum mismatch");
        }
        return p;
    }

    /** Pages written so far (tree, overflow and free-list pages; not the meta pages). Written by the single writer only. */
    volatile long pagesWritten;

    /** Computes the checksum into the page and writes it. */
    void write(long id, byte[] p) {
        pagesWritten++;
        ByteBuffer.wrap(p).putInt(0, crc(p, 4, pageSize - 4));
        writeRaw(id * pageSize, p);
        if (cache != null) remember(id, p.clone());   // the caller may reuse its array
    }

    void writeRaw(long pos, byte[] p) {
        long t0 = System.nanoTime();
        try {
            faults.beforePageWrite();
            ByteBuffer bb = ByteBuffer.wrap(p);
            while (bb.hasRemaining()) ch.write(bb, pos + bb.position());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            listener.onPageWrite(System.nanoTime() - t0, p.length);
        } catch (RuntimeException | Error e) {
            metricFailure(e);
        }
    }

    void force() {
        if (!fsync) return;
        long t0 = System.nanoTime();
        try {
            faults.beforeForce();
            ch.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            listener.onFsync(System.nanoTime() - t0);
        } catch (RuntimeException | Error e) {
            metricFailure(e);
        }
    }

    void ensureSize(long bytes) {
        try {
            if (ch.size() < bytes) ch.write(ByteBuffer.wrap(new byte[1]), bytes - 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
