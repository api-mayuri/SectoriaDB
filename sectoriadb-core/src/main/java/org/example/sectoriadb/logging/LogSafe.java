package org.example.sectoriadb.logging;

/**
 * Client-controlled text (bucket and object names, header values) in a log line. An object key may contain any character
 * a client can percent-encode, among them CR and LF, which would let a client forge log lines (the log pattern has no
 * escaping). {@link #clean} escapes every control character and caps the length; use it for every such value that is
 * logged, whatever the level.
 */
public final class LogSafe {

    private static final int MAX = 256;
    private static final int LINE_SEPARATOR = 0x2028;
    private static final int PARAGRAPH_SEPARATOR = 0x2029;

    private LogSafe() {}

    public static String clean(Object value) {
        if (value == null) return "null";
        String s = value.toString();
        StringBuilder out = new StringBuilder(Math.min(s.length(), MAX) + 8);
        int i = 0;
        for (; i < s.length() && out.length() < MAX; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        if (i < s.length()) out.append("...(").append(s.length() - i).append(" more chars)");
        return out.toString();
    }
}
