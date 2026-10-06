package org.example.sectoriadb.metastore;

import org.example.sectoriadb.metrics.StorageMetrics;

/** Immutable options for {@link MetaStore#open}. */
public final class MetaStoreOptions {
    public static final int DEFAULT_PAGE_SIZE = 4096;
    public static final int MIN_PAGE_SIZE = 512;
    public static final int MAX_PAGE_SIZE = 65536;

    private final int pageSize;
    private final boolean fsync;
    private final StorageMetrics metrics;

    private MetaStoreOptions(int pageSize, boolean fsync, StorageMetrics metrics) {
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE || Integer.bitCount(pageSize) != 1) {
            throw new IllegalArgumentException("pageSize must be a power of two in ["
                    + MIN_PAGE_SIZE + ", " + MAX_PAGE_SIZE + "]: " + pageSize);
        }
        this.pageSize = pageSize;
        this.fsync = fsync;
        this.metrics = metrics;
    }

    public static MetaStoreOptions defaults() {
        return new MetaStoreOptions(DEFAULT_PAGE_SIZE, true, StorageMetrics.NOOP);
    }

    /** Page size used when the file is created. For an existing file the size stored in its header wins. */
    public MetaStoreOptions pageSize(int pageSize) {
        return new MetaStoreOptions(pageSize, fsync, metrics);
    }

    /** If false, commits do not call force(); durability is then up to the OS (tests, bulk loads). */
    public MetaStoreOptions fsync(boolean fsync) {
        return new MetaStoreOptions(pageSize, fsync, metrics);
    }

    /** Receiver of lock-wait, commit, fsync and checksum-failure events (default: none). */
    public MetaStoreOptions metrics(StorageMetrics metrics) {
        return new MetaStoreOptions(pageSize, fsync, metrics);
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
