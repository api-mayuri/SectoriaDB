package org.example.sectoriadb.engine.format;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.example.sectoriadb.engine.error.InvalidBlobHeaderException;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Headers of blob_*.raw and small_*.sob, and small-object record headers, from damaged or hostile bytes: the callers
 * always pass a buffer of the header size, the rest is arbitrary. The contract: {@link InvalidBlobHeaderException} for a
 * file header, {@code null} for a record header, never another exception.
 */
class LayoutFuzzTest {

    @FuzzTest(maxDuration = "1m")
    void cuckooHeader(byte[] data) {
        byte[] h = Arrays.copyOf(data, BlobLayout.HEADER_SIZE);
        if (data.length > 0 && (data[0] & 1) == 0) System.arraycopy(BlobLayout.MAGIC, 0, h, 0, BlobLayout.MAGIC.length);
        try {
            BlobLayout.verifyHeader(ByteBuffer.wrap(h), 1024, 16384, "fuzz");
        } catch (InvalidBlobHeaderException expected) {
            // the contract for a header that does not check out
        }
    }

    @FuzzTest(maxDuration = "1m")
    void smallBlobHeader(byte[] data) {
        byte[] h = Arrays.copyOf(data, SmallBlobLayout.HEADER_SIZE);
        if (data.length > 0 && (data[0] & 1) == 0) System.arraycopy(SmallBlobLayout.MAGIC, 0, h, 0, SmallBlobLayout.MAGIC.length);
        try {
            SmallBlobLayout.verifyHeader(h, "fuzz");
        } catch (InvalidBlobHeaderException expected) {
            // the contract for a header that does not check out
        }
    }

    @FuzzTest(maxDuration = "1m")
    void smallRecordHeader(byte[] data) {
        byte[] raw = Arrays.copyOf(data, Math.max(data.length, SmallBlobLayout.RECORD_HEADER_SIZE));
        if ((raw[0] & 1) == 0) ByteBuffer.wrap(raw).putInt(0, SmallBlobLayout.RECORD_MAGIC);
        SmallBlobLayout.parseRecordHeader(raw, 0);   // null, or a header
    }
}
