package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.s3.access.BucketPolicyEvaluator;
import org.example.sectoriadb.s3.access.BucketPolicyEvaluator.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BucketPolicyEvaluatorTest {

    private final BucketPolicyEvaluator ev = new BucketPolicyEvaluator(new ObjectMapper());

    private static String policy(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    private static final String ALLOW_ALL_GET =
            "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::b/*\"}";

    @Test
    void allowMatches() {
        assertEquals(Decision.ALLOW, ev.evaluate(policy(ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.NONE, ev.evaluate(policy(ALLOW_ALL_GET), "s3:PutObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.NONE, ev.evaluate(policy(ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::other/k", true));
    }

    @Test
    void denyWinsRegardlessOfOrder() {
        String deny = "{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"s3:*\","
                + "\"Resource\":[\"arn:aws:s3:::b/private/*\"]}";
        assertEquals(Decision.DENY, ev.evaluate(policy(ALLOW_ALL_GET, deny), "s3:GetObject", "arn:aws:s3:::b/private/x", true));
        assertEquals(Decision.DENY, ev.evaluate(policy(deny, ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::b/private/x", true));
        assertEquals(Decision.ALLOW, ev.evaluate(policy(deny, ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::b/public/x", true));
    }

    @Test
    void wildcardsAndArraysAndAwsPrincipalList() {
        String deny = "{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":[\"arn:aws:iam::1:root\",\"*\"]},"
                + "\"Action\":[\"s3:Put*\",\"s3:Get*\"],\"Resource\":\"arn:aws:s3:::b/?ecret/*\"}";
        assertEquals(Decision.DENY, ev.evaluate(policy(deny), "s3:GetObject", "arn:aws:s3:::b/secret/a", true));
        assertEquals(Decision.DENY, ev.evaluate(policy(deny), "s3:PutObject", "arn:aws:s3:::b/Secret/a", true));
        assertEquals(Decision.NONE, ev.evaluate(policy(deny), "s3:DeleteObject", "arn:aws:s3:::b/secret/a", true));
        assertEquals(Decision.NONE, ev.evaluate(policy(deny), "s3:GetObject", "arn:aws:s3:::b/ssecret/a", true));
    }

    @Test
    void actionNamesAreCaseInsensitiveButResourcesAreNot() {
        String allow = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"S3:getobject\",\"Resource\":\"arn:aws:s3:::b/A*\"}";
        assertEquals(Decision.ALLOW, ev.evaluate(policy(allow), "s3:GetObject", "arn:aws:s3:::b/Abc", true));
        assertEquals(Decision.NONE, ev.evaluate(policy(allow), "s3:GetObject", "arn:aws:s3:::b/abc", true));
    }

    @Test
    void allowWithConditionIsNotApplied_denyWithConditionIs() {
        String allowCond = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::b/*\",\"Condition\":{\"StringEquals\":{\"aws:UserAgent\":\"x\"}}}";
        String denyCond = "{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::b/*\",\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allowCond), "s3:GetObject", "arn:aws:s3:::b/k", true));
        String denyUnknown = "{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::b/*\",\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"10.0.0.0/8\"}}}";
        assertEquals(Decision.DENY, ev.evaluate(policy(ALLOW_ALL_GET, denyUnknown), "s3:GetObject", "arn:aws:s3:::b/k", true));
        // the SecureTransport deny is now evaluated: it only bites on plain HTTP
        assertEquals(Decision.DENY, ev.evaluate(policy(ALLOW_ALL_GET, denyCond), "s3:GetObject", "arn:aws:s3:::b/k", false));
        assertEquals(Decision.ALLOW, ev.evaluate(policy(ALLOW_ALL_GET, denyCond), "s3:GetObject", "arn:aws:s3:::b/k", true));
    }

    @Test
    void notActionAndNotResourceOnlyWorkForDeny() {
        String allowNot = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"NotAction\":\"s3:PutObject\",\"Resource\":\"*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allowNot), "s3:GetObject", "arn:aws:s3:::b/k", true));
        String denyNot = "{\"Effect\":\"Deny\",\"Principal\":\"*\",\"NotAction\":\"s3:GetObject\",\"Resource\":\"*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(denyNot), "s3:GetObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.DENY, ev.evaluate(policy(denyNot), "s3:PutObject", "arn:aws:s3:::b/k", true));
    }

    @Test
    void specificPrincipalDoesNotMatchAnonymous() {
        String allow = "{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::1:user/a\"},"
                + "\"Action\":\"s3:*\",\"Resource\":\"*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allow), "s3:GetObject", "arn:aws:s3:::b/k", true));
    }

    @Test
    void singleStatementObjectAndGarbage() {
        String single = "{\"Statement\":" + ALLOW_ALL_GET + "}";
        assertEquals(Decision.ALLOW, ev.evaluate(single, "s3:GetObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.NONE, ev.evaluate("not json", "s3:GetObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.NONE, ev.evaluate(null, "s3:GetObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.NONE, ev.evaluate("{}", "s3:GetObject", "arn:aws:s3:::b/k", true));
    }

    @Test
    void bucketLevelResourceIsNotMatchedByObjectPattern() {
        String allow = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::b/*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allow), "s3:ListBucket", "arn:aws:s3:::b", true));
    }

    private static String secureStmt(String effect, String operator, String key, String value) {
        return "{\"Effect\":\"" + effect + "\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::b/*\",\"Condition\":{\"" + operator + "\":{\"" + key + "\":" + value + "}}}";
    }

    @Test
    void secureTransportConditionOnAllowAndDeny() {
        String allowTls = secureStmt("Allow", "Bool", "aws:SecureTransport", "\"true\"");
        assertEquals(Decision.ALLOW, ev.evaluate(policy(allowTls), "s3:GetObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.NONE, ev.evaluate(policy(allowTls), "s3:GetObject", "arn:aws:s3:::b/k", false));

        String denyPlain = secureStmt("Deny", "Bool", "aws:SecureTransport", "\"false\"");
        assertEquals(Decision.DENY, ev.evaluate(policy(ALLOW_ALL_GET, denyPlain), "s3:GetObject", "arn:aws:s3:::b/k", false));
        assertEquals(Decision.ALLOW, ev.evaluate(policy(ALLOW_ALL_GET, denyPlain), "s3:GetObject", "arn:aws:s3:::b/k", true));
    }

    @Test
    void secureTransportConditionAcceptsBooleanLiteralsArraysAndKeyCase() {
        assertEquals(Decision.DENY, ev.evaluate(policy(secureStmt("Deny", "Bool", "AWS:SecureTransport", "false")),
                "s3:GetObject", "arn:aws:s3:::b/k", false));
        assertEquals(Decision.NONE, ev.evaluate(policy(secureStmt("Deny", "Bool", "aws:SecureTransport", "[\"false\"]")),
                "s3:GetObject", "arn:aws:s3:::b/k", true));
        assertEquals(Decision.ALLOW, ev.evaluate(policy(secureStmt("Allow", "Bool", "aws:SecureTransport", "[\"false\",\"true\"]")),
                "s3:GetObject", "arn:aws:s3:::b/k", false));
    }

    @Test
    void otherConditionsStayFailClosed() {
        // Allow with an operator/key we do not evaluate: never applied, even if it "would" hold
        for (String[] c : new String[][]{{"StringEquals", "aws:SecureTransport"}, {"Bool", "aws:ViaAWSService"},
                {"BoolIfExists", "aws:SecureTransport"}}) {
            assertEquals(Decision.NONE, ev.evaluate(policy(secureStmt("Allow", c[0], c[1], "\"true\"")),
                    "s3:GetObject", "arn:aws:s3:::b/k", true), c[0] + " " + c[1]);
            assertEquals(Decision.DENY, ev.evaluate(policy(secureStmt("Deny", c[0], c[1], "\"true\"")),
                    "s3:GetObject", "arn:aws:s3:::b/k", true), c[0] + " " + c[1]);
        }
        // a supported key next to an unsupported one: the unsupported part decides the fail-closed outcome
        String mixed = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\","
                + "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"true\"},\"StringLike\":{\"aws:UserAgent\":\"x\"}}}";
        assertEquals(Decision.NONE, ev.evaluate(policy(mixed), "s3:GetObject", "arn:aws:s3:::b/k", true));
        // an empty or malformed Condition grants nothing
        String empty = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\",\"Condition\":{}}";
        assertEquals(Decision.NONE, ev.evaluate(policy(empty), "s3:GetObject", "arn:aws:s3:::b/k", true));
    }
}
