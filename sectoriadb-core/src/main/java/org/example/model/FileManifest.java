package org.example.model;

import java.util.List;

/**
 * Ordered list of chunk keys needed to reconstruct the original file.
 * lastChunkActualSize tracks the real size of the last chunk (may be smaller than chunkSize).
 */
public record FileManifest(
        String sourceFileName,
        int chunkSize,
        List<Long> chunkKeys,
        int lastChunkActualSize
) {
    public int totalChunks() {
        return chunkKeys.size();
    }

    @Override
    public String toString() {
        return "FileManifest{file='" + sourceFileName + "', chunks=" + totalChunks()
                + ", chunkSize=" + chunkSize + ", lastChunkSize=" + lastChunkActualSize + "}";
    }
}
