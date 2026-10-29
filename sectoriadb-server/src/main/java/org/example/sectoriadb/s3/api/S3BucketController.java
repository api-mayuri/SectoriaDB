package org.example.sectoriadb.s3.api;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.s3.access.IamAuthorizer;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.HttpDates;
import org.example.sectoriadb.s3.http.S3Request;
import org.example.sectoriadb.s3.http.UrlCodec;
import org.example.sectoriadb.s3.xml.ListBucketResult;
import org.example.sectoriadb.s3.xml.Owner;
import org.example.sectoriadb.s3.xml.S3ObjectEntry;
import org.example.sectoriadb.s3.xml.XmlSupport;
import org.example.sectoriadb.storage.ObjectCatalog;
import org.example.sectoriadb.storage.PoolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.xml.parsers.DocumentBuilder;

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
    private final ObjectCatalog catalog;
    private final S3Lookup lookup;
    private final AccessControlService accessControl;
    private final IamAuthorizer iamAuthorizer;
    private final UploadAdmission admission;

    public S3BucketController(PoolService poolService, ManifestRepository manifestRepo,
                               ObjectCatalog catalog, S3Lookup lookup,
                               AccessControlService accessControl, IamAuthorizer iamAuthorizer,
                              UploadAdmission admission) {
        this.admission = admission;
        this.iamAuthorizer = iamAuthorizer;
        this.poolService   = poolService;
        this.manifestRepo  = manifestRepo;
        this.catalog = catalog;
        this.lookup        = lookup;
        this.accessControl = accessControl;
    }

    // ── CreateBucket ──────────────────────────────────────────────────────────

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, consumes = {"application/xml", "text/xml", "*/*"})
    public ResponseEntity<Void> createBucket(@PathVariable String bucket,
                                             HttpServletRequest request) throws IOException {
        // PUT /bucket?<subresource> that no other handler claimed must not silently become CreateBucket
        S3Request.requireOnlyQueryParams(request, "x-id");
        // Validate bucket name before attempting to create
        lookup.validateBucketName(bucket);

        // x-amz-acl / x-amz-grant-* headers (e.g. `s3api create-bucket --acl public-read`): a bad ACL must not leave a bucket behind
        accessControl.resolveAcl(request, new byte[0]);
        // CreateBucket of an EXISTING bucket is idempotent and must never rewrite its ACL (that is PutBucketAcl's job)
        boolean existed = poolService.existsByName(bucket);
        if (!existed) admission.requireWritable();
        PoolEntity pool = poolService.createBucket(bucket);
        if (!existed) accessControl.applyBucketAcl(request, new byte[0], pool);
        return ResponseEntity.ok()
                .header("Location", "/" + bucket)
                .build();
    }

    // ── HeadBucket ────────────────────────────────────────────────────────────

    @RequestMapping(value = {"/{bucket}", "/{bucket}/"}, method = RequestMethod.HEAD)
    public ResponseEntity<Void> headBucket(@PathVariable String bucket) {
        // Verify bucket exists; throws NoSuchBucket if not
        lookup.requireBucket(bucket);

        return ResponseEntity.ok()
                .build();
    }

    // ── DeleteBucket ──────────────────────────────────────────────────────────

    @DeleteMapping({"/{bucket}", "/{bucket}/"})
    public ResponseEntity<Void> deleteBucket(@PathVariable String bucket, HttpServletRequest request) throws IOException {
        // DELETE /bucket?tagging|lifecycle|cors|... that no sub-resource handler claimed must NOT delete the bucket
        S3Request.requireOnlyQueryParams(request, "x-id");
        lookup.requireBucket(bucket);
        try {
            poolService.deleteBucket(bucket);
        } catch (IllegalStateException e) {
            // BucketNotEmpty is decided by the object index and the version history, not by the text of the exception
            if (manifestRepo.hasObjects(bucket) || manifestRepo.hasVersions(bucket)) {
                throw new S3Exception(HttpStatus.CONFLICT, "BucketNotEmpty",
                        "The bucket you tried to delete is not empty", "/" + bucket);
            }
            throw e;
        }

        return ResponseEntity.noContent()
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

        DeleteRequest del = parseDeleteXml(RequestBodies.xmlBody(request));
        List<DeleteTarget> keys = del.keys();
        if (keys.size() > 1000) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "The request must not contain more than 1000 keys");
        }
        StringBuilder result = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");

        String accessKey = (String) request.getAttribute(S3Request.ACCESS_KEY_ATTRIBUTE);
        for (DeleteTarget t : keys) {
            String key = t.key();
            String action = t.versionId() != null ? "s3:DeleteObjectVersion" : "s3:DeleteObject";
            if (accessKey != null && !iamAuthorizer.isAllowed(request, accessKey, action, bucket, key)) {
                // like AWS: the call succeeds and names every refused key
                result.append("<Error><Key>").append(XmlSupport.escape(key)).append("</Key>")
                        .append(t.versionId() != null ? "<VersionId>" + XmlSupport.escape(t.versionId()) + "</VersionId>" : "")
                        .append("<Code>AccessDenied</Code><Message>Access Denied</Message></Error>");
                continue;
            }
            var outcome = catalog.delete(bucket, key, t.versionId());   // one transaction per key
            if (!del.quiet()) {
                result.append("<Deleted><Key>").append(XmlSupport.escape(key)).append("</Key>");
                if (t.versionId() != null) result.append("<VersionId>").append(XmlSupport.escape(t.versionId())).append("</VersionId>");
                if (outcome.deleteMarker()) {
                    result.append("<DeleteMarker>true</DeleteMarker>");
                    if (outcome.versionId() != null) {
                        result.append("<DeleteMarkerVersionId>").append(XmlSupport.escape(outcome.versionId())).append("</DeleteMarkerVersionId>");
                    }
                }
                result.append("</Deleted>");
            }
        }
        result.append("</DeleteResult>");

        return ResponseEntity.ok()
                .body(result.toString());
    }

    /** One {@code <Object>} of a multi-object delete: the key and, optionally, the version to delete for good. */
    private record DeleteTarget(String key, String versionId) {}

    /** Objects of a multi-object delete and its {@code <Quiet>true</Quiet>} flag (successes are then not listed). */
    private record DeleteRequest(List<DeleteTarget> keys, boolean quiet) {}

    private DeleteRequest parseDeleteXml(InputStream body) throws IOException {
        try {
            DocumentBuilder db = XmlSupport.newSecureDocumentBuilder(true);
            Document doc = db.parse(body);
            NodeList objects = doc.getElementsByTagName("Object");
            List<DeleteTarget> keys = new ArrayList<>();
            for (int i = 0; i < objects.getLength(); i++) {
                NodeList children = objects.item(i).getChildNodes();
                String key = null, versionId = null;
                for (int j = 0; j < children.getLength(); j++) {
                    if ("Key".equals(children.item(j).getNodeName())) key = children.item(j).getTextContent();
                    if ("VersionId".equals(children.item(j).getNodeName())) versionId = children.item(j).getTextContent();
                }
                if (key != null) keys.add(new DeleteTarget(key, versionId == null || versionId.isEmpty() ? null : versionId));
            }
            NodeList quiet = doc.getElementsByTagName("Quiet");
            boolean isQuiet = quiet.getLength() > 0 && "true".equalsIgnoreCase(quiet.item(0).getTextContent().trim());
            return new DeleteRequest(keys, isQuiet);
        } catch (Exception e) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML", "Failed to parse Delete XML");
        }
    }

    // ── ListObjectVersions ────────────────────────────────────────────────────

    /**
     * GET /{bucket}?versions: the versions and delete markers of the bucket (docs/architecture/versioning.md). A bucket
     * that never had versioning lists every object once, as the latest version with the id "null", as S3 does; clients
     * (SDK "empty the bucket" helpers, ceph/s3-tests cleanup) rely on this listing to find what they must delete.
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
        if (!versionIdMarker.isEmpty() && keyMarker.isEmpty()) {
            throw S3Exception.invalidArgument("A version-id marker cannot be specified without a key marker.");
        }

        ManifestRepository.VersionListing page = manifestRepo.listVersions(bucket, prefix, delimiter,
                keyMarker.isEmpty() ? null : keyMarker, versionIdMarker.isEmpty() ? null : versionIdMarker, maxKeys);

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
                .append("<Name>").append(XmlSupport.escape(bucket)).append("</Name>")
                .append("<Prefix>").append(XmlSupport.escape(prefix)).append("</Prefix>")
                .append("<KeyMarker>").append(XmlSupport.escape(keyMarker)).append("</KeyMarker>")
                .append("<VersionIdMarker>").append(XmlSupport.escape(versionIdMarker)).append("</VersionIdMarker>");
        if (page.truncated() && page.nextKeyMarker() != null) {
            xml.append("<NextKeyMarker>").append(XmlSupport.escape(page.nextKeyMarker())).append("</NextKeyMarker>");
            if (page.nextVersionIdMarker() != null) {
                xml.append("<NextVersionIdMarker>").append(XmlSupport.escape(page.nextVersionIdMarker())).append("</NextVersionIdMarker>");
            }
        }
        xml.append("<MaxKeys>").append(maxKeys).append("</MaxKeys>");
        if (delimiter != null && !delimiter.isEmpty()) {
            xml.append("<Delimiter>").append(XmlSupport.escape(delimiter)).append("</Delimiter>");
        }
        xml.append("<IsTruncated>").append(page.truncated()).append("</IsTruncated>");
        for (ManifestRepository.VersionEntry v : page.versions()) {
            String common = "<Key>" + XmlSupport.escape(v.key()) + "</Key><VersionId>" + XmlSupport.escape(v.versionId())
                    + "</VersionId><IsLatest>" + v.latest() + "</IsLatest><LastModified>" + HttpDates.isoDate(v.lastModified())
                    + "</LastModified>";
            if (v.deleteMarker()) {
                xml.append("<DeleteMarker>").append(common).append("</DeleteMarker>");
            } else {
                xml.append("<Version>").append(common)
                   .append("<ETag>").append(XmlSupport.escape(v.etag() != null ? v.etag() : "\"\"")).append("</ETag>")
                   .append("<Size>").append(v.size()).append("</Size>")
                   .append("<StorageClass>STANDARD</StorageClass></Version>");
            }
        }
        for (String cp : page.commonPrefixes()) {
            xml.append("<CommonPrefixes><Prefix>").append(XmlSupport.escape(cp)).append("</Prefix></CommonPrefixes>");
        }
        xml.append("</ListVersionsResult>");
        return ResponseEntity.ok()
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

        S3Request.rejectUnsupportedBucketSubresource(request);

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
        UnaryOperator<String> e = v -> enc ? UrlCodec.urlEncodeKey(v) : v;
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
                            HttpDates.isoDate(o.lastModified()),
                            o.etag() != null ? o.etag() : "\"\"",
                            o.size());
                    // V1 always lists the owner, V2 only with fetch-owner=true
                    if (listType == 1 || fetchOwner) entry.setOwner(new Owner("sectoriadb", "sectoriadb"));
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
                .body(result);
    }
}
