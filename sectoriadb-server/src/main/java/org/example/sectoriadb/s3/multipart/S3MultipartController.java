package org.example.sectoriadb.s3.multipart;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.MultipartPart;
import org.example.sectoriadb.catalog.MultipartRepository;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.s3.api.ObjectHeaders;
import org.example.sectoriadb.s3.api.RequestBodies;
import org.example.sectoriadb.s3.api.S3Lookup;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.ByteRange;
import org.example.sectoriadb.s3.http.CopySource;
import org.example.sectoriadb.s3.http.HttpDates;
import org.example.sectoriadb.s3.http.S3Checksums;
import org.example.sectoriadb.s3.http.S3Request;
import org.example.sectoriadb.s3.xml.XmlSupport;
import org.example.sectoriadb.storage.multipart.MultipartService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * The HTTP layer only: the state lives in the metadata store and every transaction is {@link MultipartService}
 *. Errors of the service ({@code MultipartException}) are mapped by the exception handler.
 */
@RestController
public class S3MultipartController {

    private static final int MAX_PART_NUMBER = ParsedParts.MAX_PARTS;

    private final S3Lookup lookup;
    private final ManifestRepository manifestRepo;
    private final RequestBodies bodies;
    private final MultipartService multipart;
    private final AccessControlService accessControl;

    public S3MultipartController(S3Lookup lookup, ManifestRepository manifestRepo, RequestBodies bodies,
                                 MultipartService multipart, AccessControlService accessControl) {
        this.lookup = lookup;
        this.manifestRepo = manifestRepo;
        this.bodies = bodies;
        this.multipart = multipart;
        this.accessControl = accessControl;
    }

    // ── CreateMultipartUpload ─────────────────────────────────────────────────

    @PostMapping(value = "/{bucket}/**", params = "uploads",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> createMultipartUpload(
            @PathVariable String bucket,
            @RequestHeader(value = "Content-Type", defaultValue = "application/octet-stream") String contentType,
            HttpServletRequest request) {

        PoolEntity pool = lookup.requireBucket(bucket);

        String objectKey = S3Request.extractKey(request, bucket);
        lookup.validateObjectKey(objectKey);
        bodies.requireWritable();

        ChecksumAlgorithm checksumAlg = S3Checksums.declaredAlgorithm(request);
        ChecksumType checksumType = S3Checksums.multipartType(request, checksumAlg);
        // Object attributes given at CreateMultipartUpload belong to the finished object (applied at Complete)
        MultipartUpload template = new MultipartUpload();
        template.setKey(objectKey);
        template.setContentType(contentType);
        ObjectHeaders headers = ObjectHeaders.of(request);
        template.setCacheControl(headers.cacheControl());
        template.setContentDisposition(headers.contentDisposition());
        template.setContentEncoding(headers.contentEncoding());
        template.setContentLanguage(headers.contentLanguage());
        template.setUserMetadata(ObjectHeaders.userMetadata(request));
        template.setAcl(accessControl.resolveAcl(request, new byte[0]));
        template.setChecksumAlgorithm(checksumAlg);
        template.setChecksumType(checksumAlg == null ? null : checksumType);
        MultipartUpload upload = multipart.create(pool, template);

        var created = ResponseEntity.ok();
        if (checksumAlg != null) {
            created.header(S3Checksums.HEADER_ALGORITHM, checksumAlg.name())
                   .header(S3Checksums.HEADER_TYPE, checksumType.name());
        }
        return created.body(MultipartXml.initiateResult(bucket, objectKey, upload.getUploadId()));
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
        // refused before the body is read when the upload is unknown or finished
        MultipartUpload upload = multipart.requireOpen(uploadId, bucket, S3Request.extractKey(request, bucket));

        // Content-MD5 and the part's x-amz-checksum-* are verified while the part is staged; a bad part is never kept.
        S3Checksums.Declared declared = S3Checksums.parse(request);
        UploadChecksums wanted = declared.checksums();
        ChecksumAlgorithm uploadAlg = upload.getChecksumAlgorithm();
        if (uploadAlg != null) {
            if (wanted.algorithm() == null) {
                wanted.algorithm(uploadAlg, null);   // lenient: the client sent none, compute it so the composite works
            } else if (wanted.algorithm() != uploadAlg) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                        "Checksum Type mismatch occurred, expected checksum Type: " + uploadAlg.name().toLowerCase(Locale.ROOT)
                        + ", actual checksum Type: " + wanted.algorithm().name().toLowerCase(Locale.ROOT));
            }
        }
        MultipartPart part;
        try (InputStream body = bodies.openBody(request, declared)) {
            part = multipart.uploadPart(upload, partNumber, body, wanted);
        }
        var ok = ResponseEntity.ok().header("ETag", "\"" + part.etag() + "\"");
        if (part.algorithm() != null) {
            ok.header(part.algorithm().headerName(), part.checksum());
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
        MultipartUpload upload = multipart.requireOpen(uploadId, bucket, S3Request.extractKey(request, bucket));

        CopySource source = CopySource.parse(copySource);
        ManifestEntity srcManifest = manifestRepo.findCurrent(source.bucket(), source.key())
                .orElseThrow(() -> S3Exception.noSuchKey(source.bucket(), source.key()));
        ByteRange range = copyRange == null ? null : ByteRange.parseCopyRange(copyRange, srcManifest.getTotalBytes());
        bodies.requireAcceptableCopy(request, range == null ? srcManifest.getTotalBytes() : range.length());

        MultipartPart part = multipart.uploadPartCopy(upload, partNumber, srcManifest,
                range == null ? null : new long[]{range.first(), range.length()});

        String checksumXml = "";
        if (upload.getChecksumAlgorithm() != null && part.checksum() != null) {
            String element = S3Checksums.xmlElement(upload.getChecksumAlgorithm());
            checksumXml = "<" + element + ">" + part.checksum() + "</" + element + ">";
        }
        return ResponseEntity.ok().body(MultipartXml.copyPartResult(Instant.ofEpochMilli(part.uploadedAt()), part.etag(), checksumXml));
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

        String key = S3Request.extractKey(request, bucket);
        multipart.requireKnown(uploadId, bucket, key);                   // NoSuchUpload before the body is looked at
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

        List<MultipartRepository.RequestedPart> requested = new ArrayList<>();
        for (int pn : parts.partNumbers()) {
            requested.add(new MultipartRepository.RequestedPart(pn, parts.etags().get(pn),
                    parts.checksums().getOrDefault(pn, Map.of())));
        }
        MultipartRepository.CompleteResult result = multipart.complete(new MultipartRepository.CompleteCommand(
                uploadId, bucket, key, requested, precondition, wholeObject, System.currentTimeMillis()));

        String host = request.getHeader("Host");
        String location = request.getScheme() + "://" + (host != null ? host : request.getServerName())
                + "/" + bucket + "/" + result.key();
        return ResponseEntity.ok()
                .body(MultipartXml.completeResult(location, bucket, result.key(), result.etag(), checksumResultXml(result)));
    }

