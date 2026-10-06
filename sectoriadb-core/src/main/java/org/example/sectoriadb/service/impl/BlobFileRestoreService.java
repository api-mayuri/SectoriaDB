package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.model.FileManifest;
import org.example.sectoriadb.service.ChunkingService;
import org.example.sectoriadb.service.FileRestoreService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class BlobFileRestoreService implements FileRestoreService {

    private static final Logger log = LoggerFactory.getLogger(BlobFileRestoreService.class);

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
                boolean isLast = (i == total - 1);
                int readSize = isLast ? manifest.lastChunkActualSize() : manifest.chunkSize();
                ByteBuffer chunk = cuckooHashTable.readChunkByKey(key, readSize).orElseThrow(() ->
                        new IOException("Chunk not found: key=0x" + Long.toHexString(key)));
                out.write(chunk);

                if (total >= 2) {
                    int decile = (int)(10.0 * (i + 1) / total);
                    if (decile > lastDecile) {
                        lastDecile = decile;
                        int pct = (int)(100.0 * (i + 1) / total);
                        log.debug("Restoring: {}% ({}/{} chunks)", pct, i + 1, total);
                    }
                }
            }
        }
    }
}
