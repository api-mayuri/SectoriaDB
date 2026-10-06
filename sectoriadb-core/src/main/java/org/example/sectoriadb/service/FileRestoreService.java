package org.example.sectoriadb.service;

import org.example.sectoriadb.model.FileManifest;

import java.io.IOException;
import java.nio.file.Path;

public interface FileRestoreService {
    void restoreFile(FileManifest manifest, Path outputPath) throws IOException;
}
