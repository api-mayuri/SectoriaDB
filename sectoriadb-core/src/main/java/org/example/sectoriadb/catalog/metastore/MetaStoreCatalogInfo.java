package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.CatalogInfo;
import org.example.sectoriadb.metastore.MetaStore;

/** {@link CatalogInfo} over the metastore's own statistics. */
public class MetaStoreCatalogInfo implements CatalogInfo {

    private final MetaStoreProvider stores;

    public MetaStoreCatalogInfo(MetaStoreProvider stores) {
        this.stores = stores;
    }

    @Override
    public FileStats fileStats() {
        MetaStore.Stats s = stores.get().stats();
        return new FileStats(s.pageSize(), s.pageCount(), s.freePages(), s.lastTxId(), s.fileSize(), s.liveReaders(),
                s.oldestReaderMillis());
    }
}