    private static String checksumResultXml(MultipartRepository.CompleteResult r) {
        if (r.checksumAlgorithm() == null || r.checksumValue() == null) return "";
        String el = S3Checksums.xmlElement(r.checksumAlgorithm());
        return "<" + el + ">" + r.checksumValue() + "</" + el + ">"
                + "<ChecksumType>" + r.checksumType() + "</ChecksumType>";
    }

    // ── ListParts ─────────────────────────────────────────────────────────────

    @GetMapping(value = "/{bucket}/**", params = "uploadId",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> listParts(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            @RequestParam(value = "part-number-marker", required = false, defaultValue = "0") int partNumberMarker,
            @RequestParam(value = "max-parts", required = false, defaultValue = "1000") int maxParts,
            HttpServletRequest request) {

        if (partNumberMarker < 0 || maxParts < 0) {
            throw S3Exception.invalidArgument("part-number-marker and max-parts must not be negative");
        }
        maxParts = Math.min(maxParts, 1000);
        MultipartRepository.PartsPage page = multipart.listParts(uploadId, bucket, S3Request.extractKey(request, bucket),
                partNumberMarker, maxParts);
        MultipartUpload meta = page.upload();

        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListPartsResult xmlns=\"").append(MultipartXml.NS).append("\">")
                .append("<Bucket>").append(XmlSupport.escape(bucket)).append("</Bucket>")
                .append("<Key>").append(XmlSupport.escape(meta.getKey())).append("</Key>")
                .append("<UploadId>").append(uploadId).append("</UploadId>")
                .append("<PartNumberMarker>").append(partNumberMarker).append("</PartNumberMarker>");
        if (page.truncated()) {
            xml.append("<NextPartNumberMarker>").append(page.nextPartNumberMarker()).append("</NextPartNumberMarker>");
        }
        xml.append("<MaxParts>").append(maxParts).append("</MaxParts>")
           .append("<IsTruncated>").append(page.truncated()).append("</IsTruncated>");
        for (MultipartPart p : page.parts()) {
            xml.append("<Part>")
               .append("<PartNumber>").append(p.partNumber()).append("</PartNumber>")
               .append("<LastModified>").append(HttpDates.isoDate(Instant.ofEpochMilli(p.uploadedAt()))).append("</LastModified>")
               .append("<ETag>\"").append(p.etag()).append("\"</ETag>")
               .append("<Size>").append(p.size()).append("</Size>");
            if (p.algorithm() != null && p.checksum() != null) {
                String element = S3Checksums.xmlElement(p.algorithm());
                xml.append("<").append(element).append(">").append(p.checksum()).append("</").append(element).append(">");
            }
            xml.append("</Part>");
        }
        if (meta.getChecksumAlgorithm() != null) {
            xml.append("<ChecksumAlgorithm>").append(meta.getChecksumAlgorithm().name()).append("</ChecksumAlgorithm>")
               .append("<ChecksumType>").append(meta.getChecksumType().name()).append("</ChecksumType>");
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
            @RequestParam(value = "delimiter", required = false) String delimiter,
            @RequestParam(value = "key-marker", required = false, defaultValue = "") String keyMarker,
            @RequestParam(value = "upload-id-marker", required = false, defaultValue = "") String uploadIdMarker,
            @RequestParam(value = "max-uploads", required = false, defaultValue = "1000") int maxUploads) {

        lookup.requireBucket(bucket);
        if (maxUploads < 0) throw S3Exception.invalidArgument("max-uploads must not be negative");
        maxUploads = Math.min(maxUploads, 1000);

        MultipartRepository.UploadListing listing =
                multipart.listUploads(bucket, prefix, delimiter, keyMarker, uploadIdMarker, maxUploads);

        StringBuilder xml = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListMultipartUploadsResult xmlns=\"").append(MultipartXml.NS).append("\">")
                .append("<Bucket>").append(XmlSupport.escape(bucket)).append("</Bucket>")
                .append("<KeyMarker>").append(XmlSupport.escape(keyMarker)).append("</KeyMarker>")
                .append("<UploadIdMarker>").append(XmlSupport.escape(uploadIdMarker)).append("</UploadIdMarker>");
        if (listing.truncated()) {
            xml.append("<NextKeyMarker>").append(XmlSupport.escape(listing.nextKeyMarker())).append("</NextKeyMarker>")
               .append("<NextUploadIdMarker>")
               .append(XmlSupport.escape(listing.nextUploadIdMarker() == null ? "" : listing.nextUploadIdMarker()))
               .append("</NextUploadIdMarker>");
        }
        if (delimiter != null && !delimiter.isEmpty()) {
            xml.append("<Delimiter>").append(XmlSupport.escape(delimiter)).append("</Delimiter>");
        }
        xml.append("<Prefix>").append(XmlSupport.escape(prefix)).append("</Prefix>")
           .append("<MaxUploads>").append(maxUploads).append("</MaxUploads>")
           .append("<IsTruncated>").append(listing.truncated()).append("</IsTruncated>");
        for (MultipartUpload u : listing.uploads()) {
            xml.append("<Upload>")
               .append("<Key>").append(XmlSupport.escape(u.getKey())).append("</Key>")
               .append("<UploadId>").append(u.getUploadId()).append("</UploadId>")
               .append("<Initiated>").append(HttpDates.isoDate(Instant.ofEpochMilli(u.getInitiatedAt()))).append("</Initiated>")
               .append("<StorageClass>STANDARD</StorageClass>");
            if (u.getChecksumAlgorithm() != null) {
                xml.append("<ChecksumAlgorithm>").append(u.getChecksumAlgorithm().name()).append("</ChecksumAlgorithm>")
                   .append("<ChecksumType>").append(u.getChecksumType().name()).append("</ChecksumType>");
            }
            xml.append("</Upload>");
        }
        for (String common : listing.commonPrefixes()) {
            xml.append("<CommonPrefixes><Prefix>").append(XmlSupport.escape(common)).append("</Prefix></CommonPrefixes>");
        }
        xml.append("</ListMultipartUploadsResult>");
        return ResponseEntity.ok().body(xml.toString());
    }

    // ── AbortMultipartUpload ──────────────────────────────────────────────────

    @DeleteMapping(value = "/{bucket}/**", params = "uploadId")
    public ResponseEntity<Void> abortMultipartUpload(
            @PathVariable String bucket,
            @RequestParam String uploadId,
            HttpServletRequest request) {

        multipart.abort(uploadId, bucket, S3Request.extractKey(request, bucket));
        return ResponseEntity.noContent().build();
    }
}
