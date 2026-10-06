package org.example.sectoriadb.model;

/** Physical kind of a blob file registered in a pool. */
public enum BlobKind {
    /** Cuckoo-hash table of fixed-size chunk slots ({@code blob_*.raw}). Default for registry entries without a kind. */
    CUCKOO,
    /** Append-only log of whole small objects ({@code small_*.sob}). */
    SMALL
}
