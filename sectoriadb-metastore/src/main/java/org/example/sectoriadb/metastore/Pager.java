package org.example.sectoriadb.metastore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32C;

/** Fixed-size page I/O with CRC32C. Page layout: crc(4) type(1) flags(1) count(2) payload. */
final class Pager {
    private static final Logger log = LoggerFactory.getLogger(Pager.class);
    static final int HEADER = 8;
    static final byte T_LEAF = 1, T_BRANCH = 2, T_OVERFLOW = 3, T_FREELIST = 4;

    final FileChannel ch;
    final int pageSize;
    final boolean fsync;
    final MetaStoreListener listener;
    final FaultInjector faults = new FaultInjector();

    Pager(FileChannel ch, int pageSize, boolean fsync, MetaStoreListener listener) {
        this.ch = ch;
        this.pageSize = pageSize;
        this.fsync = fsync;
        this.listener = listener;
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

    byte[] read(long id) {
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
