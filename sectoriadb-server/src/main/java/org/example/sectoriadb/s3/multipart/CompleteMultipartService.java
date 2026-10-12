package org.example.sectoriadb.s3.multipart;

import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumMismatchException;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.s3.api.S3Lookup;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.S3Checksums;
import org.example.sectoriadb.storage.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import static org.example.sectoriadb.s3.multipart.MultipartUploadStore.State;
import static org.example.sectoriadb.s3.multipart.MultipartUploadStore.Upload;

/**
 * CompleteMultipartUpload: the state machine of the upload (OPEN to COMPLETING to COMPLETED, back to OPEN when the
 * completion fails) and the assembly of the object from the part files. The parts are concatenated through the normal
 * write path (checksums, chunking, one commit transaction); until stage 13 the part data is still copied once.
 *
 * <p>A client retry of a finished Complete with the same part list gets the stored result, not an error.
 */
@Component
public class CompleteMultipartService {

    private static final Logger log = LoggerFactory.getLogger(CompleteMultipartService.class);

    /** What the client is told about the finished object. */
    public record Result(String key, String etag, String checksumXml) {}

    private final MultipartUploadStore store;
    private final ObjectWriter writer;
    private final S3Lookup lookup;

    public CompleteMultipartService(MultipartUploadStore store, ObjectWriter writer, S3Lookup lookup) {
        this.store = store;
        this.writer = writer;
        this.lookup = lookup;
    }

