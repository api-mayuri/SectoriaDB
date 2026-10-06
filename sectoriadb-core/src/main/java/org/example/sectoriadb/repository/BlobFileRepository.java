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
     * Removes the blob record and the (dead) manifests that still refer to it, if no live manifest does.
     *
     * @throws BlobInUseException if live manifests reference the blob
     */
    void deleteUnreferenced(String blobId);

    /**
     * Resize commit in ONE transaction: registers {@code replacement}, repoints every manifest of {@code oldBlobId}
     * (live and dead) to it through the {@code manifests_by_blob} index, and removes the old blob record.
     *
     * @return the number of manifests moved
     */
    int replaceBlob(String oldBlobId, BlobFileEntity replacement);

    /** Live manifests still reference the blob. */
    class BlobInUseException extends IllegalStateException {
        private final long liveManifests;

        public BlobInUseException(String blobId, long liveManifests) {
            super(liveManifests + " live manifest(s) reference blob " + blobId);
            this.liveManifests = liveManifests;
        }

        public long liveManifests() {
            return liveManifests;
        }
    }
}
