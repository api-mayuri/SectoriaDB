package org.example.sectoriadb.checksum;

/**
 * How a stored whole-object checksum was obtained (S3 "checksum type").
 * FULL_OBJECT: the algorithm applied to the object's bytes. COMPOSITE: the algorithm applied to the concatenated
 * binary per-part checksums of a multipart upload, written as {@code base64-N}.
 */
public enum ChecksumType {
    FULL_OBJECT,
    COMPOSITE
}
