package org.example.sectoriadb.shell;

import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.ObjectVerificationService;
import org.example.sectoriadb.service.PoolService;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

@ShellComponent
public class FileCommands {

    private final FileStorageService fileService;
    private final PoolService poolService;
    private final ObjectVerificationService verifier;

    public FileCommands(FileStorageService fileService, PoolService poolService, ObjectVerificationService verifier) {
        this.fileService = fileService;
        this.poolService = poolService;
        this.verifier = verifier;
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
                    .filter(m -> poolEntity.getId().equals(m.getPoolId())
                            || (m.getPhysicalBlob() != null
                                && poolEntity.getId().equals(m.getPhysicalBlob().getPoolId())))
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
        var phys = m.getPhysicalBlob();
        String poolName = (phys != null && phys.getPool() != null)
                ? phys.getPool().getName()
                : phys != null ? phys.getPoolId() : m.getPoolId() != null ? m.getPoolId() : "?";
        String stored = switch (m.getStorageKind()) {
            case EMPTY -> "empty object (no blob)";
            case SMALL -> String.format("1 record in small blob at offset %d (%d B)",
                    m.getSmallOffset(), m.getSmallLength());
            case CHUNKED -> String.format("%d × %s  (last chunk: %s)", m.getTotalChunks(),
                    ShellTable.humanSize(m.getChunkSize()), ShellTable.humanSize(m.getLastChunkSize()));
        };
        return String.format(
                "File: %s%n" +
                "  ID:          %s%n" +
                "  Kind:        %s%n" +
                "  Blob:        %s%n" +
                "  Pool:        %s%n" +
                "  Size:        %s%n" +
                "  Stored as:   %s%n" +
                "  Stored at:   %s%n" +
                "  CRC32C:      %s%n" +
                "  Checksum:    %s",
                m.getSourceFileName(), m.getId(), m.getStorageKind(),
                phys != null ? phys.getId() : "-", poolName,
                ShellTable.humanSize(m.getTotalBytes()),
                stored,
                m.getCreatedAt(),
                m.getCrc32c() != null ? m.getCrc32c() : "(not stored)",
                m.getChecksumAlgorithm() != null
                        ? m.getChecksumAlgorithm() + " " + m.getChecksumValue() + " (" + m.getChecksumType() + ")"
                        : "-");
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

    @ShellMethod(key = "verify",
            value = "Re-read an object and check chunk/record CRCs, the stored whole-object CRC32C, the client " +
                    "checksum and (non-multipart) MD5/ETag  |  verify --id FILE_ID")
    public String verify(@ShellOption(help = "File ID") String id) {
        ManifestEntity m = fileService.getActiveManifest(id);
        ObjectVerificationService.Result r = verifier.verify(m);
        StringBuilder sb = new StringBuilder();
        sb.append(r.status()).append(": ").append(r.name()).append("  (id=").append(r.manifestId()).append(')');
        for (String d : r.details()) sb.append(System.lineSeparator()).append("  ").append(d);
        return sb.toString();
    }

    @ShellMethod(key = "verify-all",
            value = "Verify every live object (optionally only one pool) and print a summary  |  " +
                    "verify-all [--pool NAME]")
    public String verifyAll(
            @ShellOption(defaultValue = ShellOption.NULL, help = "Only objects of this pool / bucket") String pool) {
        Predicate<ManifestEntity> filter = m -> true;
        if (pool != null) {
            PoolEntity poolEntity = poolService.getByName(pool);
            filter = m -> poolEntity.getId().equals(m.getPoolId())
                    || (m.getPhysicalBlob() != null && poolEntity.getId().equals(m.getPhysicalBlob().getPoolId()));
        }
        ObjectVerificationService.Summary s = verifier.verifyAll(filter);
        StringBuilder sb = new StringBuilder();
        for (ObjectVerificationService.Result r : s.problems()) {
            sb.append(r.status()).append(": ").append(r.name()).append("  (id=").append(r.manifestId()).append(')')
              .append(System.lineSeparator());
            for (String d : r.details()) sb.append("    ").append(d).append(System.lineSeparator());
        }
        sb.append(String.format("Verified %d object(s): ok=%d corrupt=%d missing=%d%n",
                s.total(), s.ok(), s.corrupt(), s.missing()));
        sb.append(s.allOk()
                ? "RESULT: OK"
                : "RESULT: FAILED (corrupt=" + s.corrupt() + ", missing=" + s.missing() + ")");
        return sb.toString();
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
