package org.example.sectoriadb.storage.multipart;

import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.MultipartException;
import org.example.sectoriadb.catalog.MultipartException.Kind;
import org.example.sectoriadb.catalog.MultipartPart;
import org.example.sectoriadb.catalog.MultipartRepository;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.PoolRepository;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.MultiDigest;
import org.example.sectoriadb.checksum.MultiDigestOutputStream;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.logging.LogSafe;
import org.example.sectoriadb.storage.ObjectReader;
import org.example.sectoriadb.storage.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;

/**
 * The multipart use cases. Parts go through the regular write path ({@link ObjectWriter#stagePart}: chunking,
 * deduplication, placement, small-object records) and are committed as hidden part manifests in ONE metastore transaction
 * together with their {@code mpu_parts} row; CompleteMultipartUpload is a single metadata transaction
 * ({@link MultipartRepository#complete}) that moves the parts' chunk references and small records into the object
 * manifest, so it copies and writes no data at all.
 */
public class MultipartService {

    private static final Logger log = LoggerFactory.getLogger(MultipartService.class);

    private final MultipartRepository repo;
    private final PoolRepository pools;
    private final ObjectWriter writer;
    private final ObjectReader reader;

    public MultipartService(MultipartRepository repo, PoolRepository pools, ObjectWriter writer, ObjectReader reader) {
        this.repo = repo;
        this.pools = pools;
        this.writer = writer;
        this.reader = reader;
    }

    // ── create ────────────────────────────────────────────────────────────────

    /** CreateMultipartUpload: records the upload with the attributes the finished object will get. */
    public MultipartUpload create(PoolEntity pool, MultipartUpload template) {
        template.setBucket(pool.getName());
        template.setPoolId(pool.getId());
        long now = System.currentTimeMillis();
        template.setInitiatedAt(now);
        template.setUpdatedAt(now);
        MultipartUpload created = repo.create(template);
        log.debug("Multipart upload {} created: bucket={} key={}", created.getUploadId(), LogSafe.clean(created.getBucket()),
                LogSafe.clean(created.getKey()));
        return created;
    }

    /**
     * The OPEN upload with this id for this bucket and key; call it before reading a part body so that a request for a
     * missing or finished upload is refused without receiving the body.
     *
     * @throws MultipartException NO_SUCH_UPLOAD
     */
    public MultipartUpload requireOpen(String uploadId, String bucket, String key) {
        return repo.find(uploadId)
                .filter(u -> u.getState() == MultipartUpload.State.OPEN && u.getBucket().equals(bucket) && u.getKey().equals(key))
                .orElseThrow(() -> new MultipartException(Kind.NO_SUCH_UPLOAD, "The specified upload does not exist: " + uploadId));
    }

    /**
     * The upload for this bucket and key if it is OPEN or COMPLETED (a retried Complete is answered from the stored result);
     * anything else is NoSuchUpload.
     */
    public MultipartUpload requireKnown(String uploadId, String bucket, String key) {
        return repo.find(uploadId)
                .filter(u -> u.getState() != MultipartUpload.State.ABORTED && u.getBucket().equals(bucket) && u.getKey().equals(key))
                .orElseThrow(() -> new MultipartException(Kind.NO_SUCH_UPLOAD, "The specified upload does not exist: " + uploadId));
    }

    // ── parts ─────────────────────────────────────────────────────────────────

    /**
     * UploadPart: stages the body (declared Content-MD5 / checksum are verified before any blob is touched) and commits the
     * part atomically; a retry of the same number replaces the earlier part in the same transaction.
     *
     * @param wanted the algorithm of the part: the upload's own, or the client's
     */
    public MultipartPart uploadPart(MultipartUpload upload, int partNumber, InputStream body, UploadChecksums wanted)
            throws IOException {
        PoolEntity pool = poolOf(upload);
        ManifestEntity staged = writer.stagePart(body, pool, wanted);
        return commitPart(upload, partNumber, staged);
    }

