package org.example.sectoriadb.catalog;

/**
 * One part of a {@link StorageKind#MULTIPART} object: a run of bytes that was stored as one upload, with its own chunk
 * boundaries. A part's last chunk is usually shorter than the chunk size, so the chunk size alone cannot locate a byte of
 * the concatenation: the manifest keeps the segments in object order and the reader walks them.
 *
 * <p>A CHUNKED segment owns the next {@code chunkCount} entries of the manifest's chunk key list (the segments' counts add up to
 * the list length); a SMALL segment is one record of a small-object blob; an EMPTY segment has no bytes at all.
 *
 * @param partNumber the part number the client used (what {@code GET ?partNumber=N} addresses)
 * @param length     bytes of the part
 * @param kind       CHUNKED, SMALL or EMPTY
 */
public record PartSegment(int partNumber, long length, StorageKind kind, int chunkSize, int lastChunkSize, int chunkCount,
                          String smallBlobId, long smallOffset, int smallLength, int smallCrc32c) {

    public PartSegment {
        if (kind == StorageKind.MULTIPART) throw new IllegalArgumentException("a segment cannot be MULTIPART");
    }

    public static PartSegment chunked(int partNumber, long length, int chunkSize, int lastChunkSize, int chunkCount) {
        return new PartSegment(partNumber, length, StorageKind.CHUNKED, chunkSize, lastChunkSize, chunkCount, null, 0, 0, 0);
    }

    public static PartSegment small(int partNumber, String blobId, long offset, int length, int crc32c) {
        return new PartSegment(partNumber, length, StorageKind.SMALL, 0, 0, 0, blobId, offset, length, crc32c);
    }

    public static PartSegment empty(int partNumber) {
        return new PartSegment(partNumber, 0, StorageKind.EMPTY, 0, 0, 0, null, 0, 0, 0);
    }

    /** The same SMALL segment at another place (compaction). */
    public PartSegment movedTo(String blobId, long offset) {
        return new PartSegment(partNumber, length, kind, chunkSize, lastChunkSize, chunkCount, blobId, offset, smallLength, smallCrc32c);
    }

    /** Bytes of chunk {@code i} (0-based within this segment). */
    public int chunkLength(int i) {
        return i == chunkCount - 1 ? lastChunkSize : chunkSize;
    }
}
