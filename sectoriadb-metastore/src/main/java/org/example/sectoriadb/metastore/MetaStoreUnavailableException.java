package org.example.sectoriadb.metastore;

/**
 * The write side of the metadata store is refused: its last commit failed (typically a full or failing disk) and the
 * recovery attempt either is not due yet or failed again. Reads are not affected. The cause chain holds the original
 * failure (an {@code IOException} "No space left on device" for a full disk), so callers can tell a full disk from other
 * faults. Retrying later is meaningful: the store recovers by itself once the disk works.
 */
public class MetaStoreUnavailableException extends IllegalStateException {
    public MetaStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
