package org.example.sectoriadb.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * On-disk blob layout (all integers big-endian).
 *
 * <pre>
 * [0 .. 4096)                      header
 * [4096 .. 4096 + M)               metadata: totalSlots * 32-byte entries, M = roundUp(totalSlots*32, 4096)
 * [tableA .. tableA + T)           table A: numBuckets * 4 slots * chunkSize bytes   (tableA = 4096 + M, 4096-aligned)
 * [tableA + T .. tableA + 2T)      table B: same size
 *
 * Header:
 *   0   8  magic "SDBBLOB1"
 *   8   4  formatVersion (1)
 *   12  4  numBuckets (per table)
 *   16  4  chunkSize
 *   20  4  slotsPerBucket (4)
 *   24  4  hashScheme (1 = bucket A: splitmix64 finalizer, bucket B: murmur3 fmix64, distinct seeds)
 *   28  4  reserved (0)
 *   32  8  creation time, epoch millis
 *   40  4052 zero padding
 *   4092 4 CRC32C of bytes [0, 4092)
 *
 * Meta entry (32 bytes; 4096 and 512 are multiples of 32, so an entry never straddles a sector):
 *   0   8  chunk key
 *   8   1  state (0 FREE, 1 ACTIVE, 2 DELETED, 3 RESERVED)
 *   9   1  flags (0)
 *   10  2  reserved (0)
 *   12  4  dataLength: number of stored bytes (1..chunkSize) at the start of the slot's data area
 *   16  4  dataCrc32c over those dataLength bytes
 *   20  8  sequence: monotonically increasing write counter of the table
 *   28  4  entryCrc32c over bytes [0, 28)
 * An all-zero entry (never written, sparse file) is FREE.
 * </pre>
 */
public final class BlobLayout {

    public static final int SLOTS_PER_BUCKET = 4;
    public static final int HEADER_SIZE = 4096;
    public static final int META_ENTRY_BYTES = 32;
    public static final int ALIGNMENT = 4096;
    public static final int FORMAT_VERSION = 1;
    public static final int HASH_SCHEME = 1;
    public static final byte[] MAGIC = "SDBBLOB1".getBytes(StandardCharsets.US_ASCII);

    private static final int HEADER_CRC_OFFSET = HEADER_SIZE - 4;

    private static final long SEED_A = 0x9e3779b97f4a7c15L;
    private static final long SEED_B = 0xc2b2ae3d27d4eb4fL;

    private BlobLayout() {
    }

    public static long metaSectionBytes(int numBuckets) {
        long raw = 2L * numBuckets * SLOTS_PER_BUCKET * META_ENTRY_BYTES;
        return (raw + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
    }

    public static long tableAOffset(int numBuckets) {
        return HEADER_SIZE + metaSectionBytes(numBuckets);
    }

    public static long requiredSize(int numBuckets, int chunkSize) {
        return tableAOffset(numBuckets) + 2L * numBuckets * SLOTS_PER_BUCKET * chunkSize;
    }

    // ── Bucket hashes: two independent mixers over the 64-bit key ──────────────

    /** splitmix64 finalizer. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** murmur3 fmix64. */
    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    public static int bucketA(long key, int numBuckets) {
        return (int) Math.floorMod(mix64(key ^ SEED_A), (long) numBuckets);
    }

    public static int bucketB(long key, int numBuckets) {
        return (int) Math.floorMod(fmix64(key ^ SEED_B), (long) numBuckets);
    }

    // ── Header ─────────────────────────────────────────────────────────────────

    public static ByteBuffer encodeHeader(int numBuckets, int chunkSize, long createdMillis) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE);
        b.put(MAGIC);
        b.putInt(FORMAT_VERSION);
        b.putInt(numBuckets);
        b.putInt(chunkSize);
        b.putInt(SLOTS_PER_BUCKET);
        b.putInt(HASH_SCHEME);
        b.putInt(0);
        b.putLong(createdMillis);
        CRC32C crc = new CRC32C();
        crc.update(b.array(), 0, HEADER_CRC_OFFSET);
        b.putInt(HEADER_CRC_OFFSET, (int) crc.getValue());
        b.clear();
        return b;
    }

    /** Verifies magic, version, CRC and that the geometry equals the expected one. Fails loudly. */
    public static void verifyHeader(ByteBuffer header, int numBuckets, int chunkSize, String blobId)
            throws InvalidBlobHeaderException {
        byte[] raw = new byte[HEADER_SIZE];
        header.duplicate().get(raw);
        ByteBuffer b = ByteBuffer.wrap(raw);
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                throw new InvalidBlobHeaderException("Blob " + blobId + ": bad magic (not a SectoriaDB blob, "
                        + "or a pre-header legacy blob which is no longer supported)");
            }
        }
        CRC32C crc = new CRC32C();
        crc.update(raw, 0, HEADER_CRC_OFFSET);
        if ((int) crc.getValue() != b.getInt(HEADER_CRC_OFFSET)) {
            throw new InvalidBlobHeaderException("Blob " + blobId + ": header CRC mismatch");
        }
        int version = b.getInt(8);
        if (version != FORMAT_VERSION) {
            throw new InvalidBlobHeaderException("Blob " + blobId + ": unsupported format version " + version
                    + " (supported: " + FORMAT_VERSION + ")");
        }
        check(blobId, "numBuckets", b.getInt(12), numBuckets);
        check(blobId, "chunkSize", b.getInt(16), chunkSize);
        check(blobId, "slotsPerBucket", b.getInt(20), SLOTS_PER_BUCKET);
        check(blobId, "hashScheme", b.getInt(24), HASH_SCHEME);
    }

    private static void check(String blobId, String what, int actual, int expected) throws InvalidBlobHeaderException {
        if (actual != expected) {
            throw new InvalidBlobHeaderException("Blob " + blobId + ": header " + what + "=" + actual
                    + " but expected " + expected);
        }
    }

    /** Creates a sparse blob file of the full size and writes its header. */
    public static void createFile(Path path, int numBuckets, int chunkSize) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        long size = requiredSize(numBuckets, chunkSize);
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING)) {
            fc.position(size - 1);
            fc.write(ByteBuffer.wrap(new byte[]{0}));
            ByteBuffer header = encodeHeader(numBuckets, chunkSize, System.currentTimeMillis());
            while (header.hasRemaining()) {
                fc.write(header, header.position());
            }
            fc.force(true);
        }
    }
}
