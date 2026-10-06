package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.model.FileManifest;
import org.example.sectoriadb.service.ChunkingService;
import org.example.sectoriadb.service.FileWriteService;
import org.example.sectoriadb.tools.BytesHasher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BlobFileWriteService implements FileWriteService {

    private final CuckooHashTable cuckooHashTable;
    private final ChunkingService chunkingService;
    private final BytesHasher hasher;

    public BlobFileWriteService(CuckooHashTable cuckooHashTable,
                                ChunkingService chunkingService,
                                BytesHasher hasher) {
        this.cuckooHashTable = cuckooHashTable;
        this.chunkingService = chunkingService;
        this.hasher = hasher;
    }

    @Override
    public FileManifest writeFile(Path filePath) throws IOException {
        int chunkSize = cuckooHashTable.getChunkSize();
        List<Long> chunkKeys = new ArrayList<>();
        int lastChunkSize = 0;

        try (java.nio.channels.FileChannel fc =
                     java.nio.channels.FileChannel.open(filePath, java.nio.file.StandardOpenOption.READ)) {
            long fileSize = fc.size();
            long totalChunks = (fileSize + chunkSize - 1) / chunkSize;
            long fileOffset = 0;
            long chunksDone = 0;
            int lastDecile = -1;
            while (fileOffset < fileSize) {
                int size = (int) Math.min(chunkSize, fileSize - fileOffset);
                ByteBuffer chunk = ByteBuffer.allocate(size);
                fc.read(chunk, fileOffset);
                chunk.flip();

                long key = hasher.hash64(chunk.duplicate());
                cuckooHashTable.insert(key, chunk);
                chunkKeys.add(key);

                lastChunkSize = size;
                fileOffset += size;
                chunksDone++;

                if (totalChunks >= 2) {
                    int decile = (int)(10.0 * chunksDone / totalChunks);
                    if (decile > lastDecile) {
                        lastDecile = decile;
                        int pct = (int)(100.0 * chunksDone / totalChunks);
                        System.out.printf("  %3d%% (%d/%d чанков)%n", pct, chunksDone, totalChunks);
                    }
                }
            }
        }

        return new FileManifest(filePath.getFileName().toString(), chunkSize, chunkKeys, lastChunkSize);
    }
}
