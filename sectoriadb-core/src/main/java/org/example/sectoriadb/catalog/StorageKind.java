package org.example.sectoriadb.catalog;

/** How the bytes of one object are stored. */
public enum StorageKind {
    /** Split into chunks that live in a cuckoo blob. Default for manifests written before small objects existed. */
    CHUNKED,
    /** Stored whole as one record of a small-object blob. */
    SMALL,
    /** Zero-length object: nothing is stored anywhere. */
    EMPTY,
    /**
     * Concatenation of the parts of a completed multipart upload, in object order ({@link PartSegment}). The chunks of
     * its CHUNKED segments are in the manifest's chunk key list, the small records are named by the segments.
     */
    MULTIPART
}
