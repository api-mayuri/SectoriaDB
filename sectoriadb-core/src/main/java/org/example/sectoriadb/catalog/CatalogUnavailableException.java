package org.example.sectoriadb.catalog;

/**
 * The catalog (the transactional metadata store) refuses work because its last commit failed and it has not recovered
 * yet. Reads keep working; a retry later is meaningful. The cause names the fault (an {@code IOException} "No space left
 * on device" for a full disk), so callers can tell a full disk from other faults.
 *
 * <p>It wraps the metastore module's own exception at the boundary of the catalog repositories, so that nothing above the
 * core has to know the metastore module.
 */
public class CatalogUnavailableException extends IllegalStateException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
