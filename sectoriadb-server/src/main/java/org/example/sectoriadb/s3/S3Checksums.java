package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.s3.auth.PayloadVerificationException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Reads the S3 "additional checksum" request headers (AWS "Checking object integrity") into an
 * {@link UploadChecksums} and renders them back in response headers.
 */
public final class S3Checksums {

    public static final String HEADER_SDK_ALGORITHM = "x-amz-sdk-checksum-algorithm";
    public static final String HEADER_ALGORITHM = "x-amz-checksum-algorithm";
    public static final String HEADER_TYPE = "x-amz-checksum-type";
    public static final String HEADER_MODE = "x-amz-checksum-mode";
    private static final String HEADER_TRAILER = "x-amz-trailer";

    private S3Checksums() {}

    /**
     * The checksums declared by one upload request. {@code trailer} is the algorithm announced in
     * {@code x-amz-trailer} (its value arrives after the body), or null.
     */
    public record Declared(UploadChecksums checksums, ChecksumAlgorithm trailer) {

        /** Called with the aws-chunked trailer headers; stores the expected value or rejects the request. */
        public void acceptTrailers(Map<String, String> trailers) throws IOException {
            boolean seen = false;
            for (Map.Entry<String, String> e : trailers.entrySet()) {
                if (!e.getKey().startsWith("x-amz-checksum-")) continue;
                ChecksumAlgorithm alg = ChecksumAlgorithm.fromHeader(e.getKey()).orElse(null);
                if (alg == null || alg != trailer) {
                    throw new PayloadVerificationException("InvalidRequest", 400,
                            "The trailing header " + e.getKey() + " was not announced in x-amz-trailer.");
                }
                try {
                    checksums.expected(alg.decode(e.getValue()));
                } catch (IllegalArgumentException ex) {
                    throw new PayloadVerificationException("InvalidRequest", 400,
                            "Value for " + e.getKey() + " trailer is invalid.");
                }
                seen = true;
            }
            if (trailer != null && !seen) {
                throw new PayloadVerificationException("InvalidRequest", 400,
                        "The announced trailing checksum " + trailer.headerName() + " is missing.");
            }
        }
    }

