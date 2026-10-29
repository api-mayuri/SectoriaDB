package org.example.sectoriadb.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern ACCESS_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9]{16,128}$");

    private final CredentialRepository credentialRepo;

    public CredentialService(CredentialRepository credentialRepo) {
        this.credentialRepo = credentialRepo;
    }

    /**
     * The identity policy of an administrator key: every S3 action on every resource. A key without any policy has no
     * access at all (deny by default), so full access is always explicit and visible in {@code key-policy --show}.
     */
    public static JsonNode fullAccessPolicy() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode statement = f.objectNode().put("Sid", "FullAccess").put("Effect", "Allow").put("Action", "s3:*").put("Resource", "*");
        ObjectNode policy = f.objectNode().put("Version", "2012-10-17");
        policy.putArray("Statement").add(statement);
        return policy;
    }

    /** Access key ids without an identity policy: they are refused every request. */
    public List<String> keysWithoutPolicy() {
        return credentialRepo.findAll().stream().filter(c -> c.getPolicy() == null).map(CredentialEntity::getAccessKeyId).toList();
    }

    /** Generates a new access key + secret key pair. */
    public CredentialEntity create(String description) {
        String accessKeyId = generateAccessKeyId();
        String secretKey   = generateSecretKey();
        CredentialEntity entity = new CredentialEntity(accessKeyId, secretKey, description, Instant.now());
        credentialRepo.save(entity);
        log.info("Created credential: accessKeyId={}", accessKeyId);
        log.debug("Created credential {} description: {}", accessKeyId, description);
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
        validate(accessKeyId, secretKey);
        if (credentialRepo.existsByAccessKeyId(accessKeyId)) {
            throw new IllegalArgumentException("Access Key ID already exists: " + accessKeyId);
        }
        CredentialEntity entity = new CredentialEntity(accessKeyId, secretKey, description, Instant.now());
        credentialRepo.save(entity);
        log.info("Registered credential: accessKeyId={}", accessKeyId);
        log.debug("Registered credential {} description: {}", accessKeyId, description);
        return entity;
    }

    /** The rules every credential obeys, whichever way it enters (shell, environment list, legacy pair). */
    static void validate(String accessKeyId, String secretKey) {
        if (accessKeyId == null || !ACCESS_KEY_PATTERN.matcher(accessKeyId).matches()) {
            throw new IllegalArgumentException("Access Key ID must be 16-128 alphanumeric characters");
        }
        if (secretKey == null || secretKey.length() < 16) {
            throw new IllegalArgumentException("Secret Key must be at least 16 characters");
        }
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

    /**
     * Attaches an IAM identity policy to the key (replacing any earlier one); {@code null} removes it, which leaves the
     * key without any access.
     *
     * @return warnings of the validation (the policy is stored anyway)
     * @throws IllegalArgumentException if the key is unknown or the policy is invalid
     */
    public List<String> setPolicy(String accessKeyId, JsonNode policy) {
        CredentialEntity entity = credentialRepo.findByAccessKeyId(accessKeyId)
                .orElseThrow(() -> new IllegalArgumentException("Access key not found: " + accessKeyId));
        List<String> warnings = List.of();
        if (policy != null) {
            IamPolicyValidator.Result result = IamPolicyValidator.validate(policy);
            if (!result.errors().isEmpty()) {
                throw new IllegalArgumentException("Invalid policy: " + String.join("; ", result.errors()));
            }
            warnings = result.warnings();
        }
        entity.setPolicy(policy);
        credentialRepo.save(entity);
        log.info("{} policy of credential: accessKeyId={}", policy == null ? "Removed" : "Set", accessKeyId);
        return warnings;
    }

    /** Creates or updates a credential with the given fixed access/secret key pair. */
    public void ensureCredential(String accessKeyId, String secretKey) {
        validate(accessKeyId, secretKey);
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
