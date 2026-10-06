package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.MetaStore;

/**
 * Late-bound access to the {@link MetaStore}. The repositories ask for the store on every operation instead of
 * holding it, which lets the Spring configuration open the file lazily: an admin CLI process that only manages
 * credentials must not fight the running server for the file lock.
 */
@FunctionalInterface
public interface MetaStoreProvider {

    MetaStore get();

    static MetaStoreProvider of(MetaStore store) {
        return () -> store;
    }
}
