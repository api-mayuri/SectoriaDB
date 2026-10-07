package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.service.CredentialService;
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
        // System properties win over application.properties, so no web server is started
        // and the command can run next to an already-running server (shared credentials.json).
        boolean cli = isCliMode(args);
        if (cli) {
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

    private static final java.util.Set<String> CLI_COMMANDS = java.util.Set.of(
            "mk-key", "keys", "rm-key", "enable-key", "disable-key",
            "bucket-acl", "object-acl", "public-list",
            // offline integrity check: `java -jar sectoriadb.jar verify-all [--pool NAME]` with the server stopped
            // (the metastore is single-process); prints "RESULT: OK" or "RESULT: FAILED (...)"
            "verify-all", "verify");

    static boolean isCliMode(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                return CLI_COMMANDS.contains(arg);
            }
        }
        return false;
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
