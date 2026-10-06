package org.example.sectoriadb.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.ManifestEntity;
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
import java.util.stream.Stream;

@Repository
public class JsonManifestRepository implements ManifestRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonManifestRepository.class);

    private final ObjectMapper mapper;
    private final BlobFileRepository blobRepo;
    private final Path manifestDir;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonManifestRepository(ObjectMapper mapper, BlobFileRepository blobRepo, StorageProperties props) {
        this.mapper      = mapper;
        this.blobRepo    = blobRepo;
        this.manifestDir = Path.of(props.getMetaDir(), "manifests");
    }

    @Override
    public ManifestEntity save(ManifestEntity entity) {
        lock.writeLock().lock();
        try {
            ensureDir();
            Path file = manifestFile(entity.getId());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entity);
            log.debug("Saved manifest: id={}", entity.getId());
            return entity;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<ManifestEntity> saveAll(List<ManifestEntity> entities) {
        entities.forEach(this::save);
        return entities;
    }

    @Override
    public Optional<ManifestEntity> findById(String id) {
        lock.readLock().lock();
        try {
            Path file = manifestFile(id);
            if (!Files.exists(file)) return Optional.empty();
            ManifestEntity e = mapper.readValue(file.toFile(), ManifestEntity.class);
            resolveRef(e);
            return Optional.of(e);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<ManifestEntity> findAll() {
        lock.readLock().lock();
        try {
            return loadAll();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void delete(ManifestEntity entity) {
        lock.writeLock().lock();
        try {
            Files.deleteIfExists(manifestFile(entity.getId()));
            log.debug("Deleted manifest file: id={}", entity.getId());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean existsById(String id) {
        return Files.exists(manifestFile(id));
    }

    @Override
    public long count() {
        lock.readLock().lock();
        try {
            if (!Files.exists(manifestDir)) return 0;
            try (Stream<Path> s = Files.list(manifestDir)) {
                return s.filter(p -> p.toString().endsWith(".json")).count();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<ManifestEntity> findByDeletedFalse() {
        return findAll().stream().filter(e -> !e.isDeleted()).toList();
    }

    @Override
    public List<ManifestEntity> findByBlobFileIdAndDeletedFalse(String blobFileId) {
        return findAll().stream()
                .filter(e -> refersTo(e, blobFileId) && !e.isDeleted())
                .toList();
    }

    @Override
    public List<ManifestEntity> findByBlobFileId(String blobFileId) {
        return findAll().stream()
                .filter(e -> refersTo(e, blobFileId))
                .toList();
    }

    @Override
    public long countByBlobFileIdAndDeletedFalse(String blobFileId) {
        return findAll().stream()
                .filter(e -> refersTo(e, blobFileId) && !e.isDeleted())
                .count();
    }

    @Override
    public Optional<ManifestEntity> findByBucketNameAndObjectKeyAndDeletedFalse(String bucketName, String objectKey) {
        return findAll().stream()
                .filter(e -> bucketName.equals(e.getBucketName())
                        && objectKey.equals(e.getObjectKey())
                        && !e.isDeleted())
                .findFirst();
    }

    @Override
    public List<ManifestEntity> findByBucketNameAndDeletedFalse(String bucketName) {
        return findAll().stream()
                .filter(e -> bucketName.equals(e.getBucketName()) && !e.isDeleted())
                .toList();
    }

    @Override
    public List<ManifestEntity> findByBucketName(String bucketName) {
        return findAll().stream()
                .filter(e -> bucketName.equals(e.getBucketName()))
                .toList();
    }

    @Override
    public boolean existsByBucketNameAndObjectKeyAndDeletedFalse(String bucketName, String objectKey) {
        return findAll().stream()
                .anyMatch(e -> bucketName.equals(e.getBucketName())
                        && objectKey.equals(e.getObjectKey())
                        && !e.isDeleted());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Path manifestFile(String id) {
        return manifestDir.resolve(id + ".json");
    }

    private void ensureDir() throws IOException {
        Files.createDirectories(manifestDir);
    }

    private List<ManifestEntity> loadAll() {
        if (!Files.exists(manifestDir)) return new ArrayList<>();
        try (Stream<Path> stream = Files.list(manifestDir)) {
            List<ManifestEntity> result = new ArrayList<>();
            for (Path p : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    ManifestEntity e = mapper.readValue(p.toFile(), ManifestEntity.class);
                    resolveRef(e);
                    result.add(e);
                } catch (IOException ex) {
                    log.warn("Skipping corrupt manifest file {}: {}", p, ex.getMessage());
                }
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True if the manifest keeps bytes in this blob (as chunks of a cuckoo blob or as a small-object record). */
    private static boolean refersTo(ManifestEntity e, String blobId) {
        return blobId.equals(e.getBlobFileId()) || blobId.equals(e.getSmallBlobId());
    }

    private void resolveRef(ManifestEntity e) {
        if (e.getBlobFileId() != null) {
            blobRepo.findById(e.getBlobFileId()).ifPresent(e::setBlobFile);
        }
        if (e.getSmallBlobId() != null) {
            blobRepo.findById(e.getSmallBlobId()).ifPresent(e::setSmallBlob);
        }
    }
}
