package org.example.sectoriadb.security;

import org.example.sectoriadb.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String VALID = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":[\"s3:GetObject\"],\"Resource\":\"arn:aws:s3:::b/*\"}]}";

    private static List<String> errors(String policy) {
        return IamPolicyValidator.validate(json(policy)).errors();
    }

    @Test
    void aWellFormedPolicyHasNoErrorsAndNoWarnings() {
        var r = IamPolicyValidator.validate(json(VALID));
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void aSingleStatementObjectAndStarsAreAccepted() {
        assertTrue(errors("{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"*\",\"Resource\":\"*\"}}").isEmpty());
        assertTrue(errors("{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"s3:*\","
                + "\"Resource\":\"arn:aws:s3:::*\",\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}}").isEmpty());
    }

    @Test
    void syntaxMistakesAreRejected() {
        assertFalse(errors("[]").isEmpty(), "not an object");
        assertFalse(errors("{\"Version\":\"2012-10-17\"}").isEmpty(), "no Statement");
        assertFalse(errors("{\"Version\":\"2012-10-17\",\"Statement\":[]}").isEmpty(), "empty Statement");
        assertFalse(errors("{\"Version\":\"2099\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}").isEmpty());
        assertFalse(errors(VALID.replace("Allow", "allow")).isEmpty(), "Effect is case-sensitive");
        assertFalse(errors(VALID.replace("s3:GetObject", "ec2:RunInstances")).isEmpty(), "only s3 actions");
        assertFalse(errors(VALID.replace("arn:aws:s3:::b/*", "b/*")).isEmpty(), "Resource must be an ARN");
        assertFalse(errors(VALID.replace("\"Action\"", "\"Actions\"")).isEmpty(), "misspelled element");
        assertFalse(errors(VALID.replace("\"Resource\":\"arn:aws:s3:::b/*\"", "\"Resource\":[]")).isEmpty());
    }

    @Test
    void constructsThatTheEvaluatorCannotHonourAreRejectedInsteadOfSilentlyIgnored() {
        String base = "{\"Version\":\"2012-10-17\",\"Statement\":[%s]}";
        assertFalse(errors(base.formatted("{\"Effect\":\"Allow\",\"NotAction\":\"s3:Get*\",\"Resource\":\"*\"}")).isEmpty());
        assertFalse(errors(base.formatted("{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"NotResource\":\"arn:aws:s3:::x\"}")).isEmpty());
        assertTrue(errors(base.formatted("{\"Effect\":\"Deny\",\"NotAction\":\"s3:Get*\",\"Resource\":\"*\"}")).isEmpty());
        assertFalse(errors(base.formatted("{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:*\",\"Resource\":\"*\"}")).isEmpty());
        assertFalse(errors(base.formatted("{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"arn:aws:s3:::${aws:username}/*\"}")).isEmpty());
        assertFalse(errors(base.formatted("{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\","
                + "\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"10.0.0.0/8\"}}}")).isEmpty());
        assertFalse(errors(base.formatted("{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:username\":\"x\"}}}")).isEmpty());
        assertFalse(errors(base.formatted("{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\","
                + "\"Condition\":{\"Bool\":{\"s3:prefix\":\"x\"}}}")).isEmpty());
        assertTrue(errors(base.formatted("{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::b\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":[\"home/*\",\"\"]}}}")).isEmpty());
    }

    @Test
    void unknownTopLevelKeysAndMissingVersionAreWarnings() {
        var r = IamPolicyValidator.validate(json("{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}],\"Foo\":1}"));
        assertTrue(r.errors().isEmpty());
        assertEquals(2, r.warnings().size(), r.warnings().toString());
    }

    @Test
    void thePolicyIsStoredNextToTheCredentialAndSurvivesAReload() throws Exception {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        var repo = new JsonCredentialRepository(MAPPER, props);
        var service = new CredentialService(repo);
        var key = service.create("tenant");
        assertNull(key.getPolicy());
        assertFalse(Files.readString(dir.resolve("credentials.json")).contains("policy"), "a key without a policy writes no field");

        service.setPolicy(key.getAccessKeyId(), json(VALID));
        var other = new CredentialService(new JsonCredentialRepository(MAPPER, props, 0));   // a second process reading the file
        assertEquals(json(VALID), other.findByAccessKeyId(key.getAccessKeyId()).orElseThrow().getPolicy());

        service.setPolicy(key.getAccessKeyId(), null);
        assertNull(other.findByAccessKeyId(key.getAccessKeyId()).orElseThrow().getPolicy(), "the other process sees the removal");
    }

    @Test
    void anInvalidPolicyIsNotStoredAndAnUnknownKeyIsAnError() {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        var service = new CredentialService(new JsonCredentialRepository(MAPPER, props));
        var key = service.create("t");
        service.setPolicy(key.getAccessKeyId(), json(VALID));
        assertThrows(IllegalArgumentException.class, () -> service.setPolicy(key.getAccessKeyId(), json("{\"Statement\":[]}")));
        assertEquals(json(VALID), service.findByAccessKeyId(key.getAccessKeyId()).orElseThrow().getPolicy(), "the earlier policy stays");
        assertThrows(IllegalArgumentException.class, () -> service.setPolicy("nope", json(VALID)));
    }

    @Test
    void aCredentialsFileWrittenBeforePoliciesExistedStillLoads() throws Exception {
        Files.writeString(dir.resolve("credentials.json"),
                "[{\"accessKeyId\":\"LEGACYKEY0000000001\",\"secretKey\":\"s3cr3t-s3cr3t-s3cr3t\",\"description\":\"old\","
                        + "\"createdAt\":1700000000.000000000,\"enabled\":true},"
                        + "{\"accessKeyId\":\"NULLPOLICY000000001\",\"secretKey\":\"s3cr3t-s3cr3t-s3cr3t\",\"policy\":null}]");
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        var repo = new JsonCredentialRepository(new ObjectMapper(), props);
        assertNull(repo.findByAccessKeyId("LEGACYKEY0000000001").orElseThrow().getPolicy());
        assertNull(repo.findByAccessKeyId("NULLPOLICY000000001").orElseThrow().getPolicy());
    }
}
