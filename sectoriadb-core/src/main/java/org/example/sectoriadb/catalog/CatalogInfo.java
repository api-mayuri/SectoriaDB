package org.example.sectoriadb.catalog;

/** Size and activity of the metadata store file, for the status gauges. */
public interface CatalogInfo {

    FileStats fileStats();

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
