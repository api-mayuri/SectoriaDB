package org.example.sectoriadb.repository;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The metastore side of garbage collection (doc 10): the exclusive transactions that free chunk slots, drop orphan and
 * tombstone rows and swap small-object blobs. Every method that frees something runs ONE exclusive write transaction
 * (the single-writer slot, which excludes every commit, resize commit and other collector step), re-reads what it is
 * about to remove inside it and frees the physical slot through the {@link SlotFreer} callback, which the service layer
 * implements on the cuckoo tables. Nothing here knows about files.
 */
public interface GcRepository {

    // ---- chunk_gc ---------------------------------------------------------------------------------------------

    /**
     * Zero-reference chunks whose newest {@code chunk_gc} row is at least as old as {@code queuedBeforeMillis}
     * (grace period), in queue order, at most {@code limit}. One key once, whatever its number of rows.
     *
     * @param poolId only this pool, or null for all
     */
    List<ChunkRef> dueChunks(String poolId, long queuedBeforeMillis, int limit);

    /**
     * One exclusive transaction for a batch of chunks, each handled by the protocol of doc 09 ("contract for stage 10"):
     * <ol>
     *   <li>no index entry: the queue rows are dropped;</li>
     *   <li>reference count not zero (revived): the queue rows are dropped, the entry stays;</li>
     *   <li>newest queue row younger than the cutoff (the chunk went through zero again after the caller looked): left alone;</li>
     *   <li>otherwise the slot is freed through {@code freer}; unless the freer defers (frozen / replaced blob), the
     *       {@code chunks}, {@code chunks_by_blob} and {@code chunk_gc} rows are deleted.</li>
     * </ol>
     * A failing slot free ends the batch early: what was done before it is committed, the failing chunk is untouched.
     * The caller calls {@code ChunkStore.forget} for {@link ChunkBatchResult#removed()}.
     */
    ChunkBatchResult collectChunks(List<ChunkRef> batch, long queuedBeforeMillis, SlotFreer freer);

    record ChunkRef(String poolId, long chunkKey) {
    }

    /**
     * @param freed    slots physically freed
     * @param bytes    bytes of those chunks
     * @param absent   entries removed whose slot was not in the table (an earlier pass freed it, crash between slot and commit)
     * @param revived  queue rows dropped because the chunk is referenced again
     * @param gone     queue rows dropped because the entry no longer exists
     * @param tooNew   skipped, went through zero again after the caller looked
     * @param deferred skipped, blob frozen / replaced
     * @param removed  keys whose index entry was deleted (to {@code forget})
     * @param error    the IO failure that ended the batch early, or null
     */
    record ChunkBatchResult(int freed, long bytes, int absent, int revived, int gone, int tooNew, int deferred,
                            List<ChunkRef> removed, IOException error) {
    }

    // ---- stray copies and orphans -----------------------------------------------------------------------------

    /** Keys among {@code keys} (physically in {@code blobId}) for which no index entry points at that blob. */
    long[] unindexed(String blobId, long[] keys);

    /** Orphan records (copies of chunks that the index holds in another blob), at most {@code limit}. */
    List<ChunkRepository.Orphan> orphans(int limit);

    /**
     * One exclusive transaction: for each key re-read the index; if an entry points at {@code blobId} the copy is not
     * stray (its orphan record, if any, is dropped), otherwise the slot is freed through {@code freer} and the orphan
     * record dropped. The CALLER must hold the upload gate of the blob's pool (see {@code UploadGate}): without it an
     * upload that is about to commit may be relying on a copy this method would free.
     */
    StrayBatchResult freeStrays(String blobId, long[] keys, SlotFreer freer);

    /** @param freed slots freed; @param bytes their bytes; @param notStray keys that turned out to be indexed; @param deferred left for later. */
    record StrayBatchResult(int freed, long bytes, int notStray, int deferred, IOException error) {
    }

    // ---- tombstones -------------------------------------------------------------------------------------------

    /** Retired manifests waiting longer than the grace period, in queue order. Rows from before timestamps count as due. */
    List<Tombstone> dueTombstones(long queuedBeforeMillis, int limit);

    /**
     * One exclusive transaction: removes the manifest records and the queue rows of the tombstones (a manifest that is
     * live again or already gone only loses its queue row). Returns the number of manifests removed.
     */
    int deleteTombstones(List<Tombstone> tombstones);

    record Tombstone(long txId, String manifestId, long queuedAtMillis) {
    }

    // ---- small-object compaction ------------------------------------------------------------------------------

    /** Where the record of a live small object was copied to. */
    record SmallMove(long oldOffset, long newOffset, int length, int crc32c) {
    }

    /**
     * ONE exclusive transaction that finishes a compaction: every live manifest of {@code oldBlobId} must be in
     * {@code moves} (keyed by manifest id) and still be at its old offset; each is repointed to {@code newBlobId} at its
     * new offset ({@code manifests_by_blob} follows), the dead manifests that still name the old blob are removed with
     * their queue rows, and the old blob record is deleted. Nothing is changed (and the result says why) if a live
     * manifest has no move or changed; a manifest that was retired meanwhile is just not repointed.
     */
    CompactionSwap swapSmallBlob(String oldBlobId, String newBlobId, Map<String, SmallMove> moves);

    /**
     * @param swapped   false if nothing was changed ({@code reason} says why)
     * @param repointed manifests now at the new blob
     * @param retired   manifest ids that were retired by the time of the swap: their copy in the new blob is dead
     */
    record CompactionSwap(boolean swapped, String reason, int repointed, List<String> retired) {
    }

    /** What a collector step has to free: the physical slot of a chunk in a blob. */
    @FunctionalInterface
    interface SlotFreer {
        SlotFree free(String poolId, String blobId, long chunkKey) throws IOException;
    }

    /** Result of {@link SlotFreer#free}. */
    record SlotFree(Result result, int bytes) {
        public enum Result {
            /** the slot was ACTIVE and is now free */
            FREED,
            /** there was no such slot (never written, already freed, blob gone) */
            ABSENT,
            /** the blob is frozen or replaced by a resize: leave everything as is, retry later */
            DEFERRED
        }

        public static final SlotFree ABSENT = new SlotFree(Result.ABSENT, 0);
        public static final SlotFree DEFERRED = new SlotFree(Result.DEFERRED, 0);

        public static SlotFree freed(int bytes) {
            return new SlotFree(Result.FREED, bytes);
        }
    }
}
