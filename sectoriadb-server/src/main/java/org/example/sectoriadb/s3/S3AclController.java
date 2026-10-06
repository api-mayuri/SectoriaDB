package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.service.PoolService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

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
    private final String region;

    public S3AclController(PoolService poolService, ManifestRepository manifestRepo,
                           AccessControlService aclService,
                           @Value("${sectoriadb.s3.region:us-east-1}") String region) {
        this.poolService = poolService;
        this.manifestRepo = manifestRepo;
        this.aclService = aclService;
        this.region = region;
    }

    // ── Bucket ACL ────────────────────────────────────────────────────────────

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, params = "acl")
    public ResponseEntity<Void> putBucketAcl(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        PoolEntity pool = poolService.getByName(bucket);
        byte[] body = request.getInputStream().readAllBytes();
        aclService.applyBucketAcl(request, body, pool);

        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .build();
    }

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "acl",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketAcl(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        PoolEntity pool = poolService.getByName(bucket);
        String acl = pool.getAcl();
        String xml = aclService.aclXml(acl);

        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .body(xml);
    }

    // ── Object ACL ────────────────────────────────────────────────────────────

    @PutMapping(value = "/{bucket}/**", params = "acl")
    public ResponseEntity<Void> putObjectAcl(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        String key = S3Support.extractKey(request, bucket);
        ManifestEntity manifest = manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(bucket, key));

        byte[] body = request.getInputStream().readAllBytes();
        aclService.applyObjectAcl(request, body, manifest);
        manifestRepo.save(manifest);

        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .build();
    }

    @GetMapping(value = "/{bucket}/**", params = "acl",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getObjectAcl(
            @PathVariable String bucket,
            HttpServletRequest request) {

        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        String key = S3Support.extractKey(request, bucket);
        ManifestEntity manifest = manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key)
                .orElseThrow(() -> S3Exception.noSuchKey(bucket, key));

        String acl = manifest.getAcl();
        String xml = aclService.aclXml(acl);

        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .body(xml);
    }

    // ── Bucket Policy ─────────────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "policy",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getBucketPolicy(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        PoolEntity pool = poolService.getByName(bucket);
        if (pool.getPolicy() == null) {
            // Return 404 NoSuchBucketPolicy
            throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchBucketPolicy",
                    "The bucket policy does not exist", "/" + bucket);
        }

        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .body(pool.getPolicy());
    }

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, params = "policy")
    public ResponseEntity<Void> putBucketPolicy(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        byte[] body = request.getInputStream().readAllBytes();
        String policyJson = new String(body);

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
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .build();
    }

    @DeleteMapping(value = {"/{bucket}", "/{bucket}/"}, params = "policy")
    public ResponseEntity<Void> deleteBucketPolicy(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        poolService.deletePolicy(bucket);

        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .build();
    }

    // ── Bucket Location ───────────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "location",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketLocation(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

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
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .body(locationXml);
    }

    // ── Bucket Versioning (stub) ──────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "versioning",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketVersioning(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        String versioningXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>";

        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .body(versioningXml);
    }

    // ── Bucket Tagging (stub) ─────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "tagging",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketTagging(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchTagSet",
                "There is no tag set associated with the bucket.", "/" + bucket);
    }

    // ── Bucket CORS (stub) ────────────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "cors",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketCors(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchCORSConfiguration",
                "The CORS configuration does not exist.", "/" + bucket);
    }

    // ── Bucket Lifecycle (stub) ───────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "lifecycle",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketLifecycle(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchLifecycleConfiguration",
                "The lifecycle configuration does not exist.", "/" + bucket);
    }

    // ── Bucket Encryption (stub) ─────────────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "encryption",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketEncryption(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        throw new S3Exception(HttpStatus.NOT_FOUND, "ServerSideEncryptionConfigurationNotFoundError",
                "The server side encryption configuration was not found.", "/" + bucket);
    }

    // ── Bucket PublicAccessBlock (stub) ───────────────────────────────────────

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "publicAccessBlock",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketPublicAccessBlock(@PathVariable String bucket) {
        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchPublicAccessBlockConfiguration",
                "The public access block configuration does not exist.", "/" + bucket);
    }

    // ── Object Tagging ────────────────────────────────────────────────────────

    @GetMapping(value = "/{bucket}/**", params = "tagging",
                produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getObjectTagging(
            @PathVariable String bucket,
            HttpServletRequest request) {

        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        String key = S3Support.extractKey(request, bucket);
        if (!manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key).isPresent()) {
            throw S3Exception.noSuchKey(bucket, key);
        }

        // Return empty TagSet (tagging not stored)
        String taggingXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Tagging xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n" +
                "  <TagSet/>\n" +
                "</Tagging>";

        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .body(taggingXml);
    }

    @PutMapping(value = "/{bucket}/**", params = "tagging")
    public ResponseEntity<Void> putObjectTagging(
            @PathVariable String bucket,
            HttpServletRequest request) throws IOException {

        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        String key = S3Support.extractKey(request, bucket);
        if (!manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key).isPresent()) {
            throw S3Exception.noSuchKey(bucket, key);
        }

        // Tagging not stored, but accept the request
        return ResponseEntity.ok()
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .build();
    }

    @DeleteMapping(value = "/{bucket}/**", params = "tagging")
    public ResponseEntity<Void> deleteObjectTagging(
            @PathVariable String bucket,
            HttpServletRequest request) {

        if (!poolService.existsByName(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }

        String key = S3Support.extractKey(request, bucket);
        if (!manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key).isPresent()) {
            throw S3Exception.noSuchKey(bucket, key);
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header("x-amz-request-id", UUID.randomUUID().toString())
                .build();
    }
}
