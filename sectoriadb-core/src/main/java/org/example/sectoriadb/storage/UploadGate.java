package org.example.sectoriadb.storage;

import org.example.sectoriadb.catalog.UploadHold;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The upload gate of the garbage collector.
 *
 * <p><b>Problem.</b> An upload writes its chunks into blobs <i>before</i> its commit transaction creates the index
 * entries. Between the two a copy exists that nothing points to, and it is indistinguishable from the stray copy of a
 * crashed or aborted upload. Worse, an upload may <i>reuse</i> such a copy (the table deduplicates by key inside a
 * blob). If the collector freed the slot of a copy "without index entry" while an upload that relies on it was about to
 * commit, the commit would create an index entry for a freed slot: a corrupt object.
 *
 * <p><b>Design.</b> A per-pool gate with a reader side and a writer side, but not a {@code ReentrantReadWriteLock}
 * (holds are taken in one request step and released in another, possibly by another thread, and an abandoned upload must
 * not wedge the collector forever):
 * <ul>
 *   <li>an upload takes a {@link Hold} ({@link #enter}) before it writes anything and releases it when its commit has
 *       finished (or it failed / was abandoned). Taking a hold waits only while a sweep is draining or running;</li>
 *   <li>the collector asks for the gate ({@link #tryBegin}) <i>per batch</i>: it stops new holds, waits up to a bound
 *       for the existing ones to finish and, when none is left, frees the batch of stray copies (re-checking each
 *       against the committed index in an exclusive metastore transaction) and lets the uploads in again. When the
 *       holds do not drain in time the batch is simply deferred to the next pass;</li>
 *   <li>a hold older than the ticket time-to-live may be <i>revoked</i> by the collector (safety net for an abandoned
 *       upload). A revoked hold makes {@link Hold#beginCommit()} return false, so a late upload fails with a retryable
 *       error instead of committing against freed slots. A hold that already began its commit is never revoked: the
 *       collector waits for it.</li>
 * </ul>
 * Correctness argument: with the gate held, no upload is between "wrote" and "committed", and none can start, so a slot
 * whose key has no index entry (in the committed state, re-read under the exclusive writer slot) belongs to nobody.
 *
 * <p>Zero-reference chunks of the {@code chunk_gc} queue are also freed per batch under the gate: the slot is freed
 * before the metadata commit, and without the gate a failed commit could leave an index entry whose slot is gone while
 * an upload that deduplicated against it commits a reference to it. Under sustained upload load such a batch is
 * deferred to the next pass.
 */
public final class UploadGate {

    private static final int ACTIVE = 0, COMMITTING = 1, CLOSED = 2, REVOKED = 3;

    private final ConcurrentHashMap<String, PoolGate> pools = new ConcurrentHashMap<>();

    private static final class PoolGate {
        final Set<Hold> holds = new HashSet<>();
        /** chunk keys a collector batch is freeing right now: holds wait before they rely on one of them */
        final Set<Long> collecting = new HashSet<>();
        boolean sweeping;
    }

    /** A hold of one upload on one pool. */
    public final class Hold implements UploadHold {
        private final PoolGate gate;
        private final String poolId;
        private final long startedNanos = System.nanoTime();
        private int state = ACTIVE;   // guarded by gate
        private final LongSet touched = new LongSet();   // chunk keys this upload looked at or wrote; guarded by gate

        private Hold(PoolGate gate, String poolId) {
            this.gate = gate;
            this.poolId = poolId;
        }

        public String poolId() {
            return poolId;
        }

        /**
         * The upload is about to rely on the chunk stored under {@code key} (deduplicate against it, reuse or write its
         * slot). Waits while a collector batch is freeing that very key (milliseconds); afterwards the collector sees the
         * key as touched and leaves it alone until this hold is released. This is what lets the collector ignore every
         * upload that never looked at the chunks it frees, however long it runs.
         */
        public void touch(long key) {
            boolean interrupted = false;
            try {
                synchronized (gate) {
                    while (gate.collecting.contains(key)) {
                        try {
                            gate.wait();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    touched.add(key);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean beginCommit() {
            synchronized (gate) {
                if (state == REVOKED || state == CLOSED) return false;
                state = COMMITTING;
                return true;
            }
        }

        @Override
        public void release() {
            synchronized (gate) {
                if (state == CLOSED) return;
                boolean wasRevoked = state == REVOKED;
                state = CLOSED;
                if (!wasRevoked) gate.holds.remove(this);
                gate.notifyAll();
            }
        }

        public boolean revoked() {
            synchronized (gate) {
                return state == REVOKED;
            }
        }
    }

    /** The exclusive side, held by the collector while it frees a batch of stray copies. Closing lets uploads in again. */
    public final class Sweep implements AutoCloseable {
        private final PoolGate gate;

        private Sweep(PoolGate gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            synchronized (gate) {
                gate.sweeping = false;
                gate.notifyAll();
            }
        }
    }

    /** The keyed exclusive side: the chunks of {@link #granted()} may be freed until it is closed. */
    public final class KeySweep implements AutoCloseable {
        private final PoolGate gate;
        private final long[] granted;

        private KeySweep(PoolGate gate, long[] granted) {
            this.gate = gate;
            this.granted = granted;
        }

        /** The requested keys that no live upload has touched; the others are left for a later pass. */
        public long[] granted() {
            return granted;
        }

        @Override
        public void close() {
            synchronized (gate) {
                for (long k : granted) gate.collecting.remove(k);
                gate.notifyAll();
            }
        }
    }

    private PoolGate gate(String poolId) {
        return pools.computeIfAbsent(poolId, k -> new PoolGate());
    }

    /** Registers an upload on the pool. Waits while the collector holds the gate or drains it (milliseconds). */
    public Hold enter(String poolId) {
        PoolGate g = gate(poolId);
        boolean interrupted = false;
        try {
            synchronized (g) {
                while (g.sweeping) {
                    try {
                        g.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                Hold h = new Hold(g, poolId);
                g.holds.add(h);
                return h;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Takes the exclusive side for one pool: stops new holds and waits up to {@code waitMillis} until the existing ones
     * are gone; holds older than {@code ttlMillis} (and not committing) are revoked instead of waited for. Returns
     * {@code null}, with the gate open again, when uploads were still in flight at the deadline or another sweep runs.
     */
    public Sweep tryBegin(String poolId, long waitMillis, long ttlMillis) {
        PoolGate g = gate(poolId);
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        synchronized (g) {
            if (g.sweeping || !g.collecting.isEmpty()) return null;
            g.sweeping = true;
            try {
                while (true) {
                    long now = System.nanoTime();
                    for (Hold h : new ArrayList<>(g.holds)) {
                        if (h.state == ACTIVE && now - h.startedNanos > ttlMillis * 1_000_000L) {
                            h.state = REVOKED;
                            g.holds.remove(h);
                        }
                    }
                    if (g.holds.isEmpty()) return new Sweep(g);
                    long left = deadline - now;
                    if (left <= 0) break;
                    g.wait(Math.max(1, left / 1_000_000L));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            g.sweeping = false;
            g.notifyAll();
            return null;
        }
    }

    /**
     * Claims chunk keys for the collector (freeing zero-reference chunks, stray and orphan copies) without draining the
     * pool: new touches of these keys wait for the batch, uploads that already touched one of them are waited for up to
     * {@code waitMillis} (holds older than {@code ttlMillis} that are not committing are revoked), and the keys that are
     * still touched at the deadline are dropped from the grant. Uploads that never looked at the keys do not matter, so
     * a stream of long uploads cannot keep the collector away. Returns {@code null} when a full drain or another batch
     * is running; the result's {@code granted()} may be empty.
     */
    public KeySweep tryBeginKeys(String poolId, long[] keys, long waitMillis, long ttlMillis) {
        PoolGate g = gate(poolId);
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        synchronized (g) {
            if (g.sweeping || !g.collecting.isEmpty()) return null;
            for (long k : keys) g.collecting.add(k);
            try {
                while (true) {
                    long now = System.nanoTime();
                    for (Hold h : new ArrayList<>(g.holds)) {
                        if (h.state == ACTIVE && now - h.startedNanos > ttlMillis * 1_000_000L) {
                            h.state = REVOKED;
                            g.holds.remove(h);
                        }
                    }
                    boolean conflict = false;
                    for (long k : keys) {
                        for (Hold h : g.holds) {
                            if (h.touched.contains(k)) {
                                conflict = true;
                                break;
                            }
                        }
                        if (conflict) break;
                    }
                    long left = deadline - now;
                    if (!conflict || left <= 0) break;
                    g.wait(Math.max(1, left / 1_000_000L));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long[] granted = new long[keys.length];
            int n = 0;
            for (long k : keys) {
                boolean touched = false;
                for (Hold h : g.holds) {
                    if (h.touched.contains(k)) {
                        touched = true;
                        break;
                    }
                }
                if (touched) g.collecting.remove(k);
                else granted[n++] = k;
            }
            g.notifyAll();
            return new KeySweep(g, java.util.Arrays.copyOf(granted, n));
        }
    }

    /** Uploads currently holding the pool (diagnostics, tests). */
    public int inFlight(String poolId) {
        PoolGate g = pools.get(poolId);
        if (g == null) return 0;
        synchronized (g) {
            return g.holds.size();
        }
    }

    /** Uploads holding any pool. */
    public int inFlight() {
        int n = 0;
        for (PoolGate g : pools.values()) {
            synchronized (g) {
                n += g.holds.size();
            }
        }
        return n;
    }
}
