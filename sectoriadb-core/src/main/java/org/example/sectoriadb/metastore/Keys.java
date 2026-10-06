package org.example.sectoriadb.metastore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Order-preserving composite key encoding. Comparing encoded keys with unsigned lexicographic
 * order gives the same result as comparing the component tuples.
 *
 * <ul>
 *   <li>string: UTF-8 bytes, 0x00 escaped as 0x00 0xFF, terminated by 0x00 0x01. The terminator
 *       sorts below any continuation, so "a" &lt; "a\0" &lt; "ab".</li>
 *   <li>long: 8 bytes big-endian; {@link Builder#longSigned} flips the sign bit so negative numbers sort first.</li>
 *   <li>raw bytes: appended as is; only meaningful as the last component.</li>
 * </ul>
 * The encoding of a leading sequence of components is a byte prefix of every key that starts with
 * those components, so it can be used with {@code tree.scanPrefix(...)}.
 */
public final class Keys {
    private Keys() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Shortcut: a single string component. */
    public static byte[] of(String s) {
        return builder().string(s).build();
    }

    /** Smallest key strictly greater than every key starting with {@code prefix}; null if there is none. */
    public static byte[] prefixEnd(byte[] prefix) {
        int i = prefix.length - 1;
        while (i >= 0 && prefix[i] == (byte) 0xFF) i--;
        if (i < 0) return null;
        byte[] end = Arrays.copyOf(prefix, i + 1);
        end[i]++;
        return end;
    }

    public static final class Builder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Builder string(String s) {
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                out.write(b);
                if (b == 0) out.write(0xFF);
            }
            out.write(0);
            out.write(1);
            return this;
        }

        public Builder longUnsigned(long v) {
            for (int sh = 56; sh >= 0; sh -= 8) out.write((int) (v >>> sh));
            return this;
        }

        public Builder longSigned(long v) {
            return longUnsigned(v ^ Long.MIN_VALUE);
        }

        public Builder raw(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        public byte[] build() {
            return out.toByteArray();
        }
    }

    /** Decoder for keys produced by {@link Builder}. */
    public static final class Reader {
        private final byte[] k;
        private int pos;

        public Reader(byte[] key) {
            this.k = key;
        }

        public String string() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            while (true) {
                if (pos >= k.length) throw new IllegalArgumentException("unterminated string component");
                byte b = k[pos++];
                if (b != 0) {
                    o.write(b);
                    continue;
                }
                if (pos >= k.length) throw new IllegalArgumentException("truncated escape");
                byte e = k[pos++];
                if (e == 1) break;
                if (e == (byte) 0xFF) o.write(0);
                else throw new IllegalArgumentException("bad escape " + e);
            }
            return o.toString(StandardCharsets.UTF_8);
        }

        public long longUnsigned() {
            if (pos + 8 > k.length) throw new IllegalArgumentException("truncated long");
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (k[pos++] & 0xFF);
            return v;
        }

        public long longSigned() {
            return longUnsigned() ^ Long.MIN_VALUE;
        }

        public byte[] rest() {
            byte[] r = Arrays.copyOfRange(k, pos, k.length);
            pos = k.length;
            return r;
        }
    }
}
