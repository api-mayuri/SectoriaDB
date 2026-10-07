package org.example.sectoriadb.metastore;

import org.example.sectoriadb.metrics.StorageMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
    private static final Logger log = LoggerFactory.getLogger(MetaStore.class);

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
    private final Semaphore writer = new Semaphore(1, true);   // fair: the committer re-acquiring in a loop must not starve direct writers
    private final StorageMetrics metrics;
    private final GroupCommitter committer;
    private final Object stateLock = new Object();
    private final TreeMap<Long, Integer> readers = new TreeMap<>();
    private volatile Snapshot committed;
    private volatile boolean closed;
    /**
     * Set when a commit failed: the in-memory free-page bookkeeping is unreliable and the write side is closed until
     * {@link #recoverIfPoisoned} has rebuilt it from disk. Reads keep serving the last committed snapshot.
     */
    private volatile Throwable poisoned;
    private final long recoveryBackoffNanos;
    private volatile long nextRecoveryAt;      // System.nanoTime() from which a recovery may be attempted again
    private volatile int dirtyMetaSlot = -1;   // slot a commit started writing its meta page to and has not finished
    private volatile long freeCountApprox;

    // free-page state; only touched while holding the writer permit
    final LongList available = new LongList();                  // reusable right now
    final TreeMap<Long, long[]> pending = new TreeMap<>();      // freeing txId -> pages, waiting for readers
    private final TreeMap<Long, LongList> restartPending = new TreeMap<>();
    long[] chainPages = new long[0];                            // pages of the persisted freelist chain
    /**
     * Old freelist chains, keyed by the txn that replaced them. Only meta pages reference a chain, never a
     * reader snapshot, so these wait for the meta fallback window only and not for the oldest reader:
     * otherwise a long-lived reader would make every commit pin a new chain sized by the whole pending set,
     * and the file would grow by a fixed percentage per commit.
     */
    final TreeMap<Long, long[]> chainPending = new TreeMap<>();

    volatile CommitHook commitHook;

    private MetaStore(FileChannel channel, FileLock lock, Pager pager, Snapshot snap, MetaStoreOptions opts) {
        this.metrics = opts.metrics();
        this.recoveryBackoffNanos = TimeUnit.MILLISECONDS.toNanos(opts.recoveryBackoffMillis());
        this.committer = new GroupCommitter(this, opts);
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
                pager = new Pager(ch, opts.pageSize(), opts.fsync(), opts.metrics());
                snap = new Snapshot(0, 0, 0, 2);
                pager.writeRaw(0, encodeMeta(pager.pageSize, snap));
                pager.writeRaw(pager.pageSize, new byte[pager.pageSize]);
                pager.force();
            } else {
                long size = ch.size();
                Object[] r = chooseMeta(ch, size);
                pager = new Pager(ch, (Integer) r[0], opts.fsync(), opts.metrics());
                snap = (Snapshot) r[1];
            }
            MetaStore s = new MetaStore(ch, lock, pager, snap, opts);
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
        loadFreelist(true);
    }

    /**
     * Rebuilds the free-page state from the persisted freelist chain of the committed snapshot. After a restart
     * ({@code restart}) nobody reads old snapshots, so every page that no meta page can reference is reusable at once;
     * in a running store readers may still hold older snapshots, so each page stays in the group of the transaction
     * that freed it and the usual rule (free once that transaction is older than the oldest reader) releases it.
     */
    private void loadFreelist(boolean restart) {
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
                long group = b.getLong(24 + i * 16);
                if (page < 2 || page >= s.pageCount()) throw new CorruptedPageException(id, "free page out of range");
                if (group != 0 && (!restart || group >= s.txId())) {
                    // freed by the newest txn: the previous meta page (the fallback snapshot) still references it
                    // (and, in a running store, readers of older snapshots may)
                    restartPending.computeIfAbsent(group, k -> new LongList()).push(page);
                } else {
                    available.push(page);   // no readers after a restart and no meta references it any more
                }
            }
            id = b.getLong(8);
        }
        chainPages = chain.stream().mapToLong(Long::longValue).toArray();
        long kept = 0;
        for (Map.Entry<Long, LongList> e : restartPending.entrySet()) {
            LongList l = e.getValue();
            long[] arr = new long[l.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = l.get(i);
            pending.put(e.getKey(), arr);
            kept += arr.length;
        }
        restartPending.clear();
        freeCountApprox = available.size() + kept;
    }

    // ---------------------------------------------------------------- transactions

    public ReadTxn beginRead() {
        checkOpen();   // a failed commit does not stop reads: the committed snapshot and its pages are intact
        synchronized (stateLock) {
            Snapshot s = committed;
            readers.merge(s.txId(), 1, Integer::sum);
            return new ReadTxn(this, s, true);
        }
    }

    /** Blocks until the single write transaction slot is free. */
    public WriteTxn beginWrite() {
        long t0 = System.nanoTime();
        writer.acquireUninterruptibly();
        metrics.metaWriterLockWait(System.nanoTime() - t0);
        return startWrite();
    }

    /** Like {@link #beginWrite()} but gives up after the timeout (empty result). */
    public Optional<WriteTxn> tryBeginWrite(long timeout, TimeUnit unit) throws InterruptedException {
        long t0 = System.nanoTime();
        if (!writer.tryAcquire(timeout, unit)) return Optional.empty();
        metrics.metaWriterLockWait(System.nanoTime() - t0);
        return Optional.of(startWrite());
    }

    private WriteTxn startWrite() {
        try {
            checkOpen();
            recoverIfPoisoned();
            Snapshot s;
            long minReader;
            synchronized (stateLock) {
                s = committed;
                minReader = readers.isEmpty() ? s.txId() : Math.min(readers.firstKey(), s.txId());
            }
            // Pages freed by txn U are unreferenced by every snapshot >= U. They are reused only once U is
            // strictly older than the oldest snapshot still needed: the committed one and also the one in the
            // other meta slot, which open() falls back to if the newest meta page turns out to be damaged.
            while (!pending.isEmpty() && pending.firstKey() < minReader) {
                for (long p : pending.pollFirstEntry().getValue()) available.push(p);
            }
            // a chain replaced by txn U is referenced by meta U-1 only, which stops being a fallback once
            // U+1 has committed (then the two meta slots hold U and U+1)
            while (!chainPending.isEmpty() && chainPending.firstKey() < s.txId()) {
                for (long p : chainPending.pollFirstEntry().getValue()) available.push(p);
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

    /**
     * Group commit: queues the body to run inside a write transaction shared with other queued bodies and returns
     * a future that completes once the commit containing its changes is durable (meta page fsynced).
     *
     * <p>The single committer thread ({@code metastore-committer}) applies queued bodies in FIFO order inside ONE
     * transaction, each in a savepoint, and commits once. Every body sees the effects of the bodies before it, so the
     * result is equivalent to running them serially in submission order. A body that throws is rolled back alone and
     * its future fails with that exception; the other bodies of the batch commit. If the commit fails all futures of
     * the batch fail and the store is poisoned; later submits return failed futures carrying the poisoning cause.
     *
     * <p>The body must neither commit nor close the transaction, must not submit further grouped writes and should
     * be short and free of blocking I/O: it runs on the committer thread while everybody else waits. Bodies are
     * not retried, so they may have side effects outside the store, but those are not undone on rollback.
     * Dependent stages of the returned future also run on the committer thread. Blocks while the queue is full.
     * {@link #beginWrite()} keeps working and takes turns with the committer (they share the single writer slot).
     */
    public <T> CompletableFuture<T> submit(Function<WriteTxn, T> body) {
        return committer.submit(body);
    }

    /**
     * {@link #submit} and wait for the result (uninterruptibly: once queued the body runs and may commit, so the
     * caller always learns the real outcome). A failure of the body is rethrown as is.
     */
    public <T> T writeGrouped(Function<WriteTxn, T> body) {
        CompletableFuture<T> f = submit(body);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return f.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof RuntimeException r) throw r;
                    if (c instanceof Error err) throw err;
                    throw new IllegalStateException(c);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void writeGroupedVoid(Consumer<WriteTxn> body) {
        writeGrouped(tx -> {
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
        long t0 = System.nanoTime();
        try {
            doCommit(w);
            metrics.metaCommit(System.nanoTime() - t0);
        } catch (Throwable t) {
            // in-memory free-page state is unreliable now; the file itself is still consistent (copy on write): the
            // write side stays closed until recoverIfPoisoned() has rebuilt it from disk, reads are not affected
            nextRecoveryAt = System.nanoTime() + recoveryBackoffNanos;
            poisoned = t;
            log.error("Metadata store commit failed, the store is read-only until it recovers: {}", t.toString());
            if (t instanceof UncheckedIOException io) {
                // a disk fault: the same exception as the writes refused afterwards, so that callers (S3: 503 / 507) treat all alike
                throw new MetaStoreUnavailableException("The metadata commit failed (" + io.getCause() + "); the store is read-only"
                        + " until its disk works again", io);
            }
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
        if (!w.pendingFreed.isEmpty()) pending.put(t, w.pendingFreed.stream().mapToLong(Long::longValue).toArray());
        if (chainPages.length > 0) chainPending.put(t, chainPages);
        for (long p : w.reusable) available.push(p);
        w.reusable.clear();

        // 3. new freelist chain; its pages come from the free list itself where possible
        long total = available.size();
        for (long[] g : pending.values()) total += g.length;
        for (long[] g : chainPending.values()) total += g.length;
        int per = (ps - 16) / 16;
        int n = (int) ((total + per - 1) / per);
        long[] chain = new long[n];
        for (int i = 0; i < n; i++) chain[i] = available.isEmpty() ? w.hwm++ : available.pop();
        List<long[]> entries = new ArrayList<>();
        for (int i = 0; i < available.size(); i++) entries.add(new long[]{available.get(i), 0});
        for (Map.Entry<Long, long[]> e : pending.entrySet()) {
            for (long p : e.getValue()) entries.add(new long[]{p, e.getKey()});
        }
        // after a restart these come back as ordinary pending groups, which is safe (no readers then)
        for (Map.Entry<Long, long[]> e : chainPending.entrySet()) {
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
        dirtyMetaSlot = (int) (t % 2);   // from here the slot may hold a valid page of a commit that then fails
        writeMeta(ns);
        pager.force();
        hook("after-meta");

        // 5. publish
        chainPages = chain;
        long free = available.size();
        for (long[] g : pending.values()) free += g.length;
        for (long[] g : chainPending.values()) free += g.length;
        freeCountApprox = free;
        synchronized (stateLock) {
            committed = ns;
        }
        dirtyMetaSlot = -1;
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
            checkOpen();
            recoverIfPoisoned();   // the check compares the pages of the file with the free-page state
            return new Verifier(this, committed).run();
        } finally {
            writer.release();
        }
    }

    Snapshot committedSnapshot() {
        return committed;
    }

    /**
     * Why a grouped write must be refused right now (closed, or the last commit failed and the next recovery attempt is
     * not due yet), or null when the write may be queued (the committer thread then attempts the recovery itself).
     */
    Throwable writeRejection() {
        if (closed) return new IllegalStateException("store is closed");
        Throwable p = poisoned;
        if (p != null && System.nanoTime() - nextRecoveryAt < 0) return unavailable(p);
        return null;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("store is closed");
    }

    /**
     * Test hook: the next {@code writes} page writes and {@code forces} fsyncs of the store file fail with an
     * {@code IOException(message)}, like a full or failing disk. Pass {@link Integer#MAX_VALUE} for "until cleared"
     * and 0, 0 to clear.
     */
    public void injectFaultsForTesting(int writes, int forces, String message) {
        pager.injectFaults(writes, forces, message);
    }

    /** True while the last commit failed and the write side has not recovered (reads still work). */
    public boolean isReadOnly() {
        return poisoned != null;
    }

    private MetaStoreUnavailableException unavailable(Throwable cause) {
        long ms = Math.max(0, TimeUnit.NANOSECONDS.toMillis(nextRecoveryAt - System.nanoTime()));
        return new MetaStoreUnavailableException("The metadata store is read-only because its last commit failed ("
                + cause + "); writes resume once the disk works again (next recovery attempt in " + ms + " ms)", cause);
    }

    // ---------------------------------------------------------------- recovery of the write side

    /**
     * Called with the writer permit held. If a commit failed earlier, rebuilds what the failure made unreliable from
     * the disk, then lets the write go on. While the disk still fails, or the back-off after the last failure has not
     * passed, throws {@link MetaStoreUnavailableException} without touching the disk (writes fail fast).
     *
     * <p>Why this is enough: pages are copy-on-write, a commit changes the visible state only by the meta page, so after
     * a failure the committed snapshot and every page it references are exactly as before. What is lost is the
     * in-memory free-page state (the failed commit had already taken pages from it, queued frees and a new freelist
     * chain): it is rebuilt from the persisted freelist of the committed snapshot. The pages the failed transaction
     * took from the free list are listed there still (the chain on disk is the previous one), the pages it allocated
     * above the high-water mark are simply forgotten. A meta page of the failed commit that did reach the disk is
     * erased (a later crash must not resurrect a transaction whose callers were told it failed, and its pages are
     * reused by the next transaction); the meta page of the committed snapshot is rewritten if the disk lost it.
     */
    private void recoverIfPoisoned() {
        Throwable cause = poisoned;
        if (cause == null) return;
        if (System.nanoTime() - nextRecoveryAt < 0) throw unavailable(cause);
        try {
            doRecover();
        } catch (Throwable t) {
            nextRecoveryAt = System.nanoTime() + recoveryBackoffNanos;
            poisoned = t;
            metrics.metaRecovery(false);
            log.warn("Metadata store recovery failed, writes stay refused: {}", t.toString());
            throw unavailable(t);
        }
        poisoned = null;
        metrics.metaRecovery(true);
        log.warn("Metadata store recovered after a failed commit (was: {}); writes are accepted again", cause.toString());
    }

    private void doRecover() {
        Snapshot s = committed;
        int ps = pager.pageSize;
        boolean wrote = false;
        int dirty = dirtyMetaSlot;
        for (int slot = 0; slot < 2; slot++) {
            Snapshot onDisk = readSlot(slot);
            // The slot of a failed commit is erased on every attempt until one attempt gets all the way through: a
            // failed fsync does not tell which of our writes reached the disk, so "the slot reads as erased" proves nothing.
            if (slot == dirty || (onDisk != null && onDisk.txId() > s.txId())) {
                pager.writeRaw((long) slot * ps, new byte[ps]);   // the failed commit's meta page: forget it
                wrote = true;
            } else if (slot == s.txId() % 2 && (onDisk == null || onDisk.txId() != s.txId())) {
                pager.writeRaw((long) slot * ps, encodeMeta(ps, s));   // the committed one is damaged or missing
                wrote = true;
            }
        }
        if (wrote) pager.force();
        dirtyMetaSlot = -1;
        available.clear();
        pending.clear();
        chainPending.clear();
        restartPending.clear();
        loadFreelist(false);
    }

    /** The valid meta page of a slot as a snapshot, or null (unreadable, damaged or of another page size). */
    private Snapshot readSlot(int slot) {
        try {
            Object[] m = readMetaAt(channel, (long) slot * pager.pageSize, channel.size());
            return m != null && (Integer) m[0] == pager.pageSize ? (Snapshot) m[1] : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        committer.shutdown();              // commit what is queued, fail late submitters, stop the thread
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
