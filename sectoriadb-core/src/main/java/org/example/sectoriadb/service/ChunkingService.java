package org.example.sectoriadb.service;

import java.nio.ByteBuffer;
import java.util.List;

public interface ChunkingService {
    List<ByteBuffer> split(ByteBuffer data, int chunkSize);
    ByteBuffer merge(List<ByteBuffer> chunks);
}
