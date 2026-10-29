package org.example.sectoriadb.shell;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.BlobFileRepository;
import org.example.sectoriadb.catalog.ChunkRepository;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.storage.BlobService;
import org.example.sectoriadb.storage.HashTableCache;
import org.example.sectoriadb.storage.PoolService;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class PoolCommands {

    private final PoolService poolService;
    private final BlobFileRepository blobRepo;
    private final BlobService blobService;
    private final HashTableCache cache;
    private final ManifestRepository manifestRepo;
    private final ChunkRepository chunkRepo;

    public PoolCommands(PoolService poolService, BlobFileRepository blobRepo,
                        BlobService blobService, HashTableCache cache, ManifestRepository manifestRepo,
                        ChunkRepository chunkRepo) {
        this.chunkRepo = chunkRepo;
        this.manifestRepo = manifestRepo;
        this.poolService = poolService;
        this.blobRepo    = blobRepo;
        this.blobService = blobService;
        this.cache       = cache;
    }

    @Command(name = "mkpool", description = "Create a new storage pool  |  mkpool --name NAME --path DIR")
    public String mkpool(
            @Option(longName = "name", required = true, description = "Pool name") String name,
            @Option(longName = "path", required = true, description = "Base directory path on disk") String path) throws IOException {
        PoolEntity pool = poolService.create(name, path);
        return String.format("Created pool '%s'  id=%s  path=%s", pool.getName(), pool.getId(), pool.getBasePath());
    }

    @Command(name = "pools", description = "List all storage pools  |  pools")
    public String pools() {
        List<PoolEntity> list = poolService.listAll();
        if (list.isEmpty()) return "No pools yet. Use 'mkpool --name NAME --path DIR' to create one.";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-36s  %-20s  %s%n", "ID", "Name", "Path"));
        sb.append("─".repeat(90)).append('\n');
        for (PoolEntity p : list) {
            long blobCount = blobRepo.countByPoolId(p.getId());
            sb.append(String.format("%-36s  %-20s  %s  [%d blob(s)]%n",
                    p.getId(), p.getName(), p.getBasePath(), blobCount));
        }
        return sb.toString().stripTrailing();
    }

    @Command(name = "pool", description = "Show pool details and blob fill stats  |  pool --name NAME")
    public String pool(@Option(longName = "name", required = true, description = "Pool name") String name) throws IOException {
        PoolEntity p = poolService.getByName(name);
        List<BlobFileEntity> blobs = blobService.listByPool(p);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Pool: %s%n", p.getName()));
        sb.append(String.format("  ID:      %s%n", p.getId()));
        sb.append(String.format("  Path:    %s%n", p.getBasePath()));
        sb.append(String.format("  Created: %s%n", p.getCreatedAt()));
        sb.append(String.format("  Blobs:   %d%n", blobs.size()));
        ManifestRepository.BucketStats stats = manifestRepo.countAndSize(p.getName());   // scans the bucket's key range
        sb.append(String.format("  Objects: %d  (%s)%n", stats.objects(), ShellTable.humanSize(stats.bytes())));

        if (!blobs.isEmpty()) {
            sb.append(ShellTable.poolBlobTable(blobService, chunkRepo, blobs));
            BlobService.PoolFill fill = blobService.poolFill(p);
            sb.append(String.format("  Pool fill: %.1f%% of %d slot(s) in %d cuckoo blob(s)%n",
                    fill.fillPercent(), fill.totalSlots(), fill.blobs()));
            ChunkRepository.Stats cs = chunkRepo.stats();
            sb.append(String.format("  Chunk index (all pools): %d chunk(s), %d reference(s), %d awaiting collection, %d orphan copy(ies)%n",
                    cs.chunks(), cs.refs(), cs.gcQueue(), cs.orphans()));
        }
        return sb.toString().stripTrailing();
    }

    @Command(name = "rmpool", description = "Delete a storage pool  |  rmpool --name NAME [--yes]")
    public String rmpool(
            @Option(longName = "name", required = true, description = "Pool name") String name,
            @Option(longName = "yes", defaultValue = "false", description = "Skip confirmation prompt") boolean yes) {
        if (!yes) return "Add '--yes' to confirm deletion of pool '" + name + "'";
        poolService.delete(name);
        return "Deleted pool: " + name;
    }
}
