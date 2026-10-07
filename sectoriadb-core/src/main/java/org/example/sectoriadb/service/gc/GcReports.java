package org.example.sectoriadb.service.gc;

import java.util.ArrayList;
import java.util.List;

/** Result records of the collector passes (shell output, tests, status). */
public final class GcReports {

    private GcReports() {
    }

    /** What a pass may do: normally the configured limits; the shell command and tests override them. */
    public record Options(String poolId, Long graceMillis, Integer maxChunks) {
        public static Options defaults() {
            return new Options(null, null, null);
        }

        public Options withPool(String pool) {
            return new Options(pool, graceMillis, maxChunks);
        }

        public Options withGrace(long millis) {
            return new Options(poolId, millis, maxChunks);
        }

        public Options withMaxChunks(int n) {
            return new Options(poolId, graceMillis, n);
        }
    }

    /** One pass over the chunk queue, the orphan records and the tombstones. */
    public static final class RunReport {
        public long startedAtMillis;
        public long durationMillis;
        public long graceMillis;
        /** slots of unreferenced chunks that were freed, and their bytes */
        public long chunksFreed, bytesFreed;
        /** index entries removed whose slot was already gone (an earlier pass freed it, then crashed before the commit) */
        public long chunksAbsent;
        /** queue rows dropped: the chunk is referenced again / its entry no longer exists */
        public long revived, gone;
        /** left for a later pass: went through zero again after being looked at / blob frozen or replaced */
        public long tooNew, deferred;
        /** orphan copies and stray copies freed, orphan rows dropped */
        public long orphansFreed, orphanBytes, orphanRowsDropped;
        /** orphan work left for later because uploads were in flight */
        public long orphansDeferred;
        /** tombstones removed, of them small-object records marked DELETED */
        public long tombstones, smallRecordsMarked;
        /** old small-object files deleted after the grace period */
        public long filesDeleted;
        public int errors;
        public final List<String> messages = new ArrayList<>();

        public void error(String message) {
            errors++;
            if (messages.size() < 20) messages.add(message);
        }

        @Override
        public String toString() {
            return String.format("chunks freed=%d (%d bytes) absent=%d revived=%d gone=%d tooNew=%d deferred=%d; "
                            + "orphans freed=%d (%d bytes) rowsDropped=%d deferred=%d; tombstones=%d (small records marked=%d); "
                            + "files deleted=%d; errors=%d; %d ms (grace %d ms)",
                    chunksFreed, bytesFreed, chunksAbsent, revived, gone, tooNew, deferred, orphansFreed, orphanBytes,
                    orphanRowsDropped, orphansDeferred, tombstones, smallRecordsMarked, filesDeleted, errors,
                    durationMillis, graceMillis);
        }
    }

    /** One sweep of the blobs of a pool for stray copies. */
    public static final class SweepReport {
        public long startedAtMillis;
        public long durationMillis;
        public int blobs;
        public long slotsScanned;
        /** copies no index entry pointed at: found / freed (the rest were committed meanwhile or deferred) */
        public long strays, freed, bytesFreed;
        public long notStray;
        /** true if uploads were in flight longer than the gate wait and the rest of the sweep was left for later */
        public boolean deferred;
        public int errors;
        public final List<String> messages = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("blobs=%d slotsScanned=%d strays=%d freed=%d (%d bytes) notStray=%d deferred=%s errors=%d; %d ms",
                    blobs, slotsScanned, strays, freed, bytesFreed, notStray, deferred, errors, durationMillis);
        }
    }

    /** Compaction of one small-object blob. */
    public record CompactionReport(String blobId, boolean compacted, String reason, long recordsMoved,
                                   long bytesBefore, long bytesAfter, String newBlobId, long durationMillis) {
        public long reclaimedBytes() {
            return Math.max(0, bytesBefore - bytesAfter);
        }

        @Override
        public String toString() {
            return compacted
                    ? String.format("blob %s compacted into %s: %d record(s) moved, %d -> %d bytes (%d reclaimed), %d ms",
                    blobId, newBlobId, recordsMoved, bytesBefore, bytesAfter, reclaimedBytes(), durationMillis)
                    : String.format("blob %s not compacted: %s", blobId, reason);
        }
    }
}
