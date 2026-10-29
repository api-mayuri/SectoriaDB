package org.example.sectoriadb.security;


import java.util.List;
import java.util.Optional;

public interface CredentialRepository {
    CredentialEntity save(CredentialEntity entity);
    Optional<CredentialEntity> findByAccessKeyId(String accessKeyId);
    List<CredentialEntity> findAll();
    void deleteByAccessKeyId(String accessKeyId);
    boolean existsByAccessKeyId(String accessKeyId);

    /** Whether secrets are stored encrypted. */
    default boolean encrypting() {
        return false;
    }

    /** Re-stores plain-text secrets encrypted when encryption is on; returns how many there were. */
    default int sealPlaintext() {
        return 0;
    }
}
