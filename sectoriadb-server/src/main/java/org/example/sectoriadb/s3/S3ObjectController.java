package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.impl.ChunkCorruptedException;
import org.example.sectoriadb.service.impl.ChunkNotFoundException;
import org.example.sectoriadb.service.impl.ObjectCorruptedException;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.example.sectoriadb.service.PoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private final PoolService poolService;
    private final FileStorageService fileService;
    private final S3Lookup lookup;
    private final AccessControlService accessControl;

    public S3ObjectController(PoolService poolService, FileStorageService fileService, S3Lookup lookup,
                               AccessControlService accessControl) {
        this.poolService   = poolService;
        this.fileService   = fileService;
        this.lookup        = lookup;
        this.accessControl = accessControl;
    }

    // ── PutObject ─────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", headers = "!x-amz-copy-source")
    public ResponseEntity<Void> putObject(
            @PathVariable String bucket,
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream") String contentType,
            @RequestHeader(value = "Cache-Control", required = false) String cacheControl,
            @RequestHeader(value = "Content-Disposition", required = false) String contentDisposition,
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            @RequestHeader(value = "Content-Language", required = false) String contentLanguage,
            @RequestHeader(value = "x-amz-acl", required = false) String acl,
            HttpServletRequest request) throws IOException {

        String objectKey = S3Support.extractKey(request, bucket);
        lookup.validateObjectKey(objectKey);
        // UploadPart needs both parameters (the multipart controller maps that pair); one of them alone must not
        // silently overwrite the object with the part body
        if (request.getParameter("uploadId") != null || request.getParameter("partNumber") != null) {
            throw S3Exception.invalidArgument("UploadPart requires both partNumber and uploadId");
        }

        // Verify bucket exists; throws NoSuchBucket if not
        PoolEntity pool = lookup.requireBucket(bucket);

        // Content-MD5 / x-amz-checksum-* / trailing checksum: verified inside storeStream before anything is committed
        S3Checksums.Declared declared = S3Checksums.parse(request);

        // Step 1: blob data (not visible yet). Step 2: ONE transaction that saves the complete manifest, points the
        // (bucket, key) index at it and retires the previous version, so concurrent PUTs of a key cannot both win.
        try (InputStream rawIn = S3Support.openBody(request, declared)) {
            ManifestEntity entity = fileService.stageStream(rawIn, pool, objectKey, contentType, declared.checksums());

            // Set additional metadata
            if (cacheControl != null) entity.setCacheControl(cacheControl);
            if (contentDisposition != null) entity.setContentDisposition(contentDisposition);
            if (contentEncoding != null) entity.setContentEncoding(contentEncoding);
            if (contentLanguage != null) entity.setContentLanguage(contentLanguage);

            entity.setUserMetadata(extractUserMetadata(request));

            accessControl.applyObjectAcl(request, new byte[0], entity);
            entity = fileService.commitObject(entity);

            var ok = ResponseEntity.ok()
                    .header("ETag", entity.getEtag() != null ? entity.getEtag() : "\"\"")
                    .header("x-amz-request-id", S3Support.requestId())
                    .header("Server", "SectoriaDB");
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

        String objectKey = S3Support.extractKey(request, bucket);
        ManifestEntity entity = lookup.requireObject(bucket, objectKey);

        String etag = entity.getEtag() != null ? entity.getEtag() : "\"\"";
        checkPartNumber(partNumber, etag);
        response.setHeader("x-amz-request-id", S3Support.requestId());
        response.setHeader("Server", "SectoriaDB");

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
        long[] range = parseRange(rangeHeader, total);
        if (range != null) {
            start = range[0];
            length = range[1] - range[0] + 1;
            partial = true;
            response.setHeader("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + total);
        }

        String ct = respContentType != null ? respContentType
                : (entity.getContentType() != null ? entity.getContentType() : "application/octet-stream");
        response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(safeParseMediaType(ct).toString());
        response.setContentLengthLong(length);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", etag);
        response.setHeader("Last-Modified", HTTP_DATE.format(entity.getCreatedAt()));
        setIfPresent(response, "Cache-Control", respCacheControl, entity.getCacheControl());
        setIfPresent(response, "Content-Disposition", respContentDisposition, entity.getContentDisposition());
        setIfPresent(response, "Content-Encoding", respContentEncoding, entity.getContentEncoding());
        setIfPresent(response, "Content-Language", respContentLanguage, entity.getContentLanguage());
        if (respExpires != null) response.setHeader("Expires", respExpires);
        if (entity.getUserMetadata() != null) entity.getUserMetadata().forEach(response::setHeader);
        // Like S3, checksums are returned for full-object reads only; a range cannot be checked against them.
        if (!partial && S3Checksums.modeEnabled(request)) S3Checksums.addObjectHeaders(entity, response::setHeader);

        if (length == 0) return;
        OutputStream out = response.getOutputStream();
        try {
            if (partial) {
                // Range reads rely on the per-chunk / per-record CRC32C only.
                fileService.streamRange(entity, out, start, length);
            } else {
                // Verifies the whole-object CRC32C while streaming; the last chunk is held back until it matches.
                fileService.streamToOutput(entity, out);
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

    private static void setIfPresent(HttpServletResponse response, String name, String override, String stored) {
        String value = override != null ? override : stored;
        if (value != null) response.setHeader(name, value);
    }

    /**
     * Parses a single "bytes=a-b" / "bytes=a-" / "bytes=-n" range. Returns null when the header is absent
     * or not a single range (S3 then serves the whole object); throws 416 when it cannot be satisfied.
     * An end beyond the object size is clamped, as S3 does.
     */
    private static long[] parseRange(String header, long total) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) return null;
        String[] parts = header.substring("bytes=".length()).split("-", 2);
        long start;
        long end;
        try {
            if (parts[0].isBlank()) {
                long suffix = Long.parseLong(parts[1].trim());
                if (suffix <= 0) throw invalidRange();
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(parts[0].trim());
                end = parts.length < 2 || parts[1].isBlank() ? total - 1 : Long.parseLong(parts[1].trim());
            }
        } catch (NumberFormatException e) {
            return null;   // malformed → ignore the header, like S3
        }
        if (start >= total || start < 0) throw invalidRange();
        end = Math.min(end, total - 1);
        if (end < start) return null;
        return new long[]{start, end};
    }

    private static S3Exception invalidRange() {
        return new S3Exception(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "InvalidRange",
                "The requested range is not satisfiable");
    }

    /**
     * {@code ?partNumber=N} reads one part of a multipart object. The manifest does not keep part boundaries, so a
     * multi-part object cannot serve a single part: answer 501 instead of silently returning the whole object (which
     * is what clients such as warp's "multipart" mode would then mistake for the part).
     */
    private static void checkPartNumber(Integer partNumber, String etag) {
        if (partNumber == null) return;
        if (partNumber < 1 || partNumber > 10000) {
            throw S3Exception.invalidArgument("Part number must be an integer between 1 and 10000, inclusive");
        }
        int parts = 0;                                   // 0: not a multipart object
        int dash = etag.lastIndexOf('-');
        if (dash > 0) {
            try {
                parts = Integer.parseInt(etag.substring(dash + 1).replace("\"", ""));
            } catch (NumberFormatException ignored) { /* a plain ETag */ }
        }
        if (partNumber > Math.max(parts, 1)) {
            throw new S3Exception(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "InvalidPartNumber",
                    "The requested partnumber is not satisfiable");
        }
        if (parts > 1) {
            throw new S3Exception(HttpStatus.NOT_IMPLEMENTED, "NotImplemented",
                    "Reading a single part of a multipart object (partNumber) is not implemented");
        }
    }

    // ── HeadObject ────────────────────────────────────────────────────────────

    @RequestMapping(value = "/{bucket}/**", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headObject(
            @PathVariable String bucket,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(value = "partNumber", required = false) Integer partNumber,
            HttpServletRequest request) {

        String objectKey = S3Support.extractKey(request, bucket);
        ManifestEntity entity = lookup.requireObject(bucket, objectKey);

        String etag = entity.getEtag() != null ? entity.getEtag() : "\"\"";
        checkPartNumber(partNumber, etag);
        String reqId = S3Support.requestId();

        // Handle conditional requests
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header("x-amz-request-id", reqId)
                    .build();
        }
        if (ifMatch != null && !ifMatch.equals(etag)) {
            throw new S3Exception(HttpStatus.PRECONDITION_FAILED, "PreconditionFailed",
                    "At least one of the pre-conditions you specified did not hold.");
        }

        String ct = entity.getContentType() != null ? entity.getContentType() : "application/octet-stream";
        var resp = ResponseEntity.ok()
                .contentType(safeParseMediaType(ct))
                .contentLength(entity.getTotalBytes())
                .header("Accept-Ranges", "bytes")
                .header("ETag", etag)
                .header("Last-Modified", HTTP_DATE.format(entity.getCreatedAt()));

        // Add metadata headers (similar to GetObject)
        if (entity.getCacheControl() != null) {
            resp.header("Cache-Control", entity.getCacheControl());
        }
        if (entity.getContentDisposition() != null) {
            resp.header("Content-Disposition", entity.getContentDisposition());
        }
        if (entity.getContentEncoding() != null) {
            resp.header("Content-Encoding", entity.getContentEncoding());
        }
        if (entity.getContentLanguage() != null) {
            resp.header("Content-Language", entity.getContentLanguage());
        }
        addUserMetadata(resp, entity);
        if (S3Checksums.modeEnabled(request)) S3Checksums.addObjectHeaders(entity, resp::header);

        resp.header("x-amz-request-id", reqId)
            .header("Server", "SectoriaDB");

        return resp.build();
    }

    // ── DeleteObject ──────────────────────────────────────────────────────────

    @DeleteMapping("/{bucket}/**")
    public ResponseEntity<Void> deleteObject(
            @PathVariable String bucket,
            HttpServletRequest request) {

        String objectKey = S3Support.extractKey(request, bucket);
        fileService.deleteObject(bucket, objectKey);   // idempotent: deleting a missing key is a 204 as in S3

        return ResponseEntity.noContent()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Server", "SectoriaDB")
                .build();
    }

    // ── CopyObject ────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", headers = "x-amz-copy-source")
    public ResponseEntity<String> copyObject(
            @PathVariable String bucket,
            @RequestHeader(value = "x-amz-copy-source") String copySource,
            @RequestHeader(value = "x-amz-metadata-directive", defaultValue = "COPY") String metadataDirective,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Cache-Control", required = false) String cacheControl,
            @RequestHeader(value = "Content-Disposition", required = false) String contentDisposition,
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            @RequestHeader(value = "Content-Language", required = false) String contentLanguage,
            HttpServletRequest request) throws IOException {

        // x-amz-checksum-algorithm on a copy picks the destination's algorithm; otherwise the source's is kept
        ChecksumAlgorithm requestedAlg = S3Checksums.declaredAlgorithm(request);
        String destKey = S3Support.extractKey(request, bucket);
        lookup.validateObjectKey(destKey);

        // Verify destination bucket exists
        PoolEntity destPool = lookup.requireBucket(bucket);

        // Parse copy source: /source-bucket/source-key or source-bucket/source-key
        String sourceSpec = copySource.startsWith("/") ? copySource.substring(1) : copySource;
        // Remove ?versionId=... suffix if present
        int qIdx = sourceSpec.indexOf('?');
        if (qIdx >= 0) {
            sourceSpec = sourceSpec.substring(0, qIdx);
        }

        // Split into bucket and key
        int slashIdx = sourceSpec.indexOf('/');
        if (slashIdx < 0) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidArgument",
                    "x-amz-copy-source must be in format /bucket/key");
        }

        String sourceBucket = sourceSpec.substring(0, slashIdx);
        String sourceKey = S3Support.percentDecode(sourceSpec.substring(slashIdx + 1));

        // Validate: can't copy to self without REPLACE
        if (sourceBucket.equals(bucket) && sourceKey.equals(destKey) && !"REPLACE".equals(metadataDirective)) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                    "This copy request is illegal because it is trying to copy an object to itself without changing the object's metadata.");
        }

        // Get source object
        ManifestEntity srcEntity = lookup.requireObject(sourceBucket, sourceKey);

        boolean replace = "REPLACE".equalsIgnoreCase(metadataDirective);
        String newContentType = replace
                ? (contentType != null ? contentType : "application/octet-stream")
                : srcEntity.getContentType();

        Path tmp = Files.createTempFile("sectoriadb-copy-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                fileService.streamToOutput(srcEntity, out);
            }
            ChecksumAlgorithm destAlg = requestedAlg != null ? requestedAlg
                    : (srcEntity.getChecksumType() == ChecksumType.FULL_OBJECT ? srcEntity.getChecksumAlgorithm() : null);
            ManifestEntity entity;
            try (InputStream in = Files.newInputStream(tmp)) {
                entity = fileService.stageStream(in, destPool, destKey, newContentType,
                        destAlg != null ? UploadChecksums.compute(destAlg) : UploadChecksums.none());
            }

            if (replace) {
                entity.setCacheControl(cacheControl);
                entity.setContentDisposition(contentDisposition);
                entity.setContentEncoding(contentEncoding);
                entity.setContentLanguage(contentLanguage);
                entity.setUserMetadata(extractUserMetadata(request));
            } else {
                entity.setCacheControl(srcEntity.getCacheControl());
                entity.setContentDisposition(srcEntity.getContentDisposition());
                entity.setContentEncoding(srcEntity.getContentEncoding());
                entity.setContentLanguage(srcEntity.getContentLanguage());
                entity.setUserMetadata(srcEntity.getUserMetadata());
            }
            accessControl.applyObjectAcl(request, new byte[0], entity);
            entity = fileService.commitObject(entity);   // one transaction, replaces the previous destination version

            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<LastModified>" + S3Support.isoDate(entity.getCreatedAt()) + "</LastModified>"
                    + "<ETag>" + S3Support.xmlEscape(entity.getEtag()) + "</ETag>"
                    + "</CopyObjectResult>";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .header("x-amz-request-id", S3Support.requestId())
                    .header("Server", "SectoriaDB")
                    .body(xml);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final String META_PREFIX = "x-amz-meta-";

    private Map<String, String> extractUserMetadata(HttpServletRequest request) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (name.toLowerCase(Locale.ROOT).startsWith(META_PREFIX)) {
                meta.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
            }
        }
        return meta.isEmpty() ? null : meta;
    }

    private void addUserMetadata(ResponseEntity.HeadersBuilder<?> resp, ManifestEntity entity) {
        if (entity.getUserMetadata() != null) {
            entity.getUserMetadata().forEach(resp::header);
        }
    }

    private static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** If-Modified-Since: true when the object has not changed since the given date. */
    private static boolean notModifiedSince(String header, ManifestEntity entity) {
        Instant since = header == null ? null : parseHttpDate(header);
        return since != null && !entity.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).isAfter(since);
    }

    /** If-Unmodified-Since: true when the object HAS changed since the given date. */
    private static boolean modifiedSince(String header, ManifestEntity entity) {
        Instant since = header == null ? null : parseHttpDate(header);
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
