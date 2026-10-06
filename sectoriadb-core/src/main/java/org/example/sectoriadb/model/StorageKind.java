package org.example.sectoriadb.model;

/** How the bytes of one object are stored. */
public enum StorageKind {
    /** Split into chunks that live in a cuckoo blob. Default for manifests written before small objects existed. */
    CHUNKED,
    /** Stored whole as one record of a small-object blob. */
    SMALL,
    /** Zero-length object: nothing is stored anywhere. */
    EMPTY
}
