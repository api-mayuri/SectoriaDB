package org.example.sectoriadb.observability;

import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/** Generators for {@code x-amz-request-id} (16 upper-case hex characters, like S3) and {@code x-amz-id-2}. */
public final class RequestIds {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private RequestIds() {
    }

    public static String newRequestId() {
        long v = ThreadLocalRandom.current().nextLong();
        char[] c = new char[16];
        for (int i = 15; i >= 0; i--) {
            c[i] = HEX[(int) (v & 0xF)];
            v >>>= 4;
        }
        return new String(c);
    }

    public static String newExtendedId() {
        byte[] b = new byte[24];
        ThreadLocalRandom.current().nextBytes(b);
        return Base64.getEncoder().withoutPadding().encodeToString(b);
    }
}
