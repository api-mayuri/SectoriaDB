package org.example.sectoriadb.metastore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32C;

/** Fixed-size page I/O with CRC32C. Page layout: crc(4) type(1) flags(1) count(2) payload. */
final class Pager {
    static final int HEADER = 8;
    static final byte T_LEAF = 1, T_BRANCH = 2, T_OVERFLOW = 3, T_FREELIST = 4;

    final FileChannel ch;
    final int pageSize;
    final boolean fsync;

    Pager(FileChannel ch, int pageSize, boolean fsync) {
        this.ch = ch;
        this.pageSize = pageSize;
        this.fsync = fsync;
    }

    static int crc(byte[] p, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(p, off, len);
        return (int) c.getValue();
    }

    byte[] read(long id) {
        byte[] p = new byte[pageSize];
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
        int stored = ByteBuffer.wrap(p).getInt(0);
        if (stored != crc(p, 4, pageSize - 4)) throw new CorruptedPageException(id, "checksum mismatch");
        return p;
    }

    /** Computes the checksum into the page and writes it. */
    void write(long id, byte[] p) {
        ByteBuffer.wrap(p).putInt(0, crc(p, 4, pageSize - 4));
        writeRaw(id * pageSize, p);
    }

    void writeRaw(long pos, byte[] p) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(p);
            while (bb.hasRemaining()) ch.write(bb, pos + bb.position());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void force() {
        if (!fsync) return;
        try {
            ch.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
