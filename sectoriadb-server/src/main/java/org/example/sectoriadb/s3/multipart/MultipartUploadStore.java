package org.example.sectoriadb.s3.multipart;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.s3.error.S3Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Owns the {@code <data-dir>/.multipart} directory (interim filesystem design, see docs/architecture/08-observability-bench.md;
 * stage 13 moves it into the metadata store) and the lock of every upload.
 *
 * <p>One directory per upload: {@code meta.properties} (see {@link UploadMeta}), {@code state.properties}
 * ({@code state=OPEN|COMPLETING|COMPLETED}), {@code etags.properties} and {@code checksums.properties} (one entry per
 * part) and the part files {@code part-NNNNN}. A per-upload lock guards every state transition and every change of the part
 * records, so a part file and its ETag/checksum are always updated together, a second Complete cannot run next to the
 * first, and Abort / UploadPart cannot delete or replace files under a running Complete. The lock is held only for those
 * short critical sections, never while a body is transferred or while Complete copies the data.
 */
@Component
public class MultipartUploadStore {

    private static final Logger log = LoggerFactory.getLogger(MultipartUploadStore.class);

    static final String META_FILE = "meta.properties";
    static final String STATE_FILE = "state.properties";
    static final String ETAGS_FILE = "etags.properties";
    static final String CHECKSUMS_FILE = "checksums.properties";

    /** Strict canonical UUID: rules out path separators, "..", and the lenient forms UUID.fromString accepts. */
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    enum State { OPEN, COMPLETING, COMPLETED }

    /** A validated upload: its id and its directory. */
    record Upload(String id, Path dir) {}

    /** One open upload as ListMultipartUploads shows it. */
    record UploadInfo(String key, String uploadId, Instant initiated) {}

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final StorageProperties props;
    private Path root;

    public MultipartUploadStore(StorageProperties props) {
        this.props = props;
    }

    /** The root directory; created on first use. */
    synchronized Path root() throws IOException {
        if (root == null) {
            root = Path.of(props.getDataDir()).resolve(".multipart");
            Files.createDirectories(root);
        }
        return root;
    }

    /** The root directory if it exists (the cleanup must not create it). */
    Path existingRoot() {
        Path dir = Path.of(props.getDataDir()).resolve(".multipart");
        return Files.exists(dir) ? dir : null;
    }

    boolean fsync() {
        return props.isFsync();
    }

    // ── locks ─────────────────────────────────────────────────────────────────

    ReentrantLock lockOf(String uploadId) {
        return locks.computeIfAbsent(uploadId, k -> new ReentrantLock());
    }

    void forget(String uploadId) {
        locks.remove(uploadId);
    }

    // ── create / open ─────────────────────────────────────────────────────────

    /** Creates the directory of a new upload and records its metadata. */
    Upload create(UploadMeta meta) throws IOException {
        String uploadId = UUID.randomUUID().toString();
        Path dir = root().resolve(uploadId);
        Files.createDirectories(dir);
        try (OutputStream os = Files.newOutputStream(dir.resolve(META_FILE))) {
            meta.toProperties().store(os, null);
        }
        return new Upload(uploadId, dir);
    }

    /**
     * Validates an upload id and its ownership: an upload id is only valid for the bucket and key it was created for.
     *
     * @param requestKey the object key of the request URI, parsed only once the upload id and its directory checked out
     * @param allowCompleted a finished upload is still addressable (a retried Complete gets the stored answer); every
     *                       other operation on it is NoSuchUpload
     */
    Upload open(String uploadId, String bucket, Supplier<String> requestKey, boolean allowCompleted) throws IOException {
        if (uploadId == null || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        Path dir = root().resolve(uploadId);
        if (!dir.normalize().getParent().equals(root().normalize())) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        if (!Files.isDirectory(dir)) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(dir.resolve(META_FILE))) {
            meta.load(is);
        } catch (IOException e) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        if (!bucket.equals(meta.getProperty("bucket")) || !requestKey.get().equals(meta.getProperty("key"))) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        Upload upload = new Upload(uploadId, dir);
        if (!allowCompleted && stateOf(upload) == State.COMPLETED) {
            throw S3Exception.noSuchUpload(uploadId);
        }
        return upload;
    }

