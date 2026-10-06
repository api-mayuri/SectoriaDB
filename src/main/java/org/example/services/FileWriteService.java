package org.example.services;

import org.example.entities.FileManifest;

import java.io.IOException;
import java.nio.file.Path;

public interface FileWriteService {
    FileManifest writeFile(Path filePath) throws IOException;
}