    /**
     * UploadPartCopy: reads the source (the whole object or a range) through the reader, so every integrity check of a
     * normal read applies, and stages the bytes like an uploaded part. (Chunks of the source are not re-linked even when the
     * range is chunk-aligned: the copy is deduplicated against the source by the regular write path, so it costs the read
     * and the hashing but writes no new chunk.)
     *
     * @param range null for the whole source, else {start, length}
     */
    public MultipartPart uploadPartCopy(MultipartUpload upload, int partNumber, ManifestEntity source, long[] range)
            throws IOException {
        PoolEntity pool = poolOf(upload);
        ChecksumAlgorithm alg = upload.getChecksumAlgorithm();
        EnumSet<ChecksumAlgorithm> algs = EnumSet.of(ChecksumAlgorithm.CRC32C);
        if (alg != null) algs.add(alg);
        MultiDigest digest = new MultiDigest(true, algs);
        Path tmp = Files.createTempFile(writer.uploadTmpDir(), "sectoriadb-partcopy-", ".tmp");
        try {
            try (OutputStream out = new MultiDigestOutputStream(Files.newOutputStream(tmp), digest)) {
                if (range != null) {
                    reader.streamRange(source, out, range[0], range[1]);
                } else {
                    reader.streamToOutput(source, out);   // a corrupt source fails the copy: no part is stored
                }
            }
            return commitPart(upload, partNumber, writer.stagePartFile(tmp, pool, digest, alg));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private PoolEntity poolOf(MultipartUpload upload) {
        return pools.findById(upload.getPoolId()).orElseThrow(() -> new ManifestRepository.PoolNotFoundException(upload.getPoolId()));
    }

    private MultipartPart commitPart(MultipartUpload upload, int partNumber, ManifestEntity staged) {
        try {
            staged.setMultipartUploadId(upload.getUploadId());
            staged.setPartNumber(partNumber);
            String etag = staged.getEtag().replace("\"", "");
            MultipartPart row = new MultipartPart(partNumber, staged.getId(), staged.getTotalBytes(), etag,
                    staged.getCrc32c(), staged.getChecksumAlgorithm(), staged.getChecksumValue(), System.currentTimeMillis());
            MultipartRepository.PutPartResult r = writer.commitStaged(staged,
                    () -> repo.putPart(upload.getBucket(), upload.getKey(), staged, row));
            r.replaced().ifPresent(old -> log.debug("Part {} of upload {} replaced (was {} bytes)", partNumber,
                    upload.getUploadId(), old.size()));
            return r.part();
        } finally {
            writer.abortStaged(staged);   // no-op after the commit; releases the collector hold on a failure
        }
    }

    // ── complete / abort / lists ──────────────────────────────────────────────

    /** CompleteMultipartUpload: one metadata transaction, no data is read or written. */
    public MultipartRepository.CompleteResult complete(MultipartRepository.CompleteCommand command) {
        MultipartRepository.CompleteResult r = repo.complete(command);
        if (!r.replayed()) {
            log.debug("Multipart upload {} completed: bucket={} key={}", command.uploadId(), LogSafe.clean(r.bucket()), LogSafe.clean(r.key()));
        }
        return r;
    }

    /** AbortMultipartUpload; returns the number of parts released. */
    public int abort(String uploadId, String bucket, String key) {
        int parts = repo.abort(uploadId, bucket, key, System.currentTimeMillis());
        log.debug("Multipart upload {} aborted: bucket={} key={}", uploadId, LogSafe.clean(bucket), LogSafe.clean(key));
        return parts;
    }

    public MultipartRepository.PartsPage listParts(String uploadId, String bucket, String key, int afterPartNumber, int maxParts) {
        return repo.listParts(uploadId, bucket, key, afterPartNumber, maxParts);
    }

    public MultipartRepository.UploadListing listUploads(String bucket, String prefix, String delimiter, String keyMarker,
                                                         String uploadIdMarker, int maxUploads) {
        return repo.listUploads(bucket, prefix, delimiter, keyMarker, uploadIdMarker, maxUploads);
    }

    /** The expiry sweep (scheduled by the server): abandoned uploads are aborted, old COMPLETED / ABORTED records removed. */
    public MultipartRepository.CleanupReport cleanup(Duration staleAfter, Duration completedRetention) {
        return repo.cleanup(System.currentTimeMillis(), staleAfter.toMillis(), completedRetention.toMillis());
    }
}
