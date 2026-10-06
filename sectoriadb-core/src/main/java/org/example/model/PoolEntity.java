package org.example.model;

import java.time.Instant;

/** Logical storage pool — a named directory that contains blob files. */
public class PoolEntity {

    private String id;
    private String name;
    private String basePath;
    private Instant createdAt;
    private String acl = "private";  // Canned ACL: private, public-read, public-read-write, authenticated-read
    private String policy;            // Bucket policy as JSON string, or null

    public PoolEntity() {}

    public PoolEntity(String id, String name, String basePath, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.basePath = basePath;
        this.createdAt = createdAt;
        this.acl = "private";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getAcl() { return acl != null ? acl : "private"; }
    public void setAcl(String acl) { this.acl = acl != null ? acl : "private"; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
}
