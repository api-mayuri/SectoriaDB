package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.model.FileManifest;
import org.example.sectoriadb.service.ChunkingService;
import org.example.sectoriadb.service.FileWriteService;
import org.example.sectoriadb.tools.BytesHasher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BlobFileWriteService implements FileWriteService {

    private static final Logger log = LoggerFactory.getLogger(BlobFileWriteService.class);

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
                try {
                    FileChannelStorageIOEngine.readFully(fc::read, chunk, fileOffset);
                } catch (IOException e) {
                    throw new IOException("Source file shrank while being read: " + filePath
                            + " (expected " + fileSize + " bytes, failed at offset " + fileOffset + ")", e);
                }
                chunk.flip();

                long key = hasher.hash64(chunk.duplicate());
                // The table may re-key the chunk on a hash collision: the manifest must record the final key.
                InsertResult stored = cuckooHashTable.insert(key, chunk);
                chunkKeys.add(stored.key());

                lastChunkSize = size;
                fileOffset += size;
                chunksDone++;

                if (totalChunks >= 2) {
                    int decile = (int)(10.0 * chunksDone / totalChunks);
                    if (decile > lastDecile) {
                        lastDecile = decile;
                        int pct = (int)(100.0 * chunksDone / totalChunks);
                        log.debug("Writing {}: {}% ({}/{} chunks)", filePath.getFileName(), pct, chunksDone, totalChunks);
                    }
                }
            }
        }

        return new FileManifest(filePath.getFileName().toString(), chunkSize, chunkKeys, lastChunkSize);
    }
}
