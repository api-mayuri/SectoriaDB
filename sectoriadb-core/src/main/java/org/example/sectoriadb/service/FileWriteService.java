package org.example.sectoriadb.service;

import org.example.sectoriadb.model.FileManifest;

import java.io.IOException;
import java.nio.file.Path;

public interface FileWriteService {
    FileManifest writeFile(Path filePath) throws IOException;
}
