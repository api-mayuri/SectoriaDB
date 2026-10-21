package org.example.sectoriadb.metastore;

/** Immutable options for {@link MetaStore#open}. */
public final class MetaStoreOptions {
    public static final int DEFAULT_PAGE_SIZE = 4096;
    public static final int MIN_PAGE_SIZE = 512;
    public static final int MAX_PAGE_SIZE = 65536;

    public static final int DEFAULT_MAX_BATCH_SIZE = 256;
    public static final int DEFAULT_MAX_BATCH_PAGES = 1024;
    public static final int DEFAULT_QUEUE_CAPACITY = 4096;
    public static final long DEFAULT_RECOVERY_BACKOFF_MILLIS = 1000;
    /** 8192 pages = 32 MiB at the default page size: far above any single object operation. */
    public static final int DEFAULT_LARGE_COMMIT_PAGES = 8192;

    private final int pageSize;
    private final boolean fsync;
    private final MetaStoreListener listener;
    private final int maxBatchSize;
    private final int maxBatchPages;
    private final long maxBatchWaitMicros;
    private final int queueCapacity;
    private final long recoveryBackoffMillis;
    private final int largeCommitPages;

    private MetaStoreOptions(int pageSize, boolean fsync, MetaStoreListener listener,
                             int maxBatchSize, int maxBatchPages, long maxBatchWaitMicros, int queueCapacity,
                             long recoveryBackoffMillis, int largeCommitPages) {
        this.recoveryBackoffMillis = Math.max(0, recoveryBackoffMillis);
        this.largeCommitPages = Math.max(0, largeCommitPages);
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
        this.listener = listener;
    }

    public static MetaStoreOptions defaults() {
        return new MetaStoreOptions(DEFAULT_PAGE_SIZE, true, MetaStoreListener.NOOP,
                DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_BATCH_PAGES, 0, DEFAULT_QUEUE_CAPACITY, DEFAULT_RECOVERY_BACKOFF_MILLIS,
                DEFAULT_LARGE_COMMIT_PAGES);
    }

    /** Page size used when the file is created. For an existing file the size stored in its header wins. */
    public MetaStoreOptions pageSize(int pageSize) {
        return new MetaStoreOptions(pageSize, fsync, listener, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity, recoveryBackoffMillis, largeCommitPages);
    }

    /** If false, commits do not call force(); durability is then up to the OS (tests, bulk loads). */
    public MetaStoreOptions fsync(boolean fsync) {
        return new MetaStoreOptions(pageSize, fsync, listener, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity, recoveryBackoffMillis, largeCommitPages);
    }

    /** Receiver of lock-wait, commit, fsync and checksum-failure events (default: none). */
    public MetaStoreOptions listener(MetaStoreListener listener) {
        return new MetaStoreOptions(pageSize, fsync, listener, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity, recoveryBackoffMillis, largeCommitPages);
    }

    /**
     * Group commit limits (see {@link MetaStore#submit}): at most {@code maxBatchSize} bodies and about
     * {@code maxBatchPages} newly written pages per commit (the body that crosses the page limit still belongs to
     * the batch), a leader waiting up to {@code maxWaitMicros} for more bodies (0: none, the batch is whatever
     * accumulated during the previous commit), and a bounded queue of {@code queueCapacity} bodies; submitters
     * block when it is full.
     */
    public MetaStoreOptions groupCommit(int maxBatchSize, int maxBatchPages, long maxWaitMicros, int queueCapacity) {
        return new MetaStoreOptions(pageSize, fsync, listener, maxBatchSize, maxBatchPages, maxWaitMicros, queueCapacity, recoveryBackoffMillis, largeCommitPages);
    }

    /**
     * After a failed commit the store refuses writes (reads keep working) and tries to recover on the next write; when
     * the recovery fails, or the commit failed less than this long ago, writes fail fast without touching the disk.
     */
    public MetaStoreOptions recoveryBackoff(long millis) {
        return new MetaStoreOptions(pageSize, fsync, listener, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity, millis, largeCommitPages);
    }

    /**
     * A commit that writes at least this many pages is logged at WARN with the transaction's label, its size and its
     * duration (0 disables the log). A large commit holds the single writer for long and allocates its pages at the end of
     * the file when the free list is short, so it is the first suspect for latency spikes and sudden file growth.
     */
    public MetaStoreOptions largeCommitPages(int pages) {
        return new MetaStoreOptions(pageSize, fsync, listener, maxBatchSize, maxBatchPages, maxBatchWaitMicros, queueCapacity,
                recoveryBackoffMillis, pages);
    }

    public int largeCommitPages() {
        return largeCommitPages;
    }

    public long recoveryBackoffMillis() {
        return recoveryBackoffMillis;
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

    public MetaStoreListener listener() {
        return listener;
    }

    public int pageSize() {
        return pageSize;
    }

    public boolean fsync() {
        return fsync;
    }
}
