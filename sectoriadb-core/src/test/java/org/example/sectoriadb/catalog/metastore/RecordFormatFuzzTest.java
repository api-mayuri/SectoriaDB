package org.example.sectoriadb.catalog.metastore;

import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Metastore records decoded from damaged or hostile bytes (a record is read back from a page that passed its checksum,
 * so this is a bug or a deliberate edit, but it must still fail cleanly): {@link IllegalStateException} with the reason,
 * never an index error, a negative or huge allocation.
 */
class RecordFormatFuzzTest {

    @FuzzTest(maxDuration = "2m")
    void manifestRecord(byte[] data) {
        try {
            ManifestCodec.decode(data);
        } catch (IllegalStateException expected) {
            // corrupt record
        }
    }

    @FuzzTest(maxDuration = "1m")
    void chunkRecord(byte[] data) {
        try {
            ChunkCodec.decode(data);
        } catch (IllegalStateException expected) {
            // corrupt record
        }
    }

    @FuzzTest(maxDuration = "1m")
    void tombstoneRow(byte[] data) {
        try {
            Trees.tombstonePool(data);
            Trees.tombstoneRefs(data);
        } catch (IllegalStateException expected) {
            // corrupt row
        }
    }
}
