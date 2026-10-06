package org.example.model;

public record StoredFileInfo(
        String id,
        String fileName,
        int totalChunks,
        long totalBytes
) {}
