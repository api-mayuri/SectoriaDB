package org.example.sectoriadb.s3;

import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.PoolService;
import org.springframework.stereotype.Component;

/**
 * Helper for S3 entity lookups with proper error handling.
 * Consolidates validation logic used by S3BucketController and S3ObjectController.
 */
@Component
public class S3Lookup {

    private final PoolService poolService;
    private final ManifestRepository manifestRepo;

    public S3Lookup(PoolService poolService, ManifestRepository manifestRepo) {
        this.poolService = poolService;
        this.manifestRepo = manifestRepo;
    }

    /**
     * Retrieves a bucket (pool) by name, throwing NoSuchBucket if not found.
     */
    public PoolEntity requireBucket(String bucketName) {
        return poolService.findByName(bucketName).orElseThrow(() -> S3Exception.noSuchBucket(bucketName));
    }

    /**
     * Retrieves an object by bucket and key, throwing NoSuchBucket or NoSuchKey as appropriate.
     */
    public ManifestEntity requireObject(String bucketName, String objectKey) {
        // The hot path is one index lookup (objects tree) plus the manifest read; only a miss checks the bucket
        // to tell NoSuchBucket from NoSuchKey.
        return manifestRepo.findCurrent(bucketName, objectKey).orElseThrow(() ->
                poolService.existsByName(bucketName)
                        ? S3Exception.noSuchKey(bucketName, objectKey)
                        : S3Exception.noSuchBucket(bucketName));
    }

    /**
     * Validates bucket name against S3 rules.
     * Throws InvalidBucketName if validation fails.
     */
    public void validateBucketName(String name) {
        if (name == null || name.isEmpty()) {
            throw invalidBucketName("Bucket name cannot be empty");
        }

        // Length: 3-63 characters
        if (name.length() < 3 || name.length() > 63) {
            throw invalidBucketName("Bucket name must be between 3 and 63 characters");
        }

        // Character set: [a-z0-9.-]
        for (char c : name.toCharArray()) {
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) {
                throw invalidBucketName("Bucket name contains invalid character: " + c);
            }
        }

        // First and last character must be letter or digit
        char first = name.charAt(0);
        char last = name.charAt(name.length() - 1);
        if (!Character.isLetterOrDigit(first) || !Character.isLetterOrDigit(last)) {
            throw invalidBucketName("Bucket name must start and end with a letter or digit");
        }

        // Must not contain ".."
        if (name.contains("..")) {
            throw invalidBucketName("Bucket name must not contain consecutive dots");
        }

        // Must not be a valid IP address (very basic check)
        if (isIpAddress(name)) {
            throw invalidBucketName("Bucket name must not be a valid IP address");
        }
    }

    private static S3Exception invalidBucketName(String message) {
        return new S3Exception(org.springframework.http.HttpStatus.BAD_REQUEST, "InvalidBucketName", message);
    }

    /**
     * Very basic IP address check: looks for pattern xxx.xxx.xxx.xxx
     */
    private boolean isIpAddress(String name) {
        String[] parts = name.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates object key constraints.
     * Throws InvalidArgument if validation fails.
     */
    public void validateObjectKey(String key) {
        if (key == null || key.isEmpty()) {
            throw S3Exception.invalidArgument("Object key cannot be empty");
        }
        if (key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
            throw new S3Exception(org.springframework.http.HttpStatus.BAD_REQUEST, "KeyTooLongError",
                    "Your key is too long");
        }
    }
}
