package org.example.sectoriadb.storage;

import org.example.sectoriadb.engine.error.StorageException;

/** The bytes of an object, read back in full, do not match its stored whole-object CRC32C. */
public class ObjectCorruptedException extends StorageException {

    private final String manifestId;

    public ObjectCorruptedException(String manifestId, String bucket, String key, String expected, String actual) {
        super("Object corrupted: id=" + manifestId + " bucket=" + bucket + " key=" + key
                + ": stored CRC32C " + expected + " but data hashes to " + actual);
        this.manifestId = manifestId;
    }

    public String getManifestId() { return manifestId; }
}
