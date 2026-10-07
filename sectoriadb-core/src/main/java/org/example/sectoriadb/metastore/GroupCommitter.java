package org.example.sectoriadb.metastore;

import org.example.sectoriadb.metrics.StorageMetrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Group commit: many small write transactions share one metastore commit (one data fsync, one meta fsync).
 *
 * <p>A single daemon thread {@code metastore-committer} takes whatever has queued up, opens ONE write transaction,
 * applies the bodies in FIFO order, each inside a savepoint, commits once and only then completes the futures.
 * Serializability: bodies run strictly one after another inside the transaction and every body sees the effects of
 * the earlier ones, so the outcome is that of the serial order of submission. Atomicity per body: a body that throws is
 * rolled back to its savepoint, the others stay in the batch. Durability: a future completes after the meta page
 * of the commit that contains its changes has been fsynced. If the commit itself fails every body of the batch fails
 * (the store is then poisoned, like for a direct writer).
 */
final class GroupCommitter {

    private static final class Task {
        final Function<WriteTxn, Object> body;
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final long enqueued = System.nanoTime();
        Object result;
        Throwable error;

        Task(Function<WriteTxn, Object> body) {
            this.body = body;
        }
    }

    private static final Task STOP = new Task(tx -> null);

    private final MetaStore store;
    private final StorageMetrics metrics;
    private final int maxBatchSize;
    private final int maxBatchPages;
    private final long maxWaitNanos;
    private final BlockingQueue<Task> queue;
    private final Object lifecycle = new Object();
    private Thread thread;                       // guarded by lifecycle
    private volatile boolean closing;
    private volatile boolean terminated;

    GroupCommitter(MetaStore store, MetaStoreOptions opts) {
        this.store = store;
        this.metrics = opts.metrics();
        this.maxBatchSize = opts.maxBatchSize();
        this.maxBatchPages = opts.maxBatchPages();
        this.maxWaitNanos = TimeUnit.MICROSECONDS.toNanos(opts.maxBatchWaitMicros());
        this.queue = new ArrayBlockingQueue<>(opts.queueCapacity());
    }

    boolean onCommitterThread() {
        Thread t;
        synchronized (lifecycle) {
            t = thread;
        }
        return t == Thread.currentThread();
    }

    @SuppressWarnings("unchecked")
    <T> CompletableFuture<T> submit(Function<WriteTxn, T> body) {
        if (body == null) throw new NullPointerException("body");
        if (onCommitterThread()) {
            throw new IllegalStateException("a grouped write body must not submit another grouped write (it would wait for itself)");
        }
        Throwable unusable = store.unusableCause();
        if (unusable != null) return CompletableFuture.failedFuture(unusable);
        Task t = new Task((Function<WriteTxn, Object>) (Function<?, ?>) body);
        synchronized (lifecycle) {
            if (closing) return CompletableFuture.failedFuture(closedError());
            if (thread == null) {
                thread = new Thread(this::run, "metastore-committer");
                thread.setDaemon(true);
                thread.start();
            }
        }
        try {
            queue.put(t);              // backpressure: blocks while the queue is full
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new IllegalStateException("interrupted while queueing a write", e));
        }
        if (terminated) sweep();       // the committer is gone: do not leave the task behind
        return (CompletableFuture<T>) t.future;
    }

    private static IllegalStateException closedError() {
        return new IllegalStateException("store is closed");
    }

    // ---------------------------------------------------------------- committer thread

    private void run() {
        ArrayDeque<Task> local = new ArrayDeque<>();
        boolean stopping = false;
        while (true) {
            try {
                if (local.isEmpty()) {
                    if (stopping) break;
                    Task t = queue.take();
                    if (t == STOP) break;
                    local.add(t);
                }
                stopping |= runBatch(local, stopping);
            } catch (InterruptedException e) {
                // nobody interrupts this thread on purpose; treat it as a stop request after finishing the queue
                stopping = true;
            } catch (Throwable t) {
                // runBatch handles its own failures; this is a last line of defence so the thread never dies silently
                for (Task x : local) x.future.completeExceptionally(t);
                local.clear();
            }
        }
        terminated = true;
        sweep();
    }

    /**
     * Takes the writer slot first and only then collects the batch, so everything that queued up while the previous
     * commit was fsyncing (or while a direct writer held the slot) goes into this one. Runs the bodies of
     * {@code local}; whatever is left over (limits) stays in it.
     *
     * @return true if the stop marker was seen
     */
    private boolean runBatch(ArrayDeque<Task> local, boolean alreadyStopping) throws InterruptedException {
        WriteTxn tx;
        try {
            tx = store.beginWrite();
        } catch (Throwable t) {                 // poisoned or closed: fail fast with that cause
            Throwable cause = t instanceof IllegalStateException ? t : new IllegalStateException(t);
            for (Task x : local) x.future.completeExceptionally(cause);
            local.clear();
            return false;
        }
        boolean stop = alreadyStopping;
        try {
            if (!stop) stop = collect(local);
        } catch (Throwable t) {
            tx.abortInternal();
            throw t;
        }
        tx.managed = true;
        List<Task> taken = new ArrayList<>();
        try {
            while (!local.isEmpty()) {
                if (!taken.isEmpty() && (taken.size() >= maxBatchSize || tx.owned.size() >= maxBatchPages)) break;
                Task t = local.poll();
                taken.add(t);
                metrics.metaGroupQueueWait(System.nanoTime() - t.enqueued);
                WriteTxn.Savepoint sp = tx.savepoint();
                try {
                    t.result = t.body.apply(tx);
                    tx.release(sp);
                } catch (Throwable e) {
                    tx.rollbackTo(sp);
                    t.error = e;
                    metrics.metaGroupBodyRollback();
                }
            }
            int pages = tx.owned.size();
            tx.commitInternal();                // data fsync, meta page, meta fsync
            metrics.metaGroupBatch(taken.size(), pages);
        } catch (Throwable commitFailure) {
            tx.abortInternal();
            for (Task t : taken) t.future.completeExceptionally(t.error != null ? t.error : commitFailure);
            return stop;
        }
        for (Task t : taken) {
            if (t.error != null) t.future.completeExceptionally(t.error);
            else t.future.complete(t.result);
        }
        return stop;
    }

    /** Moves queued tasks into {@code local} up to the batch size, waiting at most the configured time. @return true on the stop marker */
    private boolean collect(ArrayDeque<Task> local) throws InterruptedException {
        while (local.size() < maxBatchSize) {
            Task t = queue.poll();
            if (t == null) break;
            if (t == STOP) return true;
            local.add(t);
        }
        if (maxWaitNanos > 0 && local.size() < maxBatchSize) {
            long deadline = System.nanoTime() + maxWaitNanos;
            while (local.size() < maxBatchSize) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                Task t = queue.poll(left, TimeUnit.NANOSECONDS);
                if (t == null) break;
                if (t == STOP) return true;
                local.add(t);
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- shutdown

    /** Stops accepting writes, lets the committer commit what is queued, waits for it, fails what is left. */
    void shutdown() {
        Thread t;
        synchronized (lifecycle) {
            closing = true;
            t = thread;
        }
        if (t != null) {
            if (t == Thread.currentThread()) throw new IllegalStateException("close() called from inside a grouped write");
            boolean interrupted = false;
            while (t.isAlive()) {
                try {
                    if (queue.offer(STOP, 10, TimeUnit.MILLISECONDS)) {
                        t.join();
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        terminated = true;
        sweep();
    }

    private void sweep() {
        Task t;
        while ((t = queue.poll()) != null) {
            if (t != STOP) t.future.completeExceptionally(closedError());
        }
    }
}
