package org.example.sectoriadb.shell;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.impl.SmallObjectBlob;

import java.io.IOException;
import java.util.List;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.service.impl.CuckooHashTable;

/** Formatting helpers shared across shell command classes. */
class ShellTable {

    static String blobTableHeader() {
        return String.format("  %-36s  %-6s  %-24s  %8s  %8s  %9s  %7s  %s%n",
                "Blob ID", "Kind", "File", "Buckets", "Fill %", "Chunks", "HRW %", "Size") +
               "  " + "─".repeat(120) + "\n";
    }

    /**
     * The blobs of a pool as a table: for cuckoo blobs the fill, the number of chunks the chunk index holds in the
     * blob and the share of new chunks the weighted rendezvous placement currently gives it (HRW %); for
     * small-object blobs the live / dead bytes.
     */
    static String poolBlobTable(BlobService blobService, ChunkRepository chunks, List<BlobFileEntity> blobs)
            throws IOException {
        java.util.Map<String, Double> weight = new java.util.HashMap<>();
        double sum = 0;
        for (BlobFileEntity b : blobs) {
            if (b.getKind() != BlobKind.CUCKOO) continue;
            double w = blobService.placementWeight(b);
            weight.put(b.getId(), w);
            sum += w;
        }
        StringBuilder sb = new StringBuilder(blobTableHeader());
        for (BlobFileEntity b : blobs) {
            if (b.getKind() == BlobKind.SMALL) {
                SmallObjectBlob.Stats s = blobService.getSmallStats(b);
                sb.append(String.format("  %-36s  %-6s  %-24s  %8s  %8s  %9s  %7s  %s  (live %s, dead %s%s)%n",
                        b.getId(), "SMALL", truncate(b.getFileName(), 24), "-", "-", "-", "-",
                        humanSize(s.fileBytes()), humanSize(s.liveBytes()), humanSize(s.deadBytes()),
                        s.writable() ? "" : ", READ-ONLY"));
                continue;
            }
            CuckooHashTable.FillStats st = blobService.getFillStats(b);
            double share = sum > 0 ? 100.0 * weight.get(b.getId()) / sum : 0;
            sb.append(String.format("  %-36s  %-6s  %-24s  %8d  %6.1f%%  %9d  %6.1f%%  %s%n",
                    b.getId(), "CUCKOO", truncate(b.getFileName(), 24), b.getNumBuckets(), st.fillPercent(),
                    chunks.countByBlob(b.getId()), share, humanSize(b.getTotalBytes())));
        }
        return sb.toString();
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
