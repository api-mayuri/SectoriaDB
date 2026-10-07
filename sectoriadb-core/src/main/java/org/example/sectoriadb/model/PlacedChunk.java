package org.example.sectoriadb.model;

/**
 * A chunk an upload wrote (or found) before its commit: the input of the commit transaction, which turns it into a
 * {@code chunks} index entry or into one more reference on the existing entry.
 *
 * @param blobId  the cuckoo blob that holds the bytes this upload wrote or verified
 * @param indexed true if the chunk was already in the committed chunk index when the upload looked (nothing was
 *                written for it): if the entry is gone at commit time the data may be gone as well, and the commit
 *                fails instead of trusting a copy nobody protects (see doc 09, "contract for stage 10")
 */
public record PlacedChunk(long key, String blobId, int dataLength, int crc32c, boolean indexed) {

    public PlacedChunk withBlob(String newBlobId) {
        return new PlacedChunk(key, newBlobId, dataLength, crc32c, indexed);
    }
}
