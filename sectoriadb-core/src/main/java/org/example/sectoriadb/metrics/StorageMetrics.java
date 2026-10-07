package org.example.sectoriadb.metrics;

/**
 * Observability SPI of the storage engine. The core module knows nothing about Micrometer or Prometheus: it reports
 * events through this interface, the server module adapts them to its metrics registry, and tests use
 * {@link #NOOP} or a recording stub.
 *
 * <p>All methods are called on hot paths: implementations must be cheap, thread-safe and must never throw.
 * Parameters are low-cardinality by construction (enums and numbers); no blob id, bucket or key is ever passed.
 */
public interface StorageMetrics {

    /** Which file family an IO event belongs to (label {@code target}). */
    enum Target {
        /** Cuckoo blob file ({@code blob_*.raw}): chunk data and slot metadata. */
        CUCKOO("cuckoo"),
        /** Small-object append-only log ({@code small_*.sob}). */
        SMALL("small"),
        /** The transactional metadata store ({@code sectoria.db}). */
        METASTORE("metastore");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** What failed its CRC (label {@code kind}). */
    enum CrcKind {
        /** A chunk read from a cuckoo blob did not match the CRC32C / length in its slot. */
        CHUNK("chunk"),
        /** A small-object record did not match its header / data CRC32C. */
        SMALL_RECORD("small_record"),
        /** The CRC32C of a whole object differs from the one stored in its manifest. */
        WHOLE_OBJECT("whole_object"),
        /** A metastore page failed its checksum. */
        METASTORE_PAGE("metastore_page"),
        /** A slot metadata entry failed its CRC while a blob was loaded (the slot is quarantined). */
        SLOT_META("slot_meta");

        private final String label;

        CrcKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    // ---- disk IO -----------------------------------------------------------------------------------------------

    /** A positional read finished: {@code nanos} of wall time for {@code bytes} bytes. */
    void diskRead(Target target, long nanos, long bytes);

    /** A write finished: {@code nanos} of wall time for {@code bytes} bytes (page cache, before the fsync). */
    void diskWrite(Target target, long nanos, long bytes);

    /** An {@code fsync} ({@code FileChannel.force}) finished after {@code nanos}. Not called when fsync is off. */
    void fsync(Target target, long nanos);

    // ---- cuckoo table ------------------------------------------------------------------------------------------

    /** An insert that needed a new slot found an eviction path with {@code moves} moves (0 = free slot in a candidate bucket). */
    void evictionPath(int moves);

    /** An insert failed with TableFullException (no eviction path within the budget). */
    void tableFull();

    /** An insert found the identical chunk already stored (deduplication). */
    void dedupHit();

    /** A genuine 64-bit hash collision was detected and the chunk was stored under a salted key. */
    void hashCollisionRekey();

    /** Time spent waiting for the write lock of a cuckoo table before an insert could start. */
    void cuckooLockWait(long nanos);

    // ---- integrity ---------------------------------------------------------------------------------------------

    /** A CRC / checksum verification failed. */
    void crcFailure(CrcKind kind);

    // ---- small-object blobs ------------------------------------------------------------------------------------

    /** Time an append waited for the per-file append lock of a small-object blob. */
    void smallAppendLockWait(long nanos);

    /** One force of a small-object blob made {@code records} appended records durable at once (group fsync). */
    default void smallGroupFsync(int records) { }

    /** An append waited {@code nanos} from taking the lock until its record was durable (lock wait + write + force). */
    default void smallDurabilityWait(long nanos) { }

    // ---- metastore ---------------------------------------------------------------------------------------------

    /** Time a write transaction waited for the single-writer slot. */
    void metaWriterLockWait(long nanos);

    /** A metastore commit (pages, fsync, meta page, fsync) took {@code nanos}. */
    void metaCommit(long nanos);

    /** A group commit batch of {@code bodies} write bodies (rolled back ones included) wrote {@code pages} pages. */
    default void metaGroupBatch(int bodies, int pages) { }

    /** A grouped write body waited {@code nanos} in the queue before it started to run. */
    default void metaGroupQueueWait(long nanos) { }

    /** A grouped write body threw and was rolled back without affecting the rest of its batch. */
    default void metaGroupBodyRollback() { }

    // ---- resize ------------------------------------------------------------------------------------------------

    /** A blob resize finished (successfully or not) after {@code nanos}. */
    void resize(long nanos, boolean success);

    /** One pass of the auto-resize scheduler ran (whether or not it resized anything). */
    void autoResizeRun();

    /** Does nothing. Used when no metrics backend is wired (CLI, unit tests). */
    StorageMetrics NOOP = new StorageMetrics() {
        @Override public void diskRead(Target target, long nanos, long bytes) { }
        @Override public void diskWrite(Target target, long nanos, long bytes) { }
        @Override public void fsync(Target target, long nanos) { }
        @Override public void evictionPath(int moves) { }
        @Override public void tableFull() { }
        @Override public void dedupHit() { }
        @Override public void hashCollisionRekey() { }
        @Override public void cuckooLockWait(long nanos) { }
        @Override public void crcFailure(CrcKind kind) { }
        @Override public void smallAppendLockWait(long nanos) { }
        @Override public void metaWriterLockWait(long nanos) { }
        @Override public void metaCommit(long nanos) { }
        @Override public void resize(long nanos, boolean success) { }
        @Override public void autoResizeRun() { }
        @Override public String toString() { return "StorageMetrics.NOOP"; }
    };
}
