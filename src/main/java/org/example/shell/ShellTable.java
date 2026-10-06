package org.example.shell;

import org.example.entity.BlobFileEntity;
import org.example.entity.ManifestEntity;
import org.example.services.impl.CuckooHashTable;

/** Formatting helpers shared across shell command classes. */
class ShellTable {

    static String blobTableHeader() {
        return String.format("  %-36s  %-24s  %8s  %8s  %6s%n",
                "Blob ID", "File", "Buckets", "Fill %", "Size") +
               "  " + "─".repeat(90) + "\n";
    }

    static String blobTableRow(BlobFileEntity b, CuckooHashTable.FillStats stats) {
        return String.format("  %-36s  %-24s  %8d  %6.1f%%  %s%n",
                b.getId(), truncate(b.getFileName(), 24),
                b.getNumBuckets(), stats.fillPercent(),
                humanSize(b.getTotalBytes()));
    }

    static String fileTableHeader() {
        return String.format("%-36s  %-40s  %8s  %12s%n", "ID", "File", "Chunks", "Size") +
               "─".repeat(100) + "\n";
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
