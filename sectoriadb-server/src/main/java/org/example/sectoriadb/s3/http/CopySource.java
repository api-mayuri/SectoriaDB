package org.example.sectoriadb.s3.http;

import org.example.sectoriadb.s3.error.S3Exception;

/** The {@code x-amz-copy-source} header: {@code /bucket/key} or {@code bucket/key}, the key percent-encoded, an optional {@code ?versionId=...} ignored. */
public record CopySource(String bucket, String key) {

    public static CopySource parse(String header) {
        String spec = header.startsWith("/") ? header.substring(1) : header;
        int versionIdx = spec.indexOf('?');   // strip ?versionId=... BEFORE decoding (a decoded %3F is part of the key)
        if (versionIdx >= 0) spec = spec.substring(0, versionIdx);
        int slash = spec.indexOf('/');
        if (slash < 0) {
            throw S3Exception.invalidArgument("x-amz-copy-source must be in format /bucket/key");
        }
        return new CopySource(spec.substring(0, slash), UrlCodec.percentDecode(spec.substring(slash + 1)));
    }
}
