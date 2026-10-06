package org.example.sectoriadb.s3.auth;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Computes SHA-256 of everything read and, when the end of the stream is reached, compares it with the
 * hash the client declared (and signed) in x-amz-content-sha256. A mismatch throws
 * {@link PayloadVerificationException} from the read that would have returned -1.
 */
public class Sha256VerifyingInputStream extends FilterInputStream {

    private final MessageDigest digest;
    private final String expectedHex;
    private boolean finished;

    public Sha256VerifyingInputStream(InputStream in, String expectedHex) {
        super(in);
        this.expectedHex = expectedHex.toLowerCase(Locale.ROOT);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) digest.update((byte) b);
        else verifyAtEof();
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) return 0;
        int n = super.read(buf, off, len);
        if (n > 0) digest.update(buf, off, n);
        else if (n < 0) verifyAtEof();
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        // Skipped bytes must still be hashed.
        if (n <= 0) return 0;
        byte[] tmp = new byte[(int) Math.min(8192, n)];
        long total = 0;
        while (total < n) {
            int r = read(tmp, 0, (int) Math.min(tmp.length, n - total));
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    @Override
    public boolean markSupported() { return false; }

    @Override
    public synchronized void mark(int readlimit) { }

    @Override
    public synchronized void reset() throws IOException { throw new IOException("mark/reset not supported"); }

    private void verifyAtEof() throws PayloadVerificationException {
        if (finished) return;
        finished = true;
        String actual = SigV4Utils.hexEncode(digest.digest());
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expectedHex.getBytes(StandardCharsets.US_ASCII))) {
            throw PayloadVerificationException.sha256Mismatch();
        }
    }
}
