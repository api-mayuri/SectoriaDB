package org.example.sectoriadb.s3.multipart;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.MultiDigest;
import org.example.sectoriadb.checksum.MultiDigestInputStream;
import org.example.sectoriadb.checksum.MultiDigestOutputStream;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.s3.api.ObjectHeaders;
import org.example.sectoriadb.s3.api.RequestBodies;
import org.example.sectoriadb.s3.api.S3Lookup;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.ByteRange;
import org.example.sectoriadb.s3.http.CopySource;
import org.example.sectoriadb.s3.http.HttpDates;
import org.example.sectoriadb.s3.http.S3Checksums;
import org.example.sectoriadb.s3.http.S3Request;
import org.example.sectoriadb.s3.multipart.MultipartUploadStore.Upload;
import org.example.sectoriadb.s3.multipart.MultipartUploadStore.UploadInfo;
import org.example.sectoriadb.s3.xml.XmlSupport;
import org.example.sectoriadb.storage.ObjectReader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
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
 *
 * The HTTP layer only: the upload directory and its locks are {@link MultipartUploadStore}, the assembly of the object
 * is {@link CompleteMultipartService}.
 */
@RestController
public class S3MultipartController {

    private static final int MAX_PART_NUMBER = ParsedParts.MAX_PARTS;

    private final S3Lookup lookup;
    private final ObjectReader reader;
    private final ManifestRepository manifestRepo;
    private final RequestBodies bodies;
    private final MultipartUploadStore store;
    private final CompleteMultipartService completer;

    public S3MultipartController(S3Lookup lookup, ObjectReader reader, ManifestRepository manifestRepo,
                                 RequestBodies bodies, MultipartUploadStore store, CompleteMultipartService completer) {
        this.lookup = lookup;
        this.reader = reader;
        this.manifestRepo = manifestRepo;
        this.bodies = bodies;
        this.store = store;
        this.completer = completer;
    }

    // ── CreateMultipartUpload ─────────────────────────────────────────────────

