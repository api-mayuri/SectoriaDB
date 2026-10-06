package org.example.sectoriadb.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.PoolEntity;
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
public class JsonPoolRepository implements PoolRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonPoolRepository.class);
    private static final TypeReference<List<PoolEntity>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final Path dataFile;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonPoolRepository(ObjectMapper mapper, StorageProperties props) {
        this.mapper   = mapper;
        this.dataFile = Path.of(props.getMetaDir(), "pools.json");
    }

    @Override
    public PoolEntity save(PoolEntity entity) {
        lock.writeLock().lock();
        try {
            List<PoolEntity> all = readAll();
            all.removeIf(e -> e.getId().equals(entity.getId()));
            all.add(entity);
            writeAll(all);
            log.debug("Saved pool: id={}", entity.getId());
            return entity;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<PoolEntity> findById(String id) {
        lock.readLock().lock();
        try {
            return readAll().stream().filter(e -> e.getId().equals(id)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<PoolEntity> findAll() {
        lock.readLock().lock();
        try {
            return readAll();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void delete(PoolEntity entity) {
        lock.writeLock().lock();
        try {
            List<PoolEntity> all = readAll();
            all.removeIf(e -> e.getId().equals(entity.getId()));
            writeAll(all);
            log.debug("Deleted pool: id={}", entity.getId());
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
            return readAll().size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<PoolEntity> findByName(String name) {
        lock.readLock().lock();
        try {
            return readAll().stream().filter(e -> e.getName().equals(name)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean existsByName(String name) {
        return findByName(name).isPresent();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<PoolEntity> readAll() {
        if (!Files.exists(dataFile)) return new ArrayList<>();
        try {
            return mapper.readValue(dataFile.toFile(), LIST_TYPE);
        } catch (IOException e) {
            log.error("Failed to read pools.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    private void writeAll(List<PoolEntity> entities) {
        try {
            Files.createDirectories(dataFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), entities);
        } catch (IOException e) {
            log.error("Failed to write pools.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
}
