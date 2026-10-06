package org.example.sectoriadb.shell;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.impl.SmallObjectBlob;

import java.io.IOException;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.service.impl.CuckooHashTable;

/** Formatting helpers shared across shell command classes. */
class ShellTable {

    static String blobTableHeader() {
        return String.format("  %-36s  %-6s  %-24s  %8s  %8s  %s%n",
                "Blob ID", "Kind", "File", "Buckets", "Fill %", "Size") +
               "  " + "─".repeat(100) + "\n";
    }

    /** One table row for a blob of either kind. */
    static String blobRow(BlobService blobService, BlobFileEntity b) throws IOException {
        if (b.getKind() == BlobKind.SMALL) {
            SmallObjectBlob.Stats s = blobService.getSmallStats(b);
            return String.format("  %-36s  %-6s  %-24s  %8s  %8s  %s  (live %s, dead %s%s)%n",
                    b.getId(), "SMALL", truncate(b.getFileName(), 24), "-", "-",
                    humanSize(s.fileBytes()), humanSize(s.liveBytes()), humanSize(s.deadBytes()),
                    s.writable() ? "" : ", READ-ONLY");
        }
        return blobTableRow(b, blobService.getFillStats(b));
    }

    static String blobTableRow(BlobFileEntity b, CuckooHashTable.FillStats stats) {
        return String.format("  %-36s  %-6s  %-24s  %8d  %6.1f%%  %s%n",
                b.getId(), "CUCKOO", truncate(b.getFileName(), 24),
                b.getNumBuckets(), stats.fillPercent(),
                humanSize(b.getTotalBytes()));
    }

    static String fileTableHeader() {
        return String.format("%-36s  %-40s  %8s  %12s%n", "ID", "File", "Chunks", "Size") +
               "─".repeat(100) + "\n";
    }

    static String kindLabel(ManifestEntity m) {
        return m.getStorageKind() == null ? "CHUNKED" : m.getStorageKind().name();
    }

    static String fileTableRow(ManifestEntity m) {
        return String.format("%-36s  %-40s  %8d  %12s%n",
                m.getId(), truncate(m.getSourceFileName(), 40),
                m.getTotalChunks(), humanSize(m.getTotalBytes()));
    }

    static String humanSize(long bytes) {
        if (bytes < 1_024)               return bytes + " B";
        if (bytes < 1_048_576)           return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824)       return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes < 1_099_511_627_776L)  return String.format("%.2f GB", bytes / 1_073_741_824.0);
        return String.format("%.2f TB", bytes / 1_099_511_627_776.0);
    }

    static String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max - 3) + "...";
    }
}
