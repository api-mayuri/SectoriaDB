package org.example.service;

import org.example.model.FileManifest;

import java.io.IOException;
import java.nio.file.Path;

public interface FileWriteService {
    FileManifest writeFile(Path filePath) throws IOException;
}
