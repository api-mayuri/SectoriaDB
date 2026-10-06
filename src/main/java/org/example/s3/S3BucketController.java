package org.example.s3;

import jakarta.servlet.http.HttpServletRequest;
import org.example.entity.ManifestEntity;
import org.example.entity.PoolEntity;
import org.example.repository.ManifestRepository;
import org.example.s3.access.AccessControlService;
import org.example.s3.xml.ListBucketResult;
import org.example.s3.xml.S3ObjectEntry;
import org.example.service.FileStorageService;
import org.example.service.PoolService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

        List<String> keys = parseDeleteXml(request.getInputStream());
        StringBuilder result = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");

        for (String key : keys) {
            manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key)
                    .ifPresent(e -> fileService.delete(e.getId()));
            result.append("<Deleted><Key>").append(S3Support.xmlEscape(key)).append("</Key></Deleted>");
        }
        result.append("</DeleteResult>");

        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .body(result.toString());
    }

    private List<String> parseDeleteXml(InputStream body) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(body);
            NodeList objects = doc.getElementsByTagName("Object");
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < objects.getLength(); i++) {
                NodeList children = objects.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    if ("Key".equals(children.item(j).getNodeName())) {
                        keys.add(children.item(j).getTextContent().trim());
                    }
                }
            }
            return keys;
        } catch (Exception e) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML", "Failed to parse Delete XML");
        }
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
            @RequestParam(value = "list-type",          defaultValue = "1") int listType) {

        // Ensure bucket exists; throws NoSuchBucket if not
        lookup.requireBucket(bucket);

        // Normalize max-keys: 0 means 1000, cap at 1000
        int maxKeys = maxKeysParam != null ? maxKeysParam : 1000;
        if (maxKeys <= 0) maxKeys = 1000;
        if (maxKeys > 1000) maxKeys = 1000;

        List<ManifestEntity> all = manifestRepo.findByBucketNameAndDeletedFalse(bucket);

        // Filter by prefix
        List<ManifestEntity> prefixed = all.stream()
                .filter(m -> m.getObjectKey() != null && !m.getObjectKey().isEmpty())
                .filter(m -> prefix.isEmpty() || m.getObjectKey().startsWith(prefix))
                .toList();

        // Determine pagination start point
        String pageStart = null;
        if (listType == 2) {
            // ListObjectsV2: use continuation-token or start-after
            pageStart = continuationToken != null ? continuationToken : startAfter;
        } else {
            // ListObjectsV1: use marker
            pageStart = marker.isEmpty() ? null : marker;
        }

        // Collect and deduplicate common prefixes (if delimiter is set)
        List<String> commonPrefixes = new ArrayList<>();
        List<ManifestEntity> objectsToReturn = new ArrayList<>();

        if (delimiter != null && !delimiter.isEmpty()) {
            java.util.Set<String> seenPrefixes = new java.util.LinkedHashSet<>();
            for (ManifestEntity m : prefixed) {
                String key = m.getObjectKey();
                // Find the next delimiter after prefix
                int delimIdx = key.indexOf(delimiter, prefix.length());
                if (delimIdx >= 0) {
                    // This key is under a directory
                    String commonPrefix = key.substring(0, delimIdx + delimiter.length());
                    if (!seenPrefixes.contains(commonPrefix)) {
                        seenPrefixes.add(commonPrefix);
                        commonPrefixes.add(commonPrefix);
                    }
                } else {
                    // This key is at this level
                    objectsToReturn.add(m);
                }
            }
        } else {
            // No delimiter: all objects at this level
            objectsToReturn = new ArrayList<>(prefixed);
        }

        // Sort by key lexicographically
        objectsToReturn.sort((a, b) -> a.getObjectKey().compareTo(b.getObjectKey()));
        commonPrefixes.sort(String::compareTo);

        // Apply pagination marker/continuation-token
        int resultCount = 0;
        List<ManifestEntity> pageObjects = new ArrayList<>();
        String nextMarker = null;

        for (ManifestEntity m : objectsToReturn) {
            if (pageStart != null && m.getObjectKey().compareTo(pageStart) <= 0) {
                continue; // Skip until we're past the marker
            }
            if (resultCount < maxKeys) {
                pageObjects.add(m);
                nextMarker = m.getObjectKey();
                resultCount++;
            } else {
                break;
            }
        }

        // Also count common prefixes toward max-keys
        List<String> pageCommonPrefixes = new ArrayList<>();
        for (String cp : commonPrefixes) {
            if (pageStart != null && cp.compareTo(pageStart) <= 0) {
                continue; // Skip until we're past the marker
            }
            if (resultCount < maxKeys) {
                pageCommonPrefixes.add(cp);
                nextMarker = cp;
                resultCount++;
            } else {
                break;
            }
        }

        boolean isTruncated = resultCount == maxKeys &&
                (pageObjects.size() + pageCommonPrefixes.size() < objectsToReturn.size() + commonPrefixes.size());

        // Build response
        ListBucketResult result = new ListBucketResult();
        result.setName(bucket);
        result.setPrefix(prefix);
        if (delimiter != null && !delimiter.isEmpty()) {
            result.setDelimiter(delimiter);
        }
        result.setMaxKeys(maxKeys);
        result.setTruncated(isTruncated);

        List<S3ObjectEntry> contents = pageObjects.stream()
                .map(m -> new S3ObjectEntry(
                        m.getObjectKey(),
                        S3Support.isoDate(m.getCreatedAt()),
                        m.getEtag() != null ? m.getEtag() : "\"\"",
                        m.getTotalBytes()))
                .toList();

        result.setContents(contents);

        if (listType == 2) {
            // ListObjectsV2 response
            if (continuationToken != null) {
                result.setContinuationToken(continuationToken);
            }
            result.setKeyCount(contents.size()); // Only Contents count, not CommonPrefixes
            if (isTruncated) {
                result.setNextContinuationToken(nextMarker);
            }
        } else {
            // ListObjectsV1 response
            if (!marker.isEmpty()) {
                result.setMarker(marker);
            }
            if (isTruncated) {
                result.setNextMarker(nextMarker);
            }
        }

        // Add CommonPrefixes to result
        if (!pageCommonPrefixes.isEmpty()) {
            result.setCommonPrefixes(pageCommonPrefixes.stream()
                    .map(ListBucketResult.CommonPrefix::new)
                    .toList());
        }

        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Server", "SectoriaDB")
                .body(result);
    }
}
