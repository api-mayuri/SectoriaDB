package org.example.sectoriadb.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.sectoriadb.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsonCredentialRepository implements CredentialRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonCredentialRepository.class);
    private static final TypeReference<List<CredentialEntity>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final Path dataFile;
    private final SecretCipher cipher;
    /** True when the last read found secrets in plain text although a master key is set (rewritten by {@link #sealPlaintext}). */
    private volatile boolean plaintextOnDisk;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * The cached view and the identity of the file it was read from. Every write is an atomic replace, so a new version is
     * a new file: comparing the file key (inode) as well as the full-precision time and the size never misses a change,
     * even two writes of the same size within one timestamp tick (a missed policy or disable would keep a key's old rights).
     */
    private record Snapshot(List<CredentialEntity> entries, Object fileKey, FileTime modifiedTime, long fileSize) {

        static Snapshot of(List<CredentialEntity> entries, BasicFileAttributes attrs) {
            return new Snapshot(entries, attrs.fileKey(), attrs.lastModifiedTime(), attrs.size());
        }

        boolean sameFile(BasicFileAttributes attrs) {
            return Objects.equals(fileKey, attrs.fileKey()) && Objects.equals(modifiedTime, attrs.lastModifiedTime())
                    && fileSize == attrs.size();
        }
    }

    private static final Snapshot ABSENT = new Snapshot(List.of(), null, null, -1);

    private volatile Snapshot snapshot = null;
    /**
     * When the file was last compared with the snapshot. Every authenticated request reads the credentials; a stat per
     * request was a system call on the hot path. Changes of this process are published at once; changes of another
     * process (the admin CLI) are seen within {@link #RECHECK_NANOS}.
     */
    private volatile long checkedAtNanos;
    static final long RECHECK_NANOS = 1_000_000_000L;
    private final long recheckNanos;

    public JsonCredentialRepository(ObjectMapper mapper, StorageProperties props) {
        this.mapper   = mapper;
        this.dataFile = Path.of(props.getMetaDir(), "credentials.json");
        this.cipher   = SecretCipher.fromBase64(props.getSecrets().getKey());
        this.recheckNanos = RECHECK_NANOS;
    }

    /** As the main constructor, with another interval between file checks (tests: 0 checks on every read). */
    JsonCredentialRepository(ObjectMapper mapper, StorageProperties props, long recheckNanos) {
        this.mapper   = mapper;
        this.dataFile = Path.of(props.getMetaDir(), "credentials.json");
        this.cipher   = SecretCipher.fromBase64(props.getSecrets().getKey());
        this.recheckNanos = recheckNanos;
    }

    /** Whether secrets are written encrypted (a master key is configured). */
    @Override
    public boolean encrypting() {
        return cipher.enabled();
    }

    /**
     * Rewrites the file with encrypted secrets if it still holds plain ones and a master key is set (the first start
     * after the key was configured). Returns the number of secrets that were in plain text.
     */
    @Override
    public int sealPlaintext() {
        if (!cipher.enabled()) return 0;
        lock.writeLock().lock();
        try {
            List<CredentialEntity> all = readAll();
            if (!plaintextOnDisk) return 0;
            int plain = (int) countPlainOnDisk();
            writeAll(all);
            publish(all);
            plaintextOnDisk = false;
            log.info("Encrypted {} secret key(s) in credentials.json with the master key", plain);
            return plain;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private long countPlainOnDisk() {
        try {
            JsonNode root = mapper.readTree(dataFile.toFile());
            long n = 0;
            for (JsonNode e : root) if (!SecretCipher.isSealed(e.path("secretKey").asText(null))) n++;
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public CredentialEntity save(CredentialEntity entity) {
        lock.writeLock().lock();
        try {
            List<CredentialEntity> all = new ArrayList<>(readAll());   // a copy: the published snapshot stays untouched
            all.removeIf(e -> e.getAccessKeyId().equals(entity.getAccessKeyId()));
            all.add(entity);
            writeAll(all);
            publish(all);                                               // only after the durable atomic move
            log.debug("Saved credential: id={}", entity.getAccessKeyId());
            return entity;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CredentialEntity> findByAccessKeyId(String accessKeyId) {
        lock.readLock().lock();
        try {
            return readAll().stream()
                    .filter(e -> e.getAccessKeyId().equals(accessKeyId))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CredentialEntity> findAll() {
        lock.readLock().lock();
        try {
            return readAll();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteByAccessKeyId(String accessKeyId) {
        lock.writeLock().lock();
        try {
            List<CredentialEntity> all = new ArrayList<>(readAll());
            all.removeIf(e -> e.getAccessKeyId().equals(accessKeyId));
            writeAll(all);
            publish(all);
            log.debug("Deleted credential: id={}", accessKeyId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean existsByAccessKeyId(String accessKeyId) {
        return findByAccessKeyId(accessKeyId).isPresent();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** The current credentials as an immutable list; reloads the file when its time or size changed (the admin CLI edits it too). */
    private List<CredentialEntity> readAll() {
        Snapshot cached = snapshot;
        long now = System.nanoTime();
        if (cached != null && now - checkedAtNanos < recheckNanos) return cached.entries();
        checkedAtNanos = now;
        if (!Files.exists(dataFile)) {
            snapshot = ABSENT;
            return ABSENT.entries();
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(dataFile, BasicFileAttributes.class);
            Snapshot s = snapshot;
            if (s != null && s != ABSENT && s.sameFile(attrs)) {
                return s.entries();
            }

            List<CredentialEntity> raw = mapper.readValue(dataFile.toFile(), LIST_TYPE);
            boolean plain = false;
            for (CredentialEntity c : raw) {
                if (!SecretCipher.isSealed(c.getSecretKey())) plain = true;
                c.setSecretKey(cipher.open(c.getAccessKeyId(), c.getSecretKey()));   // the server needs the secret (HMAC key)
            }
            plaintextOnDisk = plain && cipher.enabled();
            List<CredentialEntity> loaded = List.copyOf(raw);
            snapshot = Snapshot.of(loaded, attrs);
            return loaded;
        } catch (IOException e) {
            log.error("Failed to read credentials.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** Makes the list just written the cached view; when the file cannot be stat'ed the cache is dropped (reloaded next time). */
    private void publish(List<CredentialEntity> written) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(dataFile, BasicFileAttributes.class);
            snapshot = Snapshot.of(List.copyOf(written), attrs);
        } catch (IOException e) {
            snapshot = null;
        }
    }

    /**
     * Durable replace: write a temp file, fsync it, atomically move it over credentials.json, fsync the directory.
     * A crash at any point leaves either the old or the new complete file, and after the move returns the new
     * name survives a power loss. (Credentials stay in JSON because the admin CLI runs as a separate process next to
     * the server, which the single-process metastore file cannot serve.)
     */
    private void writeAll(List<CredentialEntity> entities) {
        try {
            Path dir = dataFile.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tempFile = Files.createTempFile(dir, "credentials", ".tmp");
            try {
                ArrayNode stored = mapper.valueToTree(entities);   // a copy: the entities keep the plain secret for the server
                for (JsonNode e : stored) {
                    ObjectNode o = (ObjectNode) e;
                    o.put("secretKey", cipher.seal(o.path("accessKeyId").asText(), o.path("secretKey").asText(null)));
                }
                byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(stored);
                try (FileChannel ch = FileChannel.open(tempFile, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buf = ByteBuffer.wrap(json);
                    while (buf.hasRemaining()) ch.write(buf);
                    ch.force(true);
                }
                Files.move(tempFile, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }
            syncDirectory(dir);
        } catch (IOException e) {
            log.error("Failed to write credentials.json: {}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** fsync of the directory makes the rename itself durable; not supported on every platform (e.g. Windows). */
    private static void syncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Directory fsync not available for {}: {}", dir, e.getMessage());
        }
    }
}
