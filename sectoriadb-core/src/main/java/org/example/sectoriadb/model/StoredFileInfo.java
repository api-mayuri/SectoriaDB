package org.example.sectoriadb.model;

public record StoredFileInfo(
        String id,
        String fileName,
        int totalChunks,
        long totalBytes
) {}
