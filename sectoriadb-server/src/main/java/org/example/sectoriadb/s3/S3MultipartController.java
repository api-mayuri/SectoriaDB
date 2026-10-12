package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumMismatchException;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.MultiDigest;
import org.example.sectoriadb.checksum.MultiDigestInputStream;
import org.example.sectoriadb.checksum.MultiDigestOutputStream;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.storage.FileStorageService;
import org.example.sectoriadb.storage.PoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * S3 Multipart Upload API:
 *
 *   POST   /{bucket}/**?uploads                          — CreateMultipartUpload
 *   PUT    /{bucket}/**?partNumber={N}&uploadId={id}     — UploadPart
 *   PUT    /{bucket}/**?partNumber={N}&uploadId={id} + x-amz-copy-source — UploadPartCopy
 *   POST   /{bucket}/**?uploadId={id}                    — CompleteMultipartUpload
 *   DELETE /{bucket}/**?uploadId={id}                    — AbortMultipartUpload
 *   GET    /{bucket}/**?uploadId={id}                    — ListParts
 *   GET    /{bucket}?uploads                             — ListMultipartUploads
 */
@RestController
public class S3MultipartController {

    private static final Logger log = LoggerFactory.getLogger(S3MultipartController.class);

    private final PoolService poolService;
    private final FileStorageService fileService;
    private final ManifestRepository manifestRepo;
    private final S3Lookup lookup;
    private final StorageProperties storageProps;
    private final RequestBodies bodies;
    private Path mpuDir;

    public S3MultipartController(PoolService poolService, FileStorageService fileService,
                                  ManifestRepository manifestRepo, S3Lookup lookup, StorageProperties storageProps,
                                  RequestBodies bodies) {
        this.bodies = bodies;
        this.lookup = lookup;
        this.poolService = poolService;
        this.fileService = fileService;
        this.manifestRepo = manifestRepo;
        this.storageProps = storageProps;
    }

    // Initialize MPU directory on first use
    private Path getMpuDir() throws IOException {
        if (mpuDir == null) {
            mpuDir = Path.of(storageProps.getDataDir()).resolve(".multipart");
            Files.createDirectories(mpuDir);
        }
        return mpuDir;
    }

    /** Key prefix of user metadata ("x-amz-meta-*") inside the upload's meta.properties. */
    private static final String USER_META_PREFIX = "usermeta.";

    // ── Per-upload state (interim filesystem design, see docs/architecture/08-observability-bench.md) ────────────────
    //
    // state.properties: state=OPEN|COMPLETING|COMPLETED. A per-upload lock guards every transition and every change of the
    // part records, so a part file and its ETag/checksum are always updated together, a second Complete cannot run next to
    // the first, and Abort / UploadPart cannot delete or replace files under a running Complete. The lock is held only
    // for those short critical sections, never while a body is transferred or while Complete copies the data.

    private static final ConcurrentHashMap<String, ReentrantLock> UPLOAD_LOCKS = new ConcurrentHashMap<>();
    private static final String STATE_FILE = "state.properties";
    private static final String OPEN = "OPEN";
    private static final String COMPLETING = "COMPLETING";
    private static final String COMPLETED = "COMPLETED";

    private static ReentrantLock lockOf(String uploadId) {
        return UPLOAD_LOCKS.computeIfAbsent(uploadId, k -> new ReentrantLock());
    }

