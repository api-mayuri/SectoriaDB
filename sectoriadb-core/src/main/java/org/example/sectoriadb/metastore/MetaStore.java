package org.example.sectoriadb.metastore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Embedded transactional key-value store with named copy-on-write B+trees in a single file.
 * See docs/architecture/04-btree-metastore.md for the file format and the commit protocol.
 *
 * <p>One write transaction at a time, any number of concurrent read transactions with snapshot isolation.
 */
public final class MetaStore implements AutoCloseable {
    static final long MAGIC = 0x53454354_4D455441L; // "SECTMETA"
    static final int FORMAT_VERSION = 1;
    static final int META_SIZE = 52;

    /** Immutable committed state. */
    record Snapshot(long txId, long catalogRoot, long freelistRoot, long pageCount) {
    }

    /** Point-in-time counters. */
    public record Stats(int pageSize, long pageCount, long freePages, long lastTxId, long fileSize, int liveReaders) {
    }

    /** Result of {@link #verify()}. */
    public record VerifyReport(int trees, long entries, long treePages, long overflowPages,
                               long freelistPages, long freePages) {
    }

    /** Test hook invoked at named points of a commit; throwing simulates a crash at that point. */
    interface CommitHook {
        void at(String stage);
    }

    final Pager pager;
    private final FileChannel channel;
    private final FileLock fileLock;
    private final Semaphore writer = new Semaphore(1);
    private final Object stateLock = new Object();
    private final TreeMap<Long, Integer> readers = new TreeMap<>();
    private volatile Snapshot committed;
    private volatile boolean closed;
    private volatile Throwable poisoned;
    private volatile long freeCountApprox;

    // free-page state; only touched while holding the writer permit
    final LongList available = new LongList();                  // reusable right now
    final TreeMap<Long, long[]> pending = new TreeMap<>();      // freeing txId -> pages, waiting for readers
    long[] chainPages = new long[0];                            // pages of the persisted freelist chain

    volatile CommitHook commitHook;

    private MetaStore(FileChannel channel, FileLock lock, Pager pager, Snapshot snap) {
        this.channel = channel;
        this.fileLock = lock;
        this.pager = pager;
        this.committed = snap;
    }

