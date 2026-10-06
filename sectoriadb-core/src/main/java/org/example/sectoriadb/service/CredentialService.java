package org.example.sectoriadb.service;

import org.example.sectoriadb.model.CredentialEntity;
import org.example.sectoriadb.repository.CredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern ACCESS_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9]{16,128}$");

    private final CredentialRepository credentialRepo;

    public CredentialService(CredentialRepository credentialRepo) {
        this.credentialRepo = credentialRepo;
    }

    /** Generates a new access key + secret key pair. */
    public CredentialEntity create(String description) {
        String accessKeyId = generateAccessKeyId();
        String secretKey   = generateSecretKey();
        CredentialEntity entity = new CredentialEntity(accessKeyId, secretKey, description, Instant.now());
        credentialRepo.save(entity);
        log.info("Created credential: accessKeyId={} desc={}", accessKeyId, description);
        return entity;
    }

    /** Creates multiple credentials with auto-generated keys. */
    public List<CredentialEntity> createMany(int count, String description) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("Count must be between 1 and 100");
        }
        List<CredentialEntity> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            created.add(create(description));
        }
        return created;
    }

    /** Registers a credential with provided keys (with validation). */
    public CredentialEntity register(String accessKeyId, String secretKey, String description) {
        if (!ACCESS_KEY_PATTERN.matcher(accessKeyId).matches()) {
            throw new IllegalArgumentException("Access Key ID must be 16-128 alphanumeric characters");
        }
        if (secretKey == null || secretKey.length() < 16) {
            throw new IllegalArgumentException("Secret Key must be at least 16 characters");
        }
        if (credentialRepo.existsByAccessKeyId(accessKeyId)) {
            throw new IllegalArgumentException("Access Key ID already exists: " + accessKeyId);
        }
        CredentialEntity entity = new CredentialEntity(accessKeyId, secretKey, description, Instant.now());
        credentialRepo.save(entity);
        log.info("Registered credential: accessKeyId={} desc={}", accessKeyId, description);
        return entity;
    }

    public Optional<CredentialEntity> findByAccessKeyId(String accessKeyId) {
        return credentialRepo.findByAccessKeyId(accessKeyId);
    }

    public List<CredentialEntity> listAll() {
        return credentialRepo.findAll();
    }

    /** Enable or disable a credential. */
    public void setEnabled(String accessKeyId, boolean enabled) {
        CredentialEntity entity = credentialRepo.findByAccessKeyId(accessKeyId)
                .orElseThrow(() -> new IllegalArgumentException("Access key not found: " + accessKeyId));
        entity.setEnabled(enabled);
        credentialRepo.save(entity);
        log.info("Set credential enabled={}: accessKeyId={}", enabled, accessKeyId);
    }

    /** Creates or updates a credential with the given fixed access/secret key pair. */
    public void ensureCredential(String accessKeyId, String secretKey) {
        credentialRepo.findByAccessKeyId(accessKeyId).ifPresentOrElse(existing -> {
            existing.setSecretKey(secretKey);
            credentialRepo.save(existing);
            log.info("Updated bootstrap credential: accessKeyId={}", accessKeyId);
        }, () -> {
            credentialRepo.save(new CredentialEntity(accessKeyId, secretKey, "bootstrap", Instant.now()));
            log.info("Registered bootstrap credential: accessKeyId={}", accessKeyId);
        });
    }

    public void delete(String accessKeyId) {
        if (!credentialRepo.existsByAccessKeyId(accessKeyId)) {
            throw new IllegalArgumentException("Access key not found: " + accessKeyId);
        }
        credentialRepo.deleteByAccessKeyId(accessKeyId);
        log.info("Deleted credential: accessKeyId={}", accessKeyId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String generateAccessKeyId() {
        byte[] bytes = new byte[10];
        RNG.nextBytes(bytes);
        return "SCTRIA" + Base64.getEncoder().withoutPadding()
                .encodeToString(bytes).replaceAll("[+/=]", "X").toUpperCase();
    }

    private static String generateSecretKey() {
        byte[] bytes = new byte[30];
        RNG.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
}
