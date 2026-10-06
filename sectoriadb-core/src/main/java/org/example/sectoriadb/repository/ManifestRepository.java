package org.example.sectoriadb.repository;

import org.example.sectoriadb.model.ManifestEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Object manifests and the {@code (bucket, key) -> current manifest} index. Every method is one atomic
 * transaction; reads see a consistent snapshot. Nothing here reads more than the entries it returns, except the
 * methods documented as administrative scans.
 */
public interface ManifestRepository {

    // ---- plain records -------------------------------------------------------------------------------------

    /**
     * Upsert of the manifest record (and its blob index). Does NOT change which version of an object is current:
     * use {@link #commitObject} for that. Meant for shell-stored files and for metadata maintenance.
     */
    ManifestEntity save(ManifestEntity entity);

    /** The manifest with resolved blob references; also finds superseded / deleted manifests until they are collected. */
    Optional<ManifestEntity> findById(String id);

    boolean existsById(String id);

    /** Number of manifest records (live and waiting for GC). */
    long count();

    // ---- S3 objects ----------------------------------------------------------------------------------------

    /** The current version of {@code bucket/key}: two B+tree lookups. */
    Optional<ManifestEntity> findCurrent(String bucketName, String objectKey);

    boolean existsCurrent(String bucketName, String objectKey);

    /**
     * Atomic object commit. In one transaction: saves the complete manifest, makes {@code objects[(bucket, key)]}
     * point at it, and retires the version it replaces (marked deleted and put on the GC queue). Two concurrent
     * commits of one key are serialized, the last one wins, and the loser's manifest ends up on the GC queue.
     *
     * @throws PoolNotFoundException if the pool was deleted meanwhile
     */
    CommitResult commitObject(ManifestEntity entity);

    /** DeleteObject: unlinks the current version and queues it for GC, in one transaction. Empty if there was none. */
    Optional<ManifestEntity> deleteObject(String bucketName, String objectKey);

    /**
     * Retires one manifest by id (shell {@code rm}); if it is the current version of an object it is unlinked from
     * the {@code objects} index in the same transaction. Empty if the manifest does not exist or is already retired.
     */
    Optional<ManifestEntity> deleteManifest(String manifestId);

    /**
     * Read-modify-write of the current version of an object in one transaction (ACL changes): the mutator cannot
     * overwrite a version that a concurrent PUT replaced meanwhile. Empty if the object does not exist.
     */
    Optional<ManifestEntity> updateCurrent(String bucketName, String objectKey, Consumer<ManifestEntity> mutator);

    /**
     * ListObjects: a range scan over {@code objects} from {@code max(prefix, startAfter)} on, returning at most
     * {@code maxKeys} entries (keys and common prefixes together). With a delimiter, whole common-prefix ranges are
     * skipped by seeking past them.
     *
     * @param delimiter  null or empty for a flat listing
     * @param startAfter exclusive lower bound (marker / continuation token / start-after), may be null
     */
    ObjectListing listObjects(String bucketName, String prefix, String delimiter, String startAfter, int maxKeys);

    /** True if the {@code objects} index has at least one entry for the bucket. */
    boolean hasObjects(String bucketName);

    /** Object count and total size of a bucket: scans the bucket's range (administrative). */
    BucketStats countAndSize(String bucketName);

    /**
     * Number and total size of the current object versions of all buckets. Maintained inside the commit
     * transactions ({@code commitObject}, {@code deleteObject}, {@code deleteManifest}), so this is a single key
     * lookup, not a scan. (The very first call on a store written by an older version counts the objects once.)
     */
    BucketStats totals();

    // ---- blob / admin queries -------------------------------------------------------------------------------

    /** All live manifests, including the ones stored from the shell (administrative full scan). */
    List<ManifestEntity> findAllLive();

    /** Manifests referencing the blob (cuckoo or small-object), via the {@code manifests_by_blob} index. */
    List<ManifestEntity> findByBlobId(String blobId, boolean liveOnly);

    long countLiveByBlobId(String blobId);

    // ---- garbage queue ------------------------------------------------------------------------------------

    /** Number of manifests waiting for the garbage collector. */
    long gcQueueSize();

    /** The oldest queued manifests first. */
    List<GcEntry> gcQueue(int limit);

    record CommitResult(ManifestEntity current, Optional<ManifestEntity> superseded) {
    }

    /** A manifest on the GC queue: it was retired by the transaction {@code txId}. */
    record GcEntry(long txId, String manifestId) {
    }

    record BucketStats(long objects, long bytes) {
    }

    /** What ListObjects needs per key; no chunk keys are decoded. */
    record ObjectSummary(String key, String manifestId, String etag, long size, Instant lastModified) {
    }

    /**
     * @param nextMarker the last returned key or common prefix: the marker for the next page
     */
    record ObjectListing(List<ObjectSummary> objects, List<String> commonPrefixes, boolean truncated,
                         String nextMarker) {
    }

    /** The pool of a manifest being committed no longer exists (the bucket was deleted concurrently). */
    class PoolNotFoundException extends IllegalStateException {
        public PoolNotFoundException(String poolId) {
            super("Pool not found: " + poolId);
        }
    }
}