    UploadMeta readMeta(Upload upload) throws IOException {
        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(upload.dir().resolve(META_FILE))) {
            meta.load(is);
        }
        return UploadMeta.from(meta);
    }

    // ── state ─────────────────────────────────────────────────────────────────

    Properties readState(Upload upload) throws IOException {
        return readProps(upload.dir().resolve(STATE_FILE));
    }

    State stateOf(Upload upload) throws IOException {
        return stateIn(readState(upload));
    }

    /** The state recorded in a state file; anything unknown counts as OPEN. */
    static State stateIn(Properties state) {
        return switch (state.getProperty("state", State.OPEN.name())) {
            case "COMPLETING" -> State.COMPLETING;
            case "COMPLETED" -> State.COMPLETED;
            default -> State.OPEN;
        };
    }

    void writeState(Upload upload, Properties state) throws IOException {
        writeProps(upload.dir().resolve(STATE_FILE), state, props.isFsync());
    }

    void writeState(Upload upload, State state) throws IOException {
        Properties p = new Properties();
        p.setProperty("state", state.name());
        writeState(upload, p);
    }

    /** Must be called with the upload lock held: only an OPEN upload accepts parts. */
    void requireOpen(Upload upload) throws IOException {
        State st = stateOf(upload);
        if (st == State.COMPLETING) throw conflict();
        if (st == State.COMPLETED) throw S3Exception.noSuchUpload(upload.id());
    }

    static S3Exception conflict() {
        return new S3Exception(HttpStatus.CONFLICT, "OperationAborted",
                "A conflicting operation is currently in progress against this resource. Please try again.");
    }

    // ── parts ─────────────────────────────────────────────────────────────────

    Path partFile(Upload upload, int partNumber) {
        return upload.dir().resolve(String.format("part-%05d", partNumber));
    }

    /** A fresh temp file next to the part file: a part is written there and moved into place atomically. */
    Path newTempPartFile(Upload upload, int partNumber) {
        return upload.dir().resolve(String.format("part-%05d.tmp.%s", partNumber,
                UUID.randomUUID().toString().substring(0, 8)));
    }

    /** Makes a finished temp file durable if {@code sectoriadb.fsync} is on. */
    void forceIfConfigured(Path file) throws IOException {
        if (props.isFsync()) force(file);
    }

    /** Moves a finished temp file into place and records its ETag, atomically with respect to other operations on the upload. */
    void installPart(Upload upload, int partNumber, Path tmpFile, String etag, String checksumEntry) throws IOException {
        ReentrantLock lock = lockOf(upload.id());
        lock.lock();
        try {
            requireOpen(upload);
            Files.move(tmpFile, partFile(upload, partNumber), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            recordPart(upload, partNumber, etag, checksumEntry);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** Records the ETag (and checksum) of a part together, under the upload lock the caller holds. */
    private void recordPart(Upload upload, int partNumber, String etag, String checksum) throws IOException {
        Properties etags = readProps(upload.dir().resolve(ETAGS_FILE));
        etags.setProperty(String.valueOf(partNumber), etag);
        Properties sums = readProps(upload.dir().resolve(CHECKSUMS_FILE));
        if (checksum != null) sums.setProperty(String.valueOf(partNumber), checksum);
        else sums.remove(String.valueOf(partNumber));
        boolean fsync = props.isFsync();
        writeProps(upload.dir().resolve(CHECKSUMS_FILE), sums, fsync);
        writeProps(upload.dir().resolve(ETAGS_FILE), etags, fsync);   // the ETag last: a part is visible only with both
    }

    /** Part number to hex MD5 of every part uploaded so far. */
    Properties partEtags(Upload upload) throws IOException {
        return readProps(upload.dir().resolve(ETAGS_FILE));
    }

    /** Part number to {@code ALGORITHM:base64} of the parts that carry a checksum. */
    Properties partChecksums(Upload upload) throws IOException {
        return readProps(upload.dir().resolve(CHECKSUMS_FILE));
    }

    /** Removes the part files and part records of a finished upload, keeping meta.properties and the state tombstone. */
    void deleteParts(Upload upload) {
        try (var files = Files.list(upload.dir())) {
            files.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith("part-") || n.equals(ETAGS_FILE) || n.equals(CHECKSUMS_FILE);
            }).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException e) { log.warn("Could not delete {}: {}", f, e.toString()); }
            });
        } catch (IOException e) {
            log.warn("Could not clean up the parts of {}: {}", upload.dir().getFileName(), e.toString());
        }
    }

    /** AbortMultipartUpload: not while a Complete is running, not after it finished. */
    void abort(Upload upload) throws IOException {
        ReentrantLock lock = lockOf(upload.id());
        lock.lock();
        try {
            requireOpen(upload);
            deleteTree(upload.dir());
        } finally {
            lock.unlock();
        }
        forget(upload.id());
    }

    // ── listing ───────────────────────────────────────────────────────────────

    /** The open uploads of a bucket whose key starts with {@code prefix} and sorts after {@code keyMarker}, by key. */
    List<UploadInfo> listOpen(String bucket, String prefix, String keyMarker) throws IOException {
        Path rootDir = root();
        List<String> uploadIds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    uploadIds.add(entry.getFileName().toString());
                }
            }
        }
        List<UploadInfo> uploads = new ArrayList<>();
        for (String uploadId : uploadIds) {
            try {
                Properties meta = readProps(rootDir.resolve(uploadId).resolve(META_FILE));
                if (!bucket.equals(meta.getProperty("bucket"))) continue;
                String key = meta.getProperty("key");
                if (key == null || !key.startsWith(prefix)) continue;
                if (!keyMarker.isEmpty() && key.compareTo(keyMarker) <= 0) continue;
                if (stateOf(new Upload(uploadId, rootDir.resolve(uploadId))) == State.COMPLETED) continue;   // a tombstone, not an open upload
                long initiatedMs = Long.parseLong(meta.getProperty("initiatedAt", "0"));
                uploads.add(new UploadInfo(key, uploadId, Instant.ofEpochMilli(initiatedMs)));
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping unreadable multipart upload {}: {}", uploadId, e.toString());   // one corrupt entry must not break the listing
            }
        }
        uploads.sort(Comparator.comparing(UploadInfo::key));
        return uploads;
    }

    // ── files ─────────────────────────────────────────────────────────────────

    static Properties readProps(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            p.load(is);
        } catch (NoSuchFileException e) {
            // absent = empty
        }
        return p;
    }

    /** Writes a properties file atomically (temp file, fsync, rename, fsync of the directory). */
    static void writeProps(Path file, Properties props, boolean fsync) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp)) {
            props.store(os, null);
        }
        if (fsync) force(tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (fsync) forceDir(file.getParent());
    }

    private static void force(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    private static void forceDir(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // directories cannot be fsynced on every platform; the rename is still atomic
        }
    }

    /** Best effort: whatever cannot be deleted now is retried by the next cleanup sweep. */
    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder())
             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { /* retried next sweep */ } });
        } catch (IOException ignored) {
            // retried next sweep
        }
    }
}
