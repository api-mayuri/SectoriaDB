package org.example.shell;

import org.example.model.ManifestEntity;
import org.example.model.PoolEntity;
import org.example.service.FileStorageService;
import org.example.service.PoolService;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellMethodAvailability;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@ShellComponent
public class FileCommands {

    private final FileStorageService fileService;
    private final PoolService poolService;

    public FileCommands(FileStorageService fileService, PoolService poolService) {
        this.fileService = fileService;
        this.poolService = poolService;
    }

    @ShellMethod(key = "store", value = "Store a file into a pool  |  store --file PATH --pool NAME")
    public String store(
            @ShellOption(help = "Path to the source file") String file,
            @ShellOption(help = "Target pool name") String pool) throws IOException {
        PoolEntity poolEntity = poolService.getByName(pool);
        ManifestEntity m = fileService.store(Path.of(file), poolEntity);
        return String.format("Stored  id=%-36s  file=%s  size=%s  chunks=%d",
                m.getId(), m.getSourceFileName(),
                ShellTable.humanSize(m.getTotalBytes()), m.getTotalChunks());
    }

    @ShellMethod(key = "ls", value = "List stored files  |  ls [--pool NAME]")
    public String ls(
            @ShellOption(defaultValue = ShellOption.NULL, help = "Filter by pool name") String pool) {
        List<ManifestEntity> manifests;
        if (pool != null) {
            PoolEntity poolEntity = poolService.getByName(pool);
            manifests = fileService.listAll().stream()
                    .filter(m -> m.getBlobFile() != null
                            && poolEntity.getId().equals(m.getBlobFile().getPoolId()))
                    .toList();
        } else {
            manifests = fileService.listAll();
        }
        if (manifests.isEmpty()) return "No files stored yet. Use 'store --file PATH --pool NAME'.";

        StringBuilder sb = new StringBuilder();
        sb.append(ShellTable.fileTableHeader());
        for (ManifestEntity m : manifests) sb.append(ShellTable.fileTableRow(m));
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "info", value = "Show detailed info about a stored file  |  info --id FILE_ID")
    public String info(@ShellOption(help = "File ID") String id) {
        ManifestEntity m = fileService.getActiveManifest(id);
        String poolName = (m.getBlobFile() != null && m.getBlobFile().getPool() != null)
                ? m.getBlobFile().getPool().getName()
                : m.getBlobFile() != null ? m.getBlobFile().getPoolId() : "?";
        return String.format(
                "File: %s%n" +
                "  ID:          %s%n" +
                "  Blob:        %s%n" +
                "  Pool:        %s%n" +
                "  Size:        %s%n" +
                "  Chunks:      %d × %s  (last chunk: %s)%n" +
                "  Stored at:   %s",
                m.getSourceFileName(), m.getId(), m.getBlobFileId(), poolName,
                ShellTable.humanSize(m.getTotalBytes()),
                m.getTotalChunks(), ShellTable.humanSize(m.getChunkSize()),
                ShellTable.humanSize(m.getLastChunkSize()),
                m.getCreatedAt());
    }

    @ShellMethod(key = "get",
            value = "Restore a stored file to disk (full or byte-range)  |  " +
                    "get --id ID --out PATH [--from N --len N]")
    public String get(
            @ShellOption(help = "File ID") String id,
            @ShellOption(help = "Output path") String out,
            @ShellOption(defaultValue = ShellOption.NULL,
                    help = "Start byte offset for range restore (0-based, inclusive)") Long from,
            @ShellOption(defaultValue = ShellOption.NULL,
                    help = "Number of bytes to extract (required when --from is set)") Long len)
            throws IOException {

        ManifestEntity m = fileService.getActiveManifest(id);

        if (from != null || len != null) {
            if (from == null || len == null) {
                return "Range restore requires both --from and --len. " +
                       "Example: get --id ID --out PATH --from 1048576 --len 524288";
            }
            System.out.printf("Extracting [%d, +%d] from '%s' (%s) → %s%n",
                    from, len, m.getSourceFileName(),
                    ShellTable.humanSize(m.getTotalBytes()), out);
            fileService.restoreRange(id, Path.of(out), from, len);
            return String.format("Extracted %s at byte %d → %s",
                    ShellTable.humanSize(len), from, Path.of(out).toAbsolutePath());
        } else {
            System.out.printf("Restoring '%s' (%s) → %s%n",
                    m.getSourceFileName(), ShellTable.humanSize(m.getTotalBytes()), out);
            fileService.restore(id, Path.of(out));
            return "Restored to: " + Path.of(out).toAbsolutePath();
        }
    }

    @ShellMethod(key = "rm", value = "Soft-delete a stored file  |  rm --id FILE_ID [--yes]")
    public String rm(
            @ShellOption(help = "File ID") String id,
            @ShellOption(defaultValue = "false", help = "Skip confirmation prompt") boolean yes) {
        if (!yes) return "Add '--yes' to confirm deletion of file '" + id + "'";
        ManifestEntity m = fileService.getActiveManifest(id);
        fileService.delete(id);
        return "Deleted: " + m.getSourceFileName() + "  (id=" + id + ")";
    }
}
