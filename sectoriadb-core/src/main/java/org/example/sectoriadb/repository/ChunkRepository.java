package org.example.sectoriadb.repository;

import org.example.sectoriadb.model.ChunkEntry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read side of the pool-wide chunk index (the writes happen inside the manifest transactions, see
 * {@link ManifestRepository#commitObject}): {@code (poolId, chunkKey) -> ChunkEntry}, the secondary index by blob, the
 * collection queue and the orphan records. Every method is one read transaction on a consistent snapshot.
 */
public interface ChunkRepository {

    Optional<ChunkEntry> find(String poolId, long chunkKey);

    /**
     * Batch lookup in ONE read transaction: the entries of the distinct keys that exist (absent keys are not in the
     * map). Used per window of a request, never per chunk.
     */
    Map<Long, ChunkEntry> findAll(String poolId, long[] chunkKeys);

    /** Chunks stored in the blob according to {@code chunks_by_blob} (referenced or not): a prefix range count. */
    long countByBlob(String blobId);

    /** Of those, the ones with at least one reference. */
    long countReferencedByBlob(String poolId, String blobId);

    /** Index-wide numbers, each an O(1) read of a tree size or a maintained counter. */
    Stats stats();

    /** The first {@code limit} rows of the collection queue (zero-reference chunks), oldest key order. */
    List<GcRow> gcQueue(int limit);

    /** The first {@code limit} orphan records. */
    List<Orphan> orphans(int limit);

    /**
     * @param chunks       entries in the index
     * @param refs         sum of all reference counts
     * @param gcQueue      zero-reference chunks waiting for the collector
     * @param orphans      physical copies not referenced by the index
     */
    record Stats(long chunks, long refs, long gcQueue, long orphans) {
    }

    /** A zero-reference chunk: it became garbage in transaction {@code txId} at {@code queuedAtMillis}. */
    record GcRow(String poolId, long chunkKey, long txId, long queuedAtMillis) {
    }

    /** A copy of {@code chunkKey} in {@code blobId} that the index does not point to. */
    record Orphan(String blobId, long chunkKey, long recordedAtMillis) {
    }

    /**
     * The chunk placement carried by a commit is not usable: unknown chunk, blob replaced or deleted, entry
     * collected after the upload deduplicated against it, or two different chunks under one key. Nothing was
     * written; the upload can be retried (it is not a data error).
     */
    class ChunkPlacementException extends IllegalStateException {

        public enum Reason {
            /** the chunk is not in the index and the commit carries no placement for it */
            UNKNOWN_CHUNK,
            /** the upload deduplicated against an entry that has been collected since */
            COLLECTED,
            /** the blob that received the chunk was replaced by a resize or deleted: re-pointing may fix it */
            BLOB_GONE,
            /** the same key already names a chunk with another length / CRC */
            COLLISION
        }

        private final Reason reason;

        public ChunkPlacementException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
