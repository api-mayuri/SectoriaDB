package org.example.sectoriadb.metastore;

import org.example.sectoriadb.metrics.StorageMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

/** Fixed-size page I/O with CRC32C. Page layout: crc(4) type(1) flags(1) count(2) payload. */
final class Pager {
    private static final Logger log = LoggerFactory.getLogger(Pager.class);
    static final int HEADER = 8;
    static final byte T_LEAF = 1, T_BRANCH = 2, T_OVERFLOW = 3, T_FREELIST = 4;

    final FileChannel ch;
    final int pageSize;
    final boolean fsync;
    final StorageMetrics metrics;

    Pager(FileChannel ch, int pageSize, boolean fsync, StorageMetrics metrics) {
        this.ch = ch;
        this.pageSize = pageSize;
        this.fsync = fsync;
        this.metrics = metrics;
    }

    // test hook: the next N writes / forces fail with an IOException (a full or failing disk)
    private final AtomicInteger writeFaults = new AtomicInteger();
    private final AtomicInteger forceFaults = new AtomicInteger();
    private volatile String faultMessage = "injected I/O failure";

    /** Makes the next {@code writes} page writes and {@code forces} fsyncs fail with {@code message}. */
    void injectFaults(int writes, int forces, String message) {
        faultMessage = message;
        writeFaults.set(writes);
        forceFaults.set(forces);
    }

    private void maybeFail(AtomicInteger counter) throws IOException {
        int n;
        do {
            n = counter.get();
            if (n <= 0) return;
        } while (!counter.compareAndSet(n, n == Integer.MAX_VALUE ? n : n - 1));
        throw new IOException(faultMessage);
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
            metrics.diskRead(StorageMetrics.Target.METASTORE, System.nanoTime() - t0, pageSize);
        } catch (RuntimeException | Error e) {
            metricFailure(e);
        }
        int stored = ByteBuffer.wrap(p).getInt(0);
        if (stored != crc(p, 4, pageSize - 4)) {
            try {
                metrics.crcFailure(StorageMetrics.CrcKind.METASTORE_PAGE);
            } catch (RuntimeException | Error e) {
                metricFailure(e);
            }
            throw new CorruptedPageException(id, "checksum mismatch");
        }
        return p;
    }

    /** Computes the checksum into the page and writes it. */
    void write(long id, byte[] p) {
        ByteBuffer.wrap(p).putInt(0, crc(p, 4, pageSize - 4));
        writeRaw(id * pageSize, p);
    }

    void writeRaw(long pos, byte[] p) {
        long t0 = System.nanoTime();
        try {
            maybeFail(writeFaults);
            ByteBuffer bb = ByteBuffer.wrap(p);
            while (bb.hasRemaining()) ch.write(bb, pos + bb.position());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            metrics.diskWrite(StorageMetrics.Target.METASTORE, System.nanoTime() - t0, p.length);
        } catch (RuntimeException | Error e) {
            metricFailure(e);
        }
    }

    void force() {
        if (!fsync) return;
        long t0 = System.nanoTime();
        try {
            maybeFail(forceFaults);
            ch.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            metrics.fsync(StorageMetrics.Target.METASTORE, System.nanoTime() - t0);
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
