package org.example.sectoriadb.format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * On-disk layout of a small-object blob ({@code small_<8hex>.sob}), all integers big-endian.
 *
 * <pre>
 * [0 .. 4096)          header
 * [4096 .. tail)       records, each starting on an 8-byte boundary
 *
 * Header:
 *   0    8  magic "SDBSMOB1"
 *   8    4  formatVersion (1)
 *   12   4  reserved (0)
 *   16   8  creation time, epoch millis
 *   24   40 reserved (0)
 *   64   32 checkpoint slot A
 *   96   32 checkpoint slot B
 *   128  3964 zero padding
 *   4092 4  CRC32C of the static prefix [0, 64)
 *
 * Checkpoint slot (32 bytes, both slots sit in the first 512-byte sector):
 *   0   8  checkpointTail: offset up to which records were durable when the slot was written
 *   8   8  generation (the valid slot with the higher generation wins; odd -> A, even -> B)
 *   16  12 reserved (0)
 *   28  4  CRC32C of bytes [0, 28)
 *
 * Record header (32 bytes):
 *   0   4  recordMagic 0x53524543 ("SREC")
 *   4   1  state: 1 ACTIVE, 2 DELETED
 *   5   1  flags (0)
 *   6   2  reserved (0)
 *   8   4  dataLength
 *   12  4  dataCrc32c over the dataLength data bytes
 *   16  8  sequence (monotonic per file)
 *   24  4  ownerTag (informative: low 32 bits of the hash of the owning manifest id)
 *   28  4  headerCrc32c over bytes [0, 28) EXCLUDING the state byte (offset 4)
 * followed by dataLength data bytes and zero padding up to the next multiple of 8.
 * </pre>
 *
 * The state byte is excluded from the header CRC so that DELETE can flip it with one single-byte write
 * (atomic at sector level). Rewriting the whole 32-byte header would not be: it may straddle a 512-byte
 * sector, and a torn rewrite would make a perfectly good record look like a torn tail. The state byte is
 * validated separately (only 1 and 2 are legal).
 *
 * The checkpoint lives in two small CRC-protected slots outside the CRC of the static header so that moving it
 * never rewrites the 4 KiB header (a torn 4 KiB rewrite would make the whole file unopenable).
 */
public final class SmallBlobLayout {

    public static final byte[] MAGIC = "SDBSMOB1".getBytes(StandardCharsets.US_ASCII);
    public static final int FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 4096;
    public static final int DATA_START = HEADER_SIZE;
    public static final int ALIGNMENT = 8;
    public static final int RECORD_HEADER_SIZE = 32;
    public static final int RECORD_MAGIC = 0x53524543;
    public static final byte STATE_ACTIVE = 1;
    public static final byte STATE_DELETED = 2;
    public static final int STATE_OFFSET = 4;

    public static final int CHECKPOINT_SLOT_A = 64;
    public static final int CHECKPOINT_SLOT_B = 96;
    public static final int CHECKPOINT_SLOT_SIZE = 32;
    private static final int STATIC_CRC_COVERS = 64;
    private static final int HEADER_CRC_OFFSET = HEADER_SIZE - 4;

    private SmallBlobLayout() {
    }

    public record RecordHeader(byte state, int dataLength, int dataCrc32c, long sequence, int ownerTag) {
        public long span() {
            return recordSpan(dataLength);
        }
    }

    public record Checkpoint(long tail, long generation) {
    }

    /** Header + data + padding to 8. */
    public static long recordSpan(int dataLength) {
        return (RECORD_HEADER_SIZE + (long) dataLength + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
    }

    public static long alignUp(long v) {
        return (v + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
    }

    public static int crc(byte[] data, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(data, off, len);
        return (int) c.getValue();
    }

    // ── File header ────────────────────────────────────────────────────────────

    public static ByteBuffer encodeHeader(long createdMillis) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE);
        b.put(MAGIC);
        b.putInt(FORMAT_VERSION);
        b.putInt(0);
        b.putLong(createdMillis);
        b.putInt(HEADER_CRC_OFFSET, crc(b.array(), 0, STATIC_CRC_COVERS));
        b.clear();
        return b;
    }

