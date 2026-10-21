package org.example.sectoriadb;

import org.example.sectoriadb.security.CredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys declared in the environment and the spool clean-up must be done before ANY application runner: the interactive
 * shell is a runner that blocks on the console, so work scheduled as a runner was never done when the shell came first
 * (the docker-compose default). A runner ordered before everything else observes the state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StartupSeedingTest {

    @TempDir
    static Path tempRoot;

    static final AtomicBoolean keySeededBeforeFirstRunner = new AtomicBoolean();
    static final AtomicBoolean staleSpoolFileGoneBeforeFirstRunner = new AtomicBoolean();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path spool = Files.createDirectories(tempRoot.resolve("spool"));
        Files.writeString(spool.resolve("sectoriadb-upload-stale.tmp"), "left behind by a crash");
        Files.writeString(spool.resolve("sectoriadb-part-stale.tmp"), "an UploadPart left behind by a crash");
        Files.writeString(spool.resolve("sectoriadb-partcopy-stale.tmp"), "an UploadPartCopy left behind by a crash");
        Files.writeString(spool.resolve("keep-me.txt"), "not ours");
        Files.writeString(Files.createDirectories(tempRoot.resolve("meta")).resolve("credentials123456.tmp"), "secrets of an interrupted update");
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.upload-tmp-dir",      spool::toString);
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.s3.auth.enabled",     () -> "true");
        // the second entry uses the retired ACCESS:SECRET:description form and must be refused, not read as a longer secret
        r.add("sectoriadb.s3.credentials",      () -> "ENVKEY0000000001:envsecret0123456789|from-env,OLDFORM000000001X:oldsecret0123456789:desc");
        r.add("sectoriadb.s3.access-key",       () -> "SHORTSECRETKEY0001");
        r.add("sectoriadb.s3.secret-key",       () -> "short");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @TestConfiguration
    static class FirstRunner {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        ApplicationRunner observeStateBeforeOtherRunners(CredentialService credentials) {
            return args -> {
                keySeededBeforeFirstRunner.set(credentials.findByAccessKeyId("ENVKEY0000000001").isPresent());
                staleSpoolFileGoneBeforeFirstRunner.set(
                        !Files.exists(tempRoot.resolve("spool").resolve("sectoriadb-upload-stale.tmp")));
            };
        }
    }

    @Autowired
    CredentialService credentials;

    @Test
    void environmentKeysAndSpoolCleanupAreDoneBeforeAnyRunnerSuchAsTheInteractiveShell() {
        assertTrue(keySeededBeforeFirstRunner.get(), "the env key must exist before the first application runner");
        assertTrue(staleSpoolFileGoneBeforeFirstRunner.get(), "stale spool files must be gone before the first runner");
        assertFalse(Files.exists(tempRoot.resolve("spool").resolve("sectoriadb-part-stale.tmp")));
        assertFalse(Files.exists(tempRoot.resolve("spool").resolve("sectoriadb-partcopy-stale.tmp")));
        assertTrue(Files.exists(tempRoot.resolve("spool").resolve("keep-me.txt")), "only our own spool files are removed");
        assertFalse(Files.exists(tempRoot.resolve("meta").resolve("credentials123456.tmp")));
        assertFalse(credentials.findByAccessKeyId("ENVKEY0000000001").isEmpty());
        assertEquals("from-env", credentials.findByAccessKeyId("ENVKEY0000000001").orElseThrow().getDescription());
        assertEquals("envsecret0123456789", credentials.findByAccessKeyId("ENVKEY0000000001").orElseThrow().getSecretKey());
        assertTrue(credentials.findByAccessKeyId("OLDFORM000000001X").isEmpty(), "the retired three-part form is refused");
        assertTrue(credentials.findByAccessKeyId("SHORTSECRETKEY0001").isEmpty(), "the legacy pair obeys the secret length rule");
    }
}
