package org.example.sectoriadb.service.impl;

import java.io.IOException;

/**
 * Thrown by {@link CuckooHashTable#insert} when no eviction path of bounded length leads to a free slot.
 * The table is guaranteed to be unchanged (nothing was written to disk or memory).
 */
public class TableFullException extends IOException {
    public TableFullException(String message) {
        super(message);
    }
}
