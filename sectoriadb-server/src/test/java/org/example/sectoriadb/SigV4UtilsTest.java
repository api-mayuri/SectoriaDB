package org.example.sectoriadb;

import org.example.sectoriadb.s3.auth.SigV4Utils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Canonical URI / query encoding of SigV4. */
class SigV4UtilsTest {

    /** Found by bench/faults/security.py: keys with emoji (surrogate pairs) failed with SignatureDoesNotMatch. */
    @Test
    void supplementaryCharactersAreEncodedAsOneUtf8Sequence() {
        assertEquals("%F0%9F%98%80/%F0%9F%92%A9", SigV4Utils.canonicalizeUri("😀/💩"));
        assertEquals("/%F0%9F%98%80", SigV4Utils.canonicalizeUri("/%F0%9F%98%80"));
        assertEquals("%F0%9F%98%80", SigV4Utils.uriEncode("😀", true));
    }

    @Test
    void bmpAndAsciiEncodingIsUnchanged() {
        assertEquals("/caf%C3%A9/a%20b/a%2Bb/~-._", SigV4Utils.canonicalizeUri("/café/a b/a+b/~-._"));
        assertEquals("%E6%97%A5%E6%9C%AC", SigV4Utils.uriEncode("日本", true));
        assertEquals("a%2Fb", SigV4Utils.uriEncode("a/b", true));
        assertEquals("a/b", SigV4Utils.uriEncode("a/b", false));
        assertEquals("a%3Db=", SigV4Utils.canonicalizeQuery("a%3Db="));
        assertEquals("delete=", SigV4Utils.canonicalizeQuery("delete"));
    }
}
