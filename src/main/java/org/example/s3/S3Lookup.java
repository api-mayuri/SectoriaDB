package org.example.s3;

import org.example.entity.ManifestEntity;
import org.example.entity.PoolEntity;
import org.example.repository.ManifestRepository;
import org.example.service.PoolService;
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
        if (!poolService.existsByName(bucketName)) {
            throw S3Exception.noSuchBucket(bucketName);
        }
        return poolService.getByName(bucketName);
    }

    /**
     * Retrieves an object by bucket and key, throwing NoSuchBucket or NoSuchKey as appropriate.
     */
    public ManifestEntity requireObject(String bucketName, String objectKey) {
        // Check bucket exists first
        if (!poolService.existsByName(bucketName)) {
            throw S3Exception.noSuchBucket(bucketName);
        }
        // Then check object
        return manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucketName, objectKey)
                .orElseThrow(() -> S3Exception.noSuchKey(bucketName, objectKey));
    }

    /**
     * Validates bucket name against S3 rules.
     * Throws InvalidBucketName if validation fails.
     */
    public void validateBucketName(String name) {
        if (name == null || name.isEmpty()) {
            throw S3Exception.invalidArgument("Bucket name cannot be empty");
        }

        // Length: 3-63 characters
        if (name.length() < 3 || name.length() > 63) {
            throw S3Exception.invalidArgument("Bucket name must be between 3 and 63 characters");
        }

        // Character set: [a-z0-9.-]
        for (char c : name.toCharArray()) {
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) {
                throw S3Exception.invalidArgument("Bucket name contains invalid character: " + c);
            }
        }

        // First and last character must be letter or digit
        char first = name.charAt(0);
        char last = name.charAt(name.length() - 1);
        if (!Character.isLetterOrDigit(first) || !Character.isLetterOrDigit(last)) {
            throw S3Exception.invalidArgument("Bucket name must start and end with a letter or digit");
        }

        // Must not contain ".."
        if (name.contains("..")) {
            throw S3Exception.invalidArgument("Bucket name must not contain consecutive dots");
        }

        // Must not be a valid IP address (very basic check)
        if (isIpAddress(name)) {
            throw S3Exception.invalidArgument("Bucket name must not be a valid IP address");
        }
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
        if (key.length() > 1024) {
            throw S3Exception.invalidArgument("Object key must not exceed 1024 bytes");
        }
    }
}