    /** Parses Content-MD5 and the x-amz-checksum-* headers/trailer announcement of a PutObject / UploadPart request. */
    public static Declared parse(HttpServletRequest request) {
        UploadChecksums checksums = UploadChecksums.none();

        String md5 = request.getHeader("Content-MD5");
        if (md5 != null && !md5.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(md5.trim());
                if (raw.length != 16) throw new IllegalArgumentException();
                checksums.contentMd5(raw);
            } catch (IllegalArgumentException e) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidDigest",
                        "The Content-MD5 you specified was not valid.");
            }
        }

        List<ChecksumAlgorithm> given = new ArrayList<>();
        for (ChecksumAlgorithm a : ChecksumAlgorithm.values()) {
            String v = request.getHeader(a.headerName());
            if (v != null) given.add(a);
        }
        ChecksumAlgorithm trailer = null;
        String trailerHeader = request.getHeader(HEADER_TRAILER);
        if (trailerHeader != null && !trailerHeader.isBlank()) {
            for (String name : trailerHeader.split(",")) {
                String n = name.trim().toLowerCase(Locale.ROOT);
                if (n.isEmpty()) continue;
                if (!n.startsWith("x-amz-checksum-")) continue;   // not a checksum trailer: not our business
                ChecksumAlgorithm a = ChecksumAlgorithm.fromHeader(n).orElseThrow(() -> invalid(
                        "Trailer " + n + " is not a supported checksum."));
                given.add(a);
                trailer = a;
            }
        }
        if (given.size() > 1) {
            throw invalid("Expecting a single x-amz-checksum- header. Multiple checksum Types are not allowed.");
        }

        ChecksumAlgorithm declaredAlg = declaredAlgorithm(request);
        ChecksumAlgorithm alg = given.isEmpty() ? declaredAlg : given.get(0);
        if (declaredAlg != null && alg != declaredAlg) {
            throw invalid("Value for " + HEADER_SDK_ALGORITHM + " does not match the provided checksum header "
                    + alg.headerName() + ".");
        }
        if (alg != null) {
            byte[] expected = null;
            if (!given.isEmpty() && trailer == null) {
                try {
                    expected = alg.decode(request.getHeader(alg.headerName()));
                } catch (IllegalArgumentException e) {
                    throw invalid("Value for " + alg.headerName() + " header is invalid.");
                }
            }
            checksums.algorithm(alg, expected);
        }
        return new Declared(checksums, trailer);
    }

    /** The algorithm named by x-amz-sdk-checksum-algorithm / x-amz-checksum-algorithm, or null. */
    public static ChecksumAlgorithm declaredAlgorithm(HttpServletRequest request) {
        ChecksumAlgorithm a = algorithmHeader(request, HEADER_SDK_ALGORITHM);
        ChecksumAlgorithm b = algorithmHeader(request, HEADER_ALGORITHM);
        if (a != null && b != null && a != b) {
            throw invalid("The " + HEADER_SDK_ALGORITHM + " and " + HEADER_ALGORITHM + " headers name different algorithms.");
        }
        return a != null ? a : b;
    }

    private static ChecksumAlgorithm algorithmHeader(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        if (v == null || v.isBlank()) return null;
        return ChecksumAlgorithm.fromAwsName(v).orElseThrow(() -> invalid(
                "Checksum algorithm provided is unsupported. Please try again with any of the valid types: "
                        + Arrays.stream(ChecksumAlgorithm.values()).map(Enum::name)
                        .collect(Collectors.joining(", ", "[", "]"))));
    }

    /** Resolves the multipart checksum type for a CreateMultipartUpload request. */
    public static ChecksumType multipartType(HttpServletRequest request, ChecksumAlgorithm alg) {
        String t = request.getHeader(HEADER_TYPE);
        if (alg == null) {
            if (t != null && !t.isBlank()) throw invalid(HEADER_TYPE + " requires " + HEADER_ALGORITHM + ".");
            return null;
        }
        ChecksumType type = alg.defaultMultipartType();
        if (t != null && !t.isBlank()) {
            try {
                type = ChecksumType.valueOf(t.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw invalid("The checksum type " + t + " is not valid. Valid types: [FULL_OBJECT, COMPOSITE]");
            }
        }
        if (type == ChecksumType.FULL_OBJECT && !alg.supportsFullObject()) {
            throw invalid("The FULL_OBJECT checksum type cannot be used with the " + alg.name().toLowerCase(Locale.ROOT)
                    + " checksum algorithm.");
        }
        if (type == ChecksumType.COMPOSITE && !alg.supportsComposite()) {
            throw invalid("The COMPOSITE checksum type cannot be used with the " + alg.name().toLowerCase(Locale.ROOT)
                    + " checksum algorithm.");
        }
        return type;
    }

    /** x-amz-checksum-mode: ENABLED on GET / HEAD. */
    public static boolean modeEnabled(HttpServletRequest request) {
        return "ENABLED".equalsIgnoreCase(request.getHeader(HEADER_MODE));
    }

    /**
     * Response headers of a stored object's checksum: the client's own algorithm when it gave one, otherwise the
     * always-present CRC32C (FULL_OBJECT). Nothing for objects that predate whole-object checksums.
     */
    public static void addObjectHeaders(ManifestEntity e, BiConsumer<String, String> header) {
        if (e.getChecksumAlgorithm() != null && e.getChecksumValue() != null) {
            header.accept(e.getChecksumAlgorithm().headerName(), e.getChecksumValue());
            header.accept(HEADER_TYPE, (e.getChecksumType() != null ? e.getChecksumType() : ChecksumType.FULL_OBJECT).name());
        } else if (e.getCrc32c() != null) {
            header.accept(ChecksumAlgorithm.CRC32C.headerName(), e.getCrc32c());
            header.accept(HEADER_TYPE, ChecksumType.FULL_OBJECT.name());
        }
    }

    /** Headers echoed on PutObject / CompleteMultipartUpload responses: only what the client chose. */
    public static void addClientHeaders(ManifestEntity e, BiConsumer<String, String> header) {
        if (e.getChecksumAlgorithm() != null && e.getChecksumValue() != null) {
            header.accept(e.getChecksumAlgorithm().headerName(), e.getChecksumValue());
            header.accept(HEADER_TYPE, (e.getChecksumType() != null ? e.getChecksumType() : ChecksumType.FULL_OBJECT).name());
        }
    }

    /** XML element name of an algorithm in ListParts / CompleteMultipartUpload bodies, e.g. "ChecksumCRC32C". */
    public static String xmlElement(ChecksumAlgorithm a) {
        return "Checksum" + a.name();
    }

    private static S3Exception invalid(String message) {
        return new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest", message);
    }
}
