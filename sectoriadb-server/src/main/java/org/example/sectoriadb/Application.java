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
            "bucket-acl", "object-acl", "public-list");

    private static boolean isCliMode(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                return CLI_COMMANDS.contains(arg);
            }
        }
        return false;
    }

    @Bean
    public ApplicationRunner bootstrapCredential(CredentialService credentialService, StorageProperties props) {
        return args -> {
            // Register credentials from SECTORIADB_S3_CREDENTIALS env (format: ak1:sk1,ak2:sk2 or ak:sk:desc)
            StorageProperties.S3 s3 = props.getS3();
            if (s3.getCredentials() != null && !s3.getCredentials().isEmpty()) {
                for (String cred : s3.getCredentials()) {
                    if (cred == null || cred.isBlank()) continue;
                    try {
                        String[] parts = cred.split(":", 3);
                        if (parts.length >= 2) {
                            String ak = parts[0].trim();
                            String sk = parts[1].trim();
                            String desc = parts.length > 2 ? parts[2].trim() : "env";
                            try {
                                credentialService.register(ak, sk, desc);
                            } catch (IllegalArgumentException e) {
                                log.warn("Skipping malformed credential from env: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse credential from env: {}", cred, e);
                    }
                }
            }

            // Also register old-style single credentials (backward compatibility)
            String ak = s3.getAccessKey();
            String sk = s3.getSecretKey();
            if (ak != null && !ak.isBlank() && sk != null && !sk.isBlank()) {
                credentialService.ensureCredential(ak, sk);
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
