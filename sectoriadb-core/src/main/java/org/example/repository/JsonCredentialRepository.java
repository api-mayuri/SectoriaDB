package org.example.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.StorageProperties;
import org.example.model.CredentialEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Repository
public class JsonCredentialRepository implements CredentialRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonCredentialRepository.class);
    private static final TypeReference<List<CredentialEntity>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final Path dataFile;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private List<CredentialEntity> cache = null;
    private long cachedModifiedTime = -1;
    private long cachedFileSize = -1;

    public JsonCredentialRepository(ObjectMapper mapper, StorageProperties props) {
        this.mapper   = mapper;
        this.dataFile = Path.of(props.getMetaDir(), "credentials.json");
    }

    @Override
    public CredentialEntity save(CredentialEntity entity) {
        lock.writeLock().lock();
        try {
            List<CredentialEntity> all = readAll();
            all.removeIf(e -> e.getAccessKeyId().equals(entity.getAccessKeyId()));
            all.add(entity);
            writeAll(all);
            invalidateCache();
            log.debug("Saved credential: id={}", entity.getAccessKeyId());
            return entity;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CredentialEntity> findByAccessKeyId(String accessKeyId) {
        lock.readLock().lock();
        try {
            return readAll().stream()
                    .filter(e -> e.getAccessKeyId().equals(accessKeyId))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CredentialEntity> findAll() {
        lock.readLock().lock();
        try {
            return readAll();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteByAccessKeyId(String accessKeyId) {
        lock.writeLock().lock();
        try {
            List<CredentialEntity> all = readAll();
            all.removeIf(e -> e.getAccessKeyId().equals(accessKeyId));
            writeAll(all);
            invalidateCache();
            log.debug("Deleted credential: id={}", accessKeyId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean existsByAccessKeyId(String accessKeyId) {
        return findByAccessKeyId(accessKeyId).isPresent();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<CredentialEntity> readAll() {
        if (!Files.exists(dataFile)) {
            if (cache == null) cache = new ArrayList<>();
            return cache;
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(dataFile, BasicFileAttributes.class);
            long modTime = attrs.lastModifiedTime().toMillis();
            long fileSize = attrs.size();

            if (cache != null && modTime == cachedModifiedTime && fileSize == cachedFileSize) {
                return cache;
            }

            cache = mapper.readValue(dataFile.toFile(), LIST_TYPE);
            cachedModifiedTime = modTime;
            cachedFileSize = fileSize;
            return cache;
        } catch (IOException e) {
            log.error("Failed to read credentials.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    private void writeAll(List<CredentialEntity> entities) {
        try {
            Files.createDirectories(dataFile.getParent());
            Path tempFile = Files.createTempFile(dataFile.getParent(), "credentials", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), entities);
                Files.move(tempFile, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }
        } catch (IOException e) {
            log.error("Failed to write credentials.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    private void invalidateCache() {
        cache = null;
        cachedModifiedTime = -1;
        cachedFileSize = -1;
    }
}