    @PostMapping(value = "/{bucket}/**", params = "uploads",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> createMultipartUpload(
            @PathVariable String bucket,
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream") String contentType,
            HttpServletRequest request) throws IOException {

        lookup.requireBucket(bucket);

        String objectKey = S3Request.extractKey(request, bucket);
        lookup.validateObjectKey(objectKey);

        ChecksumAlgorithm checksumAlg = S3Checksums.declaredAlgorithm(request);
        ChecksumType checksumType = S3Checksums.multipartType(request, checksumAlg);
        // Object attributes given at CreateMultipartUpload belong to the finished object (applied at Complete)
        UploadMeta meta = new UploadMeta(bucket, objectKey, contentType, System.currentTimeMillis(),
                ObjectHeaders.of(request), orEmpty(ObjectHeaders.userMetadata(request)), checksumAlg, checksumType);
        Upload upload = store.create(meta);

        var created = ResponseEntity.ok();
        if (checksumAlg != null) {
            created.header(S3Checksums.HEADER_ALGORITHM, checksumAlg.name())
                   .header(S3Checksums.HEADER_TYPE, checksumType.name());
        }
        return created.body(MultipartXml.initiateResult(bucket, objectKey, upload.id()));
    }

    private static Map<String, String> orEmpty(Map<String, String> m) {
        return m == null ? Map.of() : m;
    }

    // ── UploadPart ────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", params = {"partNumber", "uploadId"},
                headers = "!x-amz-copy-source")
    public ResponseEntity<Void> uploadPart(
            @PathVariable String bucket,
            @RequestParam int partNumber,
            @RequestParam String uploadId,
            HttpServletRequest request) throws IOException {

        requirePartNumber(partNumber);
        bodies.requireAcceptableUpload(request);
        Upload upload = openUpload(uploadId, bucket, request, false);

        // Use atomic write with temporary file to handle concurrent uploads
        Path tmpFile = store.newTempPartFile(upload, partNumber);

        // Content-MD5 and the part's x-amz-checksum-* are verified here; a bad part is never kept.
        S3Checksums.Declared declared = S3Checksums.parse(request);
        UploadChecksums wanted = declared.checksums();
        ChecksumAlgorithm uploadAlg = store.readMeta(upload).checksumAlgorithm();
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
            Files.copy(dis, tmpFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            digest.finish();
            wanted.verify(digest);
        } catch (IOException | RuntimeException e) {
            // e.g. payload hash / chunk signature / checksum mismatch: the part must not be kept
            Files.deleteIfExists(tmpFile);
            throw e;
        }
        store.forceIfConfigured(tmpFile);

        String etagValue = HexFormat.of().formatHex(digest.md5());
        String checksumEntry = wanted.algorithm() == null ? null
                : wanted.algorithm().name() + ":" + digest.encoded(wanted.algorithm());
        store.installPart(upload, partNumber, tmpFile, etagValue, checksumEntry);
        var ok = ResponseEntity.ok().header("ETag", "\"" + etagValue + "\"");
        if (wanted.algorithm() != null) {
            ok.header(wanted.algorithm().headerName(), digest.encoded(wanted.algorithm()));
        }
        return ok.build();
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

        requirePartNumber(partNumber);
        Upload upload = openUpload(uploadId, bucket, request, false);

        CopySource source = CopySource.parse(copySource);
        ManifestEntity srcManifest = manifestRepo.findCurrent(source.bucket(), source.key())
                .orElseThrow(() -> S3Exception.noSuchKey(source.bucket(), source.key()));

        // Write part (with optional range)
        Path tmpFile = store.newTempPartFile(upload, partNumber);
        ByteRange range = copyRange == null ? null : ByteRange.parseCopyRange(copyRange, srcManifest.getTotalBytes());
        ChecksumAlgorithm uploadAlg = store.readMeta(upload).checksumAlgorithm();
        MultiDigest digest = new MultiDigest(true, uploadAlg != null ? EnumSet.of(uploadAlg) : EnumSet.noneOf(ChecksumAlgorithm.class));
        try (OutputStream out = new MultiDigestOutputStream(Files.newOutputStream(tmpFile), digest)) {
            if (range != null) {
                reader.streamRange(srcManifest, out, range.first(), range.length());
            } else {
                // a corrupt source (whole-object CRC32C mismatch) fails the copy with 500 and no part is stored
                reader.streamToOutput(srcManifest, out);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        }
        digest.finish();
        store.forceIfConfigured(tmpFile);

        String etagValue = HexFormat.of().formatHex(digest.md5());
        String checksumXml = "";
        String checksumEntry = null;
        if (uploadAlg != null) {
            String value = digest.encoded(uploadAlg);
            checksumEntry = uploadAlg.name() + ":" + value;
            checksumXml = "<" + S3Checksums.xmlElement(uploadAlg) + ">" + value + "</" + S3Checksums.xmlElement(uploadAlg) + ">";
        }
        store.installPart(upload, partNumber, tmpFile, etagValue, checksumEntry);

        return ResponseEntity.ok().body(MultipartXml.copyPartResult(Instant.now(), etagValue, checksumXml));
    }

    private static void requirePartNumber(int partNumber) {
        if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
            throw S3Exception.invalidArgument("Invalid part number: " + partNumber);
        }
    }

    // ── CompleteMultipartUpload ───────────────────────────────────────────────