    /** Verifies magic, CRC and version of the first 4096 bytes. Fails loudly. */
    public static void verifyHeader(byte[] raw, String blobId) throws InvalidBlobHeaderException {
        if (raw.length < HEADER_SIZE) {
            throw new InvalidBlobHeaderException("Small-object blob " + blobId + ": file shorter than the header");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                throw new InvalidBlobHeaderException("Small-object blob " + blobId + ": bad magic "
                        + "(not a SectoriaDB small-object blob)");
            }
        }
        ByteBuffer b = ByteBuffer.wrap(raw);
        if (crc(raw, 0, STATIC_CRC_COVERS) != b.getInt(HEADER_CRC_OFFSET)) {
            throw new InvalidBlobHeaderException("Small-object blob " + blobId + ": header CRC mismatch");
        }
        int version = b.getInt(8);
        if (version != FORMAT_VERSION) {
            throw new InvalidBlobHeaderException("Small-object blob " + blobId + ": unsupported format version "
                    + version + " (supported: " + FORMAT_VERSION + ")");
        }
    }

    // ── Checkpoint slots ───────────────────────────────────────────────────────

    public static byte[] encodeCheckpoint(long tail, long generation) {
        ByteBuffer b = ByteBuffer.allocate(CHECKPOINT_SLOT_SIZE);
        b.putLong(tail);
        b.putLong(generation);
        b.putInt(28, crc(b.array(), 0, 28));
        return b.array();
    }

    /** Returns the checkpoint stored in a slot, or null if the slot is empty or damaged. */
    public static Checkpoint decodeCheckpoint(byte[] raw, int slotOffset) {
        ByteBuffer b = ByteBuffer.wrap(raw, slotOffset, CHECKPOINT_SLOT_SIZE);
        long tail = b.getLong(slotOffset);
        long gen = b.getLong(slotOffset + 8);
        int stored = b.getInt(slotOffset + 28);
        CRC32C c = new CRC32C();
        c.update(raw, slotOffset, 28);
        if (gen <= 0 || (int) c.getValue() != stored) {
            return null;
        }
        return new Checkpoint(tail, gen);
    }

    // ── Records ────────────────────────────────────────────────────────────────

    /** Writes the 32-byte record header into {@code dst} at {@code at}. */
    public static void encodeRecordHeader(byte[] dst, int at, byte state, int dataLength, int dataCrc,
                                          long sequence, int ownerTag) {
        ByteBuffer b = ByteBuffer.wrap(dst, at, RECORD_HEADER_SIZE);
        b.putInt(at, RECORD_MAGIC);
        dst[at + STATE_OFFSET] = state;
        dst[at + 5] = 0;
        dst[at + 6] = 0;
        dst[at + 7] = 0;
        b.putInt(at + 8, dataLength);
        b.putInt(at + 12, dataCrc);
        b.putLong(at + 16, sequence);
        b.putInt(at + 24, ownerTag);
        b.putInt(at + 28, headerCrc(dst, at));
    }

    private static int headerCrc(byte[] raw, int at) {
        CRC32C c = new CRC32C();
        c.update(raw, at, STATE_OFFSET);
        c.update(raw, at + STATE_OFFSET + 1, 28 - STATE_OFFSET - 1);
        return (int) c.getValue();
    }

    /** Parses a record header; null if magic, CRC, state or length are invalid. */
    public static RecordHeader parseRecordHeader(byte[] raw, int at) {
        ByteBuffer b = ByteBuffer.wrap(raw);
        if (b.getInt(at) != RECORD_MAGIC) {
            return null;
        }
        if (b.getInt(at + 28) != headerCrc(raw, at)) {
            return null;
        }
        byte state = raw[at + STATE_OFFSET];
        if (state != STATE_ACTIVE && state != STATE_DELETED) {
            return null;
        }
        int len = b.getInt(at + 8);
        if (len < 0) {
            return null;
        }
        return new RecordHeader(state, len, b.getInt(at + 12), b.getLong(at + 16), b.getInt(at + 24));
    }
}
