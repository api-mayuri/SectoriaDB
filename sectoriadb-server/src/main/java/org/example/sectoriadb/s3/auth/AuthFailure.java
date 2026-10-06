package org.example.sectoriadb.s3.auth;

/**
 * Why SigV4 authentication rejected a request. The {@link #label()} is the value of the {@code reason} label of
 * {@code sectoriadb_s3_auth_failures_total}; the set is closed, so the label stays low-cardinality.
 */
public enum AuthFailure {
    /** The computed signature differs from the one sent (also: credential date does not match the request date). */
    SIGNATURE_MISMATCH("signature_mismatch"),
    /** The request time is more than 15 minutes away from the server clock. */
    CLOCK_SKEW("clock_skew"),
    /** A presigned URL is past its X-Amz-Expires. */
    EXPIRED_PRESIGN("expired_presign"),
    /** The access key id is not known. */
    UNKNOWN_KEY("unknown_key"),
    /** The access key exists but is disabled. */
    DISABLED_KEY("disabled_key"),
    /** Authorization header / presign parameters / x-amz-date / x-amz-content-sha256 are missing or unparsable. */
    MALFORMED_HEADER("malformed_header"),
    /** The body does not match the signed x-amz-content-sha256. */
    PAYLOAD_HASH_MISMATCH("payload_hash_mismatch"),
    /** An aws-chunked chunk (or trailer) signature is wrong. */
    CHUNK_SIGNATURE_MISMATCH("chunk_signature_mismatch"),
    /** No access keys exist and open setup mode is off: everything is denied. */
    OPEN_SETUP_DENIED("open_setup_denied"),
    /** An unsigned request to something that does not allow anonymous access. */
    ANONYMOUS_DENIED("anonymous_denied"),
    /** UNSIGNED-PAYLOAD sent while sectoriadb.s3.auth.allow-unsigned-payload=false. */
    UNSIGNED_PAYLOAD_DENIED("unsigned_payload_denied");

    private final String label;

    AuthFailure(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
