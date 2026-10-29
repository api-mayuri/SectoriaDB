package org.example.sectoriadb.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of the secret keys stored in {@code credentials.json}: AES-256-GCM with the master key from
 * {@code sectoriadb.secrets.key} (32 random bytes, Base64; {@code openssl rand -base64 32}). A stored secret is
 * {@code enc:v1:} followed by Base64 of {@code nonce(12) ciphertext tag(16)}; the access key id is the associated data,
 * so an encrypted secret copied onto another key does not decrypt. Without a master key secrets are stored as given
 * (plain text, protected only by the file permissions and the encryption of the volume).
 *
 * <p>SigV4 needs the secret itself on the server (it is an HMAC key, not a password that could be hashed): the server
 * holds the decrypted secrets in memory, the file and its backups hold only ciphertext.
 */
public final class SecretCipher {

    static final String PREFIX = "enc:v1:";
    private static final int NONCE = 12, TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();
    private static final SecretCipher NONE = new SecretCipher(null);

    private final SecretKey key;

    private SecretCipher(SecretKey key) {
        this.key = key;
    }

    /** The cipher of a Base64 master key; blank or null means no encryption. */
    public static SecretCipher fromBase64(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) return NONE;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKey.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sectoriadb.secrets.key is not valid Base64");
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException("sectoriadb.secrets.key must be 32 bytes (Base64 of 32 random bytes), got " + raw.length);
        }
        return new SecretCipher(new SecretKeySpec(raw, "AES"));
    }

    public boolean enabled() {
        return key != null;
    }

    static boolean isSealed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** The stored form of a secret: encrypted when a master key is set, else unchanged. */
    String seal(String accessKeyId, String secret) {
        if (key == null || secret == null || isSealed(secret)) return secret;
        try {
            byte[] nonce = new byte[NONCE];
            RNG.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(accessKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot encrypt the secret of " + accessKeyId, e);
        }
    }

    /**
     * The secret of a stored value. A plain value is returned as it is (files written before encryption was switched on);
     * an encrypted one without the master key, or with another key, stops the caller: authenticating with a wrong
     * secret would only fail every request later and less clearly.
     */
    String open(String accessKeyId, String stored) {
        if (!isSealed(stored)) return stored;
        if (key == null) {
            throw new IllegalStateException("credentials.json holds encrypted secrets but sectoriadb.secrets.key "
                    + "(SECTORIADB_SECRETS_KEY) is not set");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length < NONCE + TAG_BITS / 8) throw new IllegalStateException("truncated encrypted secret of " + accessKeyId);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE));
            c.updateAAD(accessKeyId.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, NONCE, all.length - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("cannot decrypt the secret of " + accessKeyId
                    + ": sectoriadb.secrets.key is not the key the file was written with, or the entry is damaged");
        }
    }
}
