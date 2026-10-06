package org.example.sectoriadb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/** A physical blob file (.raw) inside a storage pool. */
public class BlobFileEntity {

    private String id;
    private String poolId;         // stored in JSON
    private String fileName;
    private String filePath;
    private int numBuckets;
    private int chunkSize;
    private long totalBytes;
    private Instant createdAt;

    /** Resolved reference — not stored in JSON, populated by the repository. */
    @JsonIgnore
    private PoolEntity pool;

    public BlobFileEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPoolId() { return poolId; }
    public void setPoolId(String poolId) { this.poolId = poolId; }

    public PoolEntity getPool() { return pool; }
    public void setPool(PoolEntity pool) {
        this.pool = pool;
        if (pool != null) this.poolId = pool.getId();
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getNumBuckets() { return numBuckets; }
    public void setNumBuckets(int numBuckets) { this.numBuckets = numBuckets; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
