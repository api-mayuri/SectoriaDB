package org.example.sectoriadb.s3.api;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.storage.PoolService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.S3Request;

/**
 * S3 ACL and configuration endpoints:
 *   GET/PUT  /{bucket}?acl        — Bucket ACL
 *   GET/PUT  /{bucket}/**?acl     — Object ACL
 *   GET/PUT/DELETE /{bucket}?policy  — Bucket Policy
 *   GET      /{bucket}?location   — Bucket location
 *   GET      /{bucket}?versioning — Versioning (stub)
 *   GET      /{bucket}?tagging    — Tagging (stub)
 *   GET      /{bucket}/**?tagging — Object tagging
 *   GET/PUT/DELETE /{bucket}/**?tagging — Object tagging
 *   And other bucket configuration endpoints.
 */
@RestController
public class S3AclController {

    private final PoolService poolService;
    private final ManifestRepository manifestRepo;
    private final AccessControlService aclService;
    private final S3Lookup lookup;
    private final String region;

    public S3AclController(PoolService poolService, ManifestRepository manifestRepo,
                           AccessControlService aclService, S3Lookup lookup,
                           @Value("${sectoriadb.s3.region:us-east-1}") String region) {
        this.poolService = poolService;
        this.lookup = lookup;
        this.manifestRepo = manifestRepo;
        this.aclService = aclService;
        this.region = region;
    }

