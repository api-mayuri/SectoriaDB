package org.example.sectoriadb.s3.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The two date formats of S3: RFC 1123 in headers, ISO-8601 with millis inside XML bodies. */
public final class HttpDates {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private HttpDates() {}

    /** RFC 1123 date for Last-Modified headers. */
    public static String httpDate(Instant t) { return HTTP_DATE.format(t); }

    /** ISO-8601 with millis, as used inside S3 XML bodies. */
    public static String isoDate(Instant t) { return ISO_MILLIS.format(t); }

    /** Parses the value of an {@code If-Modified-Since}-style header; null if it is not a valid RFC 1123 date. */
    public static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
