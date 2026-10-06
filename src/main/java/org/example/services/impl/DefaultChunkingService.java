package org.example.services.impl;

import org.example.services.ChunkingService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class DefaultChunkingService implements ChunkingService {

    @Override
    public List<ByteBuffer> split(ByteBuffer data, int chunkSize) {
        List<ByteBuffer> chunks = new ArrayList<>();
        ByteBuffer src = data.duplicate();
        src.rewind();
        while (src.hasRemaining()) {
            int size = Math.min(chunkSize, src.remaining());
            byte[] bytes = new byte[size];
            src.get(bytes);
            ByteBuffer chunk = ByteBuffer.wrap(bytes);
            chunks.add(chunk);
        }
        return chunks;
    }

    @Override
    public ByteBuffer merge(List<ByteBuffer> chunks) {
        int totalSize = chunks.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer result = ByteBuffer.allocate(totalSize);
        for (ByteBuffer chunk : chunks) {
            result.put(chunk.duplicate());
        }
        result.flip();
        return result;
    }
}
