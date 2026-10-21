package org.example.sectoriadb.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSafeTest {

    @Test
    void lineBreaksAndOtherControlCharactersAreEscaped() {
        String forged = "photo.jpg\n2026-01-01 00:00:00 ERROR forged line\r" + (char) 0 + (char) 0x1b + "[31m"
                + (char) 0x2028 + "end";
        String clean = LogSafe.clean(forged);
        assertFalse(clean.contains("\n") || clean.contains("\r") || clean.indexOf(0) >= 0
                || clean.indexOf(0x1b) >= 0 || clean.indexOf(0x2028) >= 0, clean);
        assertTrue(clean.startsWith("photo.jpg\\n2026-01-01"), clean);
        assertTrue(clean.contains("\\u0000") && clean.contains("\\u001b") && clean.contains("\\u2028"), clean);
    }

    @Test
    void ordinaryNamesAreUntouchedAndLongOnesAreCapped() {
        assertEquals("a/b c/;%.txt", LogSafe.clean("a/b c/;%.txt"));
        assertEquals("caf" + (char) 0xe9, LogSafe.clean("caf" + (char) 0xe9));
        assertEquals("null", LogSafe.clean(null));
        String longName = "x".repeat(5000);
        String clean = LogSafe.clean(longName);
        assertTrue(clean.length() < 300, "length " + clean.length());
        assertTrue(clean.endsWith("more chars)"), clean);
    }
}
