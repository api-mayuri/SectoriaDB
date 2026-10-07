package org.example.sectoriadb.metastore;

import org.example.sectoriadb.metrics.StorageMetrics;

/** Immutable options for {@link MetaStore#open}. */
public final class MetaStoreOptions {
    public static final int DEFAULT_PAGE_SIZE = 4096;
    public static final int MIN_PAGE_SIZE = 512;
    public static final int MAX_PAGE_SIZE = 65536;

    public static final int DEFAULT_MAX_BATCH_SIZE = 256;
    public static final int DEFAULT_MAX_BATCH_PAGES = 1024;
    public static final int DEFAULT_QUEUE_CAPACITY = 4096;

    private final int pageSize;
    private final boolean fsync;
    private final StorageMetrics metrics;
    private final int maxBatchSize;
    private final int maxBatchPages;
    private final long maxBatchWaitMicros;
    private final int queueCapacity;

    private MetaStoreOptions(int pageSize, boolean fsync, StorageMetrics metrics,
                             int maxBatchSize, int maxBatchPages, long maxBatchWaitMicros, int queueCapacity) {
        if (maxBatchSize < 1 || maxBatchPages < 1 || maxBatchWaitMicros < 0 || queueCapacity < 1) {
            throw new IllegalArgumentException("group commit limits must be positive (wait may be 0)");
        }
        this.maxBatchSize = maxBatchSize;
        this.maxBatchPages = maxBatchPages;
        this.maxBatchWaitMicros = maxBatchWaitMicros;
        this.queueCapacity = queueCapacity;
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE || Integer.bitCount(pageSize) != 1) {
            throw new IllegalArgumentException("pageSize must be a power of two in ["
                    + MIN_PAGE_SIZE + ", " + MAX_PAGE_SIZE + "]: " + pageSize);
        }
        this.pageSize = pageSize;
        this.fsync = fsync;
        this.metrics = metrics;
    }

    public static MetaStoreOptions defaults() {
        return new MetaStoreOptions(DEFAULT_PAGE_SIZE, true, StorageMetrics.NOOP,
                DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_BATCH_PAGES, 0, DEFAULT_QUEUE_CAPACITY);
    }

    /** Page size used when the file is created. For an existing file the size stored in its header wins. */
    public MetaStoreOptions pageSize(int pageSize) {
        return new MetaStoreOptions(pageSize, fsync, metrics, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity);
    }

    /** If false, commits do not call force(); durability is then up to the OS (tests, bulk loads). */
    public MetaStoreOptions fsync(boolean fsync) {
        return new MetaStoreOptions(pageSize, fsync, metrics, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity);
    }

    /** Receiver of lock-wait, commit, fsync and checksum-failure events (default: none). */
    public MetaStoreOptions metrics(StorageMetrics metrics) {
        return new MetaStoreOptions(pageSize, fsync, metrics, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity);
    }

    /**
     * Group commit limits (see {@link MetaStore#submit}): at most {@code maxBatchSize} bodies and about
     * {@code maxBatchPages} newly written pages per commit (the body that crosses the page limit still belongs to
     * the batch), a leader waiting up to {@code maxWaitMicros} for more bodies (0: none, the batch is whatever
     * accumulated during the previous commit), and a bounded queue of {@code queueCapacity} bodies; submitters
     * block when it is full.
     */
    public MetaStoreOptions groupCommit(int maxBatchSize, int maxBatchPages, long maxWaitMicros, int queueCapacity) {
        return new MetaStoreOptions(pageSize, fsync, metrics, maxBatchSize, maxBatchPages, maxWaitMicros, queueCapacity);
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    public int maxBatchPages() {
        return maxBatchPages;
    }

    public long maxBatchWaitMicros() {
        return maxBatchWaitMicros;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public StorageMetrics metrics() {
        return metrics;
    }

    public int pageSize() {
        return pageSize;
    }

    public boolean fsync() {
        return fsync;
    }
}
