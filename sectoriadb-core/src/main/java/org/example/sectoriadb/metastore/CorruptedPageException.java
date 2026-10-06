package org.example.sectoriadb.metastore;

/** Thrown when a page (or the file structure) fails a checksum or structural check. */
public class CorruptedPageException extends RuntimeException {
    private final long pageId;

    public CorruptedPageException(long pageId, String message) {
        super(pageId >= 0 ? "page " + pageId + ": " + message : message);
        this.pageId = pageId;
    }

    /** Id of the offending page, or -1 if the problem is not tied to one page. */
    public long pageId() {
        return pageId;
    }
}
