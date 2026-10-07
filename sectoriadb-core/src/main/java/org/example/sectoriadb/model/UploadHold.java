package org.example.sectoriadb.model;

/**
 * What an upload holds from the moment it writes data into a blob (chunks into cuckoo blobs, a record into a
 * small-object blob) until its manifest is committed or the upload is abandoned. While a hold exists the garbage
 * collector does not free copies that no index entry or manifest points to: they may be the upload's. See
 * {@code UploadGate} and doc 10.
 */
public interface UploadHold {

    /**
     * Called right before the commit transaction. Returns false if the collector revoked the hold (the upload sat
     * between "written" and "commit" longer than the ticket time-to-live and a sweep needed the gate): the written
     * copies may be gone and the commit must not go ahead (the caller fails it with a retryable error). After a true
     * the hold cannot be revoked until {@link #release()}.
     */
    boolean beginCommit();

    /** The upload committed, failed or was abandoned. Idempotent. */
    void release();
}
