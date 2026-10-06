package org.example.sectoriadb.repository;

import org.example.sectoriadb.model.ManifestEntity;

import java.util.List;
import java.util.Optional;

public interface ManifestRepository {
    ManifestEntity save(ManifestEntity entity);
    List<ManifestEntity> saveAll(List<ManifestEntity> entities);
    Optional<ManifestEntity> findById(String id);
    List<ManifestEntity> findAll();
    void delete(ManifestEntity entity);
    boolean existsById(String id);
    long count();

    List<ManifestEntity> findByDeletedFalse();
    List<ManifestEntity> findByBlobFileIdAndDeletedFalse(String blobFileId);
    List<ManifestEntity> findByBlobFileId(String blobFileId);
    long countByBlobFileIdAndDeletedFalse(String blobFileId);

    // S3 API queries
    Optional<ManifestEntity> findByBucketNameAndObjectKeyAndDeletedFalse(String bucketName, String objectKey);
    List<ManifestEntity> findByBucketNameAndDeletedFalse(String bucketName);
    List<ManifestEntity> findByBucketName(String bucketName);
    boolean existsByBucketNameAndObjectKeyAndDeletedFalse(String bucketName, String objectKey);
}
