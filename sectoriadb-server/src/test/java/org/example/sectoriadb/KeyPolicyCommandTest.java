package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.security.CredentialService;
import org.example.sectoriadb.security.JsonCredentialRepository;
import org.example.sectoriadb.shell.CredentialCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code key-policy}: attach from a file, show, remove; every mistake is an "Error:" line and changes nothing. */
class KeyPolicyCommandTest {

    @TempDir
    Path dir;

    private CredentialService service;
    private CredentialCommands commands;
    private String id;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        service = new CredentialService(new JsonCredentialRepository(mapper, props));
        commands = new CredentialCommands(service, mapper);
        id = service.create("t").getAccessKeyId();
    }

    private Path file(String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void attachShowRemove() throws Exception {
        Path example = Path.of("").toAbsolutePath();
        while (!Files.exists(example.resolve("deploy/iam/read-only.json"))) example = example.getParent();
        String out = commands.keyPolicy(id, example.resolve("deploy/iam/read-only.json").toString(), false, false, false);
        assertTrue(out.startsWith("Policy attached"), out);
        assertNotNull(service.findByAccessKeyId(id).orElseThrow().getPolicy());
        assertTrue(commands.keys(false).contains("custom"), "the key list shows that a policy is attached");

        String shown = commands.keyPolicy(id, "", true, false, false);
        assertTrue(shown.contains("\"s3:GetObject\"") && shown.contains("example-bucket"), shown);

        assertTrue(commands.keyPolicy(id, "", false, false, true).startsWith("Removed"));
        assertNull(service.findByAccessKeyId(id).orElseThrow().getPolicy());
        assertTrue(commands.keyPolicy(id, "", true, false, false).startsWith("No policy"));
    }

    @Test
    void adminGrantsTheFullAccessPolicyAndAKeyWithoutPolicyIsShownAsDenied() {
        assertTrue(commands.keys(false).contains("deny"), commands.keys(false));
        assertTrue(commands.keyPolicy(id, "", false, true, false).startsWith("Full access granted"));
        assertEquals(org.example.sectoriadb.security.CredentialService.fullAccessPolicy(), service.findByAccessKeyId(id).orElseThrow().getPolicy());
        assertTrue(commands.keys(false).contains("admin"), commands.keys(false));
        assertTrue(commands.keyPolicy(id, "", false, true, true).startsWith("Error: give exactly one"));
    }

    @Test
    void mistakesAreReportedAndNothingIsStored() throws Exception {
        assertTrue(commands.keyPolicy(id, "", false, false, false).startsWith("Error: give exactly one"));
        assertTrue(commands.keyPolicy(id, "x.json", true, false, false).startsWith("Error: give exactly one"));
        assertTrue(commands.keyPolicy("nosuchkey", dir.resolve("x").toString(), false, false, false).startsWith("Error: Access key not found"));
        assertTrue(commands.keyPolicy(id, dir.resolve("missing.json").toString(), false, false, false).startsWith("Error: cannot read"));
        assertTrue(commands.keyPolicy(id, file("bad.json", "{not json").toString(), false, false, false).startsWith("Error:"));
        String invalid = commands.keyPolicy(id, file("invalid.json",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Permit\",\"Action\":\"ec2:*\",\"Resource\":\"x\"}]}").toString(), false, false, false);
        assertTrue(invalid.startsWith("Error: Invalid policy") && invalid.contains("Effect") && invalid.contains("ec2:*"), invalid);
        assertNull(service.findByAccessKeyId(id).orElseThrow().getPolicy());
    }

    @Test
    void warningsAreShownButThePolicyIsStored() throws Exception {
        String out = commands.keyPolicy(id, file("w.json",
                "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}],\"Typo\":1}").toString(), false, false, false);
        assertTrue(out.contains("Warning: Version is missing") && out.contains("Warning: unknown top-level key 'Typo'"), out);
        assertFalse(service.findByAccessKeyId(id).orElseThrow().getPolicy().isNull());
    }
}
