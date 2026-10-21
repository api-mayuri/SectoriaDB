package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.CatalogInfo;
import org.example.sectoriadb.metastore.MetaStore;

import java.util.List;
import java.util.Map;

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

    @Override
    public Map<String, Long> treeEntries() {
        return stores.get().treeSizes();
    }

    @Override
    public List<TreePages> treePages() {
        return stores.get().treeStats().stream()
                .map(t -> new TreePages(t.name(), t.entries(), t.branchPages(), t.leafPages(), t.overflowPages(), t.leafFill()))
                .toList();
    }
}
