package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.service.CredentialService;
import org.example.sectoriadb.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.shell.command.CommandExceptionResolver;
import org.springframework.shell.command.CommandHandlingResult;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

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
    static final java.util.Set<String> ONLINE_COMMANDS = java.util.Set.of(
            "mk-key", "keys", "rm-key", "enable-key", "disable-key");

    /**
     * Commands that need the single-process metastore: they work only while the server is STOPPED (or from the server's
     * own interactive shell). Started next to a running server they fail at once with a clear message instead of a
     * stack trace deep in the context start-up.
     */
    static final java.util.Set<String> OFFLINE_ONLY_COMMANDS = java.util.Set.of(
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
        java.nio.file.Path file = java.nio.file.Path.of(metaDir).resolve("sectoria.db");
        if (!java.nio.file.Files.exists(file)) return;
        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = null;
            try {
                lock = ch.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                // held inside this JVM: treated as held
            }
            if (lock == null) {
                System.err.println("Error: '" + command + "' needs exclusive access to the metadata store " + file.toAbsolutePath()
                        + ", which the running server holds.\nStop the server first, or run the command in the server's interactive shell"
                        + " (docker attach sectoriadb). Only the key commands (mk-key, keys, rm-key, enable-key, disable-key)"
                        + " work next to a running server.");
                System.exit(2);
            }
            lock.release();
        } catch (java.io.IOException e) {
            // let the normal start-up report the problem
        }
    }

    /**
     * Removes upload spool files left behind by a crash or kill -9 (nothing of this process can be using them yet).
     * Not run in CLI mode: a command next to a running server must not delete that server's live uploads.
     */
    @Bean
    public ApplicationRunner cleanUploadSpool(FileStorageService fileService) {
        return args -> {
            if (System.getProperty("sectoriadb.metastore.lazy") != null) return;
            java.nio.file.Path dir = fileService.uploadTmpDir();
            int removed = 0;
            try (var files = java.nio.file.Files.newDirectoryStream(dir, "sectoriadb-{upload,copy}-*.tmp")) {
                for (java.nio.file.Path f : files) {
                    if (java.nio.file.Files.deleteIfExists(f)) removed++;
                }
            } catch (java.io.IOException e) {
                log.warn("Could not clean the upload spool directory {}: {}", dir, e.toString());
            }
            if (removed > 0) log.info("Removed {} stale upload spool file(s) from {}", removed, dir);
        };
    }

    /**
     * Seeds credentials declared in the environment ({@code SECTORIADB_S3_CREDENTIALS=ak:sk[:desc],...} and the legacy
     * access-key / secret-key pair). The environment is the source of truth for the keys it declares: an unknown key is
     * registered, a key whose secret changed gets the new secret (the enabled flag and description are kept), an
     * identical key is left alone silently. Keys not declared in the environment are never touched. Secrets are never
     * logged, not even in error messages (entries are identified by position only).
     */
    @Bean
    public ApplicationRunner bootstrapCredential(CredentialService credentialService, StorageProperties props) {
        return args -> {
            if (System.getProperty("sectoriadb.metastore.lazy") != null) return;   // CLI mode: no seeding
            StorageProperties.S3 s3 = props.getS3();
            int index = 0;
            if (s3.getCredentials() != null) {
                for (String cred : s3.getCredentials()) {
                    index++;
                    if (cred == null || cred.isBlank()) continue;
                    String[] parts = cred.split(":", 3);
                    if (parts.length < 2) {
                        log.warn("Ignoring credential #{} from the environment: expected ACCESS:SECRET[:description]", index);
                        continue;
                    }
                    String ak = parts[0].trim();
                    String sk = parts[1].trim();
                    String desc = parts.length > 2 ? parts[2].trim() : "env";
                    try {
                        var existing = credentialService.findByAccessKeyId(ak);
                        if (existing.isEmpty()) {
                            credentialService.register(ak, sk, desc);
                        } else if (!existing.get().getSecretKey().equals(sk)) {
                            credentialService.ensureCredential(ak, sk);
                        } else {
                            log.debug("Credential from the environment unchanged: accessKeyId={}", ak);
                        }
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
                var existing = credentialService.findByAccessKeyId(ak);
                if (existing.isEmpty() || !existing.get().getSecretKey().equals(sk)) {
                    credentialService.ensureCredential(ak, sk);
                }
            }
        };
    }

    /** Shows the reason when a shell/CLI command fails (otherwise the process just exits with 1). */
    @Bean
    public CommandExceptionResolver commandErrors() {
        return ex -> CommandHandlingResult.of("Error: " + ex.getMessage() + "\n", 1);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
