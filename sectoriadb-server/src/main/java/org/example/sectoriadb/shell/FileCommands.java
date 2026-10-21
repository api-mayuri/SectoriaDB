package org.example.sectoriadb.shell;

import org.example.sectoriadb.catalog.ChunkRepository;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.storage.ObjectCatalog;
import org.example.sectoriadb.storage.ObjectReader;
import org.example.sectoriadb.storage.ObjectVerificationService;
import org.example.sectoriadb.storage.ObjectWriter;
import org.example.sectoriadb.storage.PoolService;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

@ShellComponent
public class FileCommands {

    private final ObjectWriter writer;
    private final ObjectReader reader;
    private final ObjectCatalog catalog;
    private final PoolService poolService;
    private final ObjectVerificationService verifier;
    private final ChunkRepository chunkRepo;

    public FileCommands(ObjectWriter writer, ObjectReader reader, ObjectCatalog catalog, PoolService poolService, ObjectVerificationService verifier,
                        ChunkRepository chunkRepo) {
        this.chunkRepo = chunkRepo;
        this.writer = writer;

        this.reader = reader;

        this.catalog = catalog;
        this.poolService = poolService;
        this.verifier = verifier;
    }

    @ShellMethod(key = "store", value = "Store a file into a pool  |  store --file PATH --pool NAME")
    public String store(
            @ShellOption(help = "Path to the source file") String file,
            @ShellOption(help = "Target pool name") String pool) throws IOException {
        PoolEntity poolEntity = poolService.getByName(pool);
        ManifestEntity m = writer.store(Path.of(file), poolEntity);
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
            manifests = catalog.listAll().stream()
                    .filter(m -> poolEntity.getId().equals(m.getPoolId())
                            || (m.getPhysicalBlob() != null
                                && poolEntity.getId().equals(m.getPhysicalBlob().getPoolId())))
                    .toList();
        } else {
            manifests = catalog.listAll();
        }
        if (manifests.isEmpty()) return "No files stored yet. Use 'store --file PATH --pool NAME'.";

        StringBuilder sb = new StringBuilder();
        sb.append(ShellTable.fileTableHeader());
        for (ManifestEntity m : manifests) sb.append(ShellTable.fileTableRow(m));
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "info", value = "Show detailed info about a stored file  |  info --id FILE_ID")
    public String info(@ShellOption(help = "File ID") String id) {
        ManifestEntity m = catalog.getActiveManifest(id);
        var phys = m.getPhysicalBlob();
        String poolName = m.getPoolId() == null ? "?"
                : poolService.listAll().stream().filter(p -> p.getId().equals(m.getPoolId()))
                        .map(PoolEntity::getName).findFirst().orElse(m.getPoolId());
        String blobs = phys != null ? phys.getId() : "-";
        if ((m.getStorageKind() == StorageKind.CHUNKED
                || m.getStorageKind() == StorageKind.MULTIPART) && m.getPoolId() != null) {
            // the chunks of an object are spread over the pool's blobs: the chunk index says where
            var entries = chunkRepo.findAll(m.getPoolId(), m.chunkKeyArray());
            var distinct = new TreeSet<String>();
            entries.values().forEach(e -> distinct.add(e.blobId()));
            blobs = distinct.size() + " blob(s) via the chunk index" + (distinct.isEmpty() ? "" : ": " + String.join(", ", distinct));
        }
        String stored = switch (m.getStorageKind()) {
            case EMPTY -> "empty object (no blob)";
            case SMALL -> String.format("1 record in small blob at offset %d (%d B)",
                    m.getSmallOffset(), m.getSmallLength());
            case CHUNKED -> String.format("%d × %s  (last chunk: %s)", m.getTotalChunks(),
                    ShellTable.humanSize(m.getChunkSize()), ShellTable.humanSize(m.getLastChunkSize()));
            case MULTIPART -> String.format("%d part(s), %d chunk(s) and %d small record(s) in object order",
                    m.getSegments().size(), m.chunkKeyArray().length,
                    m.getSegments().stream().filter(sg -> sg.kind() == StorageKind.SMALL).count());
        };
        return String.format(
                "File: %s%n" +
                "  ID:          %s%n" +
                "  Kind:        %s%n" +
                "  Blobs:       %s%n" +
                "  Pool:        %s%n" +
                "  Size:        %s%n" +
                "  Stored as:   %s%n" +
                "  Stored at:   %s%n" +
                "  CRC32C:      %s%n" +
                "  Checksum:    %s",
                m.getSourceFileName(), m.getId(), m.getStorageKind(),
                blobs, poolName,
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

        ManifestEntity m = catalog.getActiveManifest(id);

        if (from != null || len != null) {
            if (from == null || len == null) {
                return "Range restore requires both --from and --len. " +
                       "Example: get --id ID --out PATH --from 1048576 --len 524288";
            }
            System.out.printf("Extracting [%d, +%d] from '%s' (%s) → %s%n",
                    from, len, m.getSourceFileName(),
                    ShellTable.humanSize(m.getTotalBytes()), out);
            reader.restoreRange(id, Path.of(out), from, len);
            return String.format("Extracted %s at byte %d → %s",
                    ShellTable.humanSize(len), from, Path.of(out).toAbsolutePath());
        } else {
            System.out.printf("Restoring '%s' (%s) → %s%n",
                    m.getSourceFileName(), ShellTable.humanSize(m.getTotalBytes()), out);
            reader.restore(id, Path.of(out));
            return "Restored to: " + Path.of(out).toAbsolutePath();
        }
    }

    @ShellMethod(key = "verify",
            value = "Re-read an object and check chunk/record CRCs, the stored whole-object CRC32C, the client " +
                    "checksum and (non-multipart) MD5/ETag  |  verify --id FILE_ID")
    public String verify(@ShellOption(help = "File ID") String id) {
        ManifestEntity m = catalog.getActiveManifest(id);
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
        ManifestEntity m = catalog.getActiveManifest(id);
        catalog.delete(id);
        return "Deleted: " + m.getSourceFileName() + "  (id=" + id + ")";
    }
}
