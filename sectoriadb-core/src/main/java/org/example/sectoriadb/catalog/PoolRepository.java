package org.example.sectoriadb.catalog;


import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Storage pools (S3 buckets). Pool names are unique. Every method is atomic: either all of its changes
 * are committed, or none.
 */
public interface PoolRepository {

    /** Upsert by id. @throws IllegalArgumentException if another pool already has this name */
    PoolEntity save(PoolEntity entity);

    Optional<PoolEntity> findById(String id);

    Optional<PoolEntity> findByName(String name);

    /** All pools; the number of pools is small (ListBuckets, shell). */
    List<PoolEntity> findAll();

    boolean existsById(String id);

    boolean existsByName(String name);

    long count();

    /** Removes the pool record only. */
    void delete(PoolEntity entity);

    /**
     * Read-modify-write of one pool in a single transaction (no lost updates between concurrent ACL / policy
     * changes). Empty if there is no such pool.
     */
    Optional<PoolEntity> updateByName(String name, Consumer<PoolEntity> mutator);

    /**
     * S3 DeleteBucket: verifies that the bucket has no objects, discards its open multipart uploads, then in one short
     * transaction removes the pool and its blob records (the bucket is gone from here on) and records a purge; the rest
     * (every manifest, live shell-stored, superseded or deleted, of those blobs and of the pool, and the pool's whole
     * chunk index: entries, collection queue, orphan records) is removed in transactions of about a thousand rows each,
     * so that a large bucket never holds the single writer slot for long. A crash in between is finished by
     * {@link #finishDeletions()}. The caller deletes the blob files afterwards.
     *
     * @return the removed blob records
     * @throws IllegalStateException {@code BucketNotEmpty: ...} if the {@code objects} index has entries for the bucket
     * @throws IllegalArgumentException if the pool does not exist
     */
    List<BlobFileEntity> deleteBucket(String poolId);

    /**
     * Completes bucket deletions that a crash interrupted: the pool is gone but rows of its chunk index, manifests or
     * tombstones are still being removed. Returns the number of deletions it found. Cheap when there are none.
     */
    int finishDeletions();
}
