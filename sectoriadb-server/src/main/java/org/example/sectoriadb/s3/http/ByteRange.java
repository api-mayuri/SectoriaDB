package org.example.sectoriadb.s3.http;

import org.example.sectoriadb.s3.error.S3Exception;
import org.springframework.http.HttpStatus;

/** An inclusive byte range {@code first..last} of an object. */
public record ByteRange(long first, long last) {

    public long length() {
        return last - first + 1;
    }

    /**
     * The {@code Range} header of a GET: a single "bytes=a-b" / "bytes=a-" / "bytes=-n" range. Returns null when the
     * header is absent or not a single range (S3 then serves the whole object); throws 416 when it cannot be satisfied.
     * An end beyond the object size is clamped, as S3 does.
     */
    public static ByteRange parseRequestRange(String header, long total) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) return null;
        String[] parts = header.substring("bytes=".length()).split("-", 2);
        long start;
        long end;
        try {
            if (parts[0].isBlank()) {
                if (parts.length < 2) return null;   // "bytes=" without a dash: malformed
                long suffix = Long.parseLong(parts[1].trim());
                if (suffix <= 0) throw invalidRange();
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(parts[0].trim());
                end = parts.length < 2 || parts[1].isBlank() ? total - 1 : Long.parseLong(parts[1].trim());
            }
        } catch (NumberFormatException e) {
            return null;   // malformed → ignore the header, like S3
        }
        if (start >= total || start < 0) throw invalidRange();
        end = Math.min(end, total - 1);
        if (end < start) return null;
        return new ByteRange(start, end);
    }

    /** {@code x-amz-copy-source-range} of UploadPartCopy: "bytes=first-last"; malformed is InvalidArgument, beyond the object is InvalidRange. */
    public static ByteRange parseCopyRange(String header, long total) {
        String bad = "The x-amz-copy-source-range value must be of the form bytes=first-last where first and last are the "
                + "zero-based offsets of the first and last bytes to copy";
        if (header == null || !header.startsWith("bytes=")) throw S3Exception.invalidArgument(bad);
        String[] parts = header.substring("bytes=".length()).split("-", -1);
        if (parts.length != 2) throw S3Exception.invalidArgument(bad);
        long start;
        long end;
        try {
            start = Long.parseLong(parts[0].trim());
            end = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            throw S3Exception.invalidArgument(bad);
        }
        if (start < 0 || end < start) throw S3Exception.invalidArgument(bad);
        if (end >= total) throw invalidRange();
        return new ByteRange(start, end);
    }

    private static S3Exception invalidRange() {
        return new S3Exception(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "InvalidRange",
                "The requested range is not satisfiable");
    }
}
