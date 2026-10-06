package org.example.sectoriadb.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Repository
public class JsonBlobFileRepository implements BlobFileRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonBlobFileRepository.class);
    private static final TypeReference<List<BlobFileEntity>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final PoolRepository poolRepo;
    private final Path dataFile;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonBlobFileRepository(ObjectMapper mapper, PoolRepository poolRepo, StorageProperties props) {
        this.mapper   = mapper;
        this.poolRepo = poolRepo;
        this.dataFile = Path.of(props.getMetaDir(), "blobs.json");
    }

    @Override
    public BlobFileEntity save(BlobFileEntity entity) {
        lock.writeLock().lock();
        try {
            List<BlobFileEntity> all = readRaw();
            all.removeIf(e -> e.getId().equals(entity.getId()));
            all.add(entity);
            writeAll(all);
            log.debug("Saved blob: id={}", entity.getId());
            return entity;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<BlobFileEntity> findById(String id) {
        lock.readLock().lock();
        try {
            return readAll().stream().filter(e -> e.getId().equals(id)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<BlobFileEntity> findAll() {
        lock.readLock().lock();
        try {
            return readAll();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void delete(BlobFileEntity entity) {
        lock.writeLock().lock();
        try {
            List<BlobFileEntity> all = readRaw();
            all.removeIf(e -> e.getId().equals(entity.getId()));
            writeAll(all);
            log.debug("Deleted blob: id={}", entity.getId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean existsById(String id) {
        return findById(id).isPresent();
    }

    @Override
    public long count() {
        lock.readLock().lock();
        try {
            return readRaw().size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<BlobFileEntity> findByPoolId(String poolId) {
        lock.readLock().lock();
        try {
            return readAll().stream().filter(e -> poolId.equals(e.getPoolId())).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long countByPoolId(String poolId) {
        lock.readLock().lock();
        try {
            return readRaw().stream().filter(e -> poolId.equals(e.getPoolId())).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Read raw entities without resolving pool refs. */
    private List<BlobFileEntity> readRaw() {
        if (!Files.exists(dataFile)) return new ArrayList<>();
        try {
            return new ArrayList<>(mapper.readValue(dataFile.toFile(), LIST_TYPE));
        } catch (IOException e) {
            log.error("Failed to read blobs.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** Read entities and eagerly resolve pool references. */
    private List<BlobFileEntity> readAll() {
        List<BlobFileEntity> entities = readRaw();
        for (BlobFileEntity e : entities) {
            if (e.getPoolId() != null) {
                poolRepo.findById(e.getPoolId()).ifPresent(e::setPool);
            }
        }
        return entities;
    }

    private void writeAll(List<BlobFileEntity> entities) {
        try {
            Files.createDirectories(dataFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), entities);
        } catch (IOException e) {
            log.error("Failed to write blobs.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
}
