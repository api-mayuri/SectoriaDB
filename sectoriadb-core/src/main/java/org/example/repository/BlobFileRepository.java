package org.example.repository;

import org.example.model.BlobFileEntity;

import java.util.List;
import java.util.Optional;

public interface BlobFileRepository {
    BlobFileEntity save(BlobFileEntity entity);
    Optional<BlobFileEntity> findById(String id);
    List<BlobFileEntity> findAll();
    void delete(BlobFileEntity entity);
    boolean existsById(String id);
    long count();

    List<BlobFileEntity> findByPoolId(String poolId);
    long countByPoolId(String poolId);
}
