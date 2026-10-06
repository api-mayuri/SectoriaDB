package org.example.sectoriadb.format;

import java.io.IOException;

/** The blob header is missing, damaged, of an unknown version, or does not match the expected geometry. */
public class InvalidBlobHeaderException extends IOException {
    public InvalidBlobHeaderException(String message) {
        super(message);
    }
}
