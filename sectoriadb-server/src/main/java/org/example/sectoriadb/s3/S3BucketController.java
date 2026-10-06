package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.s3.xml.ListBucketResult;
import org.example.sectoriadb.s3.xml.S3ObjectEntry;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.PoolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 bucket-level operations:
 *
 *   PUT    /{bucket}   — CreateBucket
 *   HEAD   /{bucket}   — HeadBucket
 *   DELETE /{bucket}   — DeleteBucket
 *   GET    /{bucket}   — ListObjects / ListObjectsV2
 */
@RestController
public class S3BucketController {

    private final PoolService poolService;
    private final ManifestRepository manifestRepo;
    private final FileStorageService fileService;
    private final S3Lookup lookup;
    private final AccessControlService accessControl;

    public S3BucketController(PoolService poolService, ManifestRepository manifestRepo,
                               FileStorageService fileService, S3Lookup lookup,
                               AccessControlService accessControl) {
        this.poolService   = poolService;
        this.manifestRepo  = manifestRepo;
        this.fileService   = fileService;
        this.lookup        = lookup;
        this.accessControl = accessControl;
    }

    // ── CreateBucket ──────────────────────────────────────────────────────────

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, consumes = {"application/xml", "text/xml", "*/*"})
    public ResponseEntity<Void> createBucket(@PathVariable String bucket,
                                             HttpServletRequest request) throws IOException {
        // PUT /bucket?<subresource> that no other handler claimed must not silently become CreateBucket
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            throw new S3Exception(HttpStatus.NOT_IMPLEMENTED, "NotImplemented",
                    "A header or query parameter you provided implies functionality that is not implemented");
        }
        // Validate bucket name before attempting to create
        lookup.validateBucketName(bucket);

        PoolEntity pool = poolService.createBucket(bucket);
        // x-amz-acl / x-amz-grant-* headers (e.g. `s3api create-bucket --acl public-read`)
        accessControl.applyBucketAcl(request, new byte[0], pool);
        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Location", "/" + bucket)
                .header("Server", "SectoriaDB")
                .build();
    }

    // ── HeadBucket ────────────────────────────────────────────────────────────

    @RequestMapping(value = {"/{bucket}", "/{bucket}/"}, method = RequestMethod.HEAD)
    public ResponseEntity<Void> headBucket(@PathVariable String bucket) {
        // Verify bucket exists; throws NoSuchBucket if not
        lookup.requireBucket(bucket);

        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Server", "SectoriaDB")
                .build();
    }

    // ── DeleteBucket ──────────────────────────────────────────────────────────

    @DeleteMapping({"/{bucket}", "/{bucket}/"})
    public ResponseEntity<Void> deleteBucket(@PathVariable String bucket) throws java.io.IOException {
        try {
            poolService.deleteBucket(bucket);
        } catch (IllegalStateException e) {
            // Convert BucketNotEmpty error to S3Exception
            String msg = e.getMessage() != null ? e.getMessage() : "The bucket you tried to delete is not empty";
            throw new S3Exception(HttpStatus.CONFLICT, "BucketNotEmpty", msg, "/" + bucket);
        }

        return ResponseEntity.noContent()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Server", "SectoriaDB")
                .build();
    }

    // ── DeleteObjects (Multi-Object Delete) ───────────────────────────────────

    @PostMapping(value = {"/{bucket}", "/{bucket}/"}, params = "delete",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> deleteObjects(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        // Verify bucket exists
        lookup.requireBucket(bucket);

        DeleteRequest del = parseDeleteXml(request.getInputStream());
        List<String> keys = del.keys();
        if (keys.size() > 1000) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "The request must not contain more than 1000 keys");
        }
        StringBuilder result = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");

        for (String key : keys) {
            fileService.deleteObject(bucket, key);   // one transaction per key
            if (!del.quiet()) {
                result.append("<Deleted><Key>").append(S3Support.xmlEscape(key)).append("</Key></Deleted>");
            }
        }
        result.append("</DeleteResult>");

        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .body(result.toString());
    }

    /** Keys of a multi-object delete and its {@code <Quiet>true</Quiet>} flag (successes are then not listed). */
    private record DeleteRequest(List<String> keys, boolean quiet) {}

    private DeleteRequest parseDeleteXml(InputStream body) throws IOException {
        try {
            DocumentBuilder db = XmlSupport.newSecureDocumentBuilder(true);
            Document doc = db.parse(body);
            NodeList objects = doc.getElementsByTagName("Object");
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < objects.getLength(); i++) {
                NodeList children = objects.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    if ("Key".equals(children.item(j).getNodeName())) {
                        keys.add(children.item(j).getTextContent());
                    }
                }
            }
            NodeList quiet = doc.getElementsByTagName("Quiet");
            boolean isQuiet = quiet.getLength() > 0 && "true".equalsIgnoreCase(quiet.item(0).getTextContent().trim());
            return new DeleteRequest(keys, isQuiet);
        } catch (Exception e) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML", "Failed to parse Delete XML");
        }
    }

    // ── ListObjectVersions (unversioned bucket) ───────────────────────────────

    /**
     * GET /{bucket}?versions. SectoriaDB has no versioning, so, like S3 for a bucket that never had versioning
     * enabled, every object is listed once as the latest version with VersionId "null". Clients (SDK "empty the
     * bucket" helpers, ceph/s3-tests cleanup) rely on this listing to find the objects they must delete.
     */
    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "versions", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> listObjectVersions(
            @PathVariable String bucket,
            @RequestParam(value = "prefix",         defaultValue = "") String prefix,
            @RequestParam(value = "delimiter",      required = false) String delimiter,
            @RequestParam(value = "max-keys",       defaultValue = "1000") Integer maxKeysParam,
            @RequestParam(value = "key-marker",     defaultValue = "") String keyMarker,
            @RequestParam(value = "version-id-marker", defaultValue = "") String versionIdMarker) {

        lookup.requireBucket(bucket);
        int maxKeys = maxKeysParam != null ? maxKeysParam : 1000;
        if (maxKeys < 0) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidArgument", "Argument maxKeys must be an integer between 0 and 2147483647");
        }
        if (maxKeys > 1000) maxKeys = 1000;

        ManifestRepository.ObjectListing page = maxKeys == 0
                ? new ManifestRepository.ObjectListing(List.of(), List.of(), false, null)
                : manifestRepo.listObjects(bucket, prefix, delimiter, keyMarker.isEmpty() ? null : keyMarker, maxKeys);

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
                .append("<Name>").append(S3Support.xmlEscape(bucket)).append("</Name>")
                .append("<Prefix>").append(S3Support.xmlEscape(prefix)).append("</Prefix>")
                .append("<KeyMarker>").append(S3Support.xmlEscape(keyMarker)).append("</KeyMarker>")
                .append("<VersionIdMarker>").append(S3Support.xmlEscape(versionIdMarker)).append("</VersionIdMarker>");
        if (page.truncated() && page.nextMarker() != null) {
            xml.append("<NextKeyMarker>").append(S3Support.xmlEscape(page.nextMarker())).append("</NextKeyMarker>")
               .append("<NextVersionIdMarker>null</NextVersionIdMarker>");
        }
        xml.append("<MaxKeys>").append(maxKeys).append("</MaxKeys>");
        if (delimiter != null && !delimiter.isEmpty()) {
            xml.append("<Delimiter>").append(S3Support.xmlEscape(delimiter)).append("</Delimiter>");
        }
        xml.append("<IsTruncated>").append(page.truncated()).append("</IsTruncated>");
        for (ManifestRepository.ObjectSummary o : page.objects()) {
            xml.append("<Version><Key>").append(S3Support.xmlEscape(o.key())).append("</Key>")
               .append("<VersionId>null</VersionId><IsLatest>true</IsLatest>")
               .append("<LastModified>").append(S3Support.isoDate(o.lastModified())).append("</LastModified>")
               .append("<ETag>").append(S3Support.xmlEscape(o.etag() != null ? o.etag() : "\"\"")).append("</ETag>")
               .append("<Size>").append(o.size()).append("</Size>")
               .append("<StorageClass>STANDARD</StorageClass></Version>");
        }
        for (String cp : page.commonPrefixes()) {
            xml.append("<CommonPrefixes><Prefix>").append(S3Support.xmlEscape(cp)).append("</Prefix></CommonPrefixes>");
        }
        xml.append("</ListVersionsResult>");
        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Server", "SectoriaDB")
                .body(xml.toString());
    }

    // ── ListObjects / ListObjectsV2 ───────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<ListBucketResult> listObjects(
            @PathVariable String bucket,
            @RequestParam(value = "prefix",             defaultValue = "") String prefix,
            @RequestParam(value = "delimiter",          required = false) String delimiter,
            @RequestParam(value = "max-keys",           defaultValue = "1000") Integer maxKeysParam,
            @RequestParam(value = "marker",             defaultValue = "") String marker,
            @RequestParam(value = "continuation-token", required = false) String continuationToken,
            @RequestParam(value = "start-after",        required = false) String startAfter,
            @RequestParam(value = "list-type",          defaultValue = "1") int listType,
            @RequestParam(value = "encoding-type",      required = false) String encodingType,
            @RequestParam(value = "fetch-owner",        defaultValue = "false") boolean fetchOwner,
            HttpServletRequest request) {

        S3Support.rejectUnsupportedBucketSubresource(request);

        // Ensure bucket exists; throws NoSuchBucket if not
        lookup.requireBucket(bucket);

        if (listType != 1 && listType != 2) {
            throw S3Exception.invalidArgument("Invalid list-type: " + listType);
        }
        boolean urlEncode = false;
        if (encodingType != null && !encodingType.isEmpty()) {
            if (!"url".equals(encodingType)) {
                throw S3Exception.invalidArgument("Invalid Encoding Method specified in Request");
            }
            urlEncode = true;
        }
        // max-keys: 0 is a valid request for an empty page (not "unlimited"); S3 caps larger values at 1000
        int maxKeys = maxKeysParam != null ? maxKeysParam : 1000;
        if (maxKeys < 0) {
            throw S3Exception.invalidArgument("Argument maxKeys must be an integer between 0 and 2147483647");
        }
        if (maxKeys > 1000) maxKeys = 1000;

        // ListObjectsV2: continuation-token wins over start-after; V1: marker
        String after;
        if (listType == 2) {
            after = continuationToken != null ? continuationToken : startAfter;
        } else {
            after = marker.isEmpty() ? null : marker;
        }

        // Range scan of the (bucket, key) index from max(prefix, after), maxKeys entries (keys and common prefixes
        // together); delimiter groups are skipped as whole ranges. See MetaStoreManifestRepository.listObjects.
        ManifestRepository.ObjectListing page = maxKeys == 0
                ? new ManifestRepository.ObjectListing(List.of(), List.of(), false, null)
                : manifestRepo.listObjects(bucket, prefix, delimiter, after, maxKeys);
        List<ManifestRepository.ObjectSummary> pageObjects = page.objects();
        List<String> pageCommonPrefixes = page.commonPrefixes();
        boolean isTruncated = page.truncated();
        String nextMarker = page.nextMarker();

        // Build response
        ListBucketResult result = new ListBucketResult();
        final boolean enc = urlEncode;
        java.util.function.UnaryOperator<String> e = v -> enc ? S3Support.urlEncodeKey(v) : v;
        result.setName(bucket);
        result.setPrefix(e.apply(prefix));
        if (enc) result.setEncodingType("url");
        if (delimiter != null && !delimiter.isEmpty()) {
            result.setDelimiter(e.apply(delimiter));
        }
        result.setMaxKeys(maxKeys);
        result.setTruncated(isTruncated);

        List<S3ObjectEntry> contents = pageObjects.stream()
                .map(o -> {
                    S3ObjectEntry entry = new S3ObjectEntry(
                            e.apply(o.key()),
                            S3Support.isoDate(o.lastModified()),
                            o.etag() != null ? o.etag() : "\"\"",
                            o.size());
                    // V1 always lists the owner, V2 only with fetch-owner=true
                    if (listType == 1 || fetchOwner) entry.setOwner(new org.example.sectoriadb.s3.xml.Owner("sectoriadb", "sectoriadb"));
                    return entry;
                })
                .toList();

        result.setContents(contents);

        if (listType == 2) {
            // ListObjectsV2 response
            if (continuationToken != null) {
                result.setContinuationToken(continuationToken);
            }
            if (startAfter != null && !startAfter.isEmpty()) {
                result.setStartAfter(e.apply(startAfter));
            }
            result.setKeyCount(contents.size() + pageCommonPrefixes.size());   // keys and common prefixes, as in S3
            if (isTruncated) {
                result.setNextContinuationToken(nextMarker);
            }
        } else {
            // ListObjectsV1 response: Marker is always present (empty when none was given)
            result.setMarker(e.apply(marker));
            if (isTruncated) {
                result.setNextMarker(e.apply(nextMarker));
            }
        }

        // Add CommonPrefixes to result
        if (!pageCommonPrefixes.isEmpty()) {
            result.setCommonPrefixes(pageCommonPrefixes.stream()
                    .map(cp -> new ListBucketResult.CommonPrefix(e.apply(cp)))
                    .toList());
        }

        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Server", "SectoriaDB")
                .body(result);
    }
}
