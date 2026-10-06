package org.example.sectoriadb.checksum;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Computes MD5 (the ETag) and any number of {@link ChecksumAlgorithm} digests over the same bytes in one pass.
 * Feed it with {@link #update}, then call {@link #finish()} once; after that the digests can be read.
 */
public final class MultiDigest {

    private final MessageDigest md5;
    private final Map<ChecksumAlgorithm, ChecksumCalculator> calculators = new EnumMap<>(ChecksumAlgorithm.class);
    private final Map<ChecksumAlgorithm, byte[]> results = new EnumMap<>(ChecksumAlgorithm.class);
    private byte[] md5Result;
    private long count;
    private boolean finished;

    public MultiDigest(boolean withMd5, Collection<ChecksumAlgorithm> algorithms) {
        try {
            this.md5 = withMd5 ? MessageDigest.getInstance("MD5") : null;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (ChecksumAlgorithm a : algorithms) calculators.put(a, a.newCalculator());
    }

    public void update(byte[] b, int off, int len) {
        if (finished) throw new IllegalStateException("digest already finished");
        if (md5 != null) md5.update(b, off, len);
        for (ChecksumCalculator c : calculators.values()) c.update(b, off, len);
        count += len;
    }

    public void update(int b) {
        update(new byte[]{(byte) b}, 0, 1);
    }

    /** Finalizes all digests; idempotent. */
    public MultiDigest finish() {
        if (!finished) {
            finished = true;
            if (md5 != null) md5Result = md5.digest();
            calculators.forEach((a, c) -> results.put(a, c.digest()));
        }
        return this;
    }

    public long count() { return count; }

    /** Raw 16-byte MD5; requires MD5 to have been requested. */
    public byte[] md5() {
        finish();
        if (md5Result == null) throw new IllegalStateException("MD5 was not requested");
        return md5Result;
    }

    public byte[] digest(ChecksumAlgorithm a) {
        finish();
        byte[] d = results.get(a);
        if (d == null) throw new IllegalStateException(a + " was not requested");
        return d;
    }

    public String encoded(ChecksumAlgorithm a) { return a.encode(digest(a)); }

    public boolean has(ChecksumAlgorithm a) { return calculators.containsKey(a); }
}
