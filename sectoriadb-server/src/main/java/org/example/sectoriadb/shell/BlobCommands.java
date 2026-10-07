package org.example.sectoriadb.shell;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.HashTableCache;
import org.example.sectoriadb.service.PoolService;
import org.example.sectoriadb.service.ResizeService;
import org.example.sectoriadb.service.impl.CuckooHashTable;
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
    private final ChunkRepository chunkRepo;

    public BlobCommands(BlobService blobService, PoolService poolService, HashTableCache cache,
                        ResizeService resizeService, StorageProperties props, ChunkRepository chunkRepo) {
        this.chunkRepo     = chunkRepo;
        this.blobService   = blobService;
        this.poolService   = poolService;
        this.cache         = cache;
        this.resizeService = resizeService;
        this.props         = props;
    }

    @ShellMethod(key = "mkblob", value = "Create a blob file in a pool  |  mkblob --pool NAME [--buckets N] [--chunk N] | mkblob --pool NAME --small")
    public String mkblob(
            @ShellOption(help = "Pool name") String pool,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Number of hash buckets (default from config)") Integer buckets,
            @ShellOption(defaultValue = ShellOption.NULL, help = "Chunk size in bytes (default from config)") Integer chunk,
            @ShellOption(defaultValue = "false", help = "Create a small-object blob (append-only log) instead of a cuckoo table") boolean small)
            throws IOException {
        PoolEntity poolEntity = poolService.getByName(pool);
        if (small) {
            BlobFileEntity sb = blobService.createSmall(poolEntity);
            return String.format("Created small-object blob  id=%s  file=%s", sb.getId(), sb.getFileName());
        }
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

        return ShellTable.poolBlobTable(blobService, chunkRepo, list).stripTrailing();
    }

    @ShellMethod(key = "blob", value = "Show detailed blob info and fill statistics  |  blob --id BLOB_ID")
    public String blob(@ShellOption(help = "Blob file ID") String id) throws IOException {
        BlobFileEntity b = blobService.getById(id);
        if (b.getKind() == BlobKind.SMALL) {
            SmallObjectBlob.Stats s = blobService.getSmallStats(b);
            return String.format(
                    "Blob: %s  (small-object blob)%n" +
                    "  Pool:        %s%n" +
                    "  Path:        %s%n" +
                    "  Created:     %s%n" +
                    "  File size:   %s  (valid log, header included)%n" +
                    "  Live:        %d record(s), %s%n" +
                    "  Dead:        %d record(s), %s  (reclaimable by a future compaction)%n" +
                    "  Writable:    %s",
                    b.getId(),
                    b.getPool() != null ? b.getPool().getName() : b.getPoolId(),
                    b.getFilePath(), b.getCreatedAt(),
                    ShellTable.humanSize(s.fileBytes()),
                    s.liveRecords(), ShellTable.humanSize(s.liveBytes()),
                    s.deadRecords(), ShellTable.humanSize(s.deadBytes()),
                    s.writable() ? "yes" : "NO (damage found in the middle of the log; see 'scrub')");
        }
        CuckooHashTable.FillStats stats = cache.get(b).getFillStats();

        return String.format(
                "Blob: %s%n" +
                "  Pool:        %s%n" +
                "  Path:        %s%n" +
                "  Created:     %s%n" +
                "  Buckets:     %d per table  |  Total slots: %d%n" +
                "  Chunk size:  %s%n" +
                "  Physical:    %s%n" +
                "  Fill:        %d / %d slots  (%.1f%%)  quarantined: %d%n" +
                "  Used:        %s%n" +
                "  Free:        %s%n" +
                "  Chunks:      %d indexed, %d referenced%n" +
                "  Weight:      %s  (HRW placement weight, free capacity x fill factor)",
                b.getId(),
                b.getPool() != null ? b.getPool().getName() : b.getPoolId(),
                b.getFilePath(),
                b.getCreatedAt(),
                b.getNumBuckets(), stats.totalSlots(),
                ShellTable.humanSize(b.getChunkSize()),
                ShellTable.humanSize(b.getTotalBytes()),
                stats.activeSlots(), stats.totalSlots(), stats.fillPercent(), stats.quarantinedSlots(),
                ShellTable.humanSize(stats.usedBytes(b.getChunkSize())),
                ShellTable.humanSize(stats.freeBytes(b.getChunkSize())),
                chunkRepo.countByBlob(b.getId()), chunkRepo.countReferencedByBlob(b.getPoolId(), b.getId()),
                ShellTable.humanSize((long) blobService.placementWeight(b)));
    }

    @ShellMethod(key = "resize",
            value = "Expand one blob in place via cuckoo migration (pools normally grow by adding blobs)  |  resize --id BLOB_ID --buckets N")
    public String resize(
            @ShellOption(help = "Blob file ID") String id,
            @ShellOption(help = "New number of buckets (must leave ≤70% fill after migration)") int buckets)
            throws IOException {
        BlobFileEntity old = blobService.getById(id);
        if (old.getKind() == BlobKind.SMALL) {
            return "Small-object blobs cannot be resized (they grow by rollover to a new blob).";
        }
        CuckooHashTable.FillStats stats = cache.get(old).getFillStats();
        System.out.printf("Resizing blob %s: %d → %d buckets  (current fill: %.1f%%)%n",
                id, old.getNumBuckets(), buckets, stats.fillPercent());

        BlobFileEntity newBlob = resizeService.resizeBlobFile(id, buckets);
        CuckooHashTable.FillStats newStats = cache.get(newBlob).getFillStats();
        return String.format("Done.  new id=%s  fill=%.1f%% (%d/%d slots)",
                newBlob.getId(), newStats.fillPercent(), newStats.activeSlots(), newStats.totalSlots());
    }

    @ShellMethod(key = "scrub",
            value = "Verify the CRC32C of every stored chunk in a blob  |  scrub --id BLOB_ID")
    public String scrub(@ShellOption(help = "Blob file ID") String id) throws IOException {
        BlobFileEntity b = blobService.getById(id);
        if (b.getKind() == BlobKind.SMALL) {
            SmallObjectBlob.ScrubReport r = blobService.scrubSmall(b);
            StringBuilder sb = new StringBuilder(String.format(
                    "Scrub of small-object blob %s: records=%d  active=%d  deleted=%d  ok=%d  corrupt=%d",
                    id, r.records(), r.active(), r.deleted(), r.ok(), r.corrupt()));
            for (String p : r.problems()) sb.append(System.lineSeparator()).append("  ").append(p);
            sb.append(System.lineSeparator()).append(r.corrupt() == 0 ? "RESULT: clean" : "RESULT: DAMAGED");
            return sb.toString();
        }
        CuckooHashTable.ScrubReport r = cache.get(b).scrub();
        StringBuilder sb = new StringBuilder(String.format(
                "Scrub of blob %s: active=%d  ok=%d  corrupt=%d  quarantined=%d",
                id, r.activeSlots(), r.ok(), r.corrupt(), r.quarantined()));
        for (String p : r.problems()) sb.append(System.lineSeparator()).append("  ").append(p);
        sb.append(System.lineSeparator()).append(r.corrupt() == 0 && r.quarantined() == 0 ? "RESULT: clean" : "RESULT: DAMAGED");
        return sb.toString();
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
