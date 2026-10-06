package org.example.sectoriadb.service;

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
    private final OperationLogService opLog;
    private final StorageProperties props;

    public FileStorageService(ManifestRepository manifestRepo, BlobService blobService,
                               HashTableCache cache, SmallBlobCache smallCache,
                               OperationLogService opLog, StorageProperties props) {
        this.manifestRepo = manifestRepo;
        this.blobService  = blobService;
        this.cache        = cache;
        this.smallCache   = smallCache;
        this.opLog        = opLog;
        this.props        = props;
    }

    // ── Store ─────────────────────────────────────────────────────────────────

    public ManifestEntity store(Path filePath, PoolEntity pool) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + filePath.toAbsolutePath());
        }
        MultiDigest digest = new MultiDigest(false, EnumSet.of(ChecksumAlgorithm.CRC32C));
        try (InputStream in = new MultiDigestInputStream(Files.newInputStream(filePath), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return store(filePath, pool, digest.encoded(ChecksumAlgorithm.CRC32C));
    }

    /** Stores a file whose whole-object CRC32C (base64) is already known. */
    private ManifestEntity store(Path filePath, PoolEntity pool, String crc32c) throws IOException {
        long t0 = System.currentTimeMillis();
        log.info("Storing file: {} → pool '{}'", filePath.toAbsolutePath(), pool.getName());

        long size = Files.size(filePath);
        if (size == 0) {
            return storeEmpty(filePath, pool, crc32c, t0);
        }
        if (size < props.getDefaultChunkSize() && size <= SmallObjectBlob.MAX_RECORD_DATA) {
            return storeSmall(filePath, (int) size, pool, crc32c, t0);
        }
        return storeChunked(filePath, pool, crc32c, t0);
    }

    /** Zero-byte object: no blob, no chunks — the manifest alone describes it. */
    private ManifestEntity storeEmpty(Path filePath, PoolEntity pool, String crc32c, long t0) {
        ManifestEntity entity = newManifest(filePath, pool, StorageKind.EMPTY, crc32c);
        manifestRepo.save(entity);
        opLog.success("STORE", entity.getId(), entity.getSourceFileName(),
                Map.of("poolId", pool.getId(), "totalBytes", 0L, "storageKind", "EMPTY"),
                System.currentTimeMillis() - t0);
        log.info("Stored: id={} file={} size=0 kind=EMPTY", entity.getId(), entity.getSourceFileName());
        return entity;
    }

    /**
     * Object smaller than the chunk size: stored whole as one record of a small-object blob.
     * Order: record appended and forced first, manifest saved after. A crash in between leaves an ACTIVE record no
     * manifest points to (a leak that the GC stage can reclaim), never a manifest without data.
     */
    private ManifestEntity storeSmall(Path filePath, int size, PoolEntity pool, String crc32c, long t0) throws IOException {
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
        manifestRepo.save(entity);

        opLog.success("STORE", entity.getId(), entity.getSourceFileName(),
                Map.of("blobFileId", blobEntity.getId(), "poolId", pool.getId(), "totalBytes", (long) size,
                        "storageKind", "SMALL", "offset", loc.offset()),
                System.currentTimeMillis() - t0);
        log.info("Stored: id={} file={} size={} kind=SMALL blob={} offset={}",
                entity.getId(), entity.getSourceFileName(), size, blobEntity.getId(), loc.offset());
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

    private ManifestEntity storeChunked(Path filePath, PoolEntity pool, String crc32c, long t0) throws IOException {
        BlobFileEntity blobEntity = blobService.chooseBlobFileForWrite(pool);
        CuckooHashTable table = cache.get(blobEntity);
        BlobFileWriteService writer = new BlobFileWriteService(
                table, new DefaultChunkingService(), new XxHash64BytesHasher());

        FileManifest manifest;
        try {
            manifest = writer.writeFile(filePath);
        } catch (IOException e) {
            opLog.failure("STORE", null, filePath.getFileName().toString(), e,
                    System.currentTimeMillis() - t0);
            throw e;
        }

        // Calculate total bytes; handle empty files (0 chunks)
        long totalBytes;
        if (manifest.chunkKeys().isEmpty()) {
            totalBytes = 0;
        } else {
            totalBytes = (long)(manifest.chunkKeys().size() - 1) * manifest.chunkSize()
                    + manifest.lastChunkActualSize();
        }

        ManifestEntity entity = newManifest(filePath, pool, StorageKind.CHUNKED, crc32c);
        entity.setBlobFile(blobEntity);       // also sets blobFileId
        entity.setSourceFileName(manifest.sourceFileName());
        entity.setChunkSize(manifest.chunkSize());
        entity.setTotalChunks(manifest.chunkKeys().size());
        entity.setTotalBytes(totalBytes);
        entity.setChunkKeys(ManifestEntity.encodeChunkKeys(manifest.chunkKeys()));
        entity.setLastChunkSize(manifest.lastChunkActualSize());
        manifestRepo.save(entity);

        opLog.success("STORE", entity.getId(), manifest.sourceFileName(),
                Map.of("blobFileId", blobEntity.getId(),
                        "poolId", pool.getId(),
                        "totalBytes", totalBytes,
                        "totalChunks", manifest.chunkKeys().size(),
                        "chunkKeys", manifest.chunkKeys().stream()
                                .map(k -> String.format("%016x", k)).toList()),
                System.currentTimeMillis() - t0);
        log.info("Stored: id={} file={} size={} chunks={} blob={}",
                entity.getId(), manifest.sourceFileName(), totalBytes,
                manifest.chunkKeys().size(), blobEntity.getId());
        return entity;
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
        CuckooHashTable table = cache.get(entity.getBlobFile());
        List<Long> keys = entity.parseChunkKeys();

        boolean ok = false;
        try (FileChannel out = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            CRC32C crc = new CRC32C();
            int total = keys.size();
            int lastDecile = -1;
            for (int i = 0; i < total; i++) {
                long key = keys.get(i);
                boolean isLast = (i == total - 1);
                int readSize = isLast ? entity.getLastChunkSize() : entity.getChunkSize();
                ByteBuffer chunk = table.readChunkByKey(key, readSize).orElseThrow(() -> new ChunkNotFoundException(key));
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

        List<Long> keys  = entity.parseChunkKeys();
        int chunkSize    = entity.getChunkSize();
        int totalChunks  = entity.getTotalChunks();
        int firstIdx     = (int)(startByte / chunkSize);
        int lastIdx      = (int)((startByte + lengthBytes - 1) / chunkSize);
        CuckooHashTable table = cache.get(entity.getBlobFile());

        log.info("Restoring range: id={} [{}, +{}] chunks [{},{}]",
                manifestId, startByte, lengthBytes, firstIdx, lastIdx);

        try (FileChannel out = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            for (int idx = firstIdx; idx <= lastIdx; idx++) {
                long key = keys.get(idx);
                boolean isFileLast = (idx == totalChunks - 1);
                int actualSize = isFileLast ? entity.getLastChunkSize() : chunkSize;

                ByteBuffer chunk = table.readChunkByKey(key, actualSize).orElseThrow(() ->
                        new ChunkNotFoundException(key));
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
     * Stores an object uploaded via the S3 REST API.
     * Writes the stream to a temp file while computing MD5 (the ETag), the always-on CRC32C and the client's
     * additional checksum in a single pass. The client-declared Content-MD5 / checksum is verified BEFORE
     * anything is committed: on a mismatch the temp file is removed and neither a blob record, nor chunks,
     * nor a manifest exist. Only then is the temp file handed to the storage engine.
     */
    public ManifestEntity storeStream(InputStream in, PoolEntity pool,
                                      String objectKey, String contentType) throws IOException {
        return storeStream(in, pool, objectKey, contentType, UploadChecksums.none());
    }

    public ManifestEntity storeStream(InputStream in, PoolEntity pool, String objectKey, String contentType,
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
            ManifestEntity entity = store(tmp, pool, digest.encoded(ChecksumAlgorithm.CRC32C));
            entity.setObjectKey(objectKey);
            entity.setBucketName(pool.getName());
            entity.setContentType(contentType != null ? contentType : "application/octet-stream");
            entity.setEtag(etag);
            if (declared.algorithm() != null) {
                entity.setChecksumAlgorithm(declared.algorithm());
                entity.setChecksumValue(digest.encoded(declared.algorithm()));
                entity.setChecksumType(ChecksumType.FULL_OBJECT);
            }
            manifestRepo.save(entity);
            return entity;
        } finally {
            Files.deleteIfExists(tmp);
        }
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
        List<Long> keys = entity.parseChunkKeys();
        if (keys.isEmpty()) {
            // Empty file, nothing to stream
            return;
        }

        CuckooHashTable table = cache.get(entity.getBlobFile());
        CRC32C crc = new CRC32C();
        int total = keys.size();
        for (int i = 0; i < total; i++) {
            long key = keys.get(i);
            boolean isLast = (i == total - 1);
            int readSize = isLast ? entity.getLastChunkSize() : entity.getChunkSize();
            ByteBuffer chunk = table.readChunkByKey(key, readSize).orElseThrow(() -> new ChunkNotFoundException(key));
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
        List<Long> keys     = entity.parseChunkKeys();
        if (keys.isEmpty()) {
            // Empty file, can't stream any range
            return;
        }

        int chunkSize       = entity.getChunkSize();
        int totalChunks     = entity.getTotalChunks();
        int firstIdx        = (int)(startByte / chunkSize);
        int lastIdx         = (int)((startByte + lengthBytes - 1) / chunkSize);
        CuckooHashTable table = cache.get(entity.getBlobFile());

        long written = 0;
        for (int idx = firstIdx; idx <= lastIdx; idx++) {
            long key = keys.get(idx);
            boolean isFileLast = (idx == totalChunks - 1);
            int actualSize = isFileLast ? entity.getLastChunkSize() : chunkSize;

            ByteBuffer chunk = table.readChunkByKey(key, actualSize).orElseThrow(() ->
                    new ChunkNotFoundException(key));
            int trimStart = (idx == firstIdx) ? (int)(startByte % chunkSize) : 0;
            long remaining = lengthBytes - written;
            int trimLen    = (int) Math.min(actualSize - trimStart, remaining);

            out.write(chunk.array(), chunk.arrayOffset() + chunk.position() + trimStart, trimLen);
            written += trimLen;
        }
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

    public List<ManifestEntity> listAll() {
        return manifestRepo.findByDeletedFalse();
    }

    public List<ManifestEntity> listByBlobFileId(String blobFileId) {
        return manifestRepo.findByBlobFileIdAndDeletedFalse(blobFileId);
    }

    public ManifestEntity getActiveManifest(String id) {
        ManifestEntity e = manifestRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + id));
        if (e.isDeleted()) throw new IllegalArgumentException("File has been deleted: " + id);
        return e;
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────

    public void delete(String manifestId) {
        long t0 = System.currentTimeMillis();
        ManifestEntity entity = getActiveManifest(manifestId);
        entity.setDeleted(true);
        manifestRepo.save(entity);
        // Only after the manifest is durably deleted is the record marked DELETED. A crash in between leaves a
        // dead object whose record is still ACTIVE (a leak for the GC stage), never a live object without data.
        if (entity.getStorageKind() == StorageKind.SMALL) {
            try {
                BlobFileEntity blob = entity.getSmallBlob();
                if (blob != null) {
                    smallCache.get(blob).markDeleted(entity.getSmallOffset());
                } else {
                    log.warn("Small-object blob {} of deleted file {} is gone", entity.getSmallBlobId(), manifestId);
                }
            } catch (IOException e) {
                log.warn("Could not mark small-object record DELETED (file {} stays deleted, space leaks): {}",
                        manifestId, e.getMessage());
            }
        }
        String blobId = entity.getPhysicalBlob() != null ? entity.getPhysicalBlob().getId() : "";
        opLog.success("DELETE", manifestId, entity.getSourceFileName(),
                Map.of("blobFileId", blobId, "storageKind", entity.getStorageKind().name()),
                System.currentTimeMillis() - t0);
        log.info("File soft-deleted: id={} name={}", manifestId, entity.getSourceFileName());
    }
}
