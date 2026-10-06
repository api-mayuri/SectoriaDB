package org.example.repository;

import org.example.model.CredentialEntity;

import java.util.List;
import java.util.Optional;

public interface CredentialRepository {
    CredentialEntity save(CredentialEntity entity);
    Optional<CredentialEntity> findByAccessKeyId(String accessKeyId);
    List<CredentialEntity> findAll();
    void deleteByAccessKeyId(String accessKeyId);
    boolean existsByAccessKeyId(String accessKeyId);
}
