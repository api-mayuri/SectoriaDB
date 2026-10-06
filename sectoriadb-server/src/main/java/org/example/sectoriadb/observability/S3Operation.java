package org.example.sectoriadb.observability;

/**
 * Every S3 operation the server distinguishes in metrics and logs. The enum constant name IS the value of the
 * {@code operation} label (AWS action names), so renaming a constant breaks dashboards.
 */
public enum S3Operation {
    // objects
    PutObject, GetObject, HeadObject, DeleteObject, DeleteObjects, CopyObject,
    // listing
    ListObjects, ListObjectsV2, ListBuckets,
    // multipart
    CreateMultipartUpload, UploadPart, UploadPartCopy, CompleteMultipartUpload, AbortMultipartUpload,
    ListParts, ListMultipartUploads,
    // buckets
    CreateBucket, HeadBucket, DeleteBucket, GetBucketLocation, GetBucketVersioning,
    // ACL / policy
    GetBucketAcl, PutBucketAcl, GetObjectAcl, PutObjectAcl,
    GetBucketPolicy, PutBucketPolicy, DeleteBucketPolicy,
    // tagging and other bucket sub-resources (answered with static / empty documents)
    GetBucketTagging, GetObjectTagging, PutObjectTagging, DeleteObjectTagging,
    GetBucketCors, GetBucketLifecycle, GetBucketEncryption, GetPublicAccessBlock,
    /** Anything else: unsupported sub-resources, unknown paths, other HTTP methods. */
    Other;

    /** Value of the {@code operation} label / MDC key. */
    public String label() {
        return name();
    }
}
