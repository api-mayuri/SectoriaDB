package org.example.sectoriadb.engine.error;

import org.example.sectoriadb.engine.error.StorageException;

/** The blob header is missing, damaged, of an unknown version, or does not match the expected geometry. */
public class InvalidBlobHeaderException extends StorageException {
    public InvalidBlobHeaderException(String message) {
        super(message);
    }
}
