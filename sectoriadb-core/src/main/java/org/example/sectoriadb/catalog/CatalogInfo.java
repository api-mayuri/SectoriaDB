package org.example.sectoriadb.catalog;

import java.util.List;
import java.util.Map;

/** Size and activity of the metadata store file, for the status gauges. */
public interface CatalogInfo {

    FileStats fileStats();

    /** Entries of every metadata tree (cheap: read from the catalog, nothing is walked). */
    Map<String, Long> treeEntries();

    /**
     * Pages of every metadata tree. Expensive: walks every tree on a snapshot (reads the whole file and keeps one
     * read transaction open meanwhile), so call it rarely.
     */
    List<TreePages> treePages();

    /**
     * @param tree     tree name
     * @param entries  entries
     * @param branch   branch pages
     * @param leaf     leaf pages
     * @param overflow overflow pages of large values
     * @param leafFill average used fraction of the leaf pages
     */
    record TreePages(String tree, long entries, long branch, long leaf, long overflow, double leafFill) {
    }

    /**
     * @param pageSize    bytes per page
     * @param pageCount   pages in the file
     * @param freePages   free (reusable or waiting for readers) pages
     * @param lastTxId    id of the last committed transaction
     * @param fileSize    bytes of the file
     * @param liveReaders open read transactions
     */
    record FileStats(int pageSize, long pageCount, long freePages, long lastTxId, long fileSize, int liveReaders,
                     long oldestReaderMillis) {
    }
}
