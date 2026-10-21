package org.example.sectoriadb.engine.blob;

import org.example.sectoriadb.engine.metrics.EngineListener;

import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Group force for the writes of one blob file: counts the writes issued and the writes covered by a completed fsync, and
 * lets any number of callers wait for "everything written before my call is durable" with one fsync per group (a
 * leader forces, the others wait for a force that started after their write).
 */
final class ForceBarrier {

    /** The fsync of the file. */
    @FunctionalInterface
    interface Force {
        void run() throws IOException;
    }

    private volatile long writeSeq;                 // writes issued (advanced by the writer under its own lock)
    private volatile long forcedSeq;                // every write up to this one is covered by a completed fsync
    private final ReentrantLock forceLock = new ReentrantLock();
    private final Condition forceDone = forceLock.newCondition();
    private boolean forcing;                        // a leader is inside fsync (guarded by forceLock)

    /** A write was issued; call it after the data and the meta entry were written, under the writer's lock. */
    void written() {
        writeSeq++;
    }

    boolean hasUnforced() {
        return writeSeq > forcedSeq;
    }

    /**
     * Returns when every write issued before the call is on disk. A failed force fails the leader's call only; a waiter
     * then forces by itself.
     */
    void await(Force force, EngineListener listener) throws IOException {
        final long target = writeSeq;
        if (forcedSeq >= target) return;
        long t0 = System.nanoTime();
        boolean led = false;
        long covered = 0;
        forceLock.lock();
        try {
            while (forcedSeq < target) {
                if (forcing) {
                    forceDone.awaitUninterruptibly();
                    continue;
                }
                forcing = true;
                long upTo = writeSeq;          // every write issued so far happened before it advanced the counter
                long before = forcedSeq;
                forceLock.unlock();
                boolean ok = false;
                try {
                    force.run();
                    ok = true;
                } finally {
                    forceLock.lock();
                    forcing = false;
                    if (ok) {
                        if (upTo > forcedSeq) forcedSeq = upTo;
                        led = true;
                        covered += upTo - before;
                    }
                    forceDone.signalAll();
                }
            }
        } finally {
            forceLock.unlock();
        }
        listener.onCuckooBarrier(System.nanoTime() - t0, led, covered);
    }
}