    private static Properties readProps(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            p.load(is);
        } catch (NoSuchFileException e) {
            // absent = empty
        }
        return p;
    }

    private static String stateOf(Path uploadDir) throws IOException {
        return readProps(uploadDir.resolve(STATE_FILE)).getProperty("state", OPEN);
    }

    /** Writes a properties file atomically (temp file, fsync, rename, fsync of the directory). */
    private static void writeProps(Path file, Properties props, boolean fsync) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp)) {
            props.store(os, null);
        }
        if (fsync) force(tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (fsync) forceDir(file.getParent());
    }

    private static void force(Path file) throws IOException {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    private static void forceDir(Path dir) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // directories cannot be fsynced on every platform; the rename is still atomic
        }
    }

    /** Must be called with the upload lock held: only an OPEN upload accepts parts. */
    private void requireOpen(Path uploadDir, String uploadId) throws IOException {
        String st = stateOf(uploadDir);
        if (COMPLETING.equals(st)) {
            throw new S3Exception(HttpStatus.CONFLICT, "OperationAborted",
                    "A conflicting operation is currently in progress against this resource. Please try again.");
        }
        if (COMPLETED.equals(st)) throw S3Exception.noSuchUpload(uploadId);
    }

    /** Records the ETag (and checksum) of a part together, under the upload lock the caller holds. */
    private void recordPart(Path uploadDir, int partNumber, String etag, String checksum) throws IOException {
        Properties etags = readProps(uploadDir.resolve("etags.properties"));
        etags.setProperty(String.valueOf(partNumber), etag);
        Properties sums = readProps(uploadDir.resolve(CHECKSUMS_FILE));
        if (checksum != null) sums.setProperty(String.valueOf(partNumber), checksum);
        else sums.remove(String.valueOf(partNumber));
        boolean fsync = storageProps.isFsync();
        writeProps(uploadDir.resolve(CHECKSUMS_FILE), sums, fsync);
        writeProps(uploadDir.resolve("etags.properties"), etags, fsync);   // the ETag last: a part is visible only with both
    }

    // ── CreateMultipartUpload ─────────────────────────────────────────────────

    @PostMapping(value = "/{bucket}/**", params = "uploads",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> createMultipartUpload(
            @PathVariable String bucket,
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream") String contentType,
            HttpServletRequest request) throws IOException {

        // Validate bucket exists
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        String objectKey = S3Support.extractKey(request, bucket);
        lookup.validateObjectKey(objectKey);
        String uploadId = UUID.randomUUID().toString();

        Path uploadDir = getMpuDir().resolve(uploadId);
        Files.createDirectories(uploadDir);

        // Store metadata including bucket and key for later validation
        Properties meta = new Properties();
        meta.setProperty("bucket", bucket);
        meta.setProperty("key", objectKey);
        meta.setProperty("contentType", contentType);
        meta.setProperty("initiatedAt", String.valueOf(System.currentTimeMillis()));
        // Object attributes given at CreateMultipartUpload belong to the finished object (applied at Complete)
        for (String[] h : new String[][]{{"Cache-Control", "cacheControl"}, {"Content-Disposition", "contentDisposition"},
                {"Content-Encoding", "contentEncoding"}, {"Content-Language", "contentLanguage"}}) {
            String v = request.getHeader(h[0]);
            if (v != null) meta.setProperty(h[1], v);
        }
        for (String name : java.util.Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("x-amz-meta-")) meta.setProperty(USER_META_PREFIX + lower, request.getHeader(name));
        }
        ChecksumAlgorithm checksumAlg = S3Checksums.declaredAlgorithm(request);
        ChecksumType checksumType = S3Checksums.multipartType(request, checksumAlg);
        if (checksumAlg != null) {
            meta.setProperty("checksumAlgorithm", checksumAlg.name());
            meta.setProperty("checksumType", checksumType.name());
        }
        try (OutputStream os = Files.newOutputStream(uploadDir.resolve("meta.properties"))) {
            meta.store(os, null);
        }

        var created = ResponseEntity.ok().header("x-amz-request-id", S3Support.requestId());
        if (checksumAlg != null) {
            created.header(S3Checksums.HEADER_ALGORITHM, checksumAlg.name())
                   .header(S3Checksums.HEADER_TYPE, checksumType.name());
        }
        return created
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                      "<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                      "<Bucket>" + S3Support.xmlEscape(bucket) + "</Bucket>" +
                      "<Key>" + S3Support.xmlEscape(objectKey) + "</Key>" +
                      "<UploadId>" + uploadId + "</UploadId>" +
                      "</InitiateMultipartUploadResult>");
    }

    // ── UploadPart ────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", params = {"partNumber", "uploadId"},
                headers = "!x-amz-copy-source")
    public ResponseEntity<Void> uploadPart(
            @PathVariable String bucket,
            @RequestParam int partNumber,
            @RequestParam String uploadId,
            HttpServletRequest request) throws IOException {

        // Validate partNumber
        if (partNumber < 1 || partNumber > 10000) {
            throw S3Exception.invalidArgument("Invalid part number: " + partNumber);
        }
        bodies.requireAcceptableUpload(request);

        // Validate and load upload directory
        Path uploadDir = validateUploadId(uploadId, bucket, request);

        // Use atomic write with temporary file to handle concurrent uploads
        Path partFile = uploadDir.resolve(String.format("part-%05d", partNumber));
        Path tmpFile = uploadDir.resolve(String.format("part-%05d.tmp.%s", partNumber,
                UUID.randomUUID().toString().substring(0, 8)));

        // Content-MD5 and the part's x-amz-checksum-* are verified here; a bad part is never kept.
        S3Checksums.Declared declared = S3Checksums.parse(request);
        UploadChecksums wanted = declared.checksums();
        ChecksumAlgorithm uploadAlg = uploadChecksumAlgorithm(uploadDir);
        if (uploadAlg != null) {
            if (wanted.algorithm() == null) {
                wanted.algorithm(uploadAlg, null);   // lenient: the client sent none, compute it so the composite works
            } else if (wanted.algorithm() != uploadAlg) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                        "Checksum Type mismatch occurred, expected checksum Type: " + uploadAlg.name().toLowerCase(Locale.ROOT)
                        + ", actual checksum Type: " + wanted.algorithm().name().toLowerCase(Locale.ROOT));
            }
        }
        EnumSet<ChecksumAlgorithm> algs = EnumSet.noneOf(ChecksumAlgorithm.class);
        if (wanted.algorithm() != null) algs.add(wanted.algorithm());
        MultiDigest digest = new MultiDigest(true, algs);
        try (InputStream body = bodies.openBody(request, declared);
             InputStream dis = new MultiDigestInputStream(body, digest)) {
            Files.copy(dis, tmpFile, StandardCopyOption.REPLACE_EXISTING);
            digest.finish();
            wanted.verify(digest);
        } catch (IOException | RuntimeException e) {
            // e.g. payload hash / chunk signature / checksum mismatch: the part must not be kept
            Files.deleteIfExists(tmpFile);
            throw e;
        }

        if (storageProps.isFsync()) force(tmpFile);

        String etagValue = HexFormat.of().formatHex(digest.md5());
        String checksumEntry = wanted.algorithm() == null ? null
                : wanted.algorithm().name() + ":" + digest.encoded(wanted.algorithm());
        installPart(uploadDir, uploadId, partNumber, tmpFile, partFile, etagValue, checksumEntry);
        var ok = ResponseEntity.ok()
                .header("ETag", "\"" + etagValue + "\"")
                .header("x-amz-request-id", S3Support.requestId());
        if (wanted.algorithm() != null) {
            ok.header(wanted.algorithm().headerName(), digest.encoded(wanted.algorithm()));
        }
        return ok.build();
    }

    /** Moves a finished temp file into place and records its ETag, atomically with respect to other operations on the upload. */
    private void installPart(Path uploadDir, String uploadId, int partNumber, Path tmpFile, Path partFile,
                             String etag, String checksumEntry) throws IOException {
        ReentrantLock lock = lockOf(uploadId);
        lock.lock();
        try {
            requireOpen(uploadDir, uploadId);
            Files.move(tmpFile, partFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            recordPart(uploadDir, partNumber, etag, checksumEntry);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    private static final String CHECKSUMS_FILE = "checksums.properties";

    // ── UploadPartCopy ────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", params = {"partNumber", "uploadId"},
                headers = "x-amz-copy-source")
    public ResponseEntity<String> uploadPartCopy(
            @PathVariable String bucket,
            @RequestParam int partNumber,
            @RequestParam String uploadId,
            @RequestHeader("x-amz-copy-source") String copySource,
            @RequestHeader(value = "x-amz-copy-source-range", required = false) String copyRange,
            HttpServletRequest request) throws IOException {

        // Validate partNumber
        if (partNumber < 1 || partNumber > 10000) {
            throw S3Exception.invalidArgument("Invalid part number: " + partNumber);
        }

        // Validate and load upload directory
        Path uploadDir = validateUploadId(uploadId, bucket, request);

        // Parse copy source: "/bucket/key" or "bucket/key"
        String sourcePath = copySource.startsWith("/") ? copySource.substring(1) : copySource;
        int slashIdx = sourcePath.indexOf('/');
        if (slashIdx < 0) {
            throw S3Exception.invalidArgument("Invalid x-amz-copy-source format");
        }
        String srcBucket = sourcePath.substring(0, slashIdx);
        String rawSrcKey = sourcePath.substring(slashIdx + 1);
        int versionIdx = rawSrcKey.indexOf('?');   // strip ?versionId=... BEFORE decoding (a decoded %3F is part of the key)
        if (versionIdx >= 0) rawSrcKey = rawSrcKey.substring(0, versionIdx);
        String srcKey = S3Support.percentDecode(rawSrcKey);

        // Load source object
        ManifestEntity srcManifest = manifestRepo.findCurrent(srcBucket, srcKey)
                .orElseThrow(() -> S3Exception.noSuchKey(srcBucket, srcKey));

        // Write part (with optional range)
        Path partFile = uploadDir.resolve(String.format("part-%05d", partNumber));
        Path tmpFile = uploadDir.resolve(String.format("part-%05d.tmp.%s", partNumber,
                UUID.randomUUID().toString().substring(0, 8)));

        long[] range = copyRange == null ? null : parseCopyRange(copyRange, srcManifest.getTotalBytes());
        ChecksumAlgorithm uploadAlg = uploadChecksumAlgorithm(uploadDir);
        MultiDigest digest = new MultiDigest(true, uploadAlg != null ? EnumSet.of(uploadAlg) : EnumSet.noneOf(ChecksumAlgorithm.class));
        try (OutputStream out = new MultiDigestOutputStream(Files.newOutputStream(tmpFile), digest)) {
            if (range != null) {
                fileService.streamRange(srcManifest, out, range[0], range[1] - range[0] + 1);
            } else {
                // a corrupt source (whole-object CRC32C mismatch) fails the copy with 500 and no part is stored
                fileService.streamToOutput(srcManifest, out);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        }
        digest.finish();

        if (storageProps.isFsync()) force(tmpFile);

        String etagValue = HexFormat.of().formatHex(digest.md5());
        String checksumXml = "";
        String checksumEntry = null;
        if (uploadAlg != null) {
            String value = digest.encoded(uploadAlg);
            checksumEntry = uploadAlg.name() + ":" + value;
            checksumXml = "<" + S3Checksums.xmlElement(uploadAlg) + ">" + value + "</" + S3Checksums.xmlElement(uploadAlg) + ">";
        }
        installPart(uploadDir, uploadId, partNumber, tmpFile, partFile, etagValue, checksumEntry);

        String lastModified = S3Support.isoDate(Instant.now());
        String responseBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                      "<CopyPartResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                      "<LastModified>" + lastModified + "</LastModified>" +
                      "<ETag>\"" + etagValue + "\"</ETag>" + checksumXml +
                      "</CopyPartResult>";
        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .body(responseBody);
    }

    // ── CompleteMultipartUpload ───────────────────────────────────────────────

    @PostMapping(value = "/{bucket}/**", params = "uploadId",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> completeMultipartUpload(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            HttpServletRequest request) throws IOException {

        Path uploadDir = validateUploadId(uploadId, bucket, request, true);
        ManifestRepository.CommitPrecondition precondition = S3Support.writePrecondition(request);

        // Parse and validate the part list (bounded body); the order must be ascending, nothing is sorted silently
        ParsedParts parsedParts = parseCompleteXml(RequestBodies.xmlBody(request));
        List<Integer> partNums = parsedParts.partNumbers;
        if (partNums.isEmpty()) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "Request body is not valid XML or contains no parts");
        }
        if (partNums.size() > 10000) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML", "A multipart upload has at most 10000 parts");
        }
        for (int i = 1; i < partNums.size(); i++) {
            if (partNums.get(i) <= partNums.get(i - 1)) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPartOrder",
                        "The list of parts was not in ascending order. The parts list must be specified in order by part number.");
            }
        }
        String partsFingerprint = fingerprint(partNums, parsedParts.etags);

        // State transition under the lock: OPEN -> COMPLETING. A retry of a finished Complete gets the stored result.
        ReentrantLock lock = lockOf(uploadId);
        lock.lock();
        try {
            Properties state = readProps(uploadDir.resolve(STATE_FILE));
            String st = state.getProperty("state", OPEN);
            if (COMPLETED.equals(st)) {
                if (partsFingerprint.equals(state.getProperty("partsFingerprint"))) {
                    return completeResponse(request, bucket, state.getProperty("key"), state.getProperty("etag"),
                            state.getProperty("checksumXml", ""));
                }
                throw S3Exception.noSuchUpload(uploadId);
            }
            if (COMPLETING.equals(st)) {
                throw new S3Exception(HttpStatus.CONFLICT, "OperationAborted",
                        "A conflicting operation is currently in progress against this resource. Please try again.");
            }
            Properties next = new Properties();
            next.setProperty("state", COMPLETING);
            writeProps(uploadDir.resolve(STATE_FILE), next, storageProps.isFsync());
        } finally {
            lock.unlock();
        }

        boolean done = false;
        try {
            ResponseEntity<String> response = doComplete(request, bucket, uploadId, uploadDir, parsedParts, precondition, partsFingerprint);
            done = true;
            return response;
        } finally {
            if (!done) {
                // the upload stays usable: back to OPEN so the client can fix the part list or retry
                lock.lock();
                try {
                    Properties reopened = new Properties();
                    reopened.setProperty("state", OPEN);
                    writeProps(uploadDir.resolve(STATE_FILE), reopened, storageProps.isFsync());
                } catch (IOException e) {
                    log.warn("Could not reopen upload {} after a failed Complete: {}", uploadId, e.toString());
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
        return HexFormat.of().formatHex(md5Digest().digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private ResponseEntity<String> doComplete(HttpServletRequest request, String bucket, String uploadId, Path uploadDir,
                                              ParsedParts parsedParts, ManifestRepository.CommitPrecondition precondition,
                                              String partsFingerprint) throws IOException {
        List<Integer> partNums = parsedParts.partNumbers;
        Map<Integer, String> providedEtags = parsedParts.etags;
        Properties meta = readProps(uploadDir.resolve("meta.properties"));
        String objectKey = meta.getProperty("key");
        String contentType = meta.getProperty("contentType", "application/octet-stream");
        ChecksumAlgorithm uploadAlg = ChecksumAlgorithm.fromAwsName(meta.getProperty("checksumAlgorithm")).orElse(null);
        ChecksumType uploadType = uploadAlg == null ? null
                : ChecksumType.valueOf(meta.getProperty("checksumType", uploadAlg.defaultMultipartType().name()));

        Properties etagProps = readProps(uploadDir.resolve("etags.properties"));
        Properties partChecksums = readProps(uploadDir.resolve(CHECKSUMS_FILE));

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
            for (Map.Entry<ChecksumAlgorithm, String> given : parsedParts.checksums.getOrDefault(pn, Map.of()).entrySet()) {
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

            Path partFile = uploadDir.resolve(String.format("part-%05d", pn));
            if (!Files.isRegularFile(partFile)) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPart", "Part " + pn + " has not been uploaded");
            }
            partFiles.add(partFile);   // opened lazily, one at a time, while the data is streamed
        }

        // The composite checksum (algorithm over the concatenated binary part checksums, suffixed "-N"), and the
        // whole-upload checksum the client may have sent in the Complete request headers.
        String composite = uploadType == ChecksumType.COMPOSITE ? uploadAlg.composite(partDigests) : null;
        UploadChecksums wholeObject = UploadChecksums.none();
        for (ChecksumAlgorithm a : ChecksumAlgorithm.values()) {
            String header = request.getHeader(a.headerName());
            if (header == null) continue;
            if (a != uploadAlg) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                        "The upload was not created with the " + a.name() + " checksum algorithm.");
            }
            if (composite != null) {
                if (!composite.equals(header.trim())) throw ChecksumMismatchException.forAlgorithm(a);
            } else {
                try {
                    wholeObject.algorithm(a, a.decode(header));
                } catch (IllegalArgumentException e) {
                    throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                            "Value for " + a.headerName() + " header is invalid.");
                }
            }
        }
        if (uploadType == ChecksumType.FULL_OBJECT && wholeObject.algorithm() == null) {
            wholeObject = UploadChecksums.compute(uploadAlg);
        }

        PoolEntity pool = lookupBucket(bucket);
        ManifestEntity entity;
        try (InputStream combined = new LazySequenceInputStream(partFiles)) {
            entity = fileService.stageStream(combined, pool, objectKey, contentType, wholeObject);
        }

        String s3Etag;
        try {
            // Compute S3 multipart ETag: md5(concat of binary md5s)-{partCount}
            s3Etag = "\"" + HexFormat.of().formatHex(md5Digest().digest(etagConcat)) + "-" + partNums.size() + "\"";
            entity.setEtag(s3Etag);
            if (meta.getProperty("cacheControl") != null) entity.setCacheControl(meta.getProperty("cacheControl"));
            if (meta.getProperty("contentDisposition") != null) entity.setContentDisposition(meta.getProperty("contentDisposition"));
            if (meta.getProperty("contentEncoding") != null) entity.setContentEncoding(meta.getProperty("contentEncoding"));
            if (meta.getProperty("contentLanguage") != null) entity.setContentLanguage(meta.getProperty("contentLanguage"));
            Map<String, String> userMeta = new java.util.LinkedHashMap<>();
            for (String name : meta.stringPropertyNames()) {
                if (name.startsWith(USER_META_PREFIX)) userMeta.put(name.substring(USER_META_PREFIX.length()), meta.getProperty(name));
            }
            if (!userMeta.isEmpty()) entity.setUserMetadata(userMeta);
            if (composite != null) {
                entity.setChecksumAlgorithm(uploadAlg);
                entity.setChecksumValue(composite);
                entity.setChecksumType(ChecksumType.COMPOSITE);
            }
            // One transaction: the manifest with its final multipart ETag / checksum, the (bucket, key) index entry, and
            // retirement of the version it replaces.
            entity = fileService.commitObject(entity, precondition);
        } finally {
            fileService.abortStaged(entity);   // no-op after a commit; releases the garbage collector hold on a failure
        }

        // Keep a tombstone (meta + result) so that a client retry of this Complete gets the same answer; the parts go
        String checksumXml = checksumResultXml(entity);
        ReentrantLock lock = lockOf(uploadId);
        lock.lock();
        try {
            Properties done = new Properties();
            done.setProperty("state", COMPLETED);
            done.setProperty("completedAt", String.valueOf(System.currentTimeMillis()));
            done.setProperty("key", objectKey);
            done.setProperty("etag", s3Etag);
            done.setProperty("checksumXml", checksumXml);
            done.setProperty("partsFingerprint", partsFingerprint);
            writeProps(uploadDir.resolve(STATE_FILE), done, storageProps.isFsync());
        } finally {
            lock.unlock();
        }
        deleteParts(uploadDir);

        return completeResponse(request, bucket, objectKey, s3Etag, checksumXml);
    }

    private PoolEntity lookupBucket(String bucket) {
        return lookup.requireBucket(bucket);
    }

    private ResponseEntity<String> completeResponse(HttpServletRequest request, String bucket, String objectKey,
                                                    String etag, String checksumXml) {
        String host = request.getHeader("Host");
        String location = request.getScheme() + "://" + (host != null ? host : request.getServerName())
                + "/" + bucket + "/" + objectKey;
        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                      "<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                      "<Location>" + S3Support.xmlEscape(location) + "</Location>" +
                      "<Bucket>" + S3Support.xmlEscape(bucket) + "</Bucket>" +
                      "<Key>" + S3Support.xmlEscape(objectKey) + "</Key>" +
                      "<ETag>" + S3Support.xmlEscape(etag) + "</ETag>" + checksumXml +
                      "</CompleteMultipartUploadResult>");
    }

    /** Removes the part files and part records of a finished upload, keeping meta.properties and the state tombstone. */
    private void deleteParts(Path uploadDir) {
        try (var files = Files.list(uploadDir)) {
            files.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith("part-") || n.equals("etags.properties") || n.equals(CHECKSUMS_FILE);
            }).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException e) { log.warn("Could not delete {}: {}", f, e.toString()); }
            });
        } catch (IOException e) {
            log.warn("Could not clean up the parts of {}: {}", uploadDir.getFileName(), e.toString());
        }
    }

    // ── ListParts ─────────────────────────────────────────────────────────────

    @GetMapping(value = "/{bucket}/**", params = "uploadId",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> listParts(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            @RequestParam(value = "part-number-marker", required = false, defaultValue = "0") int partNumberMarker,
            @RequestParam(value = "max-parts", required = false, defaultValue = "1000") int maxParts,
            HttpServletRequest request)
            throws IOException {

        if (partNumberMarker < 0 || maxParts < 0) {
            throw S3Exception.invalidArgument("part-number-marker and max-parts must not be negative");
        }
        maxParts = Math.min(maxParts, 1000);
        Path uploadDir = validateUploadId(uploadId, bucket, request);

        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve("meta.properties"))) {
            meta.load(is);
        }
        String objectKey = meta.getProperty("key");
        Properties partChecksums = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve(CHECKSUMS_FILE))) {
            partChecksums.load(is);
        } catch (IOException ignored) {
            // upload without part checksums
        }

        // Load part ETags
        Properties etagProps = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve("etags.properties"))) {
            etagProps.load(is);
        } catch (IOException ignored) {
            // No parts yet
        }

        // Build parts list
        List<Integer> partNums = new TreeSet<>(etagProps.stringPropertyNames().stream()
                .map(Integer::parseInt)
                .filter(pn -> pn > partNumberMarker)
                .collect(Collectors.toList())).stream().limit(maxParts + 1).toList();

        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListPartsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
                .append("<Bucket>").append(S3Support.xmlEscape(bucket)).append("</Bucket>")
                .append("<Key>").append(S3Support.xmlEscape(objectKey)).append("</Key>")
                .append("<UploadId>").append(uploadId).append("</UploadId>")
                .append("<PartNumberMarker>").append(partNumberMarker).append("</PartNumberMarker>")
                .append("<MaxParts>").append(maxParts).append("</MaxParts>")
                .append("<IsTruncated>").append(partNums.size() > maxParts).append("</IsTruncated>");
        if (meta.getProperty("checksumAlgorithm") != null) {
            xml.append("<ChecksumAlgorithm>").append(meta.getProperty("checksumAlgorithm")).append("</ChecksumAlgorithm>")
               .append("<ChecksumType>").append(meta.getProperty("checksumType")).append("</ChecksumType>");
        }

        int nextMarker = 0;
        int count = 0;
        for (int pn : partNums) {
            if (count >= maxParts) {
                nextMarker = pn;
                break;
            }
            String etag = etagProps.getProperty(String.valueOf(pn));
            long size = Files.size(uploadDir.resolve(String.format("part-%05d", pn)));
            xml.append("<Part>")
               .append("<PartNumber>").append(pn).append("</PartNumber>")
               .append("<LastModified>").append(S3Support.isoDate(
                       Files.getLastModifiedTime(uploadDir.resolve(String.format("part-%05d", pn))).toInstant())).append("</LastModified>")
               .append("<ETag>\"").append(etag).append("\"</ETag>")
               .append("<Size>").append(size).append("</Size>");
            String pc = partChecksums.getProperty(String.valueOf(pn));
            if (pc != null) {
                String alg = pc.substring(0, pc.indexOf(':'));
                xml.append("<Checksum").append(alg).append(">").append(pc.substring(pc.indexOf(':') + 1))
                   .append("</Checksum").append(alg).append(">");
            }
            xml.append("</Part>");
            count++;
        }

        if (nextMarker > 0) {
            xml.append("<NextPartNumberMarker>").append(nextMarker).append("</NextPartNumberMarker>");
        }

        xml.append("</ListPartsResult>");
        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .body(xml.toString());
    }

    // ── ListMultipartUploads ──────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "uploads",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> listMultipartUploads(
            @PathVariable String bucket,
            @RequestParam(value = "prefix", required = false, defaultValue = "") String prefix,
            @RequestParam(value = "key-marker", required = false, defaultValue = "") String keyMarker,
            @RequestParam(value = "max-uploads", required = false, defaultValue = "1000") int maxUploads)
            throws IOException {

        lookup.requireBucket(bucket);
        if (maxUploads < 0) throw S3Exception.invalidArgument("max-uploads must not be negative");
        maxUploads = Math.min(maxUploads, 1000);
        Path mpuDirPath = getMpuDir();
        if (!Files.exists(mpuDirPath)) {
            return ResponseEntity.ok()
                    .header("x-amz-request-id", S3Support.requestId())
                    .body(buildEmptyListMultipartUploadsXml(bucket, keyMarker, maxUploads));
        }

        List<String> uploadIds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mpuDirPath)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    uploadIds.add(entry.getFileName().toString());
                }
            }
        }

        // Load metadata for each upload and filter by bucket, prefix, and key-marker
        List<UploadInfo> uploads = new ArrayList<>();
        for (String uploadId : uploadIds) {
            try {
                Properties meta = readProps(mpuDirPath.resolve(uploadId).resolve("meta.properties"));
                if (!bucket.equals(meta.getProperty("bucket"))) continue;
                String key = meta.getProperty("key");
                if (key == null || !key.startsWith(prefix)) continue;
                if (!keyMarker.isEmpty() && key.compareTo(keyMarker) <= 0) continue;
                if (COMPLETED.equals(stateOf(mpuDirPath.resolve(uploadId)))) continue;   // a tombstone, not an open upload
                long initiatedMs = Long.parseLong(meta.getProperty("initiatedAt", "0"));
                uploads.add(new UploadInfo(key, uploadId, Instant.ofEpochMilli(initiatedMs)));
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping unreadable multipart upload {}: {}", uploadId, e.toString());   // one corrupt entry must not break the listing
            }
        }

        // Sort by key
        uploads.sort(Comparator.comparing(u -> u.key));

        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListMultipartUploadsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
                .append("<Bucket>").append(S3Support.xmlEscape(bucket)).append("</Bucket>")
                .append("<KeyMarker>").append(S3Support.xmlEscape(keyMarker)).append("</KeyMarker>")
                .append("<MaxUploads>").append(maxUploads).append("</MaxUploads>")
                .append("<IsTruncated>").append(uploads.size() > maxUploads).append("</IsTruncated>");

        String nextKeyMarker = "";
        int count = 0;
        for (UploadInfo u : uploads) {
            if (count >= maxUploads) {
                nextKeyMarker = u.key;
                break;
            }
            xml.append("<Upload>")
               .append("<Key>").append(S3Support.xmlEscape(u.key)).append("</Key>")
               .append("<UploadId>").append(u.uploadId).append("</UploadId>")
               .append("<Initiated>").append(S3Support.isoDate(u.initiated)).append("</Initiated>")
               .append("<StorageClass>STANDARD</StorageClass>")
               .append("</Upload>");
            count++;
        }

        if (!nextKeyMarker.isEmpty()) {
            xml.append("<NextKeyMarker>").append(S3Support.xmlEscape(nextKeyMarker)).append("</NextKeyMarker>");
        }

        xml.append("</ListMultipartUploadsResult>");
        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .body(xml.toString());
    }

    // ── AbortMultipartUpload ──────────────────────────────────────────────────

    @DeleteMapping(value = "/{bucket}/**", params = "uploadId")
    public ResponseEntity<Void> abortMultipartUpload(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            HttpServletRequest request) throws IOException {

        Path uploadDir = validateUploadId(uploadId, bucket, request);
        ReentrantLock lock = lockOf(uploadId);
        lock.lock();
        try {
            requireOpen(uploadDir, uploadId);   // not while a Complete is running, not after it finished
            deleteDir(uploadDir);
        } finally {
            lock.unlock();
        }
        UPLOAD_LOCKS.remove(uploadId);

        return ResponseEntity.noContent()
                .header("x-amz-request-id", S3Support.requestId())
                .build();
    }

    // ── Cleanup scheduled task ────────────────────────────────────────────────

    @Component
    public static class MultipartCleanup {
        private final StorageProperties storageProps;
        private final long staleAfterMs;
        private final long completedRetentionMs;

        public MultipartCleanup(StorageProperties storageProps,
                                @Value("${sectoriadb.multipart.stale-after:7d}") java.time.Duration staleAfter,
                                @Value("${sectoriadb.multipart.completed-retention:24h}") java.time.Duration completedRetention) {
            this.storageProps = storageProps;
            this.staleAfterMs = staleAfter.toMillis();
            this.completedRetentionMs = completedRetention.toMillis();
        }

        /** Removes abandoned uploads (no activity for stale-after) and expired COMPLETED tombstones; one bad entry never stops the sweep. */
        @Scheduled(fixedDelayString = "${sectoriadb.multipart.cleanup-interval-ms:3600000}",
                   initialDelayString = "${sectoriadb.multipart.cleanup-interval-ms:3600000}")
        public void cleanupStaledUploads() throws IOException {
            Path mpuDir = Path.of(storageProps.getDataDir()).resolve(".multipart");
            if (!Files.exists(mpuDir)) return;

            long now = System.currentTimeMillis();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mpuDir)) {
                for (Path uploadPath : stream) {
                    if (!Files.isDirectory(uploadPath)) continue;
                    String uploadId = uploadPath.getFileName().toString();
                    ReentrantLock lock = lockOf(uploadId);
                    if (!lock.tryLock()) continue;   // busy: next time
                    try {
                        sweep(uploadPath, uploadId, now);
                    } catch (Exception e) {
                        log.warn("Skipping multipart upload {} during cleanup: {}", uploadId, e.toString());
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }

        private void sweep(Path uploadPath, String uploadId, long now) throws IOException {
            Properties state = readProps(uploadPath.resolve(STATE_FILE));
            String st = state.getProperty("state", OPEN);
            long lastActivity = lastActivity(uploadPath);
            if (COMPLETED.equals(st)) {
                long completedAt = parseLong(state.getProperty("completedAt"), lastActivity);
                if (now - completedAt > completedRetentionMs) {
                    log.info("Removing the record of completed upload {}", uploadId);
                    deleteTree(uploadPath);
                    UPLOAD_LOCKS.remove(uploadId);
                }
                return;
            }
            if (COMPLETING.equals(st) && now - lastActivity > 3_600_000L) {
                // the server died during Complete: the upload is usable again
                Properties reopened = new Properties();
                reopened.setProperty("state", OPEN);
                writeProps(uploadPath.resolve(STATE_FILE), reopened, storageProps.isFsync());
                return;
            }
            if (COMPLETING.equals(st)) return;
            if (now - lastActivity > staleAfterMs) {
                log.info("Cleaning up stale upload: {} (idle {} ms)", uploadId, now - lastActivity);
                deleteTree(uploadPath);
                UPLOAD_LOCKS.remove(uploadId);
            }
        }

        /** Last activity = newest of the initiation time and the modification times of the directory and its files. */
        private static long lastActivity(Path uploadPath) throws IOException {
            long newest = Files.getLastModifiedTime(uploadPath).toMillis();
            try (var files = Files.list(uploadPath)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
                }
            }
            Properties meta = new Properties();
            try (InputStream is = Files.newInputStream(uploadPath.resolve("meta.properties"))) {
                meta.load(is);
                newest = Math.max(newest, parseLong(meta.getProperty("initiatedAt"), 0));
            } catch (IOException ignored) {
                // an upload without a readable meta file ages by the file times alone
            }
            return newest;
        }

        private static long parseLong(String v, long fallback) {
            try {
                return v == null ? fallback : Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return fallback;   // a corrupt number must not abort the sweep
            }
        }

        private static void deleteTree(Path dir) {
            try (var s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { /* retried next sweep */ } });
            } catch (IOException ignored) {
                // retried next sweep
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Validates upload ID (UUID format) and bucket ownership.
     * Returns the upload directory path.
     */
    private static final java.util.regex.Pattern UPLOAD_ID_PATTERN = java.util.regex.Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private Path validateUploadId(String uploadId, String bucket, HttpServletRequest request) throws IOException {
        return validateUploadId(uploadId, bucket, request, false);
    }

    /**
     * @param allowCompleted a finished upload is still addressable (a retried Complete gets the stored answer); every
     *                       other operation on it is NoSuchUpload
     */
    private Path validateUploadId(String uploadId, String bucket, HttpServletRequest request, boolean allowCompleted)
            throws IOException {
        // Strict canonical UUID only (hex digits and hyphens): rules out path separators, "..", and the
        // lenient forms UUID.fromString accepts, before the id is ever used as a directory name.
        if (uploadId == null || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw S3Exception.noSuchUpload(uploadId);
        }

        Path uploadDir = getMpuDir().resolve(uploadId);
        if (!uploadDir.normalize().getParent().equals(getMpuDir().normalize())) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        if (!Files.isDirectory(uploadDir)) {
            throw S3Exception.noSuchUpload(uploadId);
        }

        // Verify bucket and key ownership: an upload id is only valid for the object it was created for
        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve("meta.properties"))) {
            meta.load(is);
        } catch (IOException e) {
            throw S3Exception.noSuchUpload(uploadId);
        }

        if (!bucket.equals(meta.getProperty("bucket"))
                || !S3Support.extractKey(request, bucket).equals(meta.getProperty("key"))) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        if (!allowCompleted && COMPLETED.equals(stateOf(uploadDir))) {
            throw S3Exception.noSuchUpload(uploadId);
        }

        return uploadDir;
    }

    /** "bytes=first-last" of UploadPartCopy: malformed is InvalidArgument, beyond the object is InvalidRange. */
    static long[] parseCopyRange(String header, long total) {
        String bad = "The x-amz-copy-source-range value must be of the form bytes=first-last where first and last are the "
                + "zero-based offsets of the first and last bytes to copy";
        if (header == null || !header.startsWith("bytes=")) throw S3Exception.invalidArgument(bad);
        String[] parts = header.substring("bytes=".length()).split("-", -1);
        if (parts.length != 2) throw S3Exception.invalidArgument(bad);
        long start;
        long end;
        try {
            start = Long.parseLong(parts[0].trim());
            end = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            throw S3Exception.invalidArgument(bad);
        }
        if (start < 0 || end < start) throw S3Exception.invalidArgument(bad);
        if (end >= total) {
            throw new S3Exception(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "InvalidRange",
                    "The requested range is not satisfiable");
        }
        return new long[]{start, end};
    }

    /** The checksum algorithm the upload was created with (x-amz-checksum-algorithm), or null. */
    private static ChecksumAlgorithm uploadChecksumAlgorithm(Path uploadDir) throws IOException {
        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve("meta.properties"))) {
            meta.load(is);
        }
        return ChecksumAlgorithm.fromAwsName(meta.getProperty("checksumAlgorithm")).orElse(null);
    }

    private static String checksumResultXml(ManifestEntity entity) {
        if (entity.getChecksumAlgorithm() == null || entity.getChecksumValue() == null) return "";
        String el = S3Checksums.xmlElement(entity.getChecksumAlgorithm());
        return "<" + el + ">" + entity.getChecksumValue() + "</" + el + ">"
                + "<ChecksumType>" + entity.getChecksumType() + "</ChecksumType>";
    }

    static class ParsedParts {
        List<Integer> partNumbers;
        Map<Integer, String> etags;
        /** Per part: checksum values given in the Complete XML (ChecksumCRC32C, ...). */
        Map<Integer, Map<ChecksumAlgorithm, String>> checksums;

        ParsedParts(List<Integer> partNumbers, Map<Integer, String> etags,
                    Map<Integer, Map<ChecksumAlgorithm, String>> checksums) {
            this.partNumbers = partNumbers;
            this.etags = etags;
            this.checksums = checksums;
        }
    }

    private ParsedParts parseCompleteXml(InputStream body) throws IOException {
        try {
            DocumentBuilder db = XmlSupport.newSecureDocumentBuilder(false);
            Document doc = db.parse(body);
            NodeList parts = doc.getElementsByTagName("Part");
            List<Integer> result = new ArrayList<>();
            Map<Integer, String> etags = new HashMap<>();
            Map<Integer, Map<ChecksumAlgorithm, String>> checksums = new HashMap<>();

            for (int i = 0; i < parts.getLength(); i++) {
                Element partElem = (Element) parts.item(i);
                int partNum = 0;
                String etag = null;
                Map<ChecksumAlgorithm, String> partChecksums = new EnumMap<>(ChecksumAlgorithm.class);

                NodeList children = partElem.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    String nodeName = children.item(j).getNodeName();
                    String content = children.item(j).getTextContent().trim();
                    if ("PartNumber".equals(nodeName)) {
                        partNum = Integer.parseInt(content);
                    } else if ("ETag".equals(nodeName)) {
                        etag = content;
                    } else if (nodeName.startsWith("Checksum") && !content.isEmpty()) {
                        ChecksumAlgorithm.fromAwsName(nodeName.substring("Checksum".length()))
                                .ifPresent(a -> partChecksums.put(a, content));
                    }
                }

                if (partNum < 1 || partNum > 10000) {
                    throw S3Exception.invalidArgument("Part number must be an integer between 1 and 10000, inclusive");
                }
                result.add(partNum);
                if (etag != null) {
                    etags.put(partNum, etag);
                }
                if (!partChecksums.isEmpty()) checksums.put(partNum, partChecksums);
            }

            // NOT sorted: S3 requires ascending order and answers InvalidPartOrder otherwise
            return new ParsedParts(result, etags, checksums);
        } catch (S3Exception e) {
            throw e;
        } catch (Exception e) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "The XML you provided was not well-formed or did not validate against our published schema");
        }
    }

    static class UploadInfo {
        String key;
        String uploadId;
        Instant initiated;

        UploadInfo(String key, String uploadId, Instant initiated) {
            this.key = key;
            this.uploadId = uploadId;
            this.initiated = initiated;
        }
    }

    private String buildEmptyListMultipartUploadsXml(String bucket, String keyMarker, int maxUploads) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<ListMultipartUploadsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
               "<Bucket>" + S3Support.xmlEscape(bucket) + "</Bucket>" +
               "<KeyMarker>" + S3Support.xmlEscape(keyMarker) + "</KeyMarker>" +
               "<MaxUploads>" + maxUploads + "</MaxUploads>" +
               "<IsTruncated>false</IsTruncated>" +
               "</ListMultipartUploadsResult>";
    }

    private void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder())
             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Concatenation of part files; each file is opened only when the previous one is exhausted (one descriptor at a time). */
    static class LazySequenceInputStream extends InputStream {
        private final List<Path> files;
        private int next = 0;
        private InputStream current = null;

        LazySequenceInputStream(List<Path> files) {
            this.files = files;
        }

        private boolean advance() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
            if (next >= files.size()) return false;
            current = Files.newInputStream(files.get(next++));
            return true;
        }

        @Override
        public int read() throws IOException {
            while (true) {
                if (current == null && !advance()) return -1;
                int b = current.read();
                if (b >= 0) return b;
                if (!advance()) return -1;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (true) {
                if (current == null && !advance()) return -1;
                int n = current.read(b, off, len);
                if (n > 0) return n;
                if (!advance()) return -1;
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
            next = files.size();
        }
    }
}
