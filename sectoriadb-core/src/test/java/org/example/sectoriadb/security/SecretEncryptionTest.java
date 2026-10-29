package org.example.sectoriadb.security;

import org.example.sectoriadb.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Secrets in credentials.json are ciphertext when a master key is set; the server still sees the plain secret. */
class SecretEncryptionTest {

    @TempDir
    Path meta;

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String SECRET = "a-secret-that-must-not-be-on-disk-0123";

    static String newKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    CredentialService service(String masterKey) {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(meta.toString());
        props.getSecrets().setKey(masterKey);
        return new CredentialService(new JsonCredentialRepository(MAPPER, props));
    }

    String file() throws Exception {
        return Files.readString(meta.resolve("credentials.json"));
    }

    @Test
    void theFileHoldsCiphertextAndTheServerThePlainSecret() throws Exception {
        String key = newKey();
        service(key).register("ENCKEY0000000001", SECRET, "enc");
        assertFalse(file().contains(SECRET), file());
        assertTrue(file().contains("enc:v1:"), file());
        assertEquals(SECRET, service(key).findByAccessKeyId("ENCKEY0000000001").orElseThrow().getSecretKey(), "another process with the key");
    }

    @Test
    void plainSecretsOfAnOlderFileAreEncryptedAtTheFirstStartWithAKey() throws Exception {
        service(null).register("PLAINKEY00000001", SECRET, "old");
        assertTrue(file().contains(SECRET));
        String key = newKey();
        CredentialService s = service(key);
        assertEquals(1, s.sealPlaintextSecrets());
        assertFalse(file().contains(SECRET));
        assertEquals(SECRET, s.findByAccessKeyId("PLAINKEY00000001").orElseThrow().getSecretKey());
        assertEquals(0, s.sealPlaintextSecrets(), "nothing left to encrypt");
    }

    @Test
    void anEncryptedFileWithoutTheKeyOrWithAnotherKeyIsRefused() throws Exception {
        service(newKey()).register("ENCKEY0000000002", SECRET, "enc");
        IllegalStateException noKey = assertThrows(IllegalStateException.class, () -> service(null).listAll());
        assertTrue(noKey.getMessage().contains("SECTORIADB_SECRETS_KEY"), noKey.getMessage());
        IllegalStateException wrong = assertThrows(IllegalStateException.class, () -> service(newKey()).listAll());
        assertTrue(wrong.getMessage().contains("not the key"), wrong.getMessage());
    }

    @Test
    void aSecretMovedOntoAnotherKeyDoesNotDecrypt() throws Exception {
        String key = newKey();
        CredentialService s = service(key);
        s.register("ENCKEY0000000003", SECRET, "a");
        s.register("ENCKEY0000000004", "another-secret-of-the-second-0123", "b");
        // swap the stored ciphertexts: the access key id is the associated data of each
        var root = (tools.jackson.databind.node.ArrayNode) MAPPER.readTree(file());
        String c0 = root.get(0).get("secretKey").asText(), c1 = root.get(1).get("secretKey").asText();
        ((tools.jackson.databind.node.ObjectNode) root.get(0)).put("secretKey", c1);
        ((tools.jackson.databind.node.ObjectNode) root.get(1)).put("secretKey", c0);
        Files.writeString(meta.resolve("credentials.json"), MAPPER.writeValueAsString(root));
        assertThrows(IllegalStateException.class, () -> service(key).listAll());
    }

    @Test
    void aMalformedMasterKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service("not base64 !!"));
        assertThrows(IllegalArgumentException.class, () -> service(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
