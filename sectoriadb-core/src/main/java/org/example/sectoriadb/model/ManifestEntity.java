package org.example.sectoriadb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Metadata record describing how a stored object is laid out in the blobs, plus its S3 attributes.
 * Persisted by {@code MetaStoreManifestRepository}; see {@code ManifestCodec} for the value encoding.
 */
public class ManifestEntity {

    private String id;
    private String sourceFileName;
    private int chunkSize;
    private int totalChunks;
    private long totalBytes;
    /** 64-bit chunk keys in file order. Stored by the metastore as a compact binary long array, not in the JSON part. */
    @JsonIgnore
    private long[] chunkKeys = new long[0];
    private int lastChunkSize;
    private Instant createdAt;
    /**
     * True once the manifest is no longer the current version of an object (superseded by a newer PUT, deleted).
     * Such a manifest is kept only until the garbage collector releases its chunks; it is never reachable
     * through the {@code objects} index. Live objects are exactly the manifests with {@code deleted == false}.
     */
    private boolean deleted = false;

    /** Absent in old manifests: CHUNKED. */
    private StorageKind storageKind = StorageKind.CHUNKED;
    /** Pool the object lives in: scope of its chunk keys in the chunk index, and the home of EMPTY objects. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String poolId;
    // SMALL objects: one record of a small-object blob
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String smallBlobId;
    private long smallOffset;
    private int smallLength;
    private int smallCrc32c;

    // S3 API fields (nullable for backward compat with existing manifests)
    private String objectKey;     // S3 object key, e.g. "folder/image.jpg"
    private String bucketName;    // S3 bucket name = pool name
    private String contentType;   // MIME type, e.g. "image/jpeg"
    private String etag;          // MD5 hex in quotes, e.g. "\"abc123...\""

    /**
     * CRC32C of the whole object, base64 of the big-endian value (the S3 wire format). Always computed on write;
     * absent in manifests written before whole-object checksums existed (such objects skip that check).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String crc32c;
    /** Additional checksum the client asked for (null when none). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ChecksumAlgorithm checksumAlgorithm;
    /** Base64 value of {@link #checksumAlgorithm}; for COMPOSITE it carries the "-N" part-count suffix. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String checksumValue;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ChecksumType checksumType;

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

    /**
     * Chunks written by the upload that produced this manifest, handed to the commit transaction (which creates or
     * references their index entries). Never stored; empty for manifests read back from the metastore.
     */
    @JsonIgnore
    private List<PlacedChunk> stagedChunks = List.of();

    /**
     * Held from the moment the upload wrote its data until the commit finished (see {@link UploadHold}); transient,
     * never stored. Null for manifests that were loaded from the store.
     */
    @JsonIgnore
    private transient UploadHold uploadHold;

    /** Resolved small-object blob (SMALL manifests only) — not stored in JSON. */
    @JsonIgnore
    private BlobFileEntity smallBlob;

    public ManifestEntity() {}

    /**
     * The small-object blob holding the bytes of a SMALL object, null otherwise. Chunks of CHUNKED objects are not
     * tied to one blob: they are located through the chunk index (pool, chunk key) -> blob.
     */
    @JsonIgnore
    public BlobFileEntity getPhysicalBlob() { return smallBlob; }

    @JsonIgnore
    public UploadHold getUploadHold() { return uploadHold; }

    @JsonIgnore
    public void setUploadHold(UploadHold hold) { this.uploadHold = hold; }

    @JsonIgnore
    public List<PlacedChunk> getStagedChunks() { return stagedChunks; }

    @JsonIgnore
    public void setStagedChunks(List<PlacedChunk> stagedChunks) {
        this.stagedChunks = stagedChunks == null ? List.of() : stagedChunks;
    }

    // ── Chunk-key helpers ────────────────────────────────────────────────────

    /** The chunk keys as a list (a copy). */
    @JsonIgnore
    public List<Long> parseChunkKeys() {
        return Arrays.stream(chunkKeys).boxed().toList();
    }

    /** The chunk keys as an array; the returned array is the entity's own, do not modify it. */
    @JsonIgnore
    public long[] chunkKeyArray() { return chunkKeys; }

    @JsonIgnore
    public void setChunkKeys(List<Long> keys) {
        this.chunkKeys = keys == null ? new long[0] : keys.stream().mapToLong(Long::longValue).toArray();
    }

    @JsonIgnore
    public void setChunkKeyArray(long[] keys) {
        this.chunkKeys = keys == null ? new long[0] : keys;
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public StorageKind getStorageKind() { return storageKind; }
    public void setStorageKind(StorageKind k) { this.storageKind = k != null ? k : StorageKind.CHUNKED; }

    public String getPoolId() { return poolId; }
    public void setPoolId(String poolId) { this.poolId = poolId; }

    public String getSmallBlobId() { return smallBlobId; }
    public void setSmallBlobId(String smallBlobId) { this.smallBlobId = smallBlobId; }

    public BlobFileEntity getSmallBlob() { return smallBlob; }
    public void setSmallBlob(BlobFileEntity smallBlob) {
        this.smallBlob = smallBlob;
        if (smallBlob != null) this.smallBlobId = smallBlob.getId();
    }

    public long getSmallOffset() { return smallOffset; }
    public void setSmallOffset(long smallOffset) { this.smallOffset = smallOffset; }

    public int getSmallLength() { return smallLength; }
    public void setSmallLength(int smallLength) { this.smallLength = smallLength; }

    public int getSmallCrc32c() { return smallCrc32c; }
    public void setSmallCrc32c(int smallCrc32c) { this.smallCrc32c = smallCrc32c; }

    public String getCrc32c() { return crc32c; }
    public void setCrc32c(String crc32c) { this.crc32c = crc32c; }

    public ChecksumAlgorithm getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(ChecksumAlgorithm checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; }

    public String getChecksumValue() { return checksumValue; }
    public void setChecksumValue(String checksumValue) { this.checksumValue = checksumValue; }

    public ChecksumType getChecksumType() { return checksumType; }
    public void setChecksumType(ChecksumType checksumType) { this.checksumType = checksumType; }

    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String s) { this.sourceFileName = s; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }


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