    // ── Bucket ACL ────────────────────────────────────────────────────────────

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, params = "acl")
    public ResponseEntity<Void> putBucketAcl(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        PoolEntity pool = lookup.requireBucket(bucket);
        byte[] body = RequestBodies.readLimited(request, RequestBodies.MAX_ACL_BYTES, HttpStatus.BAD_REQUEST,
                "MalformedACLError", "The ACL document is too large");
        aclService.applyBucketAcl(request, body, pool);

        return ResponseEntity.ok()
                .build();
    }

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "acl",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketAcl(@PathVariable String bucket) {
        PoolEntity pool = lookup.requireBucket(bucket);
        String acl = pool.getAcl();
        String xml = aclService.aclXml(acl);

        return ResponseEntity.ok()
                .body(xml);
    }

    // ── Object ACL ────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", params = "acl")
    public ResponseEntity<Void> putObjectAcl(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        lookup.requireBucket(bucket);

        String key = S3Request.extractKey(request, bucket);
        byte[] body = RequestBodies.readLimited(request, RequestBodies.MAX_ACL_BYTES, HttpStatus.BAD_REQUEST,
                "MalformedACLError", "The ACL document is too large");
        // one transaction reads the current version, applies the ACL and writes it back: a concurrent PUT of the key
        // cannot be overwritten with the old version's manifest
        manifestRepo.updateCurrent(bucket, key, manifest -> aclService.applyObjectAcl(request, body, manifest))
                .orElseThrow(() -> S3Exception.noSuchKey(bucket, key));

        return ResponseEntity.ok()
                .build();
    }

    @GetMapping(value = "/{bucket}/**", params = "acl",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getObjectAcl(
            @PathVariable String bucket,
            HttpServletRequest request) {

        lookup.requireBucket(bucket);

        String key = S3Request.extractKey(request, bucket);
        ManifestEntity manifest = manifestRepo.findCurrent(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(bucket, key));

        String acl = manifest.getAcl();
        String xml = aclService.aclXml(acl);

        return ResponseEntity.ok()
                .body(xml);
    }

    // ── Bucket Policy ─────────────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "policy",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getBucketPolicy(@PathVariable String bucket) {
        PoolEntity pool = lookup.requireBucket(bucket);
        if (pool.getPolicy() == null) {
            // Return 404 NoSuchBucketPolicy
            throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchBucketPolicy",
                    "The bucket policy does not exist", "/" + bucket);
        }

        return ResponseEntity.ok()
                .body(pool.getPolicy());
    }

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, params = "policy")
    public ResponseEntity<Void> putBucketPolicy(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        lookup.requireBucket(bucket);

        byte[] body = RequestBodies.readLimited(request, RequestBodies.MAX_POLICY_BYTES, HttpStatus.BAD_REQUEST,
                "PolicyTooLarge", "Policies must be 20 KiB or smaller");
        String policyJson = new String(body, java.nio.charset.StandardCharsets.UTF_8);

        // Validate JSON (simple check)
        try {
            // Just ensure it's valid JSON
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(policyJson);
            if (!tree.isObject() || !tree.path("Statement").isArray()) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedPolicy",
                        "Policy must be a JSON object with a Statement array");
            }
        } catch (S3Exception e) {
            throw e;
        } catch (Exception e) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedPolicy",
                    "Invalid JSON in bucket policy: " + e.getMessage());
        }

        poolService.setPolicy(bucket, policyJson);

        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .build();
    }

    @DeleteMapping(value = {"/{bucket}", "/{bucket}/"}, params = "policy")
    public ResponseEntity<Void> deleteBucketPolicy(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        poolService.deletePolicy(bucket);

        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .build();
    }

    // ── Bucket Location ───────────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "location",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketLocation(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        String locationXml;
        if ("us-east-1".equals(region)) {
            // AWS returns empty element for us-east-1
            locationXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>";
        } else {
            locationXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                    region + "</LocationConstraint>";
        }

        return ResponseEntity.ok()
                .body(locationXml);
    }

    // ── Bucket Versioning (stub) ──────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "versioning",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketVersioning(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        String versioningXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>";

        return ResponseEntity.ok()
                .body(versioningXml);
    }

    // ── Bucket Tagging (stub) ─────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "tagging",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketTagging(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchTagSet",
                "There is no tag set associated with the bucket.", "/" + bucket);
    }

    // ── Bucket CORS (stub) ────────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "cors",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketCors(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchCORSConfiguration",
                "The CORS configuration does not exist.", "/" + bucket);
    }

    // ── Bucket Lifecycle (stub) ───────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "lifecycle",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketLifecycle(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchLifecycleConfiguration",
                "The lifecycle configuration does not exist.", "/" + bucket);
    }

    // ── Bucket Encryption (stub) ─────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "encryption",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketEncryption(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        throw new S3Exception(HttpStatus.NOT_FOUND, "ServerSideEncryptionConfigurationNotFoundError",
                "The server side encryption configuration was not found.", "/" + bucket);
    }

    // ── Bucket PublicAccessBlock (stub) ───────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "publicAccessBlock",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketPublicAccessBlock(@PathVariable String bucket) {
        lookup.requireBucket(bucket);

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchPublicAccessBlockConfiguration",
                "The public access block configuration does not exist.", "/" + bucket);
    }

    // ── Object Tagging ────────────────────────────────────────────────────────

    @GetMapping(value = "/{bucket}/**", params = "tagging",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getObjectTagging(
            @PathVariable String bucket,
            HttpServletRequest request) {

        lookup.requireBucket(bucket);

        String key = S3Request.extractKey(request, bucket);
        if (!manifestRepo.existsCurrent(bucket, key)) {
            throw S3Exception.noSuchKey(bucket, key);
        }

        // Return empty TagSet (tagging not stored)
        String taggingXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Tagging xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n" +
                "  <TagSet/>\n" +
                "</Tagging>";

        return ResponseEntity.ok()
                .body(taggingXml);
    }

    @PutMapping(value = "/{bucket}/**", params = "tagging")
    public ResponseEntity<Void> putObjectTagging(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        lookup.requireBucket(bucket);

        String key = S3Request.extractKey(request, bucket);
        if (!manifestRepo.existsCurrent(bucket, key)) {
            throw S3Exception.noSuchKey(bucket, key);
        }

        // Tags are not stored: accepting them would silently lose the data
        throw S3Exception.notImplemented("Object tagging is not implemented; the tag set was not stored");
    }

    @DeleteMapping(value = "/{bucket}/**", params = "tagging")
    public ResponseEntity<Void> deleteObjectTagging(
            @PathVariable String bucket,
            HttpServletRequest request) {

        lookup.requireBucket(bucket);

        String key = S3Request.extractKey(request, bucket);
        if (!manifestRepo.existsCurrent(bucket, key)) {
            throw S3Exception.noSuchKey(bucket, key);
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .build();
    }
}
