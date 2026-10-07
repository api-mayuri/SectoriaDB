package org.example.sectoriadb.repository;

import org.example.sectoriadb.model.BlobFileEntity;

import java.util.List;
import java.util.Optional;

/** Registry of blob files. Every method is atomic. */
public interface BlobFileRepository {

    /** Upsert by id; maintains the (pool, blob) index. */
    BlobFileEntity save(BlobFileEntity entity);

    Optional<BlobFileEntity> findById(String id);

    /** All blobs of all pools; blobs are few (admin shell, auto-resize check). */
    List<BlobFileEntity> findAll();

    boolean existsById(String id);

    long count();

    /** Blobs of one pool, by index range scan. */
    List<BlobFileEntity> findByPoolId(String poolId);

    long countByPoolId(String poolId);

    /**
     * Removes the blob record, if nothing live is stored in it: a cuckoo blob must hold no chunk with a reference, a
     * small-object blob no live manifest. In the same transaction the dead manifests that still point at it and, for a
     * cuckoo blob, its zero-reference index entries, collection rows and orphan records are dropped.
     *
     * @throws BlobInUseException if live manifests / referenced chunks are in the blob
     */
    void deleteUnreferenced(String blobId);

    /**
     * Resize commit in ONE transaction: registers {@code replacement}, repoints every chunk index entry (and orphan
     * record) of {@code oldBlobId} to it through the {@code chunks_by_blob} index, and removes the old blob record.
     * Manifests are not touched: they reference chunks, not blobs.
     *
     * @return the number of chunk index entries moved
     */
    int replaceBlob(String oldBlobId, BlobFileEntity replacement);

    /** Live manifests (small-object blob) or referenced chunks (cuckoo blob) are still in the blob. */
    class BlobInUseException extends IllegalStateException {
        private final long liveManifests;

        public BlobInUseException(String blobId, long liveManifests) {
            super(liveManifests + " live manifest(s) / referenced chunk(s) in blob " + blobId);
            this.liveManifests = liveManifests;
        }

        public long liveManifests() {
            return liveManifests;
        }
    }
}
