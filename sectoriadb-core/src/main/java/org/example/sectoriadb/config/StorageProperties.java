package org.example.sectoriadb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "sectoriadb")
public class StorageProperties {

    private String metaDir = "./sectoriadb-meta";
    private String dataDir = "./sectoriadb-data";
    private int defaultChunkSize = 1_048_576;
    private int defaultNumBuckets = 1_280;
    private int maxEvictions = 32;
    /** fsync data and metadata writes (data → force → meta → force). Disable only for tests/benchmarks. */
    private boolean fsync = true;
    private AutoResize autoResize = new AutoResize();
    private S3 s3 = new S3();

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

    public S3 getS3() { return s3; }
    public void setS3(S3 v) { this.s3 = v; }

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

    public static class AutoResize {
        private boolean enabled = true;
        private int thresholdPercent = 80;
        private int expandPercent = 50;
        private long checkIntervalMs = 60_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }

        public int getThresholdPercent() { return thresholdPercent; }
        public void setThresholdPercent(int v) { this.thresholdPercent = v; }

        public int getExpandPercent() { return expandPercent; }
        public void setExpandPercent(int v) { this.expandPercent = v; }

        public long getCheckIntervalMs() { return checkIntervalMs; }
        public void setCheckIntervalMs(long v) { this.checkIntervalMs = v; }
    }
}
