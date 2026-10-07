package org.example.sectoriadb.shell;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.HashTableCache;
import org.example.sectoriadb.service.PoolService;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.util.List;

@ShellComponent
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

    @ShellMethod(key = "mkpool", value = "Create a new storage pool  |  mkpool --name NAME --path DIR")
    public String mkpool(
            @ShellOption(help = "Pool name") String name,
            @ShellOption(help = "Base directory path on disk") String path) throws IOException {
        PoolEntity pool = poolService.create(name, path);
        return String.format("Created pool '%s'  id=%s  path=%s", pool.getName(), pool.getId(), pool.getBasePath());
    }

    @ShellMethod(key = "pools", value = "List all storage pools  |  pools")
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

    @ShellMethod(key = "pool", value = "Show pool details and blob fill stats  |  pool --name NAME")
    public String pool(@ShellOption(help = "Pool name") String name) throws IOException {
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

    @ShellMethod(key = "rmpool", value = "Delete a storage pool  |  rmpool --name NAME [--yes]")
    public String rmpool(
            @ShellOption(help = "Pool name") String name,
            @ShellOption(defaultValue = "false", help = "Skip confirmation prompt") boolean yes) {
        if (!yes) return "Add '--yes' to confirm deletion of pool '" + name + "'";
        poolService.delete(name);
        return "Deleted pool: " + name;
    }
}
