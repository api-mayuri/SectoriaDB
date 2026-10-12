package org.example.sectoriadb.catalog;

import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The state of multipart uploads (doc 13): the uploads, their parts and the hidden part manifests, all in the transactional
 * metastore. Every method is one atomic transaction; a part commits together with its references, Complete turns the parts
 * into the object manifest without touching any data.
 */
public interface MultipartRepository {

    /**
     * Records a new upload ({@code OPEN}). The caller fills in everything but {@code uploadId} (a fresh UUID is assigned),
     * {@code poolId} comes from the bucket.
     *
     * @throws ManifestRepository.PoolNotFoundException if the bucket's pool is gone
     */
    MultipartUpload create(MultipartUpload upload);

    /** The upload in any state (also COMPLETED / ABORTED while their record is kept). */
    Optional<MultipartUpload> find(String uploadId);

    // ---- parts --------------------------------------------------------------------------------------------

    /**
     * Commits an uploaded part in ONE transaction: the hidden part manifest (with the chunk references of its staged
     * chunks), the {@code mpu_parts} row, and the retirement of the part this number replaces (its references are released
     * and it goes to the garbage queue). A concurrent upload of the same number is serialized; the last commit wins as a
     * whole, the loser's manifest ends up retired.
     *
     * @param manifest the staged part: {@code multipartUploadId} and {@code partNumber} are set by the caller
     * @throws MultipartException NO_SUCH_UPLOAD if the upload is not OPEN or belongs to another bucket / key
     * @throws ChunkRepository.ChunkPlacementException as {@link ManifestRepository#commitObject}
     */
    PutPartResult putPart(String bucket, String key, ManifestEntity manifest, MultipartPart row);

    /** @param replaced the part this one replaced, if any (already retired) */
    record PutPartResult(MultipartPart part, Optional<MultipartPart> replaced) {
    }

    /**
     * One page of ListParts from a single snapshot.
     *
     * @param afterPartNumber exclusive lower bound
     * @throws MultipartException NO_SUCH_UPLOAD if the upload is not OPEN or belongs to another bucket / key
     */
    PartsPage listParts(String uploadId, String bucket, String key, int afterPartNumber, int maxParts);

    record PartsPage(MultipartUpload upload, List<MultipartPart> parts, boolean truncated, int nextPartNumberMarker) {
    }

    // ---- listing uploads -----------------------------------------------------------------------------------

    /**
     * ListMultipartUploads: the OPEN uploads of a bucket ordered by key, then by initiation time, with S3's prefix,
     * delimiter and marker semantics.
     *
     * @param delimiter      null or empty for a flat listing
     * @param keyMarker      null/empty: from the start
     * @param uploadIdMarker only meaningful with a key marker: continue after that upload of that key
     */
    UploadListing listUploads(String bucket, String prefix, String delimiter, String keyMarker, String uploadIdMarker,
                              int maxUploads);

    record UploadListing(List<MultipartUpload> uploads, List<String> commonPrefixes, boolean truncated,
                         String nextKeyMarker, String nextUploadIdMarker) {
    }

    /** True if the bucket has at least one OPEN upload. */
    boolean hasOpenUploads(String bucket);

    // ---- complete / abort ----------------------------------------------------------------------------------

    /**
     * CompleteMultipartUpload in ONE transaction, without reading or writing any object data: validates the part list,
     * builds the MULTIPART manifest from the part manifests (their references move to it), commits it as the current version
     * of the key, retires the parts that were not listed and marks the upload COMPLETED. A repeated Complete with the same
     * list returns the stored result.
     *
     * @throws MultipartException see {@link MultipartException.Kind}
     * @throws ManifestRepository.CommitPreconditionException (nothing changes, the upload stays OPEN)
     */
    CompleteResult complete(CompleteCommand command);

    /**
     * @param parts              in the order the client listed them
     * @param wholeObjectHeaders the {@code x-amz-checksum-*} values of the Complete request
     */
    record CompleteCommand(String uploadId, String bucket, String key, List<RequestedPart> parts,
                           ManifestRepository.CommitPrecondition precondition,
                           Map<ChecksumAlgorithm, String> wholeObjectHeaders, long now) {
    }

    /**
     * @param etag     as sent by the client, quotes optional; null: not given
     * @param checksums per-part checksum values given in the request XML
     */
    record RequestedPart(int partNumber, String etag, Map<ChecksumAlgorithm, String> checksums) {
    }

    /**
     * @param etag     the multipart ETag including quotes
     * @param replayed true if the upload was already COMPLETED and this is the stored answer
     */
    record CompleteResult(String bucket, String key, String etag, String manifestId, ChecksumAlgorithm checksumAlgorithm,
                          String checksumValue, ChecksumType checksumType, boolean replayed,
                          Optional<ManifestEntity> superseded) {
    }

    /**
     * AbortMultipartUpload in one transaction: every part manifest is retired (references released, garbage queue), the
     * upload is marked ABORTED.
     *
     * @throws MultipartException NO_SUCH_UPLOAD if the upload is not OPEN or belongs to another bucket / key
     */
    int abort(String uploadId, String bucket, String key, long now);

    // ---- expiry --------------------------------------------------------------------------------------------

    /**
     * One sweep: OPEN uploads without a part (or initiation) for {@code staleAfterMillis} are aborted, one transaction
     * each; COMPLETED / ABORTED records older than {@code retentionMillis} are removed. One bad upload never stops the sweep.
     */
    CleanupReport cleanup(long now, long staleAfterMillis, long retentionMillis);

    record CleanupReport(int expired, int partsRetired, int recordsRemoved, int failed) {
    }

    // ---- diagnostics ---------------------------------------------------------------------------------------

    /** Number of uploads per state, for the status gauges and tests. */
    Map<MultipartUpload.State, Long> countByState();
}
