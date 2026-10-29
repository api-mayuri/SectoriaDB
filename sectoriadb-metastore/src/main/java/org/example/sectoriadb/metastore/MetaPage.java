package org.example.sectoriadb.metastore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * The two meta pages at the start of the file (slots 0 and 1, written alternately by txId parity): encoding, parsing,
 * and choosing the valid one with the highest txId on open. See docs/architecture/metastore.md.
 */
final class MetaPage {
    private MetaPage() {
    }

    static final long MAGIC = 0x53454354_4D455441L; // "SECTMETA"
    static final int FORMAT_VERSION = 1;
    static final int META_SIZE = 52;

    /**
     * True for an empty file and for a file whose creation was interrupted: it holds no commit, because a store has at
     * least two pages and its first commit rewrites a meta page only after both exist. That is the case when the first
     * meta page is valid with txId 0 but the file is shorter than two pages, or when the file is shorter than the
     * largest possible two pages and all zeros (the size was extended but the data never reached the disk).
     */
    static boolean needsInitialisation(FileChannel ch, long size) throws IOException {
        if (size == 0) return true;
        Object[] m0 = readAt(ch, 0, size);
        if (m0 != null) {
            MetaStore.Snapshot s = (MetaStore.Snapshot) m0[1];
            return s.txId() == 0 && size < 2L * (Integer) m0[0];
        }
        if (size > 2L * MetaStoreOptions.MAX_PAGE_SIZE) return false;
        ByteBuffer bb = ByteBuffer.allocate((int) size);
        while (bb.hasRemaining()) {
            if (ch.read(bb, bb.position()) < 0) return false;
        }
        for (byte x : bb.array()) {
            if (x != 0) return false;
        }
        return true;
    }

    static byte[] encode(int pageSize, MetaStore.Snapshot s) {
        byte[] p = new byte[pageSize];
        ByteBuffer b = ByteBuffer.wrap(p);
        b.putLong(0, MAGIC).putInt(8, FORMAT_VERSION).putInt(12, pageSize).putLong(16, s.txId())
                .putLong(24, s.catalogRoot()).putLong(32, s.freelistRoot()).putLong(40, s.pageCount());
        b.putInt(48, Pager.crc(p, 0, 48));
        return p;
    }

    /**
     * Parses a meta page header; null if magic/crc/pageSize do not check out. A page with our magic and a valid checksum
     * but another format version is not "invalid": it was written by another version, and opening stops with
     * {@link UnsupportedFormatException} instead of reporting a corrupt file (or, worse, treating it as empty).
     */
    static Object[] parse(byte[] h) {
        ByteBuffer b = ByteBuffer.wrap(h);
        if (b.getLong(0) != MAGIC) return null;
        if (b.getInt(48) != Pager.crc(h, 0, 48)) return null;
        if (b.getInt(8) != FORMAT_VERSION) throw new UnsupportedFormatException(b.getInt(8), FORMAT_VERSION);
        int ps = b.getInt(12);
        if (ps < MetaStoreOptions.MIN_PAGE_SIZE || ps > MetaStoreOptions.MAX_PAGE_SIZE || Integer.bitCount(ps) != 1) {
            return null;
        }
        return new Object[]{ps, new MetaStore.Snapshot(b.getLong(16), b.getLong(24), b.getLong(32), b.getLong(40))};
    }

    static Object[] readAt(FileChannel ch, long pos, long fileSize) throws IOException {
        if (pos + META_SIZE > fileSize) return null;
        byte[] h = new byte[META_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(h);
        while (bb.hasRemaining()) {
            if (ch.read(bb, pos + bb.position()) < 0) return null;
        }
        return parse(h);
    }

    /** Reads both meta slots, drops invalid ones, picks the highest txId. Returns {pageSize, MetaStore.Snapshot}. */
    static Object[] choose(FileChannel ch, long fileSize) throws IOException {
        Object[] m0 = readAt(ch, 0, fileSize);
        List<Integer> sizes = new ArrayList<>();
        if (m0 != null) {
            sizes.add((Integer) m0[0]);
        } else {
            for (int s = MetaStoreOptions.MIN_PAGE_SIZE; s <= MetaStoreOptions.MAX_PAGE_SIZE; s *= 2) sizes.add(s);
        }
        for (int ps : sizes) {
            Object[] a = m0 != null && (Integer) m0[0] == ps ? m0 : null;
            Object[] b = readAt(ch, ps, fileSize);
            if (b != null && (Integer) b[0] != ps) b = null;
            MetaStore.Snapshot best = null;
            for (Object[] m : new Object[][]{a, b}) {
                if (m == null) continue;
                MetaStore.Snapshot s = (MetaStore.Snapshot) m[1];
                if (s.pageCount() < 2 || s.pageCount() * ps > fileSize) continue; // tail of the file is missing
                if (best == null || s.txId() > best.txId()) best = s;
            }
            if (best != null) return new Object[]{ps, best};
        }
        throw new CorruptedPageException(-1, "no valid meta page");
    }
}
