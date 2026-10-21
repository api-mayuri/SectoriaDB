package org.example.sectoriadb;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** Seeded input mutation for the parser fuzz tests: the same seed always produces the same inputs. */
final class FuzzSupport {

    static final long SEED = 18_2024L;

    /** Fragments that tend to break parsers: markup, entities, escapes, numeric overflow, control characters. */
    private static final String[] TOKENS = {
        "<", ">", "</", "/>", "<?xml", "<!DOCTYPE x [<!ENTITY a \"aaaa\">]>", "&a;", "&#0;", "&#x110000;", "&amp;", "]]>", "<![CDATA[",
        "%", "%00", "%zz", "%C3%28", "%FF", "?", "&", "=", ";", ",", "-", "--", "+", " ", "\t", "\r\n", "\0", "\u00e9", "\uD83D\uDE00",
        "99999999999999999999", "-1", "0", "2147483648", "9223372036854775807", "bytes=", "bytes=-", "bytes=0-", "bytes=-0", "bytes=5-2",
        "..", "/", "//", "versionId=", "Credential=", "Signature=", "SignedHeaders=", "AWS4-HMAC-SHA256", "x-amz-", "\\", "\"", "'"};

    private FuzzSupport() {}

    static byte[] mutate(byte[] seed, Random rnd) {
        byte[] b = Arrays.copyOf(seed, seed.length);
        int rounds = 1 + rnd.nextInt(4);
        for (int r = 0; r < rounds; r++) {
            switch (rnd.nextInt(7)) {
                case 0 -> { if (b.length > 0) b[rnd.nextInt(b.length)] ^= (byte) (1 << rnd.nextInt(8)); }
                case 1 -> { if (b.length > 0) b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256); }
                case 2 -> b = Arrays.copyOf(b, rnd.nextInt(b.length + 1));                                   // truncate
                case 3 -> b = insert(b, rnd.nextInt(b.length + 1), TOKENS[rnd.nextInt(TOKENS.length)].getBytes(StandardCharsets.UTF_8));
                case 4 -> {                                                                                // delete a range
                    if (b.length > 1) {
                        int from = rnd.nextInt(b.length), to = Math.min(b.length, from + 1 + rnd.nextInt(8));
                        byte[] c = new byte[b.length - (to - from)];
                        System.arraycopy(b, 0, c, 0, from);
                        System.arraycopy(b, to, c, from, b.length - to);
                        b = c;
                    }
                }
                case 5 -> {                                                                                // duplicate a range
                    if (b.length > 1) {
                        int from = rnd.nextInt(b.length), to = Math.min(b.length, from + 1 + rnd.nextInt(32));
                        b = insert(b, to, Arrays.copyOfRange(b, from, to));
                    }
                }
                default -> { byte[] junk = new byte[1 + rnd.nextInt(16)]; rnd.nextBytes(junk); b = insert(b, rnd.nextInt(b.length + 1), junk); }
            }
        }
        return b;
    }

    static String mutate(String seed, Random rnd) {
        return new String(mutate(seed.getBytes(StandardCharsets.UTF_8), rnd), StandardCharsets.UTF_8);
    }

    /** A string of 0..n random tokens (not derived from a valid seed). */
    static String tokens(Random rnd, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = rnd.nextInt(n + 1); i > 0; i--) sb.append(TOKENS[rnd.nextInt(TOKENS.length)]);
        return sb.toString();
    }

    private static byte[] insert(byte[] b, int at, byte[] what) {
        byte[] c = new byte[b.length + what.length];
        System.arraycopy(b, 0, c, 0, at);
        System.arraycopy(what, 0, c, at, what.length);
        System.arraycopy(b, at, c, at + what.length, b.length - at);
        return c;
    }
}
