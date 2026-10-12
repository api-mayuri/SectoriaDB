package org.example.sectoriadb.storage;

import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.PartSegment;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.engine.blob.SmallObjectBlob;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32C;
import org.example.sectoriadb.audit.OperationLogService;

/**
 * The read side of object storage: streams whole objects and byte ranges to an output stream or a file, verifying the
 * CRC32C of every chunk / record on the way and the whole-object CRC32C at the end.
 */
public class ObjectReader {

    private static final Logger log = LoggerFactory.getLogger(ObjectReader.class);

    private final ChunkStore chunkStore;
    private final SmallBlobCache smallCache;
    private final ObjectCatalog catalog;
    private final OperationLogService opLog;
    private final StorageMetrics metrics;

    public ObjectReader(ChunkStore chunkStore, SmallBlobCache smallCache, ObjectCatalog catalog,
                        OperationLogService opLog, StorageMetrics metrics) {
        this.chunkStore = chunkStore;
        this.smallCache = smallCache;
        this.catalog    = catalog;
        this.opLog      = opLog;
        this.metrics    = metrics;
    }


    // ── Restore (full) ────────────────────────────────────────────────────────

    public void restore(String manifestId, Path outputPath) throws IOException {
        long t0 = System.currentTimeMillis();
        ManifestEntity entity = catalog.getActiveManifest(manifestId);

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
                writeFully(out, chunk);

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
        ManifestEntity entity = catalog.getActiveManifest(manifestId);

        if (startByte < 0 || lengthBytes <= 0) {
            throw new IllegalArgumentException("start-byte must be >= 0 and length must be > 0");
        }
        if (startByte + lengthBytes > entity.getTotalBytes()) {
            throw new IllegalArgumentException(String.format(
                    "Range [%d, +%d] exceeds file size %d bytes",
                    startByte, lengthBytes, entity.getTotalBytes()));
        }

        if (entity.getStorageKind() != StorageKind.CHUNKED) {
            boolean done = false;
            try (OutputStream out = Files.newOutputStream(outputPath)) {
                streamRange(entity, out, startByte, lengthBytes);
                done = true;
            } finally {
                if (!done) Files.deleteIfExists(outputPath);   // no partial output
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

        boolean rangeOk = false;
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
                writeFully(out, chunk);
                written += trimLen;
            }
            rangeOk = true;
        } finally {
            if (!rangeOk) Files.deleteIfExists(outputPath);   // never leave a partial range behind
        }

        opLog.success("RESTORE_RANGE", manifestId, entity.getSourceFileName(),
                Map.of("startByte", startByte, "length", lengthBytes, "outputPath", outputPath.toString()),
                System.currentTimeMillis() - t0);
        log.info("Range restored: id={} [{},+{}] → {}", manifestId, startByte, lengthBytes, outputPath);
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
        if (entity.getStorageKind() == StorageKind.MULTIPART) {
            streamMultipart(entity, out, verifyWholeObject);
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

    /** Writes the whole remaining content of the buffer (a FileChannel write may be short). */
    private static void writeFully(FileChannel out, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            out.write(buf);
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
        if (entity.getStorageKind() == StorageKind.MULTIPART) {
            if (startByte < 0 || lengthBytes < 0 || startByte + lengthBytes > entity.getTotalBytes()) {
                throw new IllegalArgumentException("Range [" + startByte + ", +" + lengthBytes
                        + "] exceeds object size " + entity.getTotalBytes());
            }
            streamMultipartRange(entity, out, startByte, lengthBytes);
            return;
        }
        if (entity.chunkKeyArray().length == 0) {
            // Empty file, can't stream any range
            return;
        }
        if (startByte < 0 || lengthBytes < 0 || startByte + lengthBytes > entity.getTotalBytes()) {
            throw new IllegalArgumentException("Range [" + startByte + ", +" + lengthBytes
                    + "] exceeds object size " + entity.getTotalBytes());
        }
        if (lengthBytes == 0) {
            return;
        }
        streamChunkRange(readerOf(entity), entity.getChunkSize(), startByte, lengthBytes, out);
    }

    /** Bytes {@code [start, start+length)} of a run of chunks (all of {@code chunkSize} but the last), length &gt; 0. */
    private static void streamChunkRange(ChunkStore.Reader chunks, int chunkSize, long start, long length, OutputStream out)
            throws IOException {
        int firstIdx = (int) (start / chunkSize);
        int lastIdx = (int) ((start + length - 1) / chunkSize);
        long written = 0;
        for (int idx = firstIdx; idx <= lastIdx; idx++) {
            int actualSize = chunks.sizeOf(idx);
            ByteBuffer chunk = chunks.read(idx);
            int trimStart = (idx == firstIdx) ? (int) (start % chunkSize) : 0;
            long remaining = length - written;
            int trimLen = (int) Math.min(actualSize - trimStart, remaining);

            out.write(chunk.array(), chunk.arrayOffset() + chunk.position() + trimStart, trimLen);
            written += trimLen;
        }
    }

    // ── Multipart objects ─────────────────────────────────────────────────────

    /**
     * Streams the bytes of part {@code partNumber} of a MULTIPART object (the part boundaries are in the manifest).
     * The per-chunk / per-record CRCs are checked; the whole-object CRC cannot be, as only a piece is read.
     *
     * @return false if the object has no such part
     */
    public boolean streamPart(ManifestEntity entity, int partNumber, OutputStream out) throws IOException {
        var extent = entity.partExtent(partNumber);
        if (extent.isEmpty()) return false;
        streamRange(entity, out, extent.get().start(), extent.get().length());
        return true;
    }

    private void streamMultipart(ManifestEntity entity, OutputStream out, boolean verifyWholeObject) throws IOException {
        long[] keys = entity.chunkKeyArray();
        int pieces = 0;
        for (PartSegment sg : entity.getSegments()) {
            pieces += sg.kind() == StorageKind.CHUNKED ? sg.chunkCount() : sg.kind() == StorageKind.SMALL ? 1 : 0;
        }
        CRC32C crc = new CRC32C();
        if (pieces == 0) {
            if (verifyWholeObject) checkWholeObjectCrc(entity, crc);
            return;
        }
        int piece = 0;
        int keyBase = 0;
        for (PartSegment sg : entity.getSegments()) {
            if (sg.kind() == StorageKind.CHUNKED) {
                ChunkStore.Reader chunks = segmentReader(entity, keys, keyBase, sg);
                for (int i = 0; i < sg.chunkCount(); i++) {
                    ByteBuffer chunk = chunks.read(i);
                    byte[] arr = chunk.array();
                    int off = chunk.arrayOffset() + chunk.position();
                    int len = chunk.remaining();
                    if (verifyWholeObject) {
                        crc.update(arr, off, len);
                        if (piece == pieces - 1) checkWholeObjectCrc(entity, crc);   // before the final bytes leave
                    }
                    out.write(arr, off, len);
                    piece++;
                }
                keyBase += sg.chunkCount();
            } else if (sg.kind() == StorageKind.SMALL) {
                byte[] data = readSegmentRecord(entity, sg);
                if (verifyWholeObject) {
                    crc.update(data, 0, data.length);
                    if (piece == pieces - 1) checkWholeObjectCrc(entity, crc);
                }
                out.write(data);
                piece++;
            }
        }
    }

    private void streamMultipartRange(ManifestEntity entity, OutputStream out, long start, long length) throws IOException {
        if (length == 0) return;
        long[] keys = entity.chunkKeyArray();
        long end = start + length;
        long pos = 0;
        int keyBase = 0;
        for (PartSegment sg : entity.getSegments()) {
            long segStart = pos;
            long segEnd = pos + sg.length();
            pos = segEnd;
            int ownKeys = sg.kind() == StorageKind.CHUNKED ? sg.chunkCount() : 0;
            if (segEnd <= start || sg.length() == 0) {
                keyBase += ownKeys;
                continue;
            }
            if (segStart >= end) break;
            long from = Math.max(start, segStart) - segStart;
            long len = Math.min(end, segEnd) - segStart - from;
            if (sg.kind() == StorageKind.CHUNKED) {
                streamChunkRange(segmentReader(entity, keys, keyBase, sg), sg.chunkSize(), from, len, out);
            } else if (sg.kind() == StorageKind.SMALL) {
                out.write(readSegmentRecord(entity, sg), (int) from, (int) len);
            }
            keyBase += ownKeys;
        }
    }

    private ChunkStore.Reader segmentReader(ManifestEntity entity, long[] keys, int keyBase, PartSegment sg) {
        if (entity.getPoolId() == null) {
            throw new IllegalStateException("Manifest " + entity.getId() + " has no pool: its chunks cannot be located");
        }
        if (keyBase + sg.chunkCount() > keys.length) {
            throw new IllegalStateException("Manifest " + entity.getId() + ": its part " + sg.partNumber()
                    + " needs more chunks than the manifest lists");
        }
        return chunkStore.reader(entity.getPoolId(), Arrays.copyOfRange(keys, keyBase, keyBase + sg.chunkCount()),
                sg.chunkSize(), sg.lastChunkSize());
    }

    private byte[] readSegmentRecord(ManifestEntity entity, PartSegment sg) throws IOException {
        BlobFileEntity blob = entity.getSegmentBlobs().get(sg.smallBlobId());
        if (blob == null) {
            throw new IOException("Small-object blob not found: " + sg.smallBlobId());
        }
        try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(blob)) {
            return h.get().read(sg.smallOffset(), sg.smallLength(), sg.smallCrc32c());
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
        try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(blob)) {
            return h.get().read(entity.getSmallOffset(), entity.getSmallLength(), entity.getSmallCrc32c());
        }
    }
}
