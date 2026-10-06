package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PoolService {

    private static final Logger log = LoggerFactory.getLogger(PoolService.class);

    private final PoolRepository poolRepo;
    private final BlobFileRepository blobRepo;
    private final ManifestRepository manifestRepo;
    private final OperationLogService opLog;
    private final HashTableCache cache;
    private final SmallBlobCache smallCache;
    private final StorageProperties props;

    public PoolService(PoolRepository poolRepo, BlobFileRepository blobRepo,
                       ManifestRepository manifestRepo,
                       OperationLogService opLog, StorageProperties props,
                       HashTableCache cache, SmallBlobCache smallCache) {
        this.poolRepo     = poolRepo;
        this.blobRepo     = blobRepo;
        this.manifestRepo = manifestRepo;
        this.opLog        = opLog;
        this.props        = props;
        this.cache        = cache;
        this.smallCache   = smallCache;
    }

    public PoolEntity create(String name, String basePath) throws IOException {
        long t0 = System.currentTimeMillis();
        log.info("Creating pool: name={} path={}", name, basePath);

        if (poolRepo.existsByName(name)) {
            throw new IllegalArgumentException("Pool already exists: " + name);
        }
        Path dir = Path.of(basePath).toAbsolutePath();
        Files.createDirectories(dir);

        PoolEntity pool = new PoolEntity(UUID.randomUUID().toString(), name, dir.toString(), Instant.now());
        poolRepo.save(pool);

        opLog.success("POOL_CREATE", pool.getId(), name,
                Map.of("name", name, "basePath", dir.toString()),
                System.currentTimeMillis() - t0);
        log.info("Pool created: id={} name={} path={}", pool.getId(), name, dir);
        return pool;
    }

    public List<PoolEntity> listAll() {
        return poolRepo.findAll();
    }

    public PoolEntity getByName(String name) {
        return poolRepo.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Pool not found: " + name));
    }

    public PoolEntity getById(String id) {
        return poolRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pool not found: " + id));
    }

    /** Creates a bucket (pool) with an auto path under sectoriadb.data-dir. Idempotent. */
    public PoolEntity createBucket(String bucketName) throws IOException {
        if (poolRepo.existsByName(bucketName)) {
            return getByName(bucketName);
        }
        String basePath = Path.of(props.getDataDir()).resolve(bucketName).toString();
        return create(bucketName, basePath);
    }

    public boolean existsByName(String name) {
        return poolRepo.existsByName(name);
    }

    public void setAcl(String bucket, String acl) {
        PoolEntity pool = getByName(bucket);
        pool.setAcl(acl);
        poolRepo.save(pool);
    }

    public void setPolicy(String bucket, String policyJson) {
        PoolEntity pool = getByName(bucket);
        pool.setPolicy(policyJson);
        poolRepo.save(pool);
    }

    public void deletePolicy(String bucket) {
        PoolEntity pool = getByName(bucket);
        pool.setPolicy(null);
        poolRepo.save(pool);
    }

    public void delete(String name) {
        long t0 = System.currentTimeMillis();
        PoolEntity pool = getByName(name);
        long blobCount = blobRepo.countByPoolId(pool.getId());
        if (blobCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete pool '" + name + "' — it still has " + blobCount +
                    " blob file(s). Delete them first with 'blob-delete'.");
        }
        poolRepo.delete(pool);
        opLog.success("POOL_DELETE", pool.getId(), name, Map.of("name", name),
                System.currentTimeMillis() - t0);
        log.info("Pool deleted: id={} name={}", pool.getId(), name);
    }

    /** S3 DeleteBucket: checks active objects, purges orphaned blobs, then removes the pool. */
    public void deleteBucket(String bucketName) throws IOException {
        long t0 = System.currentTimeMillis();
        PoolEntity pool = getByName(bucketName);

        // Reject if any active (non-deleted) S3 objects remain
        List<ManifestEntity> activeManifests = manifestRepo.findByBucketNameAndDeletedFalse(bucketName);
        if (!activeManifests.isEmpty()) {
            throw new IllegalStateException("BucketNotEmpty: bucket '" + bucketName + "' still has " +
                    activeManifests.size() + " object(s).");
        }

        // Purge all manifests for this bucket (including soft-deleted) and every blob file of the pool
        // (cuckoo .raw and small-object .sob alike; EMPTY/SMALL manifests reference no cuckoo blob)
        for (BlobFileEntity blob : blobRepo.findByPoolId(pool.getId())) {
            cache.evict(blob.getId());
            smallCache.evict(blob.getId());
            Files.deleteIfExists(Path.of(blob.getFilePath()));
            blobRepo.delete(blob);
        }
        for (ManifestEntity m : manifestRepo.findByBucketName(bucketName)) {
            manifestRepo.delete(m);
        }

        // Delete the pool directory if empty
        Path poolDir = Path.of(pool.getBasePath());
        try (var stream = Files.list(poolDir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(poolDir);
            }
        } catch (IOException ignored) {}

        poolRepo.delete(pool);
        opLog.success("BUCKET_DELETE", pool.getId(), bucketName, Map.of("name", bucketName),
                System.currentTimeMillis() - t0);
        log.info("Bucket deleted: id={} name={}", pool.getId(), bucketName);
    }
}
