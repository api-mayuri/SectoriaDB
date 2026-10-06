package org.example;

import org.example.services.impl.DefaultChunkingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {

    private DefaultChunkingService service;

    @BeforeEach
    void setUp() {
        service = new DefaultChunkingService();
    }

    @Test
    void split_evenlyDivisible_producesEqualChunks() {
        byte[] data = new byte[8192];
        new Random(1).nextBytes(data);

        List<ByteBuffer> chunks = service.split(ByteBuffer.wrap(data), 4096);

        assertEquals(2, chunks.size());
        assertEquals(4096, chunks.get(0).remaining());
        assertEquals(4096, chunks.get(1).remaining());
    }

    @Test
    void split_withRemainder_lastChunkIsShorter() {
        byte[] data = new byte[5000];
        new Random(2).nextBytes(data);

        List<ByteBuffer> chunks = service.split(ByteBuffer.wrap(data), 4096);

        assertEquals(2, chunks.size());
        assertEquals(4096, chunks.get(0).remaining());
        assertEquals(904,  chunks.get(1).remaining());
    }

    @Test
    void splitAndMerge_roundtrip_preservesAllBytes() {
        byte[] original = new byte[13_000];
        new Random(3).nextBytes(original);

        List<ByteBuffer> chunks  = service.split(ByteBuffer.wrap(original), 4096);
        ByteBuffer merged = service.merge(chunks);

        byte[] result = new byte[merged.remaining()];
        merged.get(result);
        assertArrayEquals(original, result);
    }
}
