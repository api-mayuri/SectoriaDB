package org.example.sectoriadb.s3.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.engine.error.ChunkCorruptedException;
import org.example.sectoriadb.engine.error.SmallObjectCorruptedException;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.s3.error.ResponseAbortedException;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.ByteRange;
import org.example.sectoriadb.s3.http.CopySource;
import org.example.sectoriadb.s3.http.HttpDates;
import org.example.sectoriadb.s3.http.ResponseRateGuard;
import org.example.sectoriadb.s3.http.S3Checksums;
import org.example.sectoriadb.s3.http.S3Request;
import org.example.sectoriadb.s3.xml.XmlSupport;
import org.example.sectoriadb.storage.ChunkNotFoundException;
import org.example.sectoriadb.storage.ObjectCatalog;
import org.example.sectoriadb.storage.ObjectCorruptedException;
import org.example.sectoriadb.storage.ObjectReader;
import org.example.sectoriadb.storage.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * S3 object-level operations:
 *
 *   PUT    /{bucket}/**  — PutObject (and CopyObject via x-amz-copy-source)
 *   GET    /{bucket}/**  — GetObject (with Range support)
 *   HEAD   /{bucket}/**  — HeadObject
 *   DELETE /{bucket}/**  — DeleteObject
 */
@RestController
public class S3ObjectController {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectController.class);

    private final ObjectWriter writer;
    private final ObjectReader reader;
    private final ObjectCatalog catalog;
    private final S3Lookup lookup;
    private final AccessControlService accessControl;
    private final RequestBodies bodies;
    private final ResponseRateGuard responseGuard;

    public S3ObjectController(ObjectWriter writer, ObjectReader reader, ObjectCatalog catalog, S3Lookup lookup,
                              AccessControlService accessControl, RequestBodies bodies, ResponseRateGuard responseGuard) {
        this.responseGuard = responseGuard;
        this.writer        = writer;
        this.reader        = reader;
        this.catalog       = catalog;
        this.lookup        = lookup;
        this.accessControl = accessControl;
        this.bodies        = bodies;
    }

    // ── PutObject ─────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", headers = "!x-amz-copy-source")
    public ResponseEntity<Void> putObject(
            @PathVariable String bucket,
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream") String contentType,
            HttpServletRequest request) throws IOException {

        String objectKey = S3Request.extractKey(request, bucket);
        lookup.validateObjectKey(objectKey);
        // UploadPart (partNumber + uploadId) and the sub-resource writes (?retention, ?legal-hold, ...) have their own
        // handlers; a request that reaches PutObject with any of those parameters must not overwrite the object
        S3Request.requireOnlyQueryParams(request, "x-id");
        bodies.requireAcceptableUpload(request);
        ManifestRepository.CommitPrecondition precondition = S3Request.writePrecondition(request);
        accessControl.resolveAcl(request, new byte[0]);   // reject a bad ACL before the body is read

        // Verify bucket exists; throws NoSuchBucket if not
        PoolEntity pool = lookup.requireBucket(bucket);

        // Content-MD5 / x-amz-checksum-* / trailing checksum: verified inside storeStream before anything is committed
        S3Checksums.Declared declared = S3Checksums.parse(request);

        // Step 1: blob data (not visible yet). Step 2: ONE transaction that saves the complete manifest, points the
        // (bucket, key) index at it and retires the previous version, so concurrent PUTs of a key cannot both win.
        try (InputStream rawIn = bodies.openBody(request, declared)) {
            ManifestEntity entity = writer.stageStream(rawIn, pool, objectKey, contentType, declared.checksums());

            ObjectHeaders.of(request).applyTo(entity);
            entity.setUserMetadata(ObjectHeaders.userMetadata(request));

            try {
                accessControl.applyObjectAcl(request, new byte[0], entity);
                entity = writer.commitObject(entity, precondition);
            } finally {
                writer.abortStaged(entity);   // no-op after a commit; releases the garbage collector hold on a failure
            }

            var ok = ResponseEntity.ok()
                    .header("ETag", entity.getEtag() != null ? entity.getEtag() : "\"\"");
            S3Checksums.addClientHeaders(entity, ok::header);
            return ok.build();
        }
    }

    // ── GetObject ─────────────────────────────────────────────────────────────

    /**
     * Streams the object synchronously on the request thread. A Spring async StreamingResponseBody must
     * NOT be used here: it has a request timeout (30 s by default) that cuts long downloads/video playback
     * in the middle ("file is corrupt"), and it runs on a small shared pool.
     */
    @GetMapping("/{bucket}/**")
    public void getObject(
            @PathVariable String bucket,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-Modified-Since", required = false) String ifModifiedSince,
            @RequestHeader(value = "If-Unmodified-Since", required = false) String ifUnmodifiedSince,
            @RequestParam(value = "response-content-type", required = false) String respContentType,
            @RequestParam(value = "response-content-disposition", required = false) String respContentDisposition,
            @RequestParam(value = "response-cache-control", required = false) String respCacheControl,
            @RequestParam(value = "response-content-encoding", required = false) String respContentEncoding,
            @RequestParam(value = "response-content-language", required = false) String respContentLanguage,
            @RequestParam(value = "response-expires", required = false) String respExpires,
            @RequestParam(value = "partNumber", required = false) Integer partNumber,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        S3Request.rejectUnsupportedObjectSubresource(request);
        // Overriding response headers (content-type, disposition, ...) would let an anonymous caller turn a public object
        // into text/html on the API origin: AWS refuses them for unsigned requests
        if ((respContentType != null || respContentDisposition != null || respCacheControl != null
                || respContentEncoding != null || respContentLanguage != null || respExpires != null)
                && !Boolean.TRUE.equals(request.getAttribute(S3Request.AUTHENTICATED_ATTRIBUTE))) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                    "Request specific response headers cannot be used for anonymous GET requests.");
        }
        String objectKey = S3Request.extractKey(request, bucket);
        ManifestEntity entity = lookup.requireObject(bucket, objectKey);

        String etag = entity.getEtag() != null ? entity.getEtag() : "\"\"";
        ManifestEntity.Extent partExtent = partExtent(entity, partNumber, rangeHeader);

        if ((ifNoneMatch != null && ifNoneMatch.equals(etag)) || notModifiedSince(ifModifiedSince, entity)) {
            response.setHeader("ETag", etag);
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            return;
        }
        if ((ifMatch != null && !ifMatch.equals(etag)) || modifiedSince(ifUnmodifiedSince, entity)) {
            throw new S3Exception(HttpStatus.PRECONDITION_FAILED, "PreconditionFailed",
                    "At least one of the pre-conditions you specified did not hold.");
        }

        long total = entity.getTotalBytes();
        long start = 0;
        long length = total;
        boolean partial = false;
        ByteRange range = ByteRange.parseRequestRange(rangeHeader, total);
        if (range != null) {
            start = range.first();
            length = range.length();
            partial = true;
            response.setHeader("Content-Range", "bytes " + range.first() + "-" + range.last() + "/" + total);
        } else if (partExtent != null && partExtent.length() > 0) {
            // GET ?partNumber=N of a multipart object: that part's bytes, answered like a range
            start = partExtent.start();
            length = partExtent.length();
            partial = true;
            response.setHeader("Content-Range", "bytes " + start + "-" + (start + length - 1) + "/" + total);
        }
        if (partExtent != null) response.setHeader(PARTS_COUNT_HEADER, String.valueOf(entity.getSegments().size()));

        String ct = respContentType != null ? respContentType
                : (entity.getContentType() != null ? entity.getContentType() : "application/octet-stream");
        response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(safeParseMediaType(ct).toString());
        response.setContentLengthLong(length);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", etag);
        response.setHeader("Last-Modified", HttpDates.httpDate(entity.getCreatedAt()));
        ObjectHeaders.of(entity)
                .overriddenBy(new ObjectHeaders(respCacheControl, respContentDisposition, respContentEncoding, respContentLanguage))
                .writeTo(response::setHeader);
        if (respExpires != null) response.setHeader("Expires", respExpires);
        if (entity.getUserMetadata() != null) entity.getUserMetadata().forEach(response::setHeader);
        // Like S3, checksums are returned for full-object reads only; a range cannot be checked against them.
        if (!partial && S3Checksums.modeEnabled(request)) S3Checksums.addObjectHeaders(entity, response::setHeader);

        if (length == 0) return;
        OutputStream out = responseGuard.wrap(response.getOutputStream());
        try {
            if (partial) {
                // Range reads rely on the per-chunk / per-record CRC32C only.
                reader.streamRange(entity, out, start, length);
            } else {
                // Verifies the whole-object CRC32C while streaming; the last chunk is held back until it matches.
                reader.streamToOutput(entity, out);
            }
        } catch (ClientAbortException e) {
            throw e;
        } catch (ObjectCorruptedException | ChunkCorruptedException | SmallObjectCorruptedException
                 | ChunkNotFoundException e) {
            failStreaming(entity, response, e);
        }
    }

    /**
     * Stored data failed an integrity check while a GET was being served. If nothing has been sent yet the client
     * gets a clean 500; otherwise the body cannot be completed, so the connection is aborted instead of ending
     * the response as if everything were fine.
     */
    private void failStreaming(ManifestEntity entity, HttpServletResponse response, IOException cause) {
        if (!(cause instanceof ObjectCorruptedException)) {   // that one logged itself
            log.error("Integrity failure while serving GET: bucket={} key={} manifest={}: {}",
                    entity.getBucketName(), entity.getObjectKey(), entity.getId(), cause.getMessage());
        }
        if (!response.isCommitted()) {
            response.reset();
            throw new S3Exception(HttpStatus.INTERNAL_SERVER_ERROR, "InternalError",
                    "We encountered an internal error. Please try again.");
        }
        throw new ResponseAbortedException("Aborting response: stored object failed verification", cause);
    }

    static final String PARTS_COUNT_HEADER = "x-amz-mp-parts-count";

    /**
     * {@code ?partNumber=N}: the extent of that part of a multipart object (its boundaries are in the manifest), null when
     * the request names no part. An object that was not uploaded in parts has one part, number 1, which is the whole
     * object (served as a normal 200 response). A part number the object does not have is 416 InvalidPartNumber; the
     * parameter together with a {@code Range} header is 400, as in S3.
     */
    private static ManifestEntity.Extent partExtent(ManifestEntity entity, Integer partNumber, String rangeHeader) {
        if (partNumber == null) return null;
        if (partNumber < 1 || partNumber > 10000) {
            throw S3Exception.invalidArgument("Part number must be an integer between 1 and 10000, inclusive");
        }
        if (rangeHeader != null) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                    "Cannot specify both Range header and partNumber query parameter");
        }
        if (entity.getStorageKind() != StorageKind.MULTIPART) {
            if (partNumber > 1) {
                throw new S3Exception(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "InvalidPartNumber",
                        "The requested partnumber is not satisfiable");
            }
            return null;
        }
        return entity.partExtent(partNumber).orElseThrow(() -> new S3Exception(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                "InvalidPartNumber", "The requested partnumber is not satisfiable"));
    }

    // ── HeadObject ────────────────────────────────────────────────────────────

    @RequestMapping(value = "/{bucket}/**", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headObject(
            @PathVariable String bucket,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(value = "partNumber", required = false) Integer partNumber,
            HttpServletRequest request) {

        String objectKey = S3Request.extractKey(request, bucket);
        ManifestEntity entity = lookup.requireObject(bucket, objectKey);

        String etag = entity.getEtag() != null ? entity.getEtag() : "\"\"";
        ManifestEntity.Extent partExtent = partExtent(entity, partNumber, request.getHeader("Range"));

        // Handle conditional requests
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .build();
        }
        if (ifMatch != null && !ifMatch.equals(etag)) {
            throw new S3Exception(HttpStatus.PRECONDITION_FAILED, "PreconditionFailed",
                    "At least one of the pre-conditions you specified did not hold.");
        }

        String ct = entity.getContentType() != null ? entity.getContentType() : "application/octet-stream";
        boolean partial = partExtent != null && partExtent.length() > 0;
        var resp = ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .contentType(safeParseMediaType(ct))
                .contentLength(partial ? partExtent.length() : entity.getTotalBytes())
                .header("Accept-Ranges", "bytes")
                .header("ETag", etag)
                .header("Last-Modified", HttpDates.httpDate(entity.getCreatedAt()));

        if (partial) {
            resp.header("Content-Range", "bytes " + partExtent.start() + "-" + (partExtent.start() + partExtent.length() - 1)
                    + "/" + entity.getTotalBytes());
        }
        if (partExtent != null) resp.header(PARTS_COUNT_HEADER, String.valueOf(entity.getSegments().size()));

        ObjectHeaders.of(entity).writeTo(resp::header);
        if (entity.getUserMetadata() != null) entity.getUserMetadata().forEach(resp::header);
        if (!partial && S3Checksums.modeEnabled(request)) S3Checksums.addObjectHeaders(entity, resp::header);

        return resp.build();
    }

    // ── DeleteObject ──────────────────────────────────────────────────────────

    @DeleteMapping("/{bucket}/**")
    public ResponseEntity<Void> deleteObject(
            @PathVariable String bucket,
            HttpServletRequest request) {

        S3Request.requireOnlyQueryParams(request, "x-id", "versionId");
        String objectKey = S3Request.extractKey(request, bucket);
        catalog.deleteObject(bucket, objectKey);   // idempotent: deleting a missing key is a 204 as in S3

        return ResponseEntity.noContent()
                .build();
    }

    // ── CopyObject ────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", headers = "x-amz-copy-source")
    public ResponseEntity<String> copyObject(
            @PathVariable String bucket,
            @RequestHeader(value = "x-amz-copy-source") String copySource,
            @RequestHeader(value = "x-amz-metadata-directive", defaultValue = "COPY") String metadataDirective,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            HttpServletRequest request) throws IOException {

        S3Request.rejectCopySourceConditions(request);
        if (!"COPY".equalsIgnoreCase(metadataDirective) && !"REPLACE".equalsIgnoreCase(metadataDirective)) {
            throw S3Exception.invalidArgument("Unknown metadata directive.");
        }
        // x-amz-checksum-algorithm on a copy picks the destination's algorithm; otherwise the source's is kept
        ChecksumAlgorithm requestedAlg = S3Checksums.declaredAlgorithm(request);
        String destKey = S3Request.extractKey(request, bucket);
        lookup.validateObjectKey(destKey);

        // Verify destination bucket exists
        PoolEntity destPool = lookup.requireBucket(bucket);

        CopySource source = CopySource.parse(copySource);
        String sourceBucket = source.bucket();
        String sourceKey = source.key();

        // Validate: can't copy to self without REPLACE
        if (sourceBucket.equals(bucket) && sourceKey.equals(destKey) && !"REPLACE".equalsIgnoreCase(metadataDirective)) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                    "This copy request is illegal because it is trying to copy an object to itself without changing the object's metadata.");
        }

        // Get source object
        ManifestEntity srcEntity = lookup.requireObject(sourceBucket, sourceKey);

        boolean replace = "REPLACE".equalsIgnoreCase(metadataDirective);
        String newContentType = replace
                ? (contentType != null ? contentType : "application/octet-stream")
                : srcEntity.getContentType();

        Path tmp = Files.createTempFile(writer.uploadTmpDir(), "sectoriadb-copy-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                reader.streamToOutput(srcEntity, out);
            }
            ChecksumAlgorithm destAlg = requestedAlg != null ? requestedAlg
                    : (srcEntity.getChecksumType() == ChecksumType.FULL_OBJECT ? srcEntity.getChecksumAlgorithm() : null);
            ManifestEntity entity;
            try (InputStream in = Files.newInputStream(tmp)) {
                entity = writer.stageStream(in, destPool, destKey, newContentType,
                        destAlg != null ? UploadChecksums.compute(destAlg) : UploadChecksums.none());
            }

            if (replace) {
                ObjectHeaders.of(request).applyTo(entity);
                entity.setUserMetadata(ObjectHeaders.userMetadata(request));
            } else {
                ObjectHeaders.of(srcEntity).applyTo(entity);
                entity.setUserMetadata(srcEntity.getUserMetadata());
            }
            try {
                accessControl.applyObjectAcl(request, new byte[0], entity);
                entity = writer.commitObject(entity);   // one transaction, replaces the previous destination version
            } finally {
                writer.abortStaged(entity);   // no-op after a commit; releases the garbage collector hold on a failure
            }

            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<LastModified>" + HttpDates.isoDate(entity.getCreatedAt()) + "</LastModified>"
                    + "<ETag>" + XmlSupport.escape(entity.getEtag()) + "</ETag>"
                    + "</CopyObjectResult>";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(xml);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** If-Modified-Since: true when the object has not changed since the given date. */
    private static boolean notModifiedSince(String header, ManifestEntity entity) {
        Instant since = header == null ? null : HttpDates.parseHttpDate(header);
        return since != null && !entity.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).isAfter(since);
    }

    /** If-Unmodified-Since: true when the object HAS changed since the given date. */
    private static boolean modifiedSince(String header, ManifestEntity entity) {
        Instant since = header == null ? null : HttpDates.parseHttpDate(header);
        return since != null && entity.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).isAfter(since);
    }

    /**
     * Safely parse media type, defaulting to application/octet-stream on error.
     */
    private MediaType safeParseMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
