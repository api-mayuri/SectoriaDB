package org.example.sectoriadb.service;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.FileManifest;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.impl.BlobFileWriteService;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.DefaultChunkingService;
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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final ManifestRepository manifestRepo;
    private final BlobService blobService;
    private final HashTableCache cache;
    private final OperationLogService opLog;

    public FileStorageService(ManifestRepository manifestRepo, BlobService blobService,
                               HashTableCache cache, OperationLogService opLog) {
        this.manifestRepo = manifestRepo;
        this.blobService  = blobService;
        this.cache        = cache;
        this.opLog        = opLog;
    }

    // ── Store ─────────────────────────────────────────────────────────────────

    public ManifestEntity store(Path filePath, PoolEntity pool) throws IOException {
        long t0 = System.currentTimeMillis();
        log.info("Storing file: {} → pool '{}'", filePath.toAbsolutePath(), pool.getName());

        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + filePath.toAbsolutePath());
        }

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

        ManifestEntity entity = new ManifestEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setBlobFile(blobEntity);       // also sets blobFileId
        entity.setSourceFileName(manifest.sourceFileName());
        entity.setChunkSize(manifest.chunkSize());
        entity.setTotalChunks(manifest.chunkKeys().size());
        entity.setTotalBytes(totalBytes);
        entity.setChunkKeys(ManifestEntity.encodeChunkKeys(manifest.chunkKeys()));
        entity.setLastChunkSize(manifest.lastChunkActualSize());
        entity.setCreatedAt(Instant.now());
        entity.setDeleted(false);
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
        CuckooHashTable table = cache.get(entity.getBlobFile());
        List<Long> keys = entity.parseChunkKeys();

        log.info("Restoring file: id={} → {}", manifestId, outputPath);

        try (FileChannel out = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            int total = keys.size();
            int lastDecile = -1;
            for (int i = 0; i < total; i++) {
                long key = keys.get(i);
                boolean isLast = (i == total - 1);
                int readSize = isLast ? entity.getLastChunkSize() : entity.getChunkSize();
                out.write(table.readChunkByKey(key, readSize).orElseThrow(() ->
                        new IOException("Chunk not found: key=0x" + Long.toHexString(key))));

                if (total >= 10) {
                    int decile = (int)(10.0 * (i + 1) / total);
                    if (decile > lastDecile) {
                        lastDecile = decile;
                        log.debug("Restoring {}: {}% ({}/{} chunks)", manifestId, decile * 10, i + 1, total);
                    }
                }
            }
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
                        new IOException("Chunk not found: key=0x" + Long.toHexString(key)));
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
     * Writes the stream to a temp file, computes MD5 ETag, then delegates to the
     * existing chunked-storage engine.
     */
    public ManifestEntity storeStream(InputStream in, PoolEntity pool,
                                      String objectKey, String contentType) throws IOException {
        Path tmp = Files.createTempFile("sectoriadb-upload-", ".tmp");
        try {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            try (DigestInputStream dis = new DigestInputStream(in, md5)) {
                Files.copy(dis, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String etag = "\"" + HexFormat.of().formatHex(md5.digest()) + "\"";

            ManifestEntity entity = store(tmp, pool);
            entity.setObjectKey(objectKey);
            entity.setBucketName(pool.getName());
            entity.setContentType(contentType != null ? contentType : "application/octet-stream");
            entity.setEtag(etag);
            manifestRepo.save(entity);
            return entity;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── S3 Stream output ──────────────────────────────────────────────────────

    /** Streams the full object content to an OutputStream (for GET requests). */
    public void streamToOutput(ManifestEntity entity, OutputStream out) throws IOException {
        List<Long> keys = entity.parseChunkKeys();
        if (keys.isEmpty()) {
            // Empty file, nothing to stream
            return;
        }

        CuckooHashTable table = cache.get(entity.getBlobFile());
        int total = keys.size();
        for (int i = 0; i < total; i++) {
            long key = keys.get(i);
            boolean isLast = (i == total - 1);
            int readSize = isLast ? entity.getLastChunkSize() : entity.getChunkSize();
            ByteBuffer chunk = table.readChunkByKey(key, readSize).orElseThrow(() ->
                    new IOException("Chunk not found: key=0x" + Long.toHexString(key)));
            out.write(chunk.array(), chunk.arrayOffset() + chunk.position(), chunk.remaining());
        }
    }

    /** Streams a byte range of the object to an OutputStream (for Range requests). */
    public void streamRange(ManifestEntity entity, OutputStream out,
                            long startByte, long lengthBytes) throws IOException {
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
                    new IOException("Chunk not found: key=0x" + Long.toHexString(key)));
            int trimStart = (idx == firstIdx) ? (int)(startByte % chunkSize) : 0;
            long remaining = lengthBytes - written;
            int trimLen    = (int) Math.min(actualSize - trimStart, remaining);

            out.write(chunk.array(), chunk.arrayOffset() + chunk.position() + trimStart, trimLen);
            written += trimLen;
        }
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
        opLog.success("DELETE", manifestId, entity.getSourceFileName(),
                Map.of("blobFileId", entity.getBlobFileId()),
                System.currentTimeMillis() - t0);
        log.info("File soft-deleted: id={} name={}", manifestId, entity.getSourceFileName());
    }
}
