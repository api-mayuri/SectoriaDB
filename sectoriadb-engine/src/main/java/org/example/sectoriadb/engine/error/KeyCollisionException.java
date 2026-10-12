package org.example.sectoriadb.engine.error;

import org.example.sectoriadb.engine.error.StorageException;

/** A key that must be kept already holds a different chunk (a genuine 64-bit hash collision). */
public class KeyCollisionException extends StorageException {

    public KeyCollisionException(String message) {
        super(message);
    }
}
