package org.example.sectoriadb;

import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class XxHash64Test {

    private final XxHash64BytesHasher h = new XxHash64BytesHasher();

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Published XXH64 vectors (seed 0). The 39-byte input exercises the 4-lane main loop's tail paths too. */
    @Test
    void officialVectors_seed0() {
        assertEquals(0xEF46DB3751D8E999L, h.hash64(new byte[0]));
        assertEquals(0xD24EC4F1A98C6E5BL, h.hash64(ascii("a")));
        assertEquals(0x44BC2CF5AD770999L, h.hash64(ascii("abc")));
        assertEquals(0xFBCEA83C8A378BF1L, h.hash64(ascii("Nobody inspects the spammish repetition")));
    }

    /** Long (multi-stripe) input and non-zero seeds, cross-checked against an independent reference implementation. */
    @Test
    void crossCheckedVectors_longInputAndSeeds() {
        byte[] data = new byte[768];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        assertEquals(0x8E03C838C596036FL, h.hash64(data));
        assertEquals(0xA80257374B99ADE3L, h.hash64(data, 1L));
        assertEquals(0xD5AFBA1336A3BE4BL, h.hash64(new byte[0], 1L));
        assertEquals(0xDEC2BC81C3CD46C6L, h.hash64(ascii("a"), 1L));
    }

    @Test
    void seedChangesHash_andBufferViewIsRespected() {
        byte[] data = ascii("some chunk of data that is a bit longer than thirty-two bytes");
        assertNotEquals(h.hash64(data, 0), h.hash64(data, 1));

        ByteBuffer buf = ByteBuffer.allocate(data.length + 10);
        buf.position(5);
        buf.put(data);
        buf.position(5).limit(5 + data.length);
        assertEquals(h.hash64(data), h.hash64(buf));
        assertEquals(5, buf.position(), "hashing must not move the caller's position");
    }
}
