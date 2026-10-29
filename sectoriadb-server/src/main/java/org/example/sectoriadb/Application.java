package org.example.sectoriadb;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.security.CredentialService;
import org.example.sectoriadb.storage.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Application.class);

        // CLI mode: first non-option arg is a credential command (mk-key, keys, ...).
        // System properties win over application.properties, so no web server is started.
        // Key commands can run next to an already-running server (shared credentials.json); the others need it stopped.
        boolean cli = isCliMode(args);
        if (cli) {
            failFastIfStoreIsHeld(args);
            System.setProperty("spring.main.web-application-type", "none");
            System.setProperty("spring.main.banner-mode", "off");
            System.setProperty("spring.shell.interactive.enabled", "false");
            System.setProperty("sectoriadb.auto-resize.enabled", "false");
            System.setProperty("sectoriadb.gc.enabled", "false");
            // The metadata store is single-process: open it only if a command needs it (credential commands do not),
            // so they keep working while the server holds the file.
            System.setProperty("sectoriadb.metastore.lazy", "true");
            System.setProperty("logging.level.root", "WARN");
            System.setProperty("logging.level.org.example.sectoriadb", "WARN");
        }

        var ctx = app.run(args);
        if (cli) {
            // Scheduler threads would keep the JVM alive after the command has finished
            System.exit(SpringApplication.exit(ctx));
        }
    }

    /**
     * Commands that work next to a running server: they only touch credentials.json (and never open the metastore).
     */
    static final Set<String> ONLINE_COMMANDS = Set.of(
            "mk-key", "keys", "rm-key", "enable-key", "disable-key", "key-policy");

    /**
     * Commands that need the single-process metastore: they work only while the server is STOPPED (or from the server's
     * own interactive shell). Started next to a running server they fail at once with a clear message instead of a
     * stack trace deep in the context start-up.
     */
    static final Set<String> OFFLINE_ONLY_COMMANDS = Set.of(
            "bucket-acl", "object-acl", "public-list", "verify-all", "verify");

    static boolean isCliMode(String[] args) {
        String command = firstCommand(args);
        return command != null && (ONLINE_COMMANDS.contains(command) || OFFLINE_ONLY_COMMANDS.contains(command));
    }

    static String firstCommand(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("-")) return arg;
        }
        return null;
    }

    /** Exits with a one-line explanation when an offline-only command is started while a server holds the metastore. */
    private static void failFastIfStoreIsHeld(String[] args) {
        String command = firstCommand(args);
        if (command == null || !OFFLINE_ONLY_COMMANDS.contains(command)) return;
        String metaDir = System.getProperty("sectoriadb.meta-dir");
        if (metaDir == null) {
            for (String arg : args) {
                if (arg.startsWith("--sectoriadb.meta-dir=")) metaDir = arg.substring("--sectoriadb.meta-dir=".length());
            }
        }
        if (metaDir == null) metaDir = System.getenv("SECTORIADB_META_DIR");
        if (metaDir == null) metaDir = "./sectoriadb-meta";
        Path file = Path.of(metaDir).resolve("sectoria.db");
        if (!Files.exists(file)) return;
        try (var ch = FileChannel.open(file, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            FileLock lock = null;
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                // held inside this JVM: treated as held
            }
            if (lock == null) {
                System.err.println("Error: '" + command + "' needs exclusive access to the metadata store " + file.toAbsolutePath()
                        + ", which the running server holds.\nStop the server first, or run the command in the server's interactive shell"
                        + " (docker attach sectoriadb). Only the key commands (mk-key, keys, rm-key, enable-key, disable-key, key-policy)"
                        + " work next to a running server.");
                System.exit(2);
            }
            lock.release();
        } catch (IOException e) {
            // let the normal start-up report the problem
        }
    }

    /**
     * Removes upload spool files left behind by a crash or kill -9. Runs once all beans exist but before the web server
     * accepts connections, so no upload of this process can own a spool file yet (an ApplicationRunner would run after
     * the port is open, and behind a blocking interactive shell runner possibly never).
     * Not run in CLI mode: a command next to a running server must not delete that server's live uploads.
     */
    @Bean
    public SmartInitializingSingleton cleanUploadSpool(ObjectWriter writer, StorageProperties props) {
        return () -> {
            if (System.getProperty("sectoriadb.metastore.lazy") != null) return;
            // credentials*.tmp: the plaintext temp file of an interrupted credentials.json update
            try (var files = Files.newDirectoryStream(Path.of(props.getMetaDir()), "credentials*.tmp")) {
                for (Path f : files) Files.deleteIfExists(f);
            } catch (IOException e) {
                log.debug("No credentials temp files cleaned in {}: {}", props.getMetaDir(), e.toString());
            }
            Path dir = writer.uploadTmpDir();
            int removed = 0;
            // upload (PutObject), copy (CopyObject), part (UploadPart), partcopy (UploadPartCopy)
            try (var files = Files.newDirectoryStream(dir, "sectoriadb-{upload,copy,part,partcopy}-*.tmp")) {
                for (Path f : files) {
                    if (Files.deleteIfExists(f)) removed++;
                }
            } catch (IOException e) {
                log.warn("Could not clean the upload spool directory {}: {}", dir, e.toString());
            }
            if (removed > 0) log.info("Removed {} stale upload spool file(s) from {}", removed, dir);
        };
    }

    /**
     * Seeds credentials declared in the environment ({@code SECTORIADB_S3_CREDENTIALS=ak:sk[|desc],...} and the legacy
     * access-key / secret-key pair). The environment is the source of truth for the keys it declares: an unknown key is
     * registered, a key whose secret changed gets the new secret (the enabled flag and description are kept), an
     * identical key is left alone silently. Keys not declared in the environment are never touched. Secrets are never
     * logged, not even in error messages (entries are identified by position only).
     *
     * <p>Runs before the web server accepts connections (so there is no window where declared keys are rejected) and
     * independently of application runners: the interactive shell is itself a runner that blocks on the console, and
     * keys seeded from a runner were never applied when the shell started first (the default in docker-compose).
     */
    @Bean
    public SmartInitializingSingleton bootstrapCredential(CredentialService credentialService, StorageProperties props) {
        return () -> {
            if (System.getProperty("sectoriadb.metastore.lazy") != null) return;   // CLI mode: no seeding
            // reads the file once: an encrypted file without the right master key stops the start here, with the reason
            credentialService.sealPlaintextSecrets();
            if (!credentialService.encryptingSecrets()) {
                log.warn("Secret keys are stored in plain text in credentials.json: set SECTORIADB_SECRETS_KEY "
                        + "(openssl rand -base64 32) to store them encrypted");
            }
            StorageProperties.S3 s3 = props.getS3();
            int index = 0;
            if (s3.getCredentials() != null) {
                for (String cred : s3.getCredentials()) {
                    index++;
                    if (cred == null || cred.isBlank()) continue;
                    String ak;
                    String sk;
                    String desc;
                    try {
                        EnvCredentials parsed = EnvCredentials.parse(cred);
                        ak = parsed.accessKeyId();
                        sk = parsed.secretKey();
                        desc = parsed.description();
                    } catch (IllegalArgumentException e) {
                        // names the rule only, never the text of the entry
                        log.warn("Ignoring credential #{} from the environment: {}", index, e.getMessage());
                        continue;
                    }
                    try {
                        var existing = credentialService.findByAccessKeyId(ak);
                        if (existing.isEmpty()) {
                            credentialService.register(ak, sk, desc);
                        } else if (!existing.get().getSecretKey().equals(sk)) {
                            credentialService.ensureCredential(ak, sk);
                        } else {
                            log.debug("Credential from the environment unchanged: accessKeyId={}", ak);
                        }
                        grantFullAccessIfNoPolicy(credentialService, ak);
                    } catch (IllegalArgumentException e) {
                        // validation messages name the rule (length, characters), never the secret
                        log.warn("Ignoring credential #{} from the environment: {}", index, e.getMessage());
                    }
                }
            }

            // Legacy single pair (backward compatibility): same upsert semantics
            String ak = s3.getAccessKey();
            String sk = s3.getSecretKey();
            if (ak != null && !ak.isBlank() && sk != null && !sk.isBlank()) {
                try {
                    var existing = credentialService.findByAccessKeyId(ak);
                    if (existing.isEmpty() || !existing.get().getSecretKey().equals(sk)) {
                        credentialService.ensureCredential(ak, sk);   // same validation as register()
                    }
                    grantFullAccessIfNoPolicy(credentialService, ak);
                } catch (IllegalArgumentException e) {
                    log.warn("Ignoring the sectoriadb.s3.access-key / secret-key pair: {}", e.getMessage());
                }
            }

            List<String> powerless = credentialService.keysWithoutPolicy();
            if (!powerless.isEmpty()) {
                log.warn("{} access key(s) have no policy and are refused every request (deny by default): {}. "
                        + "Grant access with key-policy --id KEY --file POLICY.json, or --admin for full access.",
                        powerless.size(), String.join(", ", powerless));
            }
        };
    }

    /**
     * Keys declared in the environment are the operator's keys: they get an explicit full-access policy, unless a
     * policy was attached to them (which is kept). Every other key starts without access (deny by default).
     */
    private static void grantFullAccessIfNoPolicy(CredentialService credentialService, String accessKeyId) {
        credentialService.findByAccessKeyId(accessKeyId).filter(c -> c.getPolicy() == null).ifPresent(c -> {
            credentialService.setPolicy(accessKeyId, CredentialService.fullAccessPolicy());
            log.info("Credential from the environment granted full access: accessKeyId={}", accessKeyId);
        });
    }


    @Bean
    public ObjectMapper objectMapper() {
        // java.time as ISO-8601 strings is the Jackson 3 default; reading accepts what Jackson 2 accepted
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }
}
