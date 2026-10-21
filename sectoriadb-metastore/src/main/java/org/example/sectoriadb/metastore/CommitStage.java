package org.example.sectoriadb.metastore;

/** Named points of a commit at which {@link FaultInjector} can interfere (tests only). */
enum CommitStage {
    /** The transaction body is finished, nothing has been written yet. */
    BEFORE_COMMIT,
    /** The data pages are written and synced; the meta page is not. */
    AFTER_DATA,
    /** The meta page is written and synced: the commit is durable. */
    AFTER_META
}
