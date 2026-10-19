package org.example.sectoriadb.catalog;

/**
 * A chunk an upload wrote (or found) before its commit: the input of the commit transaction, which turns it into a
 * {@code chunks} index entry or into one more reference on the existing entry.
 *
 * @param blobId  the cuckoo blob that holds the bytes this upload wrote or verified
 * @param indexed true if the chunk was already in the committed chunk index when the upload looked (nothing was
 *                written for it): if the entry is gone at commit time the data may be gone as well, and the commit
 *                fails instead of trusting a copy nobody protects (see docs/architecture/metadata-model.md)
 * @param verifiedAgainst the blob of the index entry whose bytes were compared with this chunk's bytes right before the
 *                commit (null: not compared). A chunk whose blob differs from the indexed copy's is committed only if it
 *                was compared: the 64-bit key plus length and CRC32C do not prove equal content.
 */
public record PlacedChunk(long key, String blobId, int dataLength, int crc32c, boolean indexed, String verifiedAgainst) {

    /** A chunk that nobody compared with the indexed copy by bytes. */
    public PlacedChunk(long key, String blobId, int dataLength, int crc32c, boolean indexed) {
        this(key, blobId, dataLength, crc32c, indexed, null);
    }

    public PlacedChunk withBlob(String newBlobId) {
        return new PlacedChunk(key, newBlobId, dataLength, crc32c, indexed, verifiedAgainst);
    }

    /** The bytes of this chunk were compared, just before the commit, with the copy the index names in {@code indexedBlobId}. */
    public PlacedChunk verifiedAgainst(String indexedBlobId) {
        return new PlacedChunk(key, blobId, dataLength, crc32c, indexed, indexedBlobId);
    }
}
