package org.example.sectoriadb.repository;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.PoolEntity;

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
     * S3 DeleteBucket in one transaction: verifies that the bucket has no objects, then removes the pool, its blob
     * records and every manifest (live shell-stored, superseded or deleted) of those blobs and of the pool.
     * The caller deletes the blob files after the commit.
     *
     * @return the removed blob records
     * @throws IllegalStateException {@code BucketNotEmpty: ...} if the {@code objects} index has entries for the bucket
     * @throws IllegalArgumentException if the pool does not exist
     */
    List<BlobFileEntity> deleteBucket(String poolId);
}
