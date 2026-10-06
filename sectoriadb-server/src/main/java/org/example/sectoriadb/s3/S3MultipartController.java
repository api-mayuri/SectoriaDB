package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
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
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
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
    private final StorageProperties storageProps;
    private Path mpuDir;

    public S3MultipartController(PoolService poolService, FileStorageService fileService,
                                  ManifestRepository manifestRepo, StorageProperties storageProps) {
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
        String uploadId = UUID.randomUUID().toString();

        Path uploadDir = getMpuDir().resolve(uploadId);
        Files.createDirectories(uploadDir);

        // Store metadata including bucket and key for later validation
        Properties meta = new Properties();
        meta.setProperty("bucket", bucket);
        meta.setProperty("key", objectKey);
        meta.setProperty("contentType", contentType);
        meta.setProperty("initiatedAt", String.valueOf(System.currentTimeMillis()));
        try (OutputStream os = Files.newOutputStream(uploadDir.resolve("meta.properties"))) {
            meta.store(os, null);
        }

        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
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

        MessageDigest md5 = md5Digest();
        try (InputStream body = S3Support.openBody(request);
             DigestInputStream dis = new DigestInputStream(body, md5)) {
            Files.copy(dis, tmpFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // Atomic move to final location
        Files.move(tmpFile, partFile, StandardCopyOption.REPLACE_EXISTING,
                   StandardCopyOption.ATOMIC_MOVE);

        // Store ETag for later validation
        String etagValue = HexFormat.of().formatHex(md5.digest());
        recordPartEtag(uploadDir, partNumber, etagValue);

        return ResponseEntity.ok()
                .header("ETag", "\"" + etagValue + "\"")
                .header("x-amz-request-id", S3Support.requestId())
                .build();
    }

    /**
     * Records a part's ETag. Parts are uploaded concurrently (aws cli uses 10 threads), so the
     * read-modify-write must be serialized; the file is replaced atomically for readers.
     */
    private static final Object ETAG_LOCK = new Object();

    private static void recordPartEtag(Path uploadDir, int partNumber, String etag) throws IOException {
        synchronized (ETAG_LOCK) {
            Path file = uploadDir.resolve("etags.properties");
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (InputStream is = Files.newInputStream(file)) {
                    props.load(is);
                }
            }
            props.setProperty(String.valueOf(partNumber), etag);
            Path tmp = uploadDir.resolve("etags.properties.tmp");
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
        ManifestEntity srcManifest = manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(srcBucket, srcKey)
                .orElseThrow(() -> S3Exception.noSuchKey(srcBucket, srcKey));

        // Write part (with optional range)
        Path partFile = uploadDir.resolve(String.format("part-%05d", partNumber));
        Path tmpFile = uploadDir.resolve(String.format("part-%05d.tmp.%s", partNumber,
                UUID.randomUUID().toString().substring(0, 8)));

        MessageDigest md5 = md5Digest();
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(tmpFile), md5)) {
            if (copyRange != null) {
                // Parse "bytes=a-b"
                String[] parts = copyRange.replaceAll("bytes=", "").split("-");
                long start = Long.parseLong(parts[0]);
                long end = Long.parseLong(parts[1]);
                fileService.streamRange(srcManifest, out, start, end - start + 1);
            } else {
                fileService.streamToOutput(srcManifest, out);
            }
        }

        // Atomic move to final location
        Files.move(tmpFile, partFile, StandardCopyOption.REPLACE_EXISTING,
                   StandardCopyOption.ATOMIC_MOVE);

        String etagValue = HexFormat.of().formatHex(md5.digest());
        recordPartEtag(uploadDir, partNumber, etagValue);

        String lastModified = S3Support.isoDate(Instant.now());
        String responseBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                      "<CopyPartResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                      "<LastModified>" + lastModified + "</LastModified>" +
                      "<ETag>\"" + etagValue + "\"</ETag>" +
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

        List<InputStream> streams = new ArrayList<>();
        MessageDigest md5Concat = md5Digest();
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

            // Add binary MD5 to concatenation
            byte[] md5Bytes = hexToBytes(storedEtag);
            System.arraycopy(md5Bytes, 0, etagConcat, concatIdx, 16);
            concatIdx += 16;

            streams.add(Files.newInputStream(uploadDir.resolve(String.format("part-%05d", pn))));
        }

        // Delete previous version
        ManifestEntity oldManifest = manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, objectKey)
                .orElse(null);

        // Combine streams with lazy opening
        PoolEntity pool = poolService.getByName(bucket);
        ManifestEntity entity;
        try (InputStream combined = new LazySequenceInputStream(streams)) {
            entity = fileService.storeStream(combined, pool, objectKey, contentType);
        }

        // Compute S3 multipart ETag: md5(concat of binary md5s)-{partCount}
        md5Concat.update(etagConcat);
        String s3Etag = "\"" + HexFormat.of().formatHex(md5Concat.digest()) + "-" + partNums.size() + "\"";
        entity.setEtag(s3Etag);
        manifestRepo.save(entity);

        // Delete old version AFTER successful save
        if (oldManifest != null) {
            fileService.delete(oldManifest.getId());
        }

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
                      "<ETag>" + escapedEtag + "</ETag>" +
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
               .append("<Size>").append(size).append("</Size>")
               .append("</Part>");
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
    private Path validateUploadId(String uploadId, String bucket) throws IOException {
        // Validate UUID format to prevent path traversal
        try {
            UUID.fromString(uploadId);
        } catch (IllegalArgumentException e) {
            throw S3Exception.noSuchUpload(uploadId);
        }

        Path uploadDir = getMpuDir().resolve(uploadId);
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

    static class ParsedParts {
        List<Integer> partNumbers;
        Map<Integer, String> etags;

        ParsedParts(List<Integer> partNumbers, Map<Integer, String> etags) {
            this.partNumbers = partNumbers;
            this.etags = etags;
        }
    }

    private ParsedParts parseCompleteXml(InputStream body) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(body);
            NodeList parts = doc.getElementsByTagName("Part");
            List<Integer> result = new ArrayList<>();
            Map<Integer, String> etags = new HashMap<>();

            for (int i = 0; i < parts.getLength(); i++) {
                Element partElem = (Element) parts.item(i);
                int partNum = 0;
                String etag = null;

                NodeList children = partElem.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    String nodeName = children.item(j).getNodeName();
                    String content = children.item(j).getTextContent().trim();
                    if ("PartNumber".equals(nodeName)) {
                        partNum = Integer.parseInt(content);
                    } else if ("ETag".equals(nodeName)) {
                        etag = content;
                    }
                }

                if (partNum > 0) {
                    result.add(partNum);
                    if (etag != null) {
                        etags.put(partNum, etag);
                    }
                }
            }

            result.sort(Integer::compareTo);
            return new ParsedParts(result, etags);
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
