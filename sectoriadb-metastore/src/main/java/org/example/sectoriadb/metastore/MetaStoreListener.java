package org.example.sectoriadb.metastore;

/**
 * Observability SPI of the metadata store. The module knows nothing about the application's metrics: it reports
 * events through this interface and the embedding application adapts them to its registry.
 *
 * <p>Called on hot paths: implementations must be cheap, thread-safe and must not throw (the store catches and
 * logs anything that is thrown, so a failing listener never fails an operation that already happened).
 */
public interface MetaStoreListener {

    /** A page was read from the file: {@code nanos} of wall time for {@code bytes} bytes. */
    default void onPageRead(long nanos, long bytes) { }

    /** A page was written to the file. */
    default void onPageWrite(long nanos, long bytes) { }

    /** An fsync of the store file finished. */
    default void onFsync(long nanos) { }

    /** A page failed its checksum when read. */
    default void onChecksumFailure() { }

    /** Time a writer waited for the single writer slot. */
    default void onWriterLockWait(long nanos) { }

    /** A commit became durable and visible; {@code nanos} is its total duration. */
    default void onCommit(long nanos) { }

    /**
     * A commit became durable: the transaction's {@code label} (see {@link WriteTxn#label}), the pages it wrote (tree,
     * overflow and free-list pages) and its total duration. Called in addition to {@link #onCommit(long)}.
     */
    default void onCommitDetail(String label, long pages, long nanos) { }

    /** An attempt to recover the write side after a failed commit finished. */
    default void onRecovery(boolean success) { }

    /** A group commit batch became durable: {@code bodies} submitted bodies sharing one commit of {@code pages} pages. */
    default void onGroupBatch(int bodies, int pages) { }

    /** Time a submitted body waited in the group commit queue before its batch started. */
    default void onGroupQueueWait(long nanos) { }

    /** A submitted body failed and its changes were rolled back without failing the rest of the batch. */
    default void onGroupBodyRollback() { }

    /** Does nothing. */
    MetaStoreListener NOOP = new MetaStoreListener() {
        @Override public String toString() { return "MetaStoreListener.NOOP"; }
    };
}
