package org.example.entities;

public record BlobFileMetaData(
        String magicBytes,
        String blobFileId,
        long totalSpaceBytes,
        long usedSpaceBytes,
        long freeSpaceBytes,
        int slotsCount,
        boolean isReadOnly
) {
}
