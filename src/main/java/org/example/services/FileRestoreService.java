package org.example.services;

import org.example.entities.FileManifest;

import java.io.IOException;
import java.nio.file.Path;

public interface FileRestoreService {
    void restoreFile(FileManifest manifest, Path outputPath) throws IOException;
}
