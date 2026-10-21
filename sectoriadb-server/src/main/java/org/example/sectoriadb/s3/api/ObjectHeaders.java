package org.example.sectoriadb.s3.api;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.s3.http.S3Headers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The four descriptive headers of an object (Cache-Control, Content-Disposition, Content-Encoding, Content-Language),
 * in one place: read from a request, stored in a manifest, written to a response, overridden by {@code response-*}
 * parameters. Also reads the user metadata ({@code x-amz-meta-*}).
 */
public record ObjectHeaders(String cacheControl, String contentDisposition, String contentEncoding,
                            String contentLanguage) {

    public static ObjectHeaders of(HttpServletRequest request) {
        return new ObjectHeaders(request.getHeader(S3Headers.CACHE_CONTROL), request.getHeader(S3Headers.CONTENT_DISPOSITION),
                request.getHeader(S3Headers.CONTENT_ENCODING), request.getHeader(S3Headers.CONTENT_LANGUAGE));
    }

    public static ObjectHeaders of(ManifestEntity entity) {
        return new ObjectHeaders(entity.getCacheControl(), entity.getContentDisposition(), entity.getContentEncoding(),
                entity.getContentLanguage());
    }

    public void applyTo(ManifestEntity entity) {
        entity.setCacheControl(cacheControl);
        entity.setContentDisposition(contentDisposition);
        entity.setContentEncoding(contentEncoding);
        entity.setContentLanguage(contentLanguage);
    }

    /** These headers, where {@code override} has a value that value instead. */
    public ObjectHeaders overriddenBy(ObjectHeaders override) {
        return new ObjectHeaders(
                override.cacheControl != null ? override.cacheControl : cacheControl,
                override.contentDisposition != null ? override.contentDisposition : contentDisposition,
                override.contentEncoding != null ? override.contentEncoding : contentEncoding,
                override.contentLanguage != null ? override.contentLanguage : contentLanguage);
    }

    /** Hands every header that has a value to {@code out(name, value)}. */
    public void writeTo(BiConsumer<String, String> out) {
        if (cacheControl != null) out.accept(S3Headers.CACHE_CONTROL, cacheControl);
        if (contentDisposition != null) out.accept(S3Headers.CONTENT_DISPOSITION, contentDisposition);
        if (contentEncoding != null) out.accept(S3Headers.CONTENT_ENCODING, contentEncoding);
        if (contentLanguage != null) out.accept(S3Headers.CONTENT_LANGUAGE, contentLanguage);
    }

    /** The {@code x-amz-meta-*} request headers (names lower-cased), or null when there are none. */
    public static Map<String, String> userMetadata(HttpServletRequest request) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(S3Headers.META_PREFIX)) meta.put(lower, request.getHeader(name));
        }
        return meta.isEmpty() ? null : meta;
    }
}
