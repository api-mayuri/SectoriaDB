package org.example.service;

import org.example.model.FileManifest;

import java.io.IOException;
import java.nio.file.Path;

public interface FileRestoreService {
    void restoreFile(FileManifest manifest, Path outputPath) throws IOException;
}
