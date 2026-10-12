package org.example.sectoriadb.storage;

import org.example.sectoriadb.audit.OperationLogService;
import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.ChunkRepository;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PlacedChunk;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.catalog.UploadHold;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.MultiDigest;
import org.example.sectoriadb.checksum.MultiDigestInputStream;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.engine.blob.SmallObjectBlob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The write side of object storage: stages the bytes of an upload into the blobs and commits the manifest.
 *
 * <p>Ordering rule of the whole write path: <b>blob data first, manifest commit after</b>. The data is on disk (forced)
 * before any metastore transaction mentions it, so a crash in between leaves orphan chunks / an ACTIVE small record that
 * no manifest points to (garbage for the collector) and never a manifest pointing to missing data.
 */
public class ObjectWriter {

    private static final Logger log = LoggerFactory.getLogger(ObjectWriter.class);

    private final ManifestRepository manifestRepo;
    private final BlobService blobService;
    private final HashTableCache cache;
    private final BlobGate blobGate;
    private final SmallBlobCache smallCache;
    private final ChunkStore chunkStore;
    private final OperationLogService opLog;
    private final StorageProperties props;

    public ObjectWriter(ManifestRepository manifestRepo, BlobService blobService, HashTableCache cache, BlobGate blobGate,
                        SmallBlobCache smallCache, ChunkStore chunkStore, OperationLogService opLog,
                        StorageProperties props) {
        this.manifestRepo = manifestRepo;
        this.blobService  = blobService;
        this.cache        = cache;
        this.blobGate     = blobGate;
        this.smallCache   = smallCache;
        this.chunkStore   = chunkStore;
        this.opLog        = opLog;
        this.props        = props;
    }


    // ── Store ─────────────────────────────────────────────────────────────────

