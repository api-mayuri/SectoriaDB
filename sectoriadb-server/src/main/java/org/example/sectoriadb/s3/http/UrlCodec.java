package org.example.sectoriadb.s3.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Percent-encoding and -decoding of keys and query strings, the way S3 does it (not {@code URLDecoder}). */
public final class UrlCodec {

    private UrlCodec() {}

    /** encoding-type=url: percent-encode everything except unreserved characters and '/'. */
    public static String urlEncodeKey(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                  .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    /** Percent-decoding that leaves '+' untouched (unlike URLDecoder). */
    public static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() + 0 && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                if (c <= 0xFF) {
                    out.write(c);   // the container hands raw request-line bytes over as ISO-8859-1 chars: keep the byte
                } else {
                    byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.write(b, 0, b.length);
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes one query-string name or value: '+' is a space, %XX is a byte, the bytes must be valid UTF-8.
     * A lone '%', bad hex or invalid UTF-8 is an {@link IllegalArgumentException} (unlike URLDecoder this is meant to be caught
     * and answered 400: it must never surface as a 500).
     */
    public static String decodeQueryComponent(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length() || !isHex(s.charAt(i + 1)) || !isHex(s.charAt(i + 2))) {
                    throw new IllegalArgumentException("malformed percent-encoding");
                }
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else if (c == '+') {
                out.write(' ');
            } else if (c <= 0xFF) {
                out.write(c);
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF-8 in query string", e);
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
