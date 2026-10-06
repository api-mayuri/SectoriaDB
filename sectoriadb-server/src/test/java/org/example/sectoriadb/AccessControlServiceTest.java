package org.example.sectoriadb;

import org.example.sectoriadb.s3.auth.SigV4Utils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AccessControlService and SigV4Utils.
 */
public class AccessControlServiceTest {

    @Test
    public void testCanonicalizeUriWithPlus() {
        // Test that '+' in URI is percent-encoded as %2B (not converted to space)
        // This is correct per AWS S3 signature version 4 spec
        String uri = "/bucket/key+value";
        String result = SigV4Utils.canonicalizeUri(uri);
        // Plus should be encoded as %2B, NOT converted to space
        assertTrue(result.contains("%2B"), "Plus sign should be encoded as %2B in canonical URI");
        assertFalse(result.contains(" "), "Plus sign should not be converted to space");
    }

    @Test
    public void testCanonicalizeUriWithPercent20() {
        // Test that %20 (encoded space) is preserved
        String uri = "/bucket/key%20value";
        String result = SigV4Utils.canonicalizeUri(uri);
        // %20 should be decoded to space, then re-encoded as %20
        assertTrue(result.contains("%20"),
                "Percent-encoded space should remain as %20");
    }

    @Test
    public void testCanonicalizeUriWithPlusAndPercent20() {
        // Test mixed encoding: + should become %2B, %20 should remain %20
        String uri = "/bucket/key+test%20space";
        String result = SigV4Utils.canonicalizeUri(uri);
        assertTrue(result.contains("%2B"), "Plus sign should be encoded as %2B");
        assertTrue(result.contains("%20"), "Space should remain as %20");
    }

    @Test
    public void testCanonicalizeHeadersWithWhitespace() {
        // Test that consecutive spaces are collapsed into one
        String testValue = "Bearer   token    value";
        assertTrue(SigV4Utils.canonicalizeHeaders(
                java.util.Map.of("authorization", testValue)
        ).contains("Bearer token value"),
                "Consecutive spaces should be collapsed");
    }

    @Test
    public void testCanonicalizeHeadersTrimsValues() {
        // Test that leading/trailing whitespace is trimmed
        String testValue = "  value  ";
        String result = SigV4Utils.canonicalizeHeaders(
                java.util.Map.of("x-custom-header", testValue)
        );
        assertTrue(result.contains(":value\n"),
                "Header values should be trimmed");
    }

    @Test
    public void testHexEncode() {
        // Test hex encoding
        byte[] data = new byte[]{(byte) 0xAB, (byte) 0xCD, (byte) 0xEF};
        String result = SigV4Utils.hexEncode(data);
        assertEquals("abcdef", result, "Hex encoding should produce lowercase hex");
    }

    @Test
    public void testSigV4CanonicalQueryStringRemovesSignature() {
        // For presigned URLs, X-Amz-Signature should be removed during canonicalization
        // This is tested implicitly through the filter flow
        String queryString = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123&other=value";
        String result = SigV4Utils.canonicalizeQuery(queryString);
        assertFalse(result.contains("X-Amz-Signature"),
                "X-Amz-Signature should be excluded from canonical query string");
        assertTrue(result.contains("X-Amz-Algorithm"),
                "Other parameters should be included");
    }
}
