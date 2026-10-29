package org.example.sectoriadb.catalog;

/**
 * One lifecycle rule of a bucket (the subset of S3 lifecycle SectoriaDB applies, docs/architecture/versioning.md):
 * noncurrent versions under {@code prefix} are deleted {@code noncurrentDays} days after they became noncurrent, and a
 * delete marker without any version left is removed when {@code expiredObjectDeleteMarker} is set.
 *
 * @param noncurrentDays null when the rule has no NoncurrentVersionExpiration
 */
public record LifecycleRule(String id, boolean enabled, String prefix, Integer noncurrentDays, boolean expiredObjectDeleteMarker) {

    public LifecycleRule {
        prefix = prefix == null ? "" : prefix;
    }
}
