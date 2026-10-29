package org.example.sectoriadb.catalog;

import java.time.Instant;

/** Logical storage pool — a named directory that contains blob files. */
public class PoolEntity {

    private String id;
    private String name;
    private String basePath;
    private Instant createdAt;
    private String acl = "private";  // Canned ACL: private, public-read, public-read-write, authenticated-read
    private String policy;            // Bucket policy as JSON string, or null
    /** null: versioning never enabled; "Enabled" or "Suspended" (it cannot be switched off again, as in S3). */
    private String versioning;
    private java.util.List<LifecycleRule> lifecycle;

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

    public String getVersioning() { return versioning; }
    public void setVersioning(String versioning) { this.versioning = versioning; }

    public java.util.List<LifecycleRule> getLifecycle() { return lifecycle; }
    public void setLifecycle(java.util.List<LifecycleRule> lifecycle) { this.lifecycle = lifecycle; }
}
