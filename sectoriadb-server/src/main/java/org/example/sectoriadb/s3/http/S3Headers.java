package org.example.sectoriadb.s3.http;

/** Names of the request and response headers the S3 handlers read or write (one spelling, in one place). */
public final class S3Headers {
    private S3Headers() {}

    public static final String SERVER = "Server";

    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CACHE_CONTROL = "Cache-Control";
    public static final String CONTENT_DISPOSITION = "Content-Disposition";
    public static final String CONTENT_ENCODING = "Content-Encoding";
    public static final String CONTENT_LANGUAGE = "Content-Language";
    public static final String IF_MATCH = "If-Match";
    public static final String IF_NONE_MATCH = "If-None-Match";

    public static final String COPY_SOURCE = "x-amz-copy-source";
    public static final String COPY_SOURCE_RANGE = "x-amz-copy-source-range";
    public static final String METADATA_DIRECTIVE = "x-amz-metadata-directive";
    public static final String META_PREFIX = "x-amz-meta-";
    public static final String ACL = "x-amz-acl";
    public static final String CONTENT_SHA256 = "x-amz-content-sha256";
    public static final String DECODED_CONTENT_LENGTH = "x-amz-decoded-content-length";
}