    /**
     * Shell store: writes the file into the pool and registers its manifest by id (no bucket / key, so it is not an
     * S3 object). The data is written first, the manifest saved after.
     */
    public ManifestEntity store(Path filePath, PoolEntity pool) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + filePath.toAbsolutePath());
        }
        MultiDigest digest = new MultiDigest(false, EnumSet.of(ChecksumAlgorithm.CRC32C));
        try (InputStream in = new MultiDigestInputStream(Files.newInputStream(filePath), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        ManifestEntity entity = stage(filePath, pool, digest.encoded(ChecksumAlgorithm.CRC32C));
        committing(entity, () -> withRedirects(entity, () -> manifestRepo.saveNew(entity)));
        logStored(entity);
        return entity;
    }

    /**
     * Writes the bytes of a file to the pool and returns the manifest that describes them, NOT yet persisted.
     * <p>
     * Ordering rule of the whole write path: <b>blob data first, manifest commit after</b>. The data is on disk
     * (forced) before any metastore transaction mentions it, so a crash in between leaves orphan chunks / an ACTIVE
     * small record that no manifest points to (garbage for the GC stage) and never a manifest pointing to missing data.
     */
    private ManifestEntity stage(Path filePath, PoolEntity pool, String crc32c) throws IOException {
        long t0 = System.currentTimeMillis();
        log.info("Storing file: {} → pool '{}'", filePath.toAbsolutePath(), pool.getName());

        long size = Files.size(filePath);
        if (size == 0) {
            return newManifest(filePath, pool, StorageKind.EMPTY, crc32c);   // no blob, no chunks
        }
        if (size < props.getDefaultChunkSize() && size <= SmallObjectBlob.MAX_RECORD_DATA) {
            return stageSmall(filePath, (int) size, pool, crc32c, t0);
        }
        return stageChunked(filePath, pool, crc32c, t0);
    }

    /** Object smaller than the chunk size: stored whole as one record of a small-object blob (appended and forced). */
    private ManifestEntity stageSmall(Path filePath, int size, PoolEntity pool, String crc32c, long t0) throws IOException {
        byte[] data = Files.readAllBytes(filePath);
        if (data.length != size) {
            throw new IOException("File changed while being stored: expected " + size + " bytes, read " + data.length);
        }
        ManifestEntity entity = newManifest(filePath, pool, StorageKind.SMALL, crc32c);
        int ownerTag = entity.getId().hashCode();
        SmallObjectBlob.Location loc = null;
        BlobFileEntity blobEntity = null;
        // the record is written before the manifest exists: the hold keeps compaction from dropping it as "unreferenced"
        UploadHold hold = chunkStore.gate().enter(pool.getId());
        try {
            for (int attempt = 0; attempt < 8 && loc == null; attempt++) {
                blobEntity = blobService.chooseSmallBlobForWrite(pool, data.length);
                try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(blobEntity)) {
                    loc = h.get().append(data, ownerTag);   // null: filled up meanwhile, choose again
                }
            }
            if (loc == null) {
                IOException e = new IOException("No small-object blob with room found for pool " + pool.getName());
                opLog.failure("STORE", null, filePath.getFileName().toString(), e, System.currentTimeMillis() - t0);
                throw e;
            }
        } catch (IOException | RuntimeException | Error e) {
            hold.release();
            throw e;
        }
        entity.setUploadHold(hold);
        entity.setSmallBlob(blobEntity);
        entity.setSmallOffset(loc.offset());
        entity.setSmallLength(loc.length());
        entity.setSmallCrc32c(loc.crc32c());
        entity.setTotalBytes(size);
        return entity;
    }

    private ManifestEntity newManifest(Path filePath, PoolEntity pool, StorageKind kind, String crc32c) {
        ManifestEntity entity = new ManifestEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setStorageKind(kind);
        entity.setCrc32c(crc32c);
        entity.setPoolId(pool.getId());
        entity.setSourceFileName(filePath.getFileName().toString());
        entity.setCreatedAt(Instant.now());
        entity.setDeleted(false);
        return entity;
    }

    private ManifestEntity stageChunked(Path filePath, PoolEntity pool, String crc32c, long t0) throws IOException {
        ChunkStore.Staged staged;
        try {
            staged = chunkStore.stage(filePath, pool);
        } catch (IOException e) {
            opLog.failure("STORE", null, filePath.getFileName().toString(), e,
                    System.currentTimeMillis() - t0);
            throw e;
        }
        ManifestEntity entity = newManifest(filePath, pool, StorageKind.CHUNKED, crc32c);
        entity.setSourceFileName(filePath.getFileName().toString());
        entity.setChunkSize(staged.chunkSize());
        entity.setTotalChunks(staged.keys().length);
        entity.setTotalBytes(staged.totalBytes());
        entity.setChunkKeyArray(staged.keys());
        entity.setLastChunkSize(staged.lastChunkSize());
        entity.setStagedChunks(staged.placed());
        entity.setUploadHold(staged.hold());
        return entity;
    }

    /** Audit entry for a stored manifest; written after the commit, failures never affect the operation. */
    private void logStored(ManifestEntity entity) {
        long ms = Math.max(0, System.currentTimeMillis() - entity.getCreatedAt().toEpochMilli());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("poolId", entity.getPoolId());
        details.put("totalBytes", entity.getTotalBytes());
        details.put("storageKind", entity.getStorageKind().name());
        String blobId = entity.getSmallBlobId();
        if (blobId != null) details.put("blobFileId", blobId);
        if (entity.getStorageKind() == StorageKind.SMALL) details.put("offset", entity.getSmallOffset());
        if (entity.getStorageKind() == StorageKind.CHUNKED) {
            // a compact summary only: the chunk keys live in the manifest, a per-PUT key list made the log grow by
            // about 1 MB per GiB stored and was written under the log's global write lock
            details.put("totalChunks", entity.getTotalChunks());
        }
        opLog.success("STORE", entity.getId(), entity.getSourceFileName(), details, ms);
        log.info("Stored: id={} file={} size={} kind={} blob={}", entity.getId(), entity.getSourceFileName(),
                entity.getTotalBytes(), entity.getStorageKind(), blobId);
    }

    /** The directory uploads are spooled to ({@code sectoriadb.upload-tmp-dir}, default {@code <data-dir>/.tmp}); created on demand. */
    public Path uploadTmpDir() {
        Path dir = props.uploadTmpPath();
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── S3 Store (InputStream) ────────────────────────────────────────────────

    /**
     * Writes an object uploaded via the S3 REST API and commits it: {@link #stageStream} followed by
     * {@link #commitObject}. Callers that still have attributes to set (ACL, user metadata, ETag of a multipart
     * upload) call the two steps themselves, so the manifest is committed once, complete.
     */
    public ManifestEntity storeStream(InputStream in, PoolEntity pool,
                                      String objectKey, String contentType) throws IOException {
        return storeStream(in, pool, objectKey, contentType, UploadChecksums.none());
    }

    public ManifestEntity storeStream(InputStream in, PoolEntity pool, String objectKey, String contentType,
                                      UploadChecksums declared) throws IOException {
        return commitObject(stageStream(in, pool, objectKey, contentType, declared));
    }

    /**
     * Step 1 of an S3 PUT: writes the stream to a temp file while computing MD5 (the ETag), the always-on CRC32C and
     * the client's additional checksum in a single pass. The client-declared Content-MD5 / checksum is verified BEFORE
     * anything is written to a blob: on a mismatch the temp file is removed and neither a blob record, nor chunks,
     * nor a manifest exist. Then the data goes into the blobs and a manifest with key, ETag, content type and
     * checksums is returned, <b>not yet visible</b>: the object appears only with {@link #commitObject}.
     * If the process dies (or the caller abandons the manifest) between the steps, the written data is garbage
     * that no manifest references.
     */
    public ManifestEntity stageStream(InputStream in, PoolEntity pool, String objectKey, String contentType,
                                      UploadChecksums declared) throws IOException {
        Path tmp = Files.createTempFile(uploadTmpDir(), "sectoriadb-upload-", ".tmp");
        try {
            MultiDigest digest = spool(in, tmp, declared);
            ManifestEntity entity = stageDigested(tmp, pool, digest, declared.algorithm());
            entity.setObjectKey(objectKey);
            entity.setBucketName(pool.getName());
            entity.setContentType(contentType != null ? contentType : "application/octet-stream");
            return entity;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Copies the stream to {@code tmp} while computing MD5, the always-on CRC32C and the client's additional checksum, and
     * verifies what the client declared, BEFORE anything is written to a blob.
     */
    private MultiDigest spool(InputStream in, Path tmp, UploadChecksums declared) throws IOException {
        EnumSet<ChecksumAlgorithm> algorithms = EnumSet.of(ChecksumAlgorithm.CRC32C);
        if (declared.algorithm() != null) algorithms.add(declared.algorithm());
        MultiDigest digest = new MultiDigest(true, algorithms);
        try (MultiDigestInputStream dis = new MultiDigestInputStream(in, digest)) {
            Files.copy(dis, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        digest.finish();
        declared.verify(digest);   // throws ChecksumMismatchException: nothing has been written anywhere yet
        return digest;
    }

    /** Stores the spooled file and fills in ETag, CRC32C and the additional checksum from the finished digest. */
    private ManifestEntity stageDigested(Path tmp, PoolEntity pool, MultiDigest digest, ChecksumAlgorithm additional)
            throws IOException {
        ManifestEntity entity = stage(tmp, pool, digest.encoded(ChecksumAlgorithm.CRC32C));
        entity.setEtag("\"" + HexFormat.of().formatHex(digest.md5()) + "\"");
        if (additional != null) {
            entity.setChecksumAlgorithm(additional);
            entity.setChecksumValue(digest.encoded(additional));
            entity.setChecksumType(ChecksumType.FULL_OBJECT);
        }
        return entity;
    }

    // ── Multipart parts ───────────────────────────────────────────────────────

    /**
     * Stages the body of an UploadPart like an object body (same chunking, deduplication, placement and small-object
     * rules) but as a hidden part manifest: no bucket and no key, so it can never be listed or read as an object. The
     * caller sets the upload id and part number and commits it with {@link #commitStaged}; the part is visible only then.
     */
    public ManifestEntity stagePart(InputStream in, PoolEntity pool, UploadChecksums declared) throws IOException {
        Path tmp = Files.createTempFile(uploadTmpDir(), "sectoriadb-part-", ".tmp");
        try {
            return stageDigested(tmp, pool, spool(in, tmp, declared), declared.algorithm());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * As {@link #stagePart} for a part whose bytes are already in {@code file} (UploadPartCopy), with the digest the caller
     * computed while writing it (finished, MD5 and CRC32C included).
     */
    public ManifestEntity stagePartFile(Path file, PoolEntity pool, MultiDigest digest, ChecksumAlgorithm additional)
            throws IOException {
        return stageDigested(file, pool, digest.finish(), additional);
    }

    /**
     * Runs a commit transaction for a staged manifest with the write path's guards: the garbage collector hold is checked
     * and released around it, the chunks are forced before the transaction, placements are re-pointed after a resize.
     * {@code commit} must read {@link ManifestEntity#getStagedChunks()} (and the entity) lazily, at call time.
     */
    public <T> T commitStaged(ManifestEntity entity, Supplier<T> commit) {
        return committing(entity, () -> withRedirects(entity, commit));
    }

    /**
     * Step 2 of an S3 PUT: ONE metastore transaction saves the manifest with all its fields, points
     * {@code objects[(bucket, key)]} at it and retires the version it replaces. The data of the replaced version is
     * NOT touched here: the chunks wait in {@code chunk_gc} and the record of a replaced small object stays ACTIVE until
     * the garbage collector marks it, after the grace period (a reader that holds the old version must still be able to
     * read it). The upload's hold on the garbage collector is released when the commit has finished, whatever its result.
     *
     * @throws org.example.sectoriadb.catalog.ManifestRepository.PoolNotFoundException if the bucket vanished
     * @throws org.example.sectoriadb.catalog.ChunkRepository.ChunkPlacementException COLLECTED (retry the upload) if
     *         the chunks were collected meanwhile
     */
    public ManifestEntity commitObject(ManifestEntity entity) {
        return commitObject(entity, ManifestRepository.CommitPrecondition.NONE);
    }

    /** As {@link #commitObject(ManifestEntity)} with a precondition checked inside the commit transaction (conditional writes). */
    public ManifestEntity commitObject(ManifestEntity entity, ManifestRepository.CommitPrecondition precondition) {
        ManifestRepository.CommitResult r = committing(entity,
                () -> withRedirects(entity, () -> manifestRepo.commitObject(entity, precondition)));
        logStored(r.current());
        return r.current();
    }

    /**
     * Gives up a staged upload that will not be committed (a later step failed): releases its hold on the garbage
     * collector; the data it wrote becomes garbage for the sweep. Safe to call after a commit and more than once.
     */
    public void abortStaged(ManifestEntity entity) {
        UploadHold hold = entity == null ? null : entity.getUploadHold();
        if (hold != null) {
            hold.release();
            entity.setUploadHold(null);
        }
    }

    private <T> T committing(ManifestEntity entity, Supplier<T> commit) {
        UploadHold hold = entity.getUploadHold();
        try {
            if (hold != null && !hold.beginCommit()) {
                throw new ChunkRepository.ChunkPlacementException(
                        ChunkRepository.ChunkPlacementException.Reason.COLLECTED,
                        "The upload waited too long between writing its data and committing: the garbage collector"
                                + " revoked its hold and may have freed the data, the upload must be retried");
            }
            // data before metadata: the chunks of this upload are forced (they were written without an fsync)
            // before the transaction that first references them
            try {
                chunkStore.barrier(entity.getStagedChunks());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return commit.get();
        } finally {
            if (hold != null) {
                hold.release();
                entity.setUploadHold(null);
            }
        }
    }

    /**
     * A resize may have replaced a blob between the moment this upload placed chunks into it and now: the data was
     * migrated with the rest of the blob, so the placement is re-pointed at the replacement.
     */
    private <T> T withRedirects(ManifestEntity entity, Supplier<T> commit) {
        for (int attempt = 0; ; attempt++) {
            redirectStagedChunks(entity);
            try {
                return commit.get();
            } catch (ChunkRepository.ChunkPlacementException e) {
                // BLOB_GONE right after a resize committed: the replacement is registered a moment later, look again
                if (e.reason() != ChunkRepository.ChunkPlacementException.Reason.BLOB_GONE
                        || attempt >= 50) {
                    throw e;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void redirectStagedChunks(ManifestEntity entity) {
        List<PlacedChunk> staged = entity.getStagedChunks();
        if (staged.isEmpty()) return;
        List<PlacedChunk> fixed = new ArrayList<>(staged.size());
        for (PlacedChunk p : staged) {
            String now = blobGate.resolveRedirect(p.blobId());
            fixed.add(now.equals(p.blobId()) ? p : p.withBlob(now));
        }
        entity.setStagedChunks(fixed);
    }
}
