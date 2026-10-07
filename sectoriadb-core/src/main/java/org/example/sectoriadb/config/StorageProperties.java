package org.example.sectoriadb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public S3 getS3() { return s3; }
    public void setS3(S3 v) { this.s3 = v; }

    /**
     * Several cuckoo blobs per pool (bucket): chunks are spread over them by weighted rendezvous hashing, see doc 09.
     */
    public static class Pool {
        private int initialBlobs = 1;
        private int maxBlobs = 16;
        private int growThresholdPercent = 75;

        /** Cuckoo blobs created when a pool receives its first chunked object. */
        public int getInitialBlobs() { return initialBlobs; }
        public void setInitialBlobs(int v) { this.initialBlobs = Math.max(1, v); }

        /** A pool never grows beyond this many cuckoo blobs (each one keeps ~9 MiB of slot metadata in memory). */
        public int getMaxBlobs() { return maxBlobs; }
        public void setMaxBlobs(int v) { this.maxBlobs = Math.max(1, v); }

        /** When the aggregate fill of the pool's blobs reaches this percent, a new blob is added (and when no blob accepts a chunk). */
        public int getGrowThresholdPercent() { return growThresholdPercent; }
        public void setGrowThresholdPercent(int v) { this.growThresholdPercent = Math.min(100, Math.max(1, v)); }
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
