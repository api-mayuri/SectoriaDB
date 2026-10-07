package org.example.sectoriadb.service;

import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.MultiDigest;
import org.example.sectoriadb.checksum.MultiDigestInputStream;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.StorageKind;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PlacedChunk;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.FileManifest;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.impl.BlobFileWriteService;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.ChunkNotFoundException;
import org.example.sectoriadb.service.impl.DefaultChunkingService;
import org.example.sectoriadb.service.impl.ObjectCorruptedException;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.UUID;
import java.util.zip.CRC32C;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final ManifestRepository manifestRepo;
    private final BlobService blobService;
    private final HashTableCache cache;
    private final SmallBlobCache smallCache;
    private final ChunkStore chunkStore;
    private final OperationLogService opLog;
    private final StorageProperties props;
    private final StorageMetrics metrics;

    public FileStorageService(ManifestRepository manifestRepo, BlobService blobService,
                               HashTableCache cache, SmallBlobCache smallCache, ChunkStore chunkStore,
                               OperationLogService opLog, StorageProperties props) {
        this(manifestRepo, blobService, cache, smallCache, chunkStore, opLog, props, StorageMetrics.NOOP);
    }

    @Autowired
    public FileStorageService(ManifestRepository manifestRepo, BlobService blobService,
                               HashTableCache cache, SmallBlobCache smallCache, ChunkStore chunkStore,
                               OperationLogService opLog, StorageProperties props, StorageMetrics metrics) {
        this.chunkStore   = chunkStore;
        this.metrics      = metrics;
        this.manifestRepo = manifestRepo;
        this.blobService  = blobService;
        this.cache        = cache;
        this.smallCache   = smallCache;
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
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        ManifestEntity entity = stage(filePath, pool, digest.encoded(ChecksumAlgorithm.CRC32C));
        redirectStagedChunks(entity);
        manifestRepo.saveNew(entity);
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
        for (int attempt = 0; attempt < 8 && loc == null; attempt++) {
            blobEntity = blobService.chooseSmallBlobForWrite(pool, data.length);
            loc = smallCache.get(blobEntity).append(data, ownerTag);   // null: filled up meanwhile, choose again
        }
        if (loc == null) {
            IOException e = new IOException("No small-object blob with room found for pool " + pool.getName());
            opLog.failure("STORE", null, filePath.getFileName().toString(), e, System.currentTimeMillis() - t0);
            throw e;
        }
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
        return entity;
    }

    /** Audit entry for a stored manifest; written after the commit, failures never affect the operation. */
    private void logStored(ManifestEntity entity) {
        long ms = Math.max(0, System.currentTimeMillis() - entity.getCreatedAt().toEpochMilli());
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("poolId", entity.getPoolId());
        details.put("totalBytes", entity.getTotalBytes());
        details.put("storageKind", entity.getStorageKind().name());
        String blobId = entity.getSmallBlobId();
        if (blobId != null) details.put("blobFileId", blobId);
        if (entity.getStorageKind() == StorageKind.SMALL) details.put("offset", entity.getSmallOffset());
        if (entity.getStorageKind() == StorageKind.CHUNKED) {
            details.put("totalChunks", entity.getTotalChunks());
            details.put("chunkKeys", entity.parseChunkKeys().stream().map(k -> String.format("%016x", k)).toList());
        }
        opLog.success("STORE", entity.getId(), entity.getSourceFileName(), details, ms);
        log.info("Stored: id={} file={} size={} kind={} blob={}", entity.getId(), entity.getSourceFileName(),
                entity.getTotalBytes(), entity.getStorageKind(), blobId);
    }

    // ── Restore (full) ────────────────────────────────────────────────────────

    public void restore(String manifestId, Path outputPath) throws IOException {
        long t0 = System.currentTimeMillis();
        ManifestEntity entity = getActiveManifest(manifestId);

        log.info("Restoring file: id={} → {}", manifestId, outputPath);
        if (entity.getStorageKind() != StorageKind.CHUNKED) {
            boolean done = false;
            try (OutputStream out = Files.newOutputStream(outputPath)) {
                streamToOutput(entity, out);
                done = true;
            } finally {
                if (!done) Files.deleteIfExists(outputPath);
            }
            opLog.success("RESTORE", manifestId, entity.getSourceFileName(),
                    Map.of("outputPath", outputPath.toString()), System.currentTimeMillis() - t0);
            log.info("Restored: id={} → {}", manifestId, outputPath);
            return;
        }
        long[] keys = entity.chunkKeyArray();
        ChunkStore.Reader chunks = readerOf(entity);

        boolean ok = false;
        try (FileChannel out = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            CRC32C crc = new CRC32C();
            int total = keys.length;
            int lastDecile = -1;
            for (int i = 0; i < total; i++) {
                boolean isLast = (i == total - 1);
                ByteBuffer chunk = chunks.read(i);
                crc.update(chunk.duplicate());
                if (isLast) checkWholeObjectCrc(entity, crc);
                out.write(chunk);

                if (total >= 10) {
                    int decile = (int)(10.0 * (i + 1) / total);
                    if (decile > lastDecile) {
                        lastDecile = decile;
                        log.debug("Restoring {}: {}% ({}/{} chunks)", manifestId, decile * 10, i + 1, total);
                    }
                }
            }
            if (total == 0) checkWholeObjectCrc(entity, crc);
            ok = true;
        } finally {
            if (!ok) Files.deleteIfExists(outputPath);   // never leave a partial / corrupt restore behind
        }

        opLog.success("RESTORE", manifestId, entity.getSourceFileName(),
                Map.of("outputPath", outputPath.toString()),
                System.currentTimeMillis() - t0);
        log.info("Restored: id={} → {}", manifestId, outputPath);
    }

    // ── Restore (byte range) ──────────────────────────────────────────────────

    /**
     * Extracts a byte range from a stored file without reading it entirely.
     * Ideal for media streaming or random access (e.g. serving video segments).
     */
    public void restoreRange(String manifestId, Path outputPath,
                              long startByte, long lengthBytes) throws IOException {
        long t0 = System.currentTimeMillis();
        ManifestEntity entity = getActiveManifest(manifestId);

        if (startByte < 0 || lengthBytes <= 0) {
            throw new IllegalArgumentException("start-byte must be >= 0 and length must be > 0");
        }
        if (startByte + lengthBytes > entity.getTotalBytes()) {
            throw new IllegalArgumentException(String.format(
                    "Range [%d, +%d] exceeds file size %d bytes",
                    startByte, lengthBytes, entity.getTotalBytes()));
        }

        if (entity.getStorageKind() != StorageKind.CHUNKED) {
            try (OutputStream out = Files.newOutputStream(outputPath)) {
                streamRange(entity, out, startByte, lengthBytes);
            }
            opLog.success("RESTORE_RANGE", manifestId, entity.getSourceFileName(),
                    Map.of("startByte", startByte, "length", lengthBytes, "outputPath", outputPath.toString()),
                    System.currentTimeMillis() - t0);
            return;
        }

        int chunkSize    = entity.getChunkSize();
        int totalChunks  = entity.getTotalChunks();
        int firstIdx     = (int)(startByte / chunkSize);
        int lastIdx      = (int)((startByte + lengthBytes - 1) / chunkSize);
        ChunkStore.Reader chunks = readerOf(entity);

        log.info("Restoring range: id={} [{}, +{}] chunks [{},{}]",
                manifestId, startByte, lengthBytes, firstIdx, lastIdx);

        try (FileChannel out = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            for (int idx = firstIdx; idx <= lastIdx; idx++) {
                boolean isFileLast = (idx == totalChunks - 1);
                int actualSize = isFileLast ? entity.getLastChunkSize() : chunkSize;

                ByteBuffer chunk = chunks.read(idx);
                int trimStart = (idx == firstIdx) ? (int)(startByte % chunkSize) : 0;
                long remaining = lengthBytes - written;
                int trimLen    = (int) Math.min(actualSize - trimStart, remaining);

                chunk.position(trimStart).limit(trimStart + trimLen);
                out.write(chunk);
                written += trimLen;
            }
        }

        opLog.success("RESTORE_RANGE", manifestId, entity.getSourceFileName(),
                Map.of("startByte", startByte, "length", lengthBytes, "outputPath", outputPath.toString()),
                System.currentTimeMillis() - t0);
        log.info("Range restored: id={} [{},+{}] → {}", manifestId, startByte, lengthBytes, outputPath);
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
        Path tmp = Files.createTempFile("sectoriadb-upload-", ".tmp");
        try {
            EnumSet<ChecksumAlgorithm> algorithms = EnumSet.of(ChecksumAlgorithm.CRC32C);
            if (declared.algorithm() != null) algorithms.add(declared.algorithm());
            MultiDigest digest = new MultiDigest(true, algorithms);
            try (MultiDigestInputStream dis = new MultiDigestInputStream(in, digest)) {
                Files.copy(dis, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            digest.finish();
            declared.verify(digest);   // throws ChecksumMismatchException: nothing has been written anywhere yet

            String etag = "\"" + HexFormat.of().formatHex(digest.md5()) + "\"";
            ManifestEntity entity = stage(tmp, pool, digest.encoded(ChecksumAlgorithm.CRC32C));
            entity.setObjectKey(objectKey);
            entity.setBucketName(pool.getName());
            entity.setContentType(contentType != null ? contentType : "application/octet-stream");
            entity.setEtag(etag);
            if (declared.algorithm() != null) {
                entity.setChecksumAlgorithm(declared.algorithm());
                entity.setChecksumValue(digest.encoded(declared.algorithm()));
                entity.setChecksumType(ChecksumType.FULL_OBJECT);
            }
            return entity;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Step 2 of an S3 PUT: ONE metastore transaction saves the manifest with all its fields, points
     * {@code objects[(bucket, key)]} at it and retires the version it replaces. Afterwards the data of a replaced
     * small object is marked DELETED (best effort; the chunks of a replaced chunked object stay until GC).
     *
     * @throws org.example.sectoriadb.repository.ManifestRepository.PoolNotFoundException if the bucket vanished
     */
    public ManifestEntity commitObject(ManifestEntity entity) {
        redirectStagedChunks(entity);
        ManifestRepository.CommitResult r = manifestRepo.commitObject(entity);
        r.superseded().ifPresent(old -> releaseData(old, "PUT"));
        logStored(r.current());
        return r.current();
    }

    /**
     * A resize may have replaced a blob between the moment this upload placed chunks into it and now: the data was
     * migrated with the rest of the blob, so the placement is re-pointed at the replacement.
     */
    private void redirectStagedChunks(ManifestEntity entity) {
        List<PlacedChunk> staged = entity.getStagedChunks();
        if (staged.isEmpty()) return;
        List<PlacedChunk> fixed = new java.util.ArrayList<>(staged.size());
        for (PlacedChunk p : staged) {
            String now = cache.resolveRedirect(p.blobId());
            fixed.add(now.equals(p.blobId()) ? p : p.withBlob(now));
        }
        entity.setStagedChunks(fixed);
    }

    /** Result of an S3 PUT as seen by a later read, used by tests and the shell. */
    public java.util.Optional<ManifestEntity> findObject(String bucket, String key) {
        return manifestRepo.findCurrent(bucket, key);
    }

    /**
     * Read-modify-write of the current version of an object in one transaction (ACL changes).
     */
    public java.util.Optional<ManifestEntity> updateObject(String bucket, String key,
                                                           java.util.function.Consumer<ManifestEntity> mutator) {
        return manifestRepo.updateCurrent(bucket, key, mutator);
    }

    // ── S3 Stream output ──────────────────────────────────────────────────────

    /**
     * Streams the full object content to an OutputStream (for GET requests, CopyObject sources, ...), recomputing
     * the whole-object CRC32C on the way and comparing it with the stored one at the end.
     * <p>
     * The last chunk is written only after the comparison, so on a mismatch the consumer has never received the
     * complete content: an HTTP response with a Content-Length stays short and the client sees a failed transfer
     * instead of a "complete" corrupt body. Throws {@link ObjectCorruptedException} (logged at ERROR).
     */
    public void streamToOutput(ManifestEntity entity, OutputStream out) throws IOException {
        streamToOutput(entity, out, true);
    }

    /** As {@link #streamToOutput(ManifestEntity, OutputStream)}; {@code verifyWholeObject=false} skips the CRC32C comparison. */
    public void streamToOutput(ManifestEntity entity, OutputStream out, boolean verifyWholeObject) throws IOException {
        if (entity.getStorageKind() == StorageKind.EMPTY) {
            if (verifyWholeObject) checkWholeObjectCrc(entity, new CRC32C());
            return;
        }
        if (entity.getStorageKind() == StorageKind.SMALL) {
            byte[] data = readSmall(entity);
            if (verifyWholeObject) {
                CRC32C crc = new CRC32C();
                crc.update(data, 0, data.length);
                checkWholeObjectCrc(entity, crc);
            }
            out.write(data);
            return;
        }
        long[] keys = entity.chunkKeyArray();
        if (keys.length == 0) {
            // Empty file, nothing to stream
            return;
        }

        ChunkStore.Reader chunks = readerOf(entity);
        CRC32C crc = new CRC32C();
        int total = keys.length;
        for (int i = 0; i < total; i++) {
            boolean isLast = (i == total - 1);
            ByteBuffer chunk = chunks.read(i);
            byte[] arr = chunk.array();
            int off = chunk.arrayOffset() + chunk.position();
            int len = chunk.remaining();
            if (verifyWholeObject) {
                crc.update(arr, off, len);
                if (isLast) checkWholeObjectCrc(entity, crc);   // before the final bytes leave
            }
            out.write(arr, off, len);
        }
    }

    private void checkWholeObjectCrc(ManifestEntity entity, CRC32C crc) throws ObjectCorruptedException {
        String stored = entity.getCrc32c();
        if (stored == null) return;   // manifest written before whole-object checksums
        String actual = ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.crc32Bytes((int) crc.getValue()));
        if (!stored.equals(actual)) {
            metrics.crcFailure(StorageMetrics.CrcKind.WHOLE_OBJECT);
            log.error("Whole-object CRC32C mismatch: bucket={} key={} manifest={} stored={} actual={}",
                    entity.getBucketName(), entity.getObjectKey(), entity.getId(), stored, actual);
            throw new ObjectCorruptedException(entity.getId(), entity.getBucketName(), entity.getObjectKey(),
                    stored, actual);
        }
    }

    /** Streams a byte range of the object to an OutputStream (for Range requests). */
    public void streamRange(ManifestEntity entity, OutputStream out,
                            long startByte, long lengthBytes) throws IOException {
        if (entity.getStorageKind() == StorageKind.EMPTY) {
            return;
        }
        if (entity.getStorageKind() == StorageKind.SMALL) {
            if (startByte < 0 || lengthBytes < 0 || startByte + lengthBytes > entity.getSmallLength()) {
                throw new IllegalArgumentException("Range [" + startByte + ", +" + lengthBytes
                        + "] exceeds object size " + entity.getSmallLength());
            }
            out.write(readSmall(entity), (int) startByte, (int) lengthBytes);   // a slice of the one record
            return;
        }
        if (entity.chunkKeyArray().length == 0) {
            // Empty file, can't stream any range
            return;
        }

        int chunkSize       = entity.getChunkSize();
        int totalChunks     = entity.getTotalChunks();
        int firstIdx        = (int)(startByte / chunkSize);
        int lastIdx         = (int)((startByte + lengthBytes - 1) / chunkSize);
        ChunkStore.Reader chunks = readerOf(entity);

        long written = 0;
        for (int idx = firstIdx; idx <= lastIdx; idx++) {
            boolean isFileLast = (idx == totalChunks - 1);
            int actualSize = isFileLast ? entity.getLastChunkSize() : chunkSize;

            ByteBuffer chunk = chunks.read(idx);
            int trimStart = (idx == firstIdx) ? (int)(startByte % chunkSize) : 0;
            long remaining = lengthBytes - written;
            int trimLen    = (int) Math.min(actualSize - trimStart, remaining);

            out.write(chunk.array(), chunk.arrayOffset() + chunk.position() + trimStart, trimLen);
            written += trimLen;
        }
    }

    /** Chunk reader of a CHUNKED manifest: positions are resolved through the pool's chunk index. */
    private ChunkStore.Reader readerOf(ManifestEntity entity) {
        if (entity.getPoolId() == null) {
            throw new IllegalStateException("Manifest " + entity.getId() + " has no pool: its chunks cannot be located");
        }
        return chunkStore.reader(entity.getPoolId(), entity.chunkKeyArray(), entity.getChunkSize(), entity.getLastChunkSize());
    }

    /** Reads and verifies the single record of a SMALL object. */
    private byte[] readSmall(ManifestEntity entity) throws IOException {
        BlobFileEntity blob = entity.getSmallBlob();
        if (blob == null) {
            throw new IOException("Small-object blob not found: " + entity.getSmallBlobId());
        }
        return smallCache.get(blob).read(entity.getSmallOffset(), entity.getSmallLength(), entity.getSmallCrc32c());
    }

    // ── List / Info ───────────────────────────────────────────────────────────

    /** Every live manifest (administrative full scan: shell {@code ls}, {@code verify-all}). */
    public List<ManifestEntity> listAll() {
        return manifestRepo.findAllLive();
    }

    public List<ManifestEntity> listByBlobFileId(String blobFileId) {
        return manifestRepo.findByBlobId(blobFileId, true);
    }

    public ManifestEntity getActiveManifest(String id) {
        ManifestEntity e = manifestRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + id));
        if (e.isDeleted()) throw new IllegalArgumentException("File has been deleted: " + id);
        return e;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** S3 DeleteObject: one transaction unlinks the current version and queues it for GC. */
    public boolean deleteObject(String bucket, String key) {
        long t0 = System.currentTimeMillis();
        java.util.Optional<ManifestEntity> removed = manifestRepo.deleteObject(bucket, key);
        removed.ifPresent(m -> afterDelete(m, t0));
        return removed.isPresent();
    }

    /** Shell {@code rm}: retires one manifest by id (and unlinks it if it is the current version of an object). */
    public void delete(String manifestId) {
        long t0 = System.currentTimeMillis();
        getActiveManifest(manifestId);
        manifestRepo.deleteManifest(manifestId).ifPresent(m -> afterDelete(m, t0));
    }

    private void afterDelete(ManifestEntity entity, long t0) {
        releaseData(entity, "DELETE");
        String blobId = entity.getPhysicalBlob() != null ? entity.getPhysicalBlob().getId() : "";
        opLog.success("DELETE", entity.getId(), entity.getSourceFileName(),
                Map.of("blobFileId", blobId, "storageKind", entity.getStorageKind().name()),
                System.currentTimeMillis() - t0);
        log.info("Object deleted: id={} name={}", entity.getId(), entity.getSourceFileName());
    }

    /**
     * Called AFTER the transaction that retired {@code entity} committed. A small object's record is flipped to DELETED
     * (one byte). A crash in between leaves a dead manifest whose record is still ACTIVE: a leak for the GC stage,
     * never a live object without data. Chunks of a chunked object are reclaimed by the GC stage from the queue.
     */
    private void releaseData(ManifestEntity entity, String reason) {
        if (entity.getStorageKind() != StorageKind.SMALL) return;
        try {
            BlobFileEntity blob = entity.getSmallBlob();
            if (blob != null) {
                smallCache.get(blob).markDeleted(entity.getSmallOffset());
            } else {
                log.warn("Small-object blob {} of retired manifest {} is gone", entity.getSmallBlobId(), entity.getId());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not mark small-object record DELETED after {} (manifest {} stays retired, space leaks): {}",
                    reason, entity.getId(), e.getMessage());
        }
    }
}
