package org.example.sectoriadb.service;

import org.example.sectoriadb.model.UploadHold;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The upload gate of the garbage collector (doc 10).
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
 * <p>Zero-reference chunks of the {@code chunk_gc} queue do not need the gate: they have an index entry, an upload that
 * relies on one is protected by the grace period and the {@code COLLECTED} check of the commit (doc 09).
 */
public final class UploadGate {

    private static final int ACTIVE = 0, COMMITTING = 1, CLOSED = 2, REVOKED = 3;

    private final ConcurrentHashMap<String, PoolGate> pools = new ConcurrentHashMap<>();

    private static final class PoolGate {
        final Set<Hold> holds = new HashSet<>();
        boolean sweeping;
    }

    /** A hold of one upload on one pool. */
    public final class Hold implements UploadHold {
        private final PoolGate gate;
        private final String poolId;
        private final long startedNanos = System.nanoTime();
        private int state = ACTIVE;   // guarded by gate

        private Hold(PoolGate gate, String poolId) {
            this.gate = gate;
            this.poolId = poolId;
        }

        public String poolId() {
            return poolId;
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
        private final int revoked;

        private Sweep(PoolGate gate, int revoked) {
            this.gate = gate;
            this.revoked = revoked;
        }

        /** Holds that were revoked (older than the time-to-live) to get this gate. */
        public int revokedHolds() {
            return revoked;
        }

        @Override
        public void close() {
            synchronized (gate) {
                gate.sweeping = false;
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
        int revoked = 0;
        synchronized (g) {
            if (g.sweeping) return null;
            g.sweeping = true;
            try {
                while (true) {
                    long now = System.nanoTime();
                    for (Hold h : new ArrayList<>(g.holds)) {
                        if (h.state == ACTIVE && now - h.startedNanos > ttlMillis * 1_000_000L) {
                            h.state = REVOKED;
                            g.holds.remove(h);
                            revoked++;
                        }
                    }
                    if (g.holds.isEmpty()) return new Sweep(g, revoked);
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

    /** Pools that have uploads in flight (diagnostics). */
    public List<String> poolsInFlight() {
        List<String> out = new ArrayList<>();
        pools.forEach((id, g) -> {
            synchronized (g) {
                if (!g.holds.isEmpty()) out.add(id);
            }
        });
        return out;
    }
}
