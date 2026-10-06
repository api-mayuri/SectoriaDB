package org.example.sectoriadb.checksum;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * The additional object checksums of the S3 API ("Checking object integrity"). Values travel base64-encoded
 * big-endian digests in {@code x-amz-checksum-<name>} headers or trailers.
 */
public enum ChecksumAlgorithm {

    CRC32("x-amz-checksum-crc32", 4, true),
    CRC32C("x-amz-checksum-crc32c", 4, true),
    CRC64NVME("x-amz-checksum-crc64nvme", 8, true),
    SHA1("x-amz-checksum-sha1", 20, false),
    SHA256("x-amz-checksum-sha256", 32, false);

    private final String headerName;
    private final int digestLength;
    private final boolean crc;

    ChecksumAlgorithm(String headerName, int digestLength, boolean crc) {
        this.headerName = headerName;
        this.digestLength = digestLength;
        this.crc = crc;
    }

    /** Lower-case HTTP header (or trailer) name carrying the base64 value. */
    public String headerName() { return headerName; }

    public int digestLength() { return digestLength; }

    /** CRC algorithms can describe a whole object (FULL_OBJECT); SHA-1/SHA-256 only a composite of parts. */
    public boolean supportsFullObject() { return crc; }

    /** CRC64NVME exists only as a full-object checksum. */
    public boolean supportsComposite() { return this != CRC64NVME; }

    /** The type S3 picks for a multipart upload when the client names none. */
    public ChecksumType defaultMultipartType() { return supportsComposite() ? ChecksumType.COMPOSITE : ChecksumType.FULL_OBJECT; }

    public ChecksumCalculator newCalculator() {
        return switch (this) {
            case CRC32 -> new ChecksumBased(new CRC32(), 4);
            case CRC32C -> new ChecksumBased(new CRC32C(), 4);
            case CRC64NVME -> new ChecksumBased(new Crc64Nvme(), 8);
            case SHA1 -> new DigestBased("SHA-1");
            case SHA256 -> new DigestBased("SHA-256");
        };
    }

    /** One-shot digest of {@code data}. */
    public byte[] compute(byte[] data) {
        ChecksumCalculator c = newCalculator();
        c.update(data, 0, data.length);
        return c.digest();
    }

    public String encode(byte[] digest) {
        return Base64.getEncoder().encodeToString(digest);
    }

    /** Decodes a base64 value; throws IllegalArgumentException when it is not base64 or has the wrong length. */
    public byte[] decode(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != digestLength) {
            throw new IllegalArgumentException("Expected " + digestLength + " bytes for " + name() + ", got " + raw.length);
        }
        return raw;
    }

    /**
     * Composite checksum of a multipart upload: the algorithm applied to the concatenated binary part
     * checksums, base64-encoded and suffixed with {@code -<part count>}.
     */
    public String composite(List<byte[]> partDigests) {
        ChecksumCalculator c = newCalculator();
        for (byte[] d : partDigests) c.update(d, 0, d.length);
        return encode(c.digest()) + "-" + partDigests.size();
    }

    /** Resolves an S3 algorithm name ("CRC32C", case-insensitive). */
    public static Optional<ChecksumAlgorithm> fromAwsName(String name) {
        if (name == null) return Optional.empty();
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (ChecksumAlgorithm a : values()) {
            if (a.name().equals(n)) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Resolves a header/trailer name ("x-amz-checksum-crc32c", case-insensitive). */
    public static Optional<ChecksumAlgorithm> fromHeader(String header) {
        if (header == null) return Optional.empty();
        String h = header.trim().toLowerCase(Locale.ROOT);
        for (ChecksumAlgorithm a : values()) {
            if (a.headerName.equals(h)) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Big-endian bytes of a CRC32/CRC32C value. */
    public static byte[] crc32Bytes(int crc) {
        return ByteBuffer.allocate(4).putInt(crc).array();
    }

    public static int crc32FromBytes(byte[] b) {
        return ByteBuffer.wrap(b).getInt();
    }

    private static final class ChecksumBased implements ChecksumCalculator {
        private final Checksum checksum;
        private final int length;

        ChecksumBased(Checksum checksum, int length) {
            this.checksum = checksum;
            this.length = length;
        }

        @Override
        public void update(byte[] b, int off, int len) { checksum.update(b, off, len); }

        @Override
        public byte[] digest() {
            long v = checksum.getValue();
            ByteBuffer buf = ByteBuffer.allocate(length);
            if (length == 4) buf.putInt((int) v); else buf.putLong(v);
            return buf.array();
        }
    }

    private static final class DigestBased implements ChecksumCalculator {
        private final MessageDigest md;

        DigestBased(String algorithm) {
            try {
                this.md = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void update(byte[] b, int off, int len) { md.update(b, off, len); }

        @Override
        public byte[] digest() { return md.digest(); }
    }
}
