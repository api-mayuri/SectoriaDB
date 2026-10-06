package org.example.repository;

import org.example.entity.PoolEntity;

import java.util.List;
import java.util.Optional;

public interface PoolRepository {
    PoolEntity save(PoolEntity entity);
    Optional<PoolEntity> findById(String id);
    List<PoolEntity> findAll();
    void delete(PoolEntity entity);
    boolean existsById(String id);
    long count();

    Optional<PoolEntity> findByName(String name);
    boolean existsByName(String name);
}
