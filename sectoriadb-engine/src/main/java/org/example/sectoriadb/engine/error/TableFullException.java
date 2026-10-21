package org.example.sectoriadb.engine.error;


/**
 * Thrown by {@link CuckooHashTable#insert} when no eviction path of bounded length leads to a free slot.
 * The table is guaranteed to be unchanged (nothing was written to disk or memory).
 */
public class TableFullException extends StorageException {
    public TableFullException(String message) {
        super(message);
    }
}
