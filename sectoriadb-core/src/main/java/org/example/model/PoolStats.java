package org.example.model;

public class PoolStats {
    private long totalSpaceBytes;
    private long usedSpaceBytes;
    private long freeSpaceBytes;
    private int blobFilesCount;
    private boolean isReadOnly;

    public long getTotalSpaceBytes() {
        return totalSpaceBytes;
    }

    public void setTotalSpaceBytes(long totalSpaceBytes) {
        this.totalSpaceBytes = totalSpaceBytes;
    }

    public long getUsedSpaceBytes() {
        return usedSpaceBytes;
    }

    public void setUsedSpaceBytes(long usedSpaceBytes) {
        this.usedSpaceBytes = usedSpaceBytes;
    }

    public long getFreeSpaceBytes() {
        return freeSpaceBytes;
    }

    public void setFreeSpaceBytes(long freeSpaceBytes) {
        this.freeSpaceBytes = freeSpaceBytes;
    }

    public int getBlobFilesCount() {
        return blobFilesCount;
    }

    public void setBlobFilesCount(int blobFilesCount) {
        this.blobFilesCount = blobFilesCount;
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    public void setReadOnly(boolean readOnly) {
        isReadOnly = readOnly;
    }

    @Override
    public String toString() {
        return "PoolStats{" +
                "totalSpaceBytes=" + totalSpaceBytes +
                ", usedSpaceBytes=" + usedSpaceBytes +
                ", freeSpaceBytes=" + freeSpaceBytes +
                ", blobFilesCount=" + blobFilesCount +
                ", isReadOnly=" + isReadOnly +
                '}';
    }
}
