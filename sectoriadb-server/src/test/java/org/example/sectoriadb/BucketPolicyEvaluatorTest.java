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
        assertEquals(Decision.ALLOW, ev.evaluate(policy(ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::b/k"));
        assertEquals(Decision.NONE, ev.evaluate(policy(ALLOW_ALL_GET), "s3:PutObject", "arn:aws:s3:::b/k"));
        assertEquals(Decision.NONE, ev.evaluate(policy(ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::other/k"));
    }

    @Test
    void denyWinsRegardlessOfOrder() {
        String deny = "{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"s3:*\","
                + "\"Resource\":[\"arn:aws:s3:::b/private/*\"]}";
        assertEquals(Decision.DENY, ev.evaluate(policy(ALLOW_ALL_GET, deny), "s3:GetObject", "arn:aws:s3:::b/private/x"));
        assertEquals(Decision.DENY, ev.evaluate(policy(deny, ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::b/private/x"));
        assertEquals(Decision.ALLOW, ev.evaluate(policy(deny, ALLOW_ALL_GET), "s3:GetObject", "arn:aws:s3:::b/public/x"));
    }

    @Test
    void wildcardsAndArraysAndAwsPrincipalList() {
        String deny = "{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":[\"arn:aws:iam::1:root\",\"*\"]},"
                + "\"Action\":[\"s3:Put*\",\"s3:Get*\"],\"Resource\":\"arn:aws:s3:::b/?ecret/*\"}";
        assertEquals(Decision.DENY, ev.evaluate(policy(deny), "s3:GetObject", "arn:aws:s3:::b/secret/a"));
        assertEquals(Decision.DENY, ev.evaluate(policy(deny), "s3:PutObject", "arn:aws:s3:::b/Secret/a"));
        assertEquals(Decision.NONE, ev.evaluate(policy(deny), "s3:DeleteObject", "arn:aws:s3:::b/secret/a"));
        assertEquals(Decision.NONE, ev.evaluate(policy(deny), "s3:GetObject", "arn:aws:s3:::b/ssecret/a"));
    }

    @Test
    void actionNamesAreCaseInsensitiveButResourcesAreNot() {
        String allow = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"S3:getobject\",\"Resource\":\"arn:aws:s3:::b/A*\"}";
        assertEquals(Decision.ALLOW, ev.evaluate(policy(allow), "s3:GetObject", "arn:aws:s3:::b/Abc"));
        assertEquals(Decision.NONE, ev.evaluate(policy(allow), "s3:GetObject", "arn:aws:s3:::b/abc"));
    }

    @Test
    void allowWithConditionIsNotApplied_denyWithConditionIs() {
        String allowCond = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::b/*\",\"Condition\":{\"StringEquals\":{\"aws:UserAgent\":\"x\"}}}";
        String denyCond = "{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::b/*\",\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allowCond), "s3:GetObject", "arn:aws:s3:::b/k"));
        assertEquals(Decision.DENY, ev.evaluate(policy(ALLOW_ALL_GET, denyCond), "s3:GetObject", "arn:aws:s3:::b/k"));
    }

    @Test
    void notActionAndNotResourceOnlyWorkForDeny() {
        String allowNot = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"NotAction\":\"s3:PutObject\",\"Resource\":\"*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allowNot), "s3:GetObject", "arn:aws:s3:::b/k"));
        String denyNot = "{\"Effect\":\"Deny\",\"Principal\":\"*\",\"NotAction\":\"s3:GetObject\",\"Resource\":\"*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(denyNot), "s3:GetObject", "arn:aws:s3:::b/k"));
        assertEquals(Decision.DENY, ev.evaluate(policy(denyNot), "s3:PutObject", "arn:aws:s3:::b/k"));
    }

    @Test
    void specificPrincipalDoesNotMatchAnonymous() {
        String allow = "{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::1:user/a\"},"
                + "\"Action\":\"s3:*\",\"Resource\":\"*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allow), "s3:GetObject", "arn:aws:s3:::b/k"));
    }

    @Test
    void singleStatementObjectAndGarbage() {
        String single = "{\"Statement\":" + ALLOW_ALL_GET + "}";
        assertEquals(Decision.ALLOW, ev.evaluate(single, "s3:GetObject", "arn:aws:s3:::b/k"));
        assertEquals(Decision.NONE, ev.evaluate("not json", "s3:GetObject", "arn:aws:s3:::b/k"));
        assertEquals(Decision.NONE, ev.evaluate(null, "s3:GetObject", "arn:aws:s3:::b/k"));
        assertEquals(Decision.NONE, ev.evaluate("{}", "s3:GetObject", "arn:aws:s3:::b/k"));
    }

    @Test
    void bucketLevelResourceIsNotMatchedByObjectPattern() {
        String allow = "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::b/*\"}";
        assertEquals(Decision.NONE, ev.evaluate(policy(allow), "s3:ListBucket", "arn:aws:s3:::b"));
    }
}
