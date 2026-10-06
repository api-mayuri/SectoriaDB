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
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.PoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private Path mpuDir;

    public S3MultipartController(PoolService poolService, FileStorageService fileService,
                                  ManifestRepository manifestRepo, S3Lookup lookup, StorageProperties storageProps) {
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

        // Validate and load upload directory
        Path uploadDir = validateUploadId(uploadId, bucket);

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
        try (InputStream body = S3Support.openBody(request, declared);
             InputStream dis = new MultiDigestInputStream(body, digest)) {
            Files.copy(dis, tmpFile, StandardCopyOption.REPLACE_EXISTING);
            digest.finish();
            wanted.verify(digest);
        } catch (IOException | RuntimeException e) {
            // e.g. payload hash / chunk signature / checksum mismatch: the part must not be kept
            Files.deleteIfExists(tmpFile);
            throw e;
        }

        // Atomic move to final location
        Files.move(tmpFile, partFile, StandardCopyOption.REPLACE_EXISTING,
                   StandardCopyOption.ATOMIC_MOVE);

        // Store ETag (and checksum) for later validation
        String etagValue = HexFormat.of().formatHex(digest.md5());
        recordPartEtag(uploadDir, partNumber, etagValue);
        var ok = ResponseEntity.ok()
                .header("ETag", "\"" + etagValue + "\"")
                .header("x-amz-request-id", S3Support.requestId());
        if (wanted.algorithm() != null) {
            String value = digest.encoded(wanted.algorithm());
            recordPartProperty(uploadDir, CHECKSUMS_FILE, partNumber, wanted.algorithm().name() + ":" + value);
            ok.header(wanted.algorithm().headerName(), value);
        }
        return ok.build();
    }

    /**
     * Records a part's ETag. Parts are uploaded concurrently (aws cli uses 10 threads), so the
     * read-modify-write must be serialized; the file is replaced atomically for readers.
     */
    private static final Object ETAG_LOCK = new Object();

    private static final String CHECKSUMS_FILE = "checksums.properties";

    private static void recordPartEtag(Path uploadDir, int partNumber, String etag) throws IOException {
        recordPartProperty(uploadDir, "etags.properties", partNumber, etag);
    }

    /** Per-part "ALG:base64" checksums, kept beside etags.properties and replaced atomically in the same way. */
    private static void recordPartProperty(Path uploadDir, String fileName, int partNumber, String value)
            throws IOException {
        synchronized (ETAG_LOCK) {
            Path file = uploadDir.resolve(fileName);
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (InputStream is = Files.newInputStream(file)) {
                    props.load(is);
                }
            }
            props.setProperty(String.valueOf(partNumber), value);
            Path tmp = uploadDir.resolve(fileName + ".tmp");
            try (OutputStream os = Files.newOutputStream(tmp)) {
                props.store(os, null);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

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
        Path uploadDir = validateUploadId(uploadId, bucket);

        // Parse copy source: "/bucket/key" or "bucket/key"
        String sourcePath = copySource.startsWith("/") ? copySource.substring(1) : copySource;
        int slashIdx = sourcePath.indexOf('/');
        if (slashIdx < 0) {
            throw S3Exception.invalidArgument("Invalid x-amz-copy-source format");
        }
        String srcBucket = sourcePath.substring(0, slashIdx);
        String srcKey = S3Support.percentDecode(sourcePath.substring(slashIdx + 1)
                .split("\\?")[0]); // Remove ?versionId if present

        // Load source object
        ManifestEntity srcManifest = manifestRepo.findCurrent(srcBucket, srcKey)
                .orElseThrow(() -> S3Exception.noSuchKey(srcBucket, srcKey));

        // Write part (with optional range)
        Path partFile = uploadDir.resolve(String.format("part-%05d", partNumber));
        Path tmpFile = uploadDir.resolve(String.format("part-%05d.tmp.%s", partNumber,
                UUID.randomUUID().toString().substring(0, 8)));

        ChecksumAlgorithm uploadAlg = uploadChecksumAlgorithm(uploadDir);
        MultiDigest digest = new MultiDigest(true, uploadAlg != null ? EnumSet.of(uploadAlg) : EnumSet.noneOf(ChecksumAlgorithm.class));
        try (OutputStream out = new MultiDigestOutputStream(Files.newOutputStream(tmpFile), digest)) {
            if (copyRange != null) {
                // Parse "bytes=a-b"
                String[] parts = copyRange.replaceAll("bytes=", "").split("-");
                long start = Long.parseLong(parts[0]);
                long end = Long.parseLong(parts[1]);
                fileService.streamRange(srcManifest, out, start, end - start + 1);
            } else {
                // a corrupt source (whole-object CRC32C mismatch) fails the copy with 500 and no part is stored
                fileService.streamToOutput(srcManifest, out);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        }
        digest.finish();

        // Atomic move to final location
        Files.move(tmpFile, partFile, StandardCopyOption.REPLACE_EXISTING,
                   StandardCopyOption.ATOMIC_MOVE);

        String etagValue = HexFormat.of().formatHex(digest.md5());
        recordPartEtag(uploadDir, partNumber, etagValue);
        String checksumXml = "";
        if (uploadAlg != null) {
            String value = digest.encoded(uploadAlg);
            recordPartProperty(uploadDir, CHECKSUMS_FILE, partNumber, uploadAlg.name() + ":" + value);
            checksumXml = "<" + S3Checksums.xmlElement(uploadAlg) + ">" + value + "</" + S3Checksums.xmlElement(uploadAlg) + ">";
        }

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

        Path uploadDir = validateUploadId(uploadId, bucket);

        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve("meta.properties"))) {
            meta.load(is);
        }
        String objectKey = meta.getProperty("key");
        String contentType = meta.getProperty("contentType", "application/octet-stream");
        ChecksumAlgorithm uploadAlg = ChecksumAlgorithm.fromAwsName(meta.getProperty("checksumAlgorithm")).orElse(null);
        ChecksumType uploadType = uploadAlg == null ? null
                : ChecksumType.valueOf(meta.getProperty("checksumType", uploadAlg.defaultMultipartType().name()));

        // Parse and validate parts
        ParsedParts parsedParts = parseCompleteXml(S3Support.openBody(request));
        List<Integer> partNums = parsedParts.partNumbers;
        Map<Integer, String> providedEtags = parsedParts.etags;

        if (partNums.isEmpty()) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "Request body is not valid XML or contains no parts");
        }

        // Check part order (must be ascending)
        for (int i = 1; i < partNums.size(); i++) {
            if (partNums.get(i) <= partNums.get(i - 1)) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPartOrder",
                        "The list of parts was not in ascending order");
            }
        }

        // Load ETags and validate parts
        Properties etagProps = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve("etags.properties"))) {
            etagProps.load(is);
        }

        Properties partChecksums = new Properties();
        Path checksumFile = uploadDir.resolve(CHECKSUMS_FILE);
        if (Files.exists(checksumFile)) {
            try (InputStream is = Files.newInputStream(checksumFile)) {
                partChecksums.load(is);
            }
        }

        List<InputStream> streams = new ArrayList<>();
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
            byte[] md5Bytes = hexToBytes(storedEtag);
            System.arraycopy(md5Bytes, 0, etagConcat, concatIdx, 16);
            concatIdx += 16;

            streams.add(Files.newInputStream(uploadDir.resolve(String.format("part-%05d", pn))));
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

        // Combine streams with lazy opening
        PoolEntity pool = poolService.getByName(bucket);
        ManifestEntity entity;
        try (InputStream combined = new LazySequenceInputStream(streams)) {
            entity = fileService.stageStream(combined, pool, objectKey, contentType, wholeObject);
        }

        // Compute S3 multipart ETag: md5(concat of binary md5s)-{partCount}
        String s3Etag = "\"" + HexFormat.of().formatHex(md5Digest().digest(etagConcat)) + "-" + partNums.size() + "\"";
        entity.setEtag(s3Etag);
        if (composite != null) {
            entity.setChecksumAlgorithm(uploadAlg);
            entity.setChecksumValue(composite);
            entity.setChecksumType(ChecksumType.COMPOSITE);
        }
        // One transaction: the manifest with its final multipart ETag / checksum, the (bucket, key) index entry, and
        // retirement of the version it replaces.
        entity = fileService.commitObject(entity);

        // Delete upload directory
        try {
            deleteDir(uploadDir);
        } finally {
            // Don't fail if cleanup fails
        }

        String escapedEtag = S3Support.xmlEscape(s3Etag);
        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                      "<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                      "<Location>http://s3.amazonaws.com/" + S3Support.xmlEscape(bucket) + "/" +
                      S3Support.xmlEscape(objectKey) + "</Location>" +
                      "<Bucket>" + S3Support.xmlEscape(bucket) + "</Bucket>" +
                      "<Key>" + S3Support.xmlEscape(objectKey) + "</Key>" +
                      "<ETag>" + escapedEtag + "</ETag>" + checksumResultXml(entity) +
                      "</CompleteMultipartUploadResult>");
    }

    // ── ListParts ─────────────────────────────────────────────────────────────

    @GetMapping(value = "/{bucket}/**", params = "uploadId",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> listParts(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            @RequestParam(value = "part-number-marker", required = false, defaultValue = "0") int partNumberMarker,
            @RequestParam(value = "max-parts", required = false, defaultValue = "1000") int maxParts)
            throws IOException {

        Path uploadDir = validateUploadId(uploadId, bucket);

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
               .append("<LastModified>").append(S3Support.isoDate(Instant.now())).append("</LastModified>")
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
            Properties meta = new Properties();
            try (InputStream is = Files.newInputStream(mpuDirPath.resolve(uploadId).resolve("meta.properties"))) {
                meta.load(is);
            } catch (IOException ignored) {
                continue;
            }

            String uploadBucket = meta.getProperty("bucket");
            if (!uploadBucket.equals(bucket)) continue;

            String key = meta.getProperty("key");
            if (!key.startsWith(prefix)) continue;

            if (!keyMarker.isEmpty() && key.compareTo(keyMarker) <= 0) continue;

            long initiatedMs = Long.parseLong(meta.getProperty("initiatedAt", "0"));
            uploads.add(new UploadInfo(key, uploadId, Instant.ofEpochMilli(initiatedMs)));
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
            @RequestParam String uploadId) throws IOException {

        Path uploadDir = validateUploadId(uploadId, bucket);
        deleteDir(uploadDir);

        return ResponseEntity.noContent()
                .header("x-amz-request-id", S3Support.requestId())
                .build();
    }

    // ── Cleanup scheduled task ────────────────────────────────────────────────

    @Component
    public static class MultipartCleanup {
        private final StorageProperties storageProps;
        private static final long STALE_UPLOAD_AGE_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days

        public MultipartCleanup(StorageProperties storageProps) {
            this.storageProps = storageProps;
        }

        @Scheduled(fixedDelay = 3600000) // 1 hour
        public void cleanupStaledUploads() throws IOException {
            Path mpuDir = Path.of(storageProps.getDataDir()).resolve(".multipart");
            if (!Files.exists(mpuDir)) return;

            long now = System.currentTimeMillis();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mpuDir)) {
                for (Path uploadPath : stream) {
                    if (!Files.isDirectory(uploadPath)) continue;

                    Properties meta = new Properties();
                    try (InputStream is = Files.newInputStream(uploadPath.resolve("meta.properties"))) {
                        meta.load(is);
                    } catch (IOException ignored) {
                        continue;
                    }

                    long initiated = Long.parseLong(meta.getProperty("initiatedAt", "0"));
                    if (now - initiated > STALE_UPLOAD_AGE_MS) {
                        log.info("Cleaning up stale upload: {} (age: {} ms)", uploadPath.getFileName(), now - initiated);
                        deleteDir(uploadPath);
                    }
                }
            }
        }

        private void deleteDir(Path dir) {
            if (!Files.exists(dir)) return;
            try (var s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Validates upload ID (UUID format) and bucket ownership.
     * Returns the upload directory path.
     */
    private static final java.util.regex.Pattern UPLOAD_ID_PATTERN = java.util.regex.Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private Path validateUploadId(String uploadId, String bucket) throws IOException {
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

        // Verify bucket ownership
        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(uploadDir.resolve("meta.properties"))) {
            meta.load(is);
        } catch (IOException e) {
            throw S3Exception.noSuchUpload(uploadId);
        }

        String storedBucket = meta.getProperty("bucket");
        if (!bucket.equals(storedBucket)) {
            throw S3Exception.noSuchUpload(uploadId);
        }

        return uploadDir;
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

                if (partNum > 0) {
                    result.add(partNum);
                    if (etag != null) {
                        etags.put(partNum, etag);
                    }
                    if (!partChecksums.isEmpty()) checksums.put(partNum, partChecksums);
                }
            }

            result.sort(Integer::compareTo);
            return new ParsedParts(result, etags, checksums);
        } catch (Exception e) {
            throw new IOException("Failed to parse CompleteMultipartUpload XML", e);
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

    private static byte[] hexToBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    /**
     * Lazy sequence input stream that opens part files only when needed.
     * Prevents holding thousands of file descriptors.
     */
    static class LazySequenceInputStream extends InputStream {
        private final List<InputStream> streams;
        private int currentIdx = 0;
        private InputStream current = null;

        LazySequenceInputStream(List<InputStream> streams) {
            this.streams = streams;
        }

        @Override
        public int read() throws IOException {
            while (currentIdx < streams.size()) {
                if (current == null) {
                    current = streams.get(currentIdx);
                }
                int b = current.read();
                if (b >= 0) {
                    return b;
                }
                current.close();
                current = null;
                currentIdx++;
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (currentIdx < streams.size()) {
                if (current == null) {
                    current = streams.get(currentIdx);
                }
                int n = current.read(b, off, len);
                if (n > 0) {
                    return n;
                }
                current.close();
                current = null;
                currentIdx++;
            }
            return -1;
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
            }
            for (InputStream s : streams) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
