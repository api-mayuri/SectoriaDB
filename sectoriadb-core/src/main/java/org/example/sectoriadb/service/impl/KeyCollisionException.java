package org.example.sectoriadb.service.impl;

import java.io.IOException;

/** A key that must be kept already holds a different chunk (a genuine 64-bit hash collision). */
public class KeyCollisionException extends IOException {

    public KeyCollisionException(String message) {
        super(message);
    }
}
