package org.example.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Metadata record describing how a stored file is split across chunk slots. */
public class ManifestEntity {

    private String id;
    private String blobFileId;       // stored in JSON
    private String sourceFileName;
    private int chunkSize;
    private int totalChunks;
    private long totalBytes;
    /** Comma-separated hex-encoded 64-bit chunk keys, e.g. "deadbeef00000001,..." */
    private String chunkKeys;
    private int lastChunkSize;
    private Instant createdAt;
    private boolean deleted = false;

    // S3 API fields (nullable for backward compat with existing manifests)
    private String objectKey;     // S3 object key, e.g. "folder/image.jpg"
    private String bucketName;    // S3 bucket name = pool name
    private String contentType;   // MIME type, e.g. "image/jpeg"
    private String etag;          // MD5 hex in quotes, e.g. "\"abc123...\""

    // S3 ACL and metadata fields
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String acl;           // Canned ACL (null = inherit from bucket or private)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> userMetadata;  // User-defined metadata headers (x-amz-meta-*)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cacheControl;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String contentDisposition;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String contentEncoding;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String contentLanguage;

    /** Resolved reference — not stored in JSON, populated by the repository. */
    @JsonIgnore
    private BlobFileEntity blobFile;

    public ManifestEntity() {}

    // ── Chunk-key helpers ────────────────────────────────────────────────────

    public List<Long> parseChunkKeys() {
        if (chunkKeys == null || chunkKeys.isBlank()) return List.of();
        return Arrays.stream(chunkKeys.split(","))
                .map(h -> Long.parseUnsignedLong(h.strip(), 16))
                .toList();
    }

    public static String encodeChunkKeys(List<Long> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("%016x", keys.get(i)));
        }
        return sb.toString();
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBlobFileId() { return blobFileId; }
    public void setBlobFileId(String blobFileId) { this.blobFileId = blobFileId; }

    public BlobFileEntity getBlobFile() { return blobFile; }
    public void setBlobFile(BlobFileEntity blobFile) {
        this.blobFile = blobFile;
        if (blobFile != null) this.blobFileId = blobFile.getId();
    }

    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String s) { this.sourceFileName = s; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public String getChunkKeys() { return chunkKeys; }
    public void setChunkKeys(String chunkKeys) { this.chunkKeys = chunkKeys; }

    public int getLastChunkSize() { return lastChunkSize; }
    public void setLastChunkSize(int lastChunkSize) { this.lastChunkSize = lastChunkSize; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getAcl() { return acl; }
    public void setAcl(String acl) { this.acl = acl; }

    public Map<String, String> getUserMetadata() { return userMetadata; }
    public void setUserMetadata(Map<String, String> userMetadata) { this.userMetadata = userMetadata; }

    public String getCacheControl() { return cacheControl; }
    public void setCacheControl(String cacheControl) { this.cacheControl = cacheControl; }

    public String getContentDisposition() { return contentDisposition; }
    public void setContentDisposition(String contentDisposition) { this.contentDisposition = contentDisposition; }

    public String getContentEncoding() { return contentEncoding; }
    public void setContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; }

    public String getContentLanguage() { return contentLanguage; }
    public void setContentLanguage(String contentLanguage) { this.contentLanguage = contentLanguage; }
}