    /**
     * @param wholeObjectHeaders the {@code x-amz-checksum-*} headers of the Complete request (the checksum of the whole upload)
     */
    Result complete(Upload upload, String bucket, ParsedParts parts, ManifestRepository.CommitPrecondition precondition,
                    Map<ChecksumAlgorithm, String> wholeObjectHeaders) throws IOException {
        String partsFingerprint = fingerprint(parts.partNumbers(), parts.etags());

        // State transition under the lock: OPEN -> COMPLETING. A retry of a finished Complete gets the stored result.
        ReentrantLock lock = store.lockOf(upload.id());
        lock.lock();
        try {
            Properties state = store.readState(upload);
            State st = MultipartUploadStore.stateIn(state);
            if (st == State.COMPLETED) {
                if (partsFingerprint.equals(state.getProperty("partsFingerprint"))) {
                    return new Result(state.getProperty("key"), state.getProperty("etag"), state.getProperty("checksumXml", ""));
                }
                throw S3Exception.noSuchUpload(upload.id());
            }
            if (st == State.COMPLETING) throw MultipartUploadStore.conflict();
            store.writeState(upload, State.COMPLETING);
        } finally {
            lock.unlock();
        }

        boolean done = false;
        try {
            Result result = assemble(upload, bucket, parts, precondition, wholeObjectHeaders, partsFingerprint);
            done = true;
            return result;
        } finally {
            if (!done) {
                // the upload stays usable: back to OPEN so the client can fix the part list or retry
                lock.lock();
                try {
                    store.writeState(upload, State.OPEN);
                } catch (IOException e) {
                    log.warn("Could not reopen upload {} after a failed Complete: {}", upload.id(), e.toString());
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private static String fingerprint(List<Integer> partNums, Map<Integer, String> etags) {
        StringBuilder sb = new StringBuilder();
        for (int pn : partNums) {
            String etag = etags.get(pn);
            sb.append(pn).append(':').append(etag == null ? "" : etag.replace("\"", "")).append(';');
        }
        return HexFormat.of().formatHex(md5Digest().digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private Result assemble(Upload upload, String bucket, ParsedParts parsed, ManifestRepository.CommitPrecondition precondition,
                            Map<ChecksumAlgorithm, String> wholeObjectHeaders, String partsFingerprint) throws IOException {
        List<Integer> partNums = parsed.partNumbers();
        Map<Integer, String> providedEtags = parsed.etags();
        UploadMeta meta = store.readMeta(upload);
        String objectKey = meta.key();
        ChecksumAlgorithm uploadAlg = meta.checksumAlgorithm();
        ChecksumType uploadType = meta.checksumType();

        Properties etagProps = store.partEtags(upload);
        Properties partChecksums = store.partChecksums(upload);

        List<Path> partFiles = new ArrayList<>();
        List<byte[]> partDigests = new ArrayList<>();
        byte[] etagConcat = new byte[16 * partNums.size()];
        int concatIdx = 0;

        for (int pn : partNums) {
            String storedEtag = etagProps.getProperty(String.valueOf(pn));
            if (storedEtag == null) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPart",
                        "Part " + pn + " has not been uploaded");
            }

            if (providedEtags.containsKey(pn)) {
                String providedEtag = providedEtags.get(pn);
                // Remove quotes if present
                String clean = providedEtag.replace("\"", "");
                if (!clean.equals(storedEtag)) {
                    throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPart",
                            "ETag mismatch for part " + pn);
                }
            }

            // Per-part checksums: verify the ones given in the Complete XML, collect the upload algorithm's for the composite
            String storedChecksum = partChecksums.getProperty(String.valueOf(pn));
            String storedAlg = storedChecksum == null ? null : storedChecksum.substring(0, storedChecksum.indexOf(':'));
            String storedValue = storedChecksum == null ? null : storedChecksum.substring(storedChecksum.indexOf(':') + 1);
            for (Map.Entry<ChecksumAlgorithm, String> given : parsed.checksums().getOrDefault(pn, Map.of()).entrySet()) {
                if (storedAlg == null || !storedAlg.equals(given.getKey().name())) {
                    throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPart",
                            "Part " + pn + " was not uploaded with a " + given.getKey().name() + " checksum");
                }
                if (!storedValue.equals(given.getValue())) {
                    throw new S3Exception(HttpStatus.BAD_REQUEST, "BadDigest", "The " + given.getKey().name()
                            + " you specified for part " + pn + " did not match what we received.");
                }
            }
            if (uploadAlg != null) {
                if (storedAlg == null || !storedAlg.equals(uploadAlg.name())) {
                    throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPart",
                            "Part " + pn + " has no " + uploadAlg.name() + " checksum");
                }
                partDigests.add(uploadAlg.decode(storedValue));
            }

            // Add binary MD5 to concatenation
            byte[] md5Bytes = HexFormat.of().parseHex(storedEtag);
            System.arraycopy(md5Bytes, 0, etagConcat, concatIdx, 16);
            concatIdx += 16;

            Path partFile = store.partFile(upload, pn);
            if (!Files.isRegularFile(partFile)) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPart", "Part " + pn + " has not been uploaded");
            }
            partFiles.add(partFile);   // opened lazily, one at a time, while the data is streamed
        }

        // The composite checksum (algorithm over the concatenated binary part checksums, suffixed "-N"), and the
        // whole-upload checksum the client may have sent in the Complete request headers.
        String composite = uploadType == ChecksumType.COMPOSITE ? uploadAlg.composite(partDigests) : null;
        UploadChecksums wholeObject = UploadChecksums.none();
        for (Map.Entry<ChecksumAlgorithm, String> header : wholeObjectHeaders.entrySet()) {
            ChecksumAlgorithm a = header.getKey();
            if (a != uploadAlg) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                        "The upload was not created with the " + a.name() + " checksum algorithm.");
            }
            if (composite != null) {
                if (!composite.equals(header.getValue().trim())) throw ChecksumMismatchException.forAlgorithm(a);
            } else {
                try {
                    wholeObject.algorithm(a, a.decode(header.getValue()));
                } catch (IllegalArgumentException e) {
                    throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                            "Value for " + a.headerName() + " header is invalid.");
                }
            }
        }
        if (uploadType == ChecksumType.FULL_OBJECT && wholeObject.algorithm() == null) {
            wholeObject = UploadChecksums.compute(uploadAlg);
        }

        PoolEntity pool = lookup.requireBucket(bucket);
        ManifestEntity entity;
        try (InputStream combined = new LazySequenceInputStream(partFiles)) {
            entity = writer.stageStream(combined, pool, objectKey, meta.contentType(), wholeObject);
        }

        String s3Etag;
        try {
            // Compute S3 multipart ETag: md5(concat of binary md5s)-{partCount}
            s3Etag = "\"" + HexFormat.of().formatHex(md5Digest().digest(etagConcat)) + "-" + partNums.size() + "\"";
            entity.setEtag(s3Etag);
            if (meta.headers().cacheControl() != null) entity.setCacheControl(meta.headers().cacheControl());
            if (meta.headers().contentDisposition() != null) entity.setContentDisposition(meta.headers().contentDisposition());
            if (meta.headers().contentEncoding() != null) entity.setContentEncoding(meta.headers().contentEncoding());
            if (meta.headers().contentLanguage() != null) entity.setContentLanguage(meta.headers().contentLanguage());
            if (!meta.userMetadata().isEmpty()) entity.setUserMetadata(meta.userMetadata());
            if (composite != null) {
                entity.setChecksumAlgorithm(uploadAlg);
                entity.setChecksumValue(composite);
                entity.setChecksumType(ChecksumType.COMPOSITE);
            }
            // One transaction: the manifest with its final multipart ETag / checksum, the (bucket, key) index entry, and
            // retirement of the version it replaces.
            entity = writer.commitObject(entity, precondition);
        } finally {
            writer.abortStaged(entity);   // no-op after a commit; releases the garbage collector hold on a failure
        }

        // Keep a tombstone (meta + result) so that a client retry of this Complete gets the same answer; the parts go
        String checksumXml = checksumResultXml(entity);
        ReentrantLock lock = store.lockOf(upload.id());
        lock.lock();
        try {
            Properties done = new Properties();
            done.setProperty("state", State.COMPLETED.name());
            done.setProperty("completedAt", String.valueOf(System.currentTimeMillis()));
            done.setProperty("key", objectKey);
            done.setProperty("etag", s3Etag);
            done.setProperty("checksumXml", checksumXml);
            done.setProperty("partsFingerprint", partsFingerprint);
            store.writeState(upload, done);
        } finally {
            lock.unlock();
        }
        store.deleteParts(upload);

        return new Result(objectKey, s3Etag, checksumXml);
    }

    private static String checksumResultXml(ManifestEntity entity) {
        if (entity.getChecksumAlgorithm() == null || entity.getChecksumValue() == null) return "";
        String el = S3Checksums.xmlElement(entity.getChecksumAlgorithm());
        return "<" + el + ">" + entity.getChecksumValue() + "</" + el + ">"
                + "<ChecksumType>" + entity.getChecksumType() + "</ChecksumType>";
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
