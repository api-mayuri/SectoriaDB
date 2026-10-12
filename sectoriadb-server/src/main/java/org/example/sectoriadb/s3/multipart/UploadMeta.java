package org.example.sectoriadb.s3.multipart;

import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.s3.api.ObjectHeaders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * What CreateMultipartUpload recorded about the future object ({@code meta.properties} of the upload): where it will
 * live, its content type and descriptive headers, its user metadata and the checksum the upload was declared with.
 * The attributes are applied to the object when the upload is completed.
 */
record UploadMeta(String bucket, String key, String contentType, long initiatedAtMillis, ObjectHeaders headers,
                  Map<String, String> userMetadata, ChecksumAlgorithm checksumAlgorithm, ChecksumType checksumType) {

    /** Key prefix of user metadata ("x-amz-meta-*") inside the properties file. */
    private static final String USER_META_PREFIX = "usermeta.";

    Properties toProperties() {
        Properties meta = new Properties();
        meta.setProperty("bucket", bucket);
        meta.setProperty("key", key);
        meta.setProperty("contentType", contentType);
        meta.setProperty("initiatedAt", String.valueOf(initiatedAtMillis));
        headers.storeIn(meta);
        if (userMetadata != null) userMetadata.forEach((name, value) -> meta.setProperty(USER_META_PREFIX + name, value));
        if (checksumAlgorithm != null) {
            meta.setProperty("checksumAlgorithm", checksumAlgorithm.name());
            meta.setProperty("checksumType", checksumType.name());
        }
        return meta;
    }

    static UploadMeta from(Properties meta) {
        Map<String, String> user = new LinkedHashMap<>();
        for (String name : meta.stringPropertyNames()) {
            if (name.startsWith(USER_META_PREFIX)) user.put(name.substring(USER_META_PREFIX.length()), meta.getProperty(name));
        }
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromAwsName(meta.getProperty("checksumAlgorithm")).orElse(null);
        ChecksumType type = alg == null ? null
                : ChecksumType.valueOf(meta.getProperty("checksumType", alg.defaultMultipartType().name()));
        return new UploadMeta(meta.getProperty("bucket"), meta.getProperty("key"),
                meta.getProperty("contentType", "application/octet-stream"),
                parseLong(meta.getProperty("initiatedAt"), 0), ObjectHeaders.loadFrom(meta), user, alg, type);
    }

    /** A corrupt number must not make an otherwise complete upload unusable. */
    private static long parseLong(String v, long fallback) {
        try {
            return v == null ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
