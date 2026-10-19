package org.example.sectoriadb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The syntax of SECTORIADB_S3_CREDENTIALS entries (no Spring context). */
class EnvCredentialsTest {

    @Test
    void accessSecretAndAnOptionalDescriptionAfterABar() {
        var plain = EnvCredentials.parse("AKID0000000000001:secret/with+base64==chars");
        assertEquals("AKID0000000000001", plain.accessKeyId());
        assertEquals("secret/with+base64==chars", plain.secretKey());
        assertEquals("env", plain.description());

        var described = EnvCredentials.parse(" AKID0000000000001 : secretsecretsecret | prod key ");
        assertEquals("secretsecretsecret", described.secretKey());
        assertEquals("prod key", described.description());
    }

    @Test
    void aColonInTheSecretIsRefusedInsteadOfCuttingOrLengtheningTheSecret() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> EnvCredentials.parse("AKID0000000000001:abcdefghijklmnop:tail:more"));
        assertFalse(e.getMessage().contains("abcdefghijklmnop"), "the message must not echo the entry");
        assertFalse(e.getMessage().contains("tail"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EnvCredentials.parse("AKID0000000000001:sec:ret|desc"));
        assertThrows(IllegalArgumentException.class, () -> EnvCredentials.parse("no-colon-at-all"));
        assertThrows(IllegalArgumentException.class, () -> EnvCredentials.parse(":secret"));
    }
}