    @PostMapping(value = "/{bucket}/**", params = "uploadId",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> completeMultipartUpload(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            HttpServletRequest request) throws IOException {

        Upload upload = openUpload(uploadId, bucket, request, true);
        ManifestRepository.CommitPrecondition precondition = S3Request.writePrecondition(request);

        // Parse and validate the part list (bounded body); the order must be ascending, nothing is sorted silently
        ParsedParts parts = CompleteRequestParser.parse(RequestBodies.xmlBody(request));
        parts.requireValid();

        // the checksum of the whole upload, if the client sent one in the request headers
        Map<ChecksumAlgorithm, String> wholeObject = new EnumMap<>(ChecksumAlgorithm.class);
        for (ChecksumAlgorithm a : ChecksumAlgorithm.values()) {
            String header = request.getHeader(a.headerName());
            if (header != null) wholeObject.put(a, header);
        }

        CompleteMultipartService.Result result = completer.complete(upload, bucket, parts, precondition, wholeObject);
        String host = request.getHeader("Host");
        String location = request.getScheme() + "://" + (host != null ? host : request.getServerName())
                + "/" + bucket + "/" + result.key();
        return ResponseEntity.ok()
                .body(MultipartXml.completeResult(location, bucket, result.key(), result.etag(), result.checksumXml()));
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
        Upload upload = openUpload(uploadId, bucket, request, false);

        UploadMeta meta = store.readMeta(upload);
        Properties partChecksums = store.partChecksums(upload);
        Properties etagProps = store.partEtags(upload);

        // Build parts list
        List<Integer> partNums = new TreeSet<>(etagProps.stringPropertyNames().stream()
                .map(Integer::parseInt)
                .filter(pn -> pn > partNumberMarker)
                .collect(Collectors.toList())).stream().limit(maxParts + 1).toList();

        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListPartsResult xmlns=\"").append(MultipartXml.NS).append("\">")
                .append("<Bucket>").append(XmlSupport.escape(bucket)).append("</Bucket>")
                .append("<Key>").append(XmlSupport.escape(meta.key())).append("</Key>")
                .append("<UploadId>").append(uploadId).append("</UploadId>")
                .append("<PartNumberMarker>").append(partNumberMarker).append("</PartNumberMarker>")
                .append("<MaxParts>").append(maxParts).append("</MaxParts>")
                .append("<IsTruncated>").append(partNums.size() > maxParts).append("</IsTruncated>");
        if (meta.checksumAlgorithm() != null) {
            xml.append("<ChecksumAlgorithm>").append(meta.checksumAlgorithm().name()).append("</ChecksumAlgorithm>")
               .append("<ChecksumType>").append(meta.checksumType().name()).append("</ChecksumType>");
        }

        int nextMarker = 0;
        int count = 0;
        for (int pn : partNums) {
            if (count >= maxParts) {
                nextMarker = pn;
                break;
            }
            Path partFile = store.partFile(upload, pn);
            xml.append("<Part>")
               .append("<PartNumber>").append(pn).append("</PartNumber>")
               .append("<LastModified>").append(HttpDates.isoDate(Files.getLastModifiedTime(partFile).toInstant())).append("</LastModified>")
               .append("<ETag>\"").append(etagProps.getProperty(String.valueOf(pn))).append("\"</ETag>")
               .append("<Size>").append(Files.size(partFile)).append("</Size>");
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
        return ResponseEntity.ok().body(xml.toString());
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

        List<UploadInfo> uploads = store.listOpen(bucket, prefix, keyMarker);

        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListMultipartUploadsResult xmlns=\"").append(MultipartXml.NS).append("\">")
                .append("<Bucket>").append(XmlSupport.escape(bucket)).append("</Bucket>")
                .append("<KeyMarker>").append(XmlSupport.escape(keyMarker)).append("</KeyMarker>")
                .append("<MaxUploads>").append(maxUploads).append("</MaxUploads>")
                .append("<IsTruncated>").append(uploads.size() > maxUploads).append("</IsTruncated>");

        String nextKeyMarker = "";
        int count = 0;
        for (UploadInfo u : uploads) {
            if (count >= maxUploads) {
                nextKeyMarker = u.key();
                break;
            }
            xml.append("<Upload>")
               .append("<Key>").append(XmlSupport.escape(u.key())).append("</Key>")
               .append("<UploadId>").append(u.uploadId()).append("</UploadId>")
               .append("<Initiated>").append(HttpDates.isoDate(u.initiated())).append("</Initiated>")
               .append("<StorageClass>STANDARD</StorageClass>")
               .append("</Upload>");
            count++;
        }

        if (!nextKeyMarker.isEmpty()) {
            xml.append("<NextKeyMarker>").append(XmlSupport.escape(nextKeyMarker)).append("</NextKeyMarker>");
        }

        xml.append("</ListMultipartUploadsResult>");
        return ResponseEntity.ok().body(xml.toString());
    }

    // ── AbortMultipartUpload ──────────────────────────────────────────────────

    @DeleteMapping(value = "/{bucket}/**", params = "uploadId")
    public ResponseEntity<Void> abortMultipartUpload(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            HttpServletRequest request) throws IOException {

        store.abort(openUpload(uploadId, bucket, request, false));
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Upload openUpload(String uploadId, String bucket, HttpServletRequest request, boolean allowCompleted)
            throws IOException {
        return store.open(uploadId, bucket, () -> S3Request.extractKey(request, bucket), allowCompleted);
    }
}
