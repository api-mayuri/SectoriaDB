package org.example.sectoriadb.engine.metrics;

/**
 * Observability SPI of the storage engine. The engine reports what happens on its hot paths through this interface and
 * knows nothing about the application's metrics backend: the embedding module adapts the events to its registry.
 *
 * <p>Called on hot paths: implementations must be cheap, thread-safe and must never throw. Parameters are low
 * cardinality by construction (enums and numbers); no blob id or key is ever passed.
 */
public interface EngineListener {

    /** Which file family an IO event belongs to. */
    enum Target {
        /** Cuckoo blob file: chunk data and slot metadata. */
        CUCKOO,
        /** Small-object append-only log. */
        SMALL
    }

    /** What failed its checksum. */
    enum ChecksumKind {
        /** A chunk read from a cuckoo blob did not match the CRC32C / length in its slot. */
        CHUNK,
        /** A small-object record did not match its header / data CRC32C. */
        SMALL_RECORD,
        /** A slot metadata entry failed its CRC while a blob was loaded (the slot is quarantined). */
        SLOT_META
    }

    /** A positional read finished: {@code nanos} of wall time for {@code bytes} bytes. */
    default void onDiskRead(Target target, long nanos, long bytes) { }

    /** A positional write finished. */
    default void onDiskWrite(Target target, long nanos, long bytes) { }

    /** An fsync finished. */
    default void onFsync(Target target, long nanos) { }

    /** An insert chose an eviction path of {@code moves} displaced entries (0: a free slot was found directly). */
    default void onEvictionPath(int moves) { }

    /** An insert failed because no eviction path leads to a free slot. */
    default void onTableFull() { }

    /** An insert found the identical chunk already stored. */
    default void onDedupHit() { }

    /** A different chunk collided on the 64-bit key and was stored under another one. */
    default void onHashCollisionRekey() { }

    /** Time an insert waited for the table write lock. */
    default void onCuckooLockWait(long nanos) { }

    /** A checksum verification failed. */
    default void onChecksumFailure(ChecksumKind kind) { }

    /** Time an append waited for the small-object blob lock. */
    default void onSmallAppendLockWait(long nanos) { }

    /** One force covered {@code records} appended records. */
    default void onSmallGroupFsync(int records) { }

    /** Time an append waited until its record was durable. */
    default void onSmallDurabilityWait(long nanos) { }

    /** A cuckoo barrier finished: its duration, whether the caller led the fsync, and the inserts it covered. */
    default void onCuckooBarrier(long nanos, boolean led, long coveredInserts) { }

    /** Does nothing. */
    EngineListener NOOP = new EngineListener() {
        @Override public String toString() { return "EngineListener.NOOP"; }
    };
}