    public static MetaStore open(Path file, MetaStoreOptions opts) {
        FileChannel ch = null;
        FileLock lock = null;
        try {
            ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) throw new IOException("metastore file is already open: " + file);
            Snapshot snap;
            Pager pager;
            if (ch.size() == 0) {
                pager = new Pager(ch, opts.pageSize(), opts.fsync());
                snap = new Snapshot(0, 0, 0, 2);
                pager.writeRaw(0, encodeMeta(pager.pageSize, snap));
                pager.writeRaw(pager.pageSize, new byte[pager.pageSize]);
                pager.force();
            } else {
                long size = ch.size();
                Object[] r = chooseMeta(ch, size);
                pager = new Pager(ch, (Integer) r[0], opts.fsync());
                snap = (Snapshot) r[1];
            }
            MetaStore s = new MetaStore(ch, lock, pager, snap);
            s.loadFreelist();
            return s;
        } catch (IOException e) {
            closeQuietly(ch);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            closeQuietly(ch);
            throw e;
        }
    }

    private static void closeQuietly(FileChannel ch) {
        try {
            if (ch != null) ch.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    // ---------------------------------------------------------------- meta pages

    static byte[] encodeMeta(int pageSize, Snapshot s) {
        byte[] p = new byte[pageSize];
        ByteBuffer b = ByteBuffer.wrap(p);
        b.putLong(0, MAGIC).putInt(8, FORMAT_VERSION).putInt(12, pageSize).putLong(16, s.txId())
                .putLong(24, s.catalogRoot()).putLong(32, s.freelistRoot()).putLong(40, s.pageCount());
        b.putInt(48, Pager.crc(p, 0, 48));
        return p;
    }

    /** Parses a meta page header; null if magic/version/crc/pageSize do not check out. */
    private static Object[] parseMeta(byte[] h) {
        ByteBuffer b = ByteBuffer.wrap(h);
        if (b.getLong(0) != MAGIC || b.getInt(8) != FORMAT_VERSION) return null;
        if (b.getInt(48) != Pager.crc(h, 0, 48)) return null;
        int ps = b.getInt(12);
        if (ps < MetaStoreOptions.MIN_PAGE_SIZE || ps > MetaStoreOptions.MAX_PAGE_SIZE || Integer.bitCount(ps) != 1) {
            return null;
        }
        return new Object[]{ps, new Snapshot(b.getLong(16), b.getLong(24), b.getLong(32), b.getLong(40))};
    }

    private static Object[] readMetaAt(FileChannel ch, long pos, long fileSize) throws IOException {
        if (pos + META_SIZE > fileSize) return null;
        byte[] h = new byte[META_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(h);
        while (bb.hasRemaining()) {
            if (ch.read(bb, pos + bb.position()) < 0) return null;
        }
        return parseMeta(h);
    }

    /** Reads both meta slots, drops invalid ones, picks the highest txId. Returns {pageSize, Snapshot}. */
    private static Object[] chooseMeta(FileChannel ch, long fileSize) throws IOException {
        Object[] m0 = readMetaAt(ch, 0, fileSize);
        List<Integer> sizes = new ArrayList<>();
        if (m0 != null) {
            sizes.add((Integer) m0[0]);
        } else {
            for (int s = MetaStoreOptions.MIN_PAGE_SIZE; s <= MetaStoreOptions.MAX_PAGE_SIZE; s *= 2) sizes.add(s);
        }
        for (int ps : sizes) {
            Object[] a = m0 != null && (Integer) m0[0] == ps ? m0 : null;
            Object[] b = readMetaAt(ch, ps, fileSize);
            if (b != null && (Integer) b[0] != ps) b = null;
            Snapshot best = null;
            for (Object[] m : new Object[][]{a, b}) {
                if (m == null) continue;
                Snapshot s = (Snapshot) m[1];
                if (s.pageCount() < 2 || s.pageCount() * ps > fileSize) continue; // tail of the file is missing
                if (best == null || s.txId() > best.txId()) best = s;
            }
            if (best != null) return new Object[]{ps, best};
        }
        throw new CorruptedPageException(-1, "no valid meta page");
    }

    private void writeMeta(Snapshot s) {
        pager.writeRaw((s.txId() % 2) * pager.pageSize, encodeMeta(pager.pageSize, s));
    }

    // ---------------------------------------------------------------- free list persistence

    private void loadFreelist() {
        Snapshot s = committed;
        List<Long> chain = new ArrayList<>();
        long id = s.freelistRoot();
        while (id != 0) {
            if (chain.size() > s.pageCount()) throw new CorruptedPageException(id, "freelist chain loop");
            if (id < 2 || id >= s.pageCount()) throw new CorruptedPageException(id, "freelist page out of range");
            byte[] p = pager.read(id);
            if (p[4] != Pager.T_FREELIST) throw new CorruptedPageException(id, "not a freelist page");
            ByteBuffer b = ByteBuffer.wrap(p);
            int n = b.getShort(6) & 0xFFFF;
            if (16 + n * 16 > p.length) throw new CorruptedPageException(id, "bad freelist count");
            chain.add(id);
            for (int i = 0; i < n; i++) {
                long page = b.getLong(16 + i * 16);
                if (page < 2 || page >= s.pageCount()) throw new CorruptedPageException(id, "free page out of range");
                available.push(page);   // nobody is reading after a restart: every pending page is reusable
            }
            id = b.getLong(8);
        }
        chainPages = chain.stream().mapToLong(Long::longValue).toArray();
        freeCountApprox = available.size();
    }

    // ---------------------------------------------------------------- transactions

    public ReadTxn beginRead() {
        checkUsable();
        synchronized (stateLock) {
            Snapshot s = committed;
            readers.merge(s.txId(), 1, Integer::sum);
            return new ReadTxn(this, s, true);
        }
    }

    /** Blocks until the single write transaction slot is free. */
    public WriteTxn beginWrite() {
        writer.acquireUninterruptibly();
        return startWrite();
    }

    /** Like {@link #beginWrite()} but gives up after the timeout (empty result). */
    public Optional<WriteTxn> tryBeginWrite(long timeout, TimeUnit unit) throws InterruptedException {
        if (!writer.tryAcquire(timeout, unit)) return Optional.empty();
        return Optional.of(startWrite());
    }

    private WriteTxn startWrite() {
        try {
            checkUsable();
            Snapshot s;
            long minReader;
            synchronized (stateLock) {
                s = committed;
                minReader = readers.isEmpty() ? s.txId() : Math.min(readers.firstKey(), s.txId());
            }
            // pages freed by txn U are unreferenced for every snapshot >= U
            while (!pending.isEmpty() && pending.firstKey() <= minReader) {
                for (long p : pending.pollFirstEntry().getValue()) available.push(p);
            }
            return new WriteTxn(this, s);
        } catch (RuntimeException | Error e) {
            writer.release();
            throw e;
        }
    }

    void releaseWriter() {
        writer.release();
    }

    void unregisterReader(long txId) {
        synchronized (stateLock) {
            readers.computeIfPresent(txId, (k, v) -> v == 1 ? null : v - 1);
        }
    }

    /** Runs the body in a write transaction; commits on normal return, aborts on exception. */
    public <T> T write(Function<WriteTxn, T> body) {
        try (WriteTxn tx = beginWrite()) {
            T r = body.apply(tx);
            tx.commit();
            return r;
        }
    }

    public void writeVoid(Consumer<WriteTxn> body) {
        write(tx -> {
            body.accept(tx);
            return null;
        });
    }

    public <T> T read(Function<ReadTxn, T> body) {
        try (ReadTxn tx = beginRead()) {
            return body.apply(tx);
        }
    }

    void abort(WriteTxn w) {
        // give back pages taken from the free list; allocations from the high-water mark are simply forgotten
        while (!w.fromAvail.isEmpty()) available.push(w.fromAvail.pop());
    }

    // ---------------------------------------------------------------- commit

    private void hook(String stage) {
        CommitHook h = commitHook;
        if (h != null) h.at(stage);
    }

    void commit(WriteTxn w) {
        try {
            doCommit(w);
        } catch (Throwable t) {
            poisoned = t;   // in-memory free-page state is unreliable now; the file itself is still consistent
            throw t;
        }
    }

    private void doCommit(WriteTxn w) {
        final long t = w.newTxId;
        final int ps = pager.pageSize;
        hook("before-commit");

        // 1. fold tree roots into the catalog
        BTree cat = new BTree(w, null, w.snap.catalogRoot(), 0);
        for (String name : w.dropped) {
            if (!w.handles.containsKey(name)) cat.delete(w.nameKey(name));
        }
        for (Map.Entry<String, BTree> e : w.handles.entrySet()) {
            BTree tr = e.getValue();
            if (tr.dirty) cat.put(w.nameKey(e.getKey()), BTree.encodeCatalogValue(tr.root, tr.count));
        }

        // 2. free-page bookkeeping: pages freed by this txn wait for readers; the old freelist chain is
        //    still referenced by the previous meta, so it is freed by this txn as well
        List<Long> freed = new ArrayList<>(w.pendingFreed);
        for (long p : chainPages) freed.add(p);
        if (!freed.isEmpty()) pending.put(t, freed.stream().mapToLong(Long::longValue).toArray());
        for (long p : w.reusable) available.push(p);
        w.reusable.clear();

        // 3. new freelist chain; its pages come from the free list itself where possible
        long total = available.size();
        for (long[] g : pending.values()) total += g.length;
        int per = (ps - 16) / 16;
        int n = (int) ((total + per - 1) / per);
        long[] chain = new long[n];
        for (int i = 0; i < n; i++) chain[i] = available.isEmpty() ? w.hwm++ : available.pop();
        List<long[]> entries = new ArrayList<>();
        for (int i = 0; i < available.size(); i++) entries.add(new long[]{available.get(i), 0});
        for (Map.Entry<Long, long[]> e : pending.entrySet()) {
            for (long p : e.getValue()) entries.add(new long[]{p, e.getKey()});
        }
        int pos = 0;
        for (int i = 0; i < n; i++) {
            byte[] p = new byte[ps];
            ByteBuffer b = ByteBuffer.wrap(p);
            int cnt = Math.min(per, entries.size() - pos);
            p[4] = Pager.T_FREELIST;
            b.putShort(6, (short) cnt);
            b.putLong(8, i + 1 < n ? chain[i + 1] : 0);
            for (int k = 0; k < cnt; k++, pos++) {
                b.putLong(16 + k * 16, entries.get(pos)[0]);
                b.putLong(24 + k * 16, entries.get(pos)[1]);
            }
            pager.write(chain[i], p);
        }

        // 4. data pages, then barrier, then meta, then barrier
        for (Node node : w.dirty.values()) pager.write(node.id, node.encode(ps));
        pager.ensureSize(w.hwm * ps);
        pager.force();
        hook("after-data");
        Snapshot ns = new Snapshot(t, cat.root, n == 0 ? 0 : chain[0], w.hwm);
        writeMeta(ns);
        pager.force();
        hook("after-meta");

        // 5. publish
        chainPages = chain;
        long free = available.size();
        for (long[] g : pending.values()) free += g.length;
        freeCountApprox = free;
        synchronized (stateLock) {
            committed = ns;
        }
    }

    // ---------------------------------------------------------------- misc

    public Stats stats() {
        Snapshot s = committed;
        long size;
        try {
            size = channel.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int live;
        synchronized (stateLock) {
            live = readers.values().stream().mapToInt(Integer::intValue).sum();
        }
        return new Stats(pager.pageSize, s.pageCount(), freeCountApprox, s.txId(), size, live);
    }

    /**
     * Walks every tree, checks checksums, ordering, depth, entry counts, overflow chains and that every page of
     * the file is accounted for exactly once (tree, overflow, freelist chain or free). Blocks writers while running.
     *
     * @throws CorruptedPageException on the first problem found
     */
    public VerifyReport verify() {
        writer.acquireUninterruptibly();
        try {
            checkUsable();
            return new Verifier(this, committed).run();
        } finally {
            writer.release();
        }
    }

    Snapshot committedSnapshot() {
        return committed;
    }

    private void checkUsable() {
        if (closed) throw new IllegalStateException("store is closed");
        if (poisoned != null) {
            throw new IllegalStateException("store failed during a commit and must be reopened", poisoned);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        writer.acquireUninterruptibly();   // wait for a running write transaction
        try {
            if (closed) return;
            closed = true;
            if (fileLock != null) fileLock.release();
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.release();
        }
    }
}
