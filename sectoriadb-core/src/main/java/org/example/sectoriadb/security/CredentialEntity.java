package org.example.sectoriadb.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** An access key / secret key pair for S3 SigV4 authentication. */
public class CredentialEntity {

    private String accessKeyId;
    private String secretKey;
    private String description;
    private Instant createdAt;
    private boolean enabled = true;
    /** Optional IAM identity policy (standard JSON). Absent = legacy full access. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode policy;

    public CredentialEntity() {}

    public CredentialEntity(String accessKeyId, String secretKey,
                            String description, Instant createdAt) {
        this.accessKeyId = accessKeyId;
        this.secretKey   = secretKey;
        this.description = description;
        this.createdAt   = createdAt;
    }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** The IAM identity policy of this key, or null when the key has none (full access). */
    public JsonNode getPolicy() { return policy; }
    public void setPolicy(JsonNode policy) {
        this.policy = policy == null || policy.isNull() || policy.isMissingNode() ? null : policy;
    }
}
