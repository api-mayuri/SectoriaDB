package org.example.sectoriadb.metastore;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Internal test support (package-private on purpose): simulates a failing disk and crashes at named commit stages.
 * Production code never sets anything here, so the hot paths only read two volatile fields.
 */
final class FaultInjector {

    /** Invoked at {@link CommitStage}s of a commit; throwing simulates a crash at that point. */
    @FunctionalInterface
    interface CommitHook {
        void at(CommitStage stage);
    }

    private final AtomicInteger writeFaults = new AtomicInteger();
    private final AtomicInteger forceFaults = new AtomicInteger();
    private volatile String faultMessage = "injected I/O failure";
    volatile CommitHook commitHook;

    /**
     * The next {@code writes} page writes and {@code forces} fsyncs fail with an {@code IOException(message)}, like a
     * full or failing disk. {@link Integer#MAX_VALUE} means "until cleared"; 0, 0 clears.
     */
    void failDisk(int writes, int forces, String message) {
        faultMessage = message;
        writeFaults.set(writes);
        forceFaults.set(forces);
    }

    void commitStage(CommitStage stage) {
        CommitHook h = commitHook;
        if (h != null) h.at(stage);
    }

    void beforePageWrite() throws IOException {
        maybeFail(writeFaults);
    }

    void beforeForce() throws IOException {
        maybeFail(forceFaults);
    }

    private void maybeFail(AtomicInteger counter) throws IOException {
        int n;
        do {
            n = counter.get();
            if (n <= 0) return;
        } while (!counter.compareAndSet(n, n == Integer.MAX_VALUE ? n : n - 1));
        throw new IOException(faultMessage);
    }
}
