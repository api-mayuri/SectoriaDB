package org.example.sectoriadb.engine.error;

import java.io.IOException;

/**
 * Common base of the storage engine's own failures (corruption, a full table, key collisions, damaged headers), so
 * that callers can tell them from plain I/O errors with one {@code catch}.
 */
public class StorageException extends IOException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
