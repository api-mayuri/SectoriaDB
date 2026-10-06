package org.example.services.impl;

import org.example.entities.ChunkLocation;
import org.example.entities.FileManifest;
import org.example.services.ChunkingService;
import org.example.services.FileRestoreService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BlobFileRestoreService implements FileRestoreService {

    private final CuckooHashTable cuckooHashTable;
    private final ChunkingService chunkingService;

    public BlobFileRestoreService(CuckooHashTable cuckooHashTable, ChunkingService chunkingService) {
        this.cuckooHashTable = cuckooHashTable;
        this.chunkingService = chunkingService;
    }

    @Override
    public void restoreFile(FileManifest manifest, Path outputPath) throws IOException {
        List<Long> keys = manifest.chunkKeys();

        try (java.nio.channels.FileChannel out = java.nio.channels.FileChannel.open(outputPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            int total = keys.size();
            int lastDecile = -1;
            for (int i = 0; i < total; i++) {
                long key = keys.get(i);
                Optional<ChunkLocation> location = cuckooHashTable.lookup(key);
                if (location.isEmpty()) {
                    throw new IOException("Chunk not found: key=0x" + Long.toHexString(key) + " index=" + i);
                }
                boolean isLast = (i == total - 1);
                int readSize = isLast ? manifest.lastChunkActualSize() : manifest.chunkSize();
                ByteBuffer chunk = cuckooHashTable.readChunk(location.get(), readSize);
                out.write(chunk);

                if (total >= 2) {
                    int decile = (int)(10.0 * (i + 1) / total);
                    if (decile > lastDecile) {
                        lastDecile = decile;
                        int pct = (int)(100.0 * (i + 1) / total);
                        System.out.printf("  %3d%% (%d/%d чанков)%n", pct, i + 1, total);
                    }
                }
            }
        }
    }
}
