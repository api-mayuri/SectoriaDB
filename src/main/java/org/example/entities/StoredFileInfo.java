package org.example.entities;

public record StoredFileInfo(
        String id,
        String fileName,
        int totalChunks,
        long totalBytes
) {}
