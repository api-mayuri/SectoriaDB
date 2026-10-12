package org.example.sectoriadb.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;

import java.util.Map;

/**
 * A multipart upload as the {@code mpu_uploads} tree stores it: where the object will live, the attributes it gets when
 * the upload is completed, the state, and (after completion) the result a retried Complete is answered with.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultipartUpload {

    public enum State {
        /** accepts parts; listed by ListMultipartUploads */
        OPEN,
        /** the object exists; the record is kept for {@code sectoriadb.multipart.completed-retention} to answer a retried Complete */
        COMPLETED,
        /** discarded (Abort or expiry); the record is kept for the same retention so that late requests get NoSuchUpload */
        ABORTED
    }

    private String uploadId;
    private String bucket;
    private String key;
    /** The pool of the bucket: part manifests live in it. */
    private String poolId;
    private long initiatedAt;
    /** Time of the last part (or of the initiation): the expiry clock. */
    private long updatedAt;
    private State state = State.OPEN;

    private String contentType;
    private String cacheControl;
    private String contentDisposition;
    private String contentEncoding;
    private String contentLanguage;
    private Map<String, String> userMetadata;
    private String acl;
    private ChecksumAlgorithm checksumAlgorithm;
    private ChecksumType checksumType;

    // result of a completed upload
    private long completedAt;
    private String resultEtag;
    private String resultManifestId;
    private String resultChecksumValue;
    private String partsFingerprint;
    private long abortedAt;

    public MultipartUpload() {
    }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getPoolId() { return poolId; }
    public void setPoolId(String poolId) { this.poolId = poolId; }
    public long getInitiatedAt() { return initiatedAt; }
    public void setInitiatedAt(long initiatedAt) { this.initiatedAt = initiatedAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getCacheControl() { return cacheControl; }
    public void setCacheControl(String cacheControl) { this.cacheControl = cacheControl; }
    public String getContentDisposition() { return contentDisposition; }
    public void setContentDisposition(String contentDisposition) { this.contentDisposition = contentDisposition; }
    public String getContentEncoding() { return contentEncoding; }
    public void setContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; }
    public String getContentLanguage() { return contentLanguage; }
    public void setContentLanguage(String contentLanguage) { this.contentLanguage = contentLanguage; }
    public Map<String, String> getUserMetadata() { return userMetadata; }
    public void setUserMetadata(Map<String, String> userMetadata) { this.userMetadata = userMetadata; }
    public String getAcl() { return acl; }
    public void setAcl(String acl) { this.acl = acl; }
    public ChecksumAlgorithm getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(ChecksumAlgorithm checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; }
    public ChecksumType getChecksumType() { return checksumType; }
    public void setChecksumType(ChecksumType checksumType) { this.checksumType = checksumType; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
    public String getResultEtag() { return resultEtag; }
    public void setResultEtag(String resultEtag) { this.resultEtag = resultEtag; }
    public String getResultManifestId() { return resultManifestId; }
    public void setResultManifestId(String resultManifestId) { this.resultManifestId = resultManifestId; }
    public String getResultChecksumValue() { return resultChecksumValue; }
    public void setResultChecksumValue(String resultChecksumValue) { this.resultChecksumValue = resultChecksumValue; }
    public String getPartsFingerprint() { return partsFingerprint; }
    public void setPartsFingerprint(String partsFingerprint) { this.partsFingerprint = partsFingerprint; }
    public long getAbortedAt() { return abortedAt; }
    public void setAbortedAt(long abortedAt) { this.abortedAt = abortedAt; }
}
