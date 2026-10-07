package org.example.sectoriadb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "sectoriadb")
public class StorageProperties {

    private String metaDir = "./sectoriadb-meta";
    private String dataDir = "./sectoriadb-data";
    private int defaultChunkSize = 16_384;
    private int defaultNumBuckets = 65_536;
    private int maxEvictions = 32;
    /** fsync data and metadata writes (data → force → meta → force). Disable only for tests/benchmarks. */
    private boolean fsync = true;
    private AutoResize autoResize = new AutoResize();
    private Pool pool = new Pool();
    private S3 s3 = new S3();
    private SmallObject smallObject = new SmallObject();
    private Gc gc = new Gc();

    public String getMetaDir() { return metaDir; }
    public void setMetaDir(String v) { this.metaDir = v; }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String v) { this.dataDir = v; }

    public int getDefaultChunkSize() { return defaultChunkSize; }
    public void setDefaultChunkSize(int v) { this.defaultChunkSize = v; }

    public int getDefaultNumBuckets() { return defaultNumBuckets; }
    public void setDefaultNumBuckets(int v) { this.defaultNumBuckets = v; }

    public int getMaxEvictions() { return maxEvictions; }
    public void setMaxEvictions(int v) { this.maxEvictions = v; }

    public boolean isFsync() { return fsync; }
    public void setFsync(boolean v) { this.fsync = v; }

    public AutoResize getAutoResize() { return autoResize; }
    public void setAutoResize(AutoResize v) { this.autoResize = v; }

    public Pool getPool() { return pool; }
    public void setPool(Pool v) { this.pool = v; }

    public SmallObject getSmallObject() { return smallObject; }
    public void setSmallObject(SmallObject v) { this.smallObject = v; }

    public Gc getGc() { return gc; }
    public void setGc(Gc v) { this.gc = v; }

    public S3 getS3() { return s3; }
    public void setS3(S3 v) { this.s3 = v; }

    /**
     * Several cuckoo blobs per pool (bucket): chunks are spread over them by weighted rendezvous hashing, see doc 09.
     */
    public static class Pool {
        private int initialBlobs = 1;
        private int maxBlobs = 16;
        private int growThresholdPercent = 75;
        private int locationCacheEntries = 65_536;

        /** Cuckoo blobs created when a pool receives its first chunked object. */
        public int getInitialBlobs() { return initialBlobs; }
        public void setInitialBlobs(int v) { this.initialBlobs = Math.max(1, v); }

        /** A pool never grows beyond this many cuckoo blobs (each one keeps ~9 MiB of slot metadata in memory). */
        public int getMaxBlobs() { return maxBlobs; }
        public void setMaxBlobs(int v) { this.maxBlobs = Math.max(1, v); }

        /** When the aggregate fill of the pool's blobs reaches this percent, a new blob is added (and when no blob accepts a chunk). */
        public int getGrowThresholdPercent() { return growThresholdPercent; }
        public void setGrowThresholdPercent(int v) { this.growThresholdPercent = Math.min(100, Math.max(1, v)); }

        /**
         * Entries of the LRU that remembers which blob holds a chunk (about 100 bytes each). A hint only: a wrong or stale
         * entry costs one index lookup, never a wrong read. 0 disables the cache.
         */
        public int getLocationCacheEntries() { return locationCacheEntries; }
        public void setLocationCacheEntries(int v) { this.locationCacheEntries = Math.max(0, v); }
    }

    /** Small-object blobs: objects smaller than {@code default-chunk-size} are stored whole in an append-only log. */
    public static class SmallObject {
        private long maxFileBytes = 1L << 30;
        private long checkpointIntervalBytes = 16L << 20;

        /** A small-object blob that reached this size is closed for appends; the pool gets a new one. */
        public long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(long v) { this.maxFileBytes = v; }

        /** The recovery hint in the blob header is advanced after this many appended bytes (and on close). */
        public long getCheckpointIntervalBytes() { return checkpointIntervalBytes; }
        public void setCheckpointIntervalBytes(long v) { this.checkpointIntervalBytes = v; }
    }

    /** Garbage collection (doc 10): freeing the slots of unreferenced chunks, stray copies, tombstones, small-blob compaction. */
    public static class Gc {
        private boolean enabled = false;
        private Duration grace = Duration.ofMinutes(15);
        private Duration interval = Duration.ofMinutes(1);
        private int maxChunksPerRun = 10_000;
        private int batchSize = 16;
        private Duration sweepInterval = Duration.ofHours(6);
        private Duration compactInterval = Duration.ofMinutes(10);
        private int smallCompactDeadPercent = 50;
        private long smallCompactMinDeadBytes = 1L << 20;
        private Duration sweepGateWait = Duration.ofSeconds(2);
        private Duration uploadTicketTtl = Duration.ofMinutes(30);

        /** Run the background scheduler ({@code GarbageCollectionScheduler}). The shell commands work either way. */
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }

        /**
         * Grace period G: a chunk / retired manifest is collected only when it has been garbage for at least this long.
         * It covers the window "an upload found the chunk in the index and has not committed yet" and readers that walk
         * an old snapshot of a manifest (and, for compaction, the time the old small-object file is kept).
         */
        public Duration getGrace() { return grace; }
        public void setGrace(Duration v) { this.grace = v == null || v.isNegative() ? Duration.ZERO : v; }

        /** Pause between collector passes (chunks, orphans, tombstones). */
        public Duration getInterval() { return interval; }
        public void setInterval(Duration v) { this.interval = v; }

        /** At most this many chunk slots are freed per pass (rate limit); the rest waits for the next pass. */
        public int getMaxChunksPerRun() { return maxChunksPerRun; }
        public void setMaxChunksPerRun(int v) { this.maxChunksPerRun = Math.max(1, v); }

        /**
         * Chunks (or stray copies) freed per exclusive metastore transaction. 1 is the strict "one transaction per
         * chunk" of the contract; larger values amortize the two fsyncs of a commit, at the price of blocking the
         * writers a little longer per batch. Every chunk of a batch is re-checked inside the transaction.
         */
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int v) { this.batchSize = Math.max(1, v); }

        /** Pause between full sweeps for stray copies of each blob. */
        public Duration getSweepInterval() { return sweepInterval; }
        public void setSweepInterval(Duration v) { this.sweepInterval = v; }

        /** Pause between checks for small-object blobs worth compacting. */
        public Duration getCompactInterval() { return compactInterval; }
        public void setCompactInterval(Duration v) { this.compactInterval = v; }

        /** A small-object blob is compacted when at least this percent of its record bytes are dead. */
        public int getSmallCompactDeadPercent() { return smallCompactDeadPercent; }
        public void setSmallCompactDeadPercent(int v) { this.smallCompactDeadPercent = Math.min(100, Math.max(1, v)); }

        /** ... and at least this many dead bytes (compacting a few kilobytes is not worth a new file). */
        public long getSmallCompactMinDeadBytes() { return smallCompactMinDeadBytes; }
        public void setSmallCompactMinDeadBytes(long v) { this.smallCompactMinDeadBytes = Math.max(0, v); }

        /** How long a sweep waits for the uploads in flight of a pool to finish before it gives up and retries later. */
        public Duration getSweepGateWait() { return sweepGateWait; }
        public void setSweepGateWait(Duration v) { this.sweepGateWait = v; }

        /**
         * An upload that stays between "chunks written" and "committed" longer than this can be revoked by a sweep that
         * needs the gate (its commit then fails with a retryable 503 instead of pointing at a freed slot). Safety net
         * for an abandoned upload; a normal one releases its hold when it commits or fails.
         */
        public Duration getUploadTicketTtl() { return uploadTicketTtl; }
        public void setUploadTicketTtl(Duration v) { this.uploadTicketTtl = v; }
    }

    public static class S3 {
        private String region = "us-east-1";
        private boolean authEnabled = true;
        private String accessKey;
        private String secretKey;
        private List<String> credentials;

        public String getRegion() { return region; }
        public void setRegion(String v) { this.region = v; }

        public boolean isAuthEnabled() { return authEnabled; }
        public void setAuthEnabled(boolean v) { this.authEnabled = v; }

        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String v) { this.accessKey = v; }

        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { this.secretKey = v; }

        public List<String> getCredentials() { return credentials; }
        public void setCredentials(List<String> v) { this.credentials = v; }
    }

    /**
     * Background check that grows pools (adds a blob when the aggregate fill reaches
     * {@code sectoriadb.pool.grow-threshold-percent}). The old in-place expansion of one blob is now the manual
     * {@code resize} shell command only.
     */
    public static class AutoResize {
        private boolean enabled = true;
        private long checkIntervalMs = 60_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }

        public long getCheckIntervalMs() { return checkIntervalMs; }
        public void setCheckIntervalMs(long v) { this.checkIntervalMs = v; }
    }
}
