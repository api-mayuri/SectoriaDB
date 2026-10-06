package org.example.shell;

import org.example.config.StorageProperties;
import org.example.model.BlobFileEntity;
import org.example.model.PoolEntity;
import org.example.service.BlobService;
import org.example.service.HashTableCache;
import org.example.service.PoolService;
import org.example.service.ResizeService;
import org.example.service.impl.CuckooHashTable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.util.List;

@ShellComponent
public class BlobCommands {

    private final BlobService blobService;
    private final PoolService poolService;
    private final HashTableCache cache;
    private final ResizeService resizeService;
    private final StorageProperties props;

    public BlobCommands(BlobService blobService, PoolService poolService, HashTableCache cache,
                        ResizeService resizeService, StorageProperties props) {
        this.blobService   = blobService;
        this.poolService   = poolService;
        this.cache         = cache;
        this.resizeService = resizeService;
        this.props         = props;
    }

    @ShellMethod(key = "mkblob", value = "Create a blob file in a pool  |  mkblob --pool NAME [--buckets N] [--chunk N]")
    public String mkblob(
            @ShellOption(help = "Pool name") String pool,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Number of hash buckets (default from config)") Integer buckets,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Chunk size in bytes (default from config)") Integer chunk)
            throws IOException {
        PoolEntity poolEntity = poolService.getByName(pool);
        int b  = buckets != null ? buckets : props.getDefaultNumBuckets();
        int cs = chunk   != null ? chunk   : props.getDefaultChunkSize();

        BlobFileEntity e = blobService.create(poolEntity, b, cs);
        return String.format("Created blob  id=%s  buckets=%d  chunk=%s  size=%s (sparse)",
                e.getId(), b, ShellTable.humanSize(cs), ShellTable.humanSize(e.getTotalBytes()));
    }

    @ShellMethod(key = "blobs", value = "List blob files in a pool  |  blobs --pool NAME")
    public String blobs(@ShellOption(help = "Pool name") String pool) throws IOException {
        PoolEntity poolEntity = poolService.getByName(pool);
        List<BlobFileEntity> list = blobService.listByPool(poolEntity);
        if (list.isEmpty()) return "Pool '" + pool + "' has no blobs. Use 'mkblob --pool " + pool + "' to create one.";

        StringBuilder sb = new StringBuilder();
        sb.append(ShellTable.blobTableHeader());
        for (BlobFileEntity b : list) {
            CuckooHashTable.FillStats stats = cache.get(b).getFillStats();
            sb.append(ShellTable.blobTableRow(b, stats));
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "blob", value = "Show detailed blob info and fill statistics  |  blob --id BLOB_ID")
    public String blob(@ShellOption(help = "Blob file ID") String id) throws IOException {
        BlobFileEntity b = blobService.getById(id);
        CuckooHashTable.FillStats stats = cache.get(b).getFillStats();

        return String.format(
                "Blob: %s%n" +
                "  Pool:        %s%n" +
                "  Path:        %s%n" +
                "  Created:     %s%n" +
                "  Buckets:     %d per table  |  Total slots: %d%n" +
                "  Chunk size:  %s%n" +
                "  Physical:    %s%n" +
                "  Fill:        %d / %d slots  (%.1f%%)%n" +
                "  Used:        %s%n" +
                "  Free:        %s",
                b.getId(),
                b.getPool() != null ? b.getPool().getName() : b.getPoolId(),
                b.getFilePath(),
                b.getCreatedAt(),
                b.getNumBuckets(), stats.totalSlots(),
                ShellTable.humanSize(b.getChunkSize()),
                ShellTable.humanSize(b.getTotalBytes()),
                stats.activeSlots(), stats.totalSlots(), stats.fillPercent(),
                ShellTable.humanSize(stats.usedBytes(b.getChunkSize())),
                ShellTable.humanSize(stats.freeBytes(b.getChunkSize())));
    }

    @ShellMethod(key = "resize",
            value = "Resize a blob file via cuckoo migration  |  resize --id BLOB_ID --buckets N")
    public String resize(
            @ShellOption(help = "Blob file ID") String id,
            @ShellOption(help = "New number of buckets (must leave ≤70% fill after migration)") int buckets)
            throws IOException {
        BlobFileEntity old = blobService.getById(id);
        CuckooHashTable.FillStats stats = cache.get(old).getFillStats();
        System.out.printf("Resizing blob %s: %d → %d buckets  (current fill: %.1f%%)%n",
                id, old.getNumBuckets(), buckets, stats.fillPercent());

        BlobFileEntity newBlob = resizeService.resizeBlobFile(id, buckets);
        CuckooHashTable.FillStats newStats = cache.get(newBlob).getFillStats();
        return String.format("Done.  new id=%s  fill=%.1f%% (%d/%d slots)",
                newBlob.getId(), newStats.fillPercent(), newStats.activeSlots(), newStats.totalSlots());
    }

    @ShellMethod(key = "rmblob", value = "Delete a blob file (only if no files reference it)  |  rmblob --id BLOB_ID [--yes]")
    public String rmblob(
            @ShellOption(help = "Blob file ID") String id,
            @ShellOption(defaultValue = "false", help = "Skip confirmation prompt") boolean yes)
            throws IOException {
        if (!yes) return "Add '--yes' to confirm deletion of blob '" + id + "'";
        BlobFileEntity b = blobService.getById(id);
        blobService.delete(id);
        return "Deleted blob: " + b.getFileName();
    }
}
