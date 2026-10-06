package org.example.sectoriadb.s3.auth;

/**
 * Everything needed to verify aws-chunked chunk signatures; created by {@link SigV4Filter} after the
 * request signature itself has been verified.
 *
 * @param signingKey    derived SigV4 signing key
 * @param timestamp     x-amz-date of the request (yyyyMMdd'T'HHmmss'Z')
 * @param scope         credential scope (date/region/service/aws4_request)
 * @param seedSignature the (verified) signature of the request headers: the "previous signature" of chunk 0
 * @param trailerSigned true for STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER (the trailer carries a signature)
 */
public record ChunkSigningContext(byte[] signingKey, String timestamp, String scope,
                                  String seedSignature, boolean trailerSigned) {

    public static final String REQUEST_ATTRIBUTE = ChunkSigningContext.class.getName();
}
