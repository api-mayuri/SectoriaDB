package org.example.sectoriadb.s3.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates a bucket policy (for an anonymous caller or for a signed access key) and an IAM identity policy with the
 * same code, following the AWS evaluation order: an explicit Deny on a matching statement always wins over any Allow.
 *
 * Supported: Effect Allow/Deny; Principal "*" / {"AWS":"*"} / {"AWS":["*", ...]}; for a signed caller also the key's
 * ARN ({@link #principalArn}) or its plain access key id. An identity policy has no Principal: it is the key's own;
 * Action and Resource as string or array with the wildcards {@code *} and {@code ?};
 * Statement as an array or a single object; NotAction / NotResource / NotPrincipal on Deny statements.
 *
 * Conditions: {@code "Bool": {"aws:SecureTransport": "true"|"false"}} (the standard way to demand TLS per bucket) and
 * {@code StringEquals} / {@code StringLike} on {@code s3:prefix} and {@code s3:delimiter} (ListBucket); a key that is
 * not part of the request never matches. Anything else in a Condition block is not understood and handled fail-closed:
 *  - an Allow statement with an unsupported Condition is never applied;
 *  - a Deny statement with an unsupported Condition IS applied as if the condition were true;
 *  - Allow statements with NotAction / NotResource / NotPrincipal are never applied;
 *  - an unparseable policy yields {@link Decision#NONE} (no grants at all).
 */
public final class BucketPolicyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(BucketPolicyEvaluator.class);

    public enum Decision { ALLOW, DENY, NONE }

    /**
     * One authorization question.
     *
     * @param principalKeyId access key id of a signed caller, null for an anonymous one
     * @param context        lower-case condition keys of the request other than aws:SecureTransport (s3:prefix, s3:delimiter)
     */
    public record Request(String action, String resource, String principalKeyId, boolean secureTransport,
                          Map<String, String> context) {
    }

    /** The one documented ARN form of an access key as a bucket policy principal (the plain access key id is accepted too). */
    public static String principalArn(String accessKeyId) {
        return "arn:aws:iam::sectoriadb:user/" + accessKeyId;
    }

    private final ObjectMapper mapper;

    public BucketPolicyEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Bucket policy for an anonymous caller.
     *
     * @param action e.g. "s3:GetObject"; @param resource full ARN, e.g. "arn:aws:s3:::b/key"
     * @param secureTransport whether the request came over HTTPS ({@code aws:SecureTransport})
     */
    public Decision evaluate(String policyJson, String action, String resource, boolean secureTransport) {
        return evaluate(policyJson, new Request(action, resource, null, secureTransport, Map.of()));
    }

    /** A bucket policy (JSON text): statements whose Principal matches the caller of the request. */
    public Decision evaluate(String policyJson, Request request) {
        if (policyJson == null || policyJson.isBlank()) return Decision.NONE;
        try {
            return evaluate(mapper.readTree(policyJson), request, false);
        } catch (Exception e) {
            log.warn("Unparseable bucket policy ignored: {}", e.getMessage());
            return Decision.NONE;
        }
    }

    /** An IAM identity policy of the calling access key: the Principal is implicit, a Principal element is ignored. */
    public Decision evaluateIdentity(JsonNode policy, Request request) {
        return evaluate(policy, request, true);
    }

    private Decision evaluate(JsonNode root, Request req, boolean identity) {
        JsonNode statements = root == null ? null : root.get("Statement");
        if (statements == null) return Decision.NONE;

        boolean allowed = false;
        List<JsonNode> list = new ArrayList<>();
        if (statements.isArray()) statements.forEach(list::add);
        else if (statements.isObject()) list.add(statements);

        for (JsonNode stmt : list) {
            if (!stmt.isObject()) continue;
            String effect = text(stmt.get("Effect"));
            boolean deny = "Deny".equals(effect);
            boolean allow = "Allow".equals(effect);
            if (!deny && !allow) continue;

            if (allow && (stmt.has("NotAction") || stmt.has("NotResource") || stmt.has("NotPrincipal"))) {
                continue; // fail closed: cannot grant on something we do not evaluate
            }
            if (!identity && !principalMatches(stmt, req.principalKeyId())) continue;
            if (!actionMatches(stmt, req.action())) continue;
            if (!resourceMatches(stmt, req.resource())) continue;
            if (stmt.has("Condition") && !conditionHolds(stmt.get("Condition"), req, deny)) continue;

            if (deny) return Decision.DENY;   // explicit Deny (incl. Deny with unevaluated Condition)
            allowed = true;
        }
        return allowed ? Decision.ALLOW : Decision.NONE;
    }

    /**
     * True when the Condition block holds. Understood: {@code Bool} on {@code aws:SecureTransport}, {@code StringEquals}
     * and {@code StringLike} on the request's context keys; any other operator or key makes the block "unknown", which
     * counts as true for Deny and as false for Allow.
     */
    private static boolean conditionHolds(JsonNode condition, Request req, boolean deny) {
        if (!condition.isObject() || condition.isEmpty()) return deny;
        for (var op : condition.properties()) {
            String operator = op.getKey();
            JsonNode keys = op.getValue();
            if (!keys.isObject() || keys.isEmpty()) return deny;
            for (var e : keys.properties()) {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                boolean holds;
                if ("Bool".equals(operator) && key.equals("aws:securetransport")) {
                    holds = boolValueMatches(e.getValue(), req.secureTransport());
                } else if (("StringEquals".equals(operator) || "StringLike".equals(operator))
                        && (key.equals("s3:prefix") || key.equals("s3:delimiter"))) {
                    holds = stringValueMatches(e.getValue(), req.context().get(key), "StringLike".equals(operator));
                } else {
                    return deny;
                }
                if (!holds) return false;   // all keys and operators must hold
            }
        }
        return true;
    }

    /** A key that is not part of the request (null) never matches; a value that is not a string never matches. */
    private static boolean stringValueMatches(JsonNode values, String actual, boolean like) {
        if (actual == null) return false;
        if (values.isArray()) {
            for (JsonNode v : values) if (stringValueMatches(v, actual, like)) return true;
            return false;
        }
        if (!values.isTextual()) return false;
        return like ? glob(values.asText(), actual, false) : values.asText().equals(actual);
    }

    /** Bool: the key matches when any listed value equals the actual flag; a value that is not true/false never matches. */
    private static boolean boolValueMatches(JsonNode values, boolean actual) {
        if (values.isArray()) {
            for (JsonNode v : values) if (boolValueMatches(v, actual)) return true;
            return false;
        }
        if (!values.isTextual() && !values.isBoolean()) return false;
        String t = values.asText().toLowerCase(Locale.ROOT);
        return (t.equals("true") && actual) || (t.equals("false") && !actual);
    }

    private static boolean principalMatches(JsonNode stmt, String keyId) {
        JsonNode np = stmt.get("NotPrincipal");
        if (np != null) {
            // Applies to everyone except the listed principals.
            return !names(np, keyId);
        }
        JsonNode p = stmt.get("Principal");
        return p != null && names(p, keyId);
    }

    /** True for "*", {"AWS":"*"}, {"AWS":[...]} listing "*" or, for a signed caller, its ARN / plain access key id. */
    private static boolean names(JsonNode p, String keyId) {
        if (p.isTextual()) return nameMatches(p.asText(), keyId);
        if (p.isObject()) {
            JsonNode aws = p.get("AWS");
            if (aws == null) return false;
            if (aws.isTextual()) return nameMatches(aws.asText(), keyId);
            if (aws.isArray()) {
                for (JsonNode n : aws) if (n.isTextual() && nameMatches(n.asText(), keyId)) return true;
            }
        }
        return false;
    }

    private static boolean nameMatches(String name, String keyId) {
        return "*".equals(name) || keyId != null && (name.equals(keyId) || name.equals(principalArn(keyId)));
    }

    private static boolean actionMatches(JsonNode stmt, String action) {
        if (stmt.has("NotAction")) return !anyMatch(stmt.get("NotAction"), action, true);
        return anyMatch(stmt.get("Action"), action, true);
    }

    private static boolean resourceMatches(JsonNode stmt, String resource) {
        if (stmt.has("NotResource")) return !anyMatch(stmt.get("NotResource"), resource, false);
        return anyMatch(stmt.get("Resource"), resource, false);
    }

    private static boolean anyMatch(JsonNode node, String value, boolean ignoreCase) {
        if (node == null) return false;
        if (node.isTextual()) return glob(node.asText(), value, ignoreCase);
        if (node.isArray()) {
            for (JsonNode n : node) if (n.isTextual() && glob(n.asText(), value, ignoreCase)) return true;
        }
        return false;
    }

    private static String text(JsonNode n) { return n == null || !n.isTextual() ? null : n.asText(); }

    /** IAM-style wildcard match: '*' = any run of characters, '?' = exactly one character. */
    static boolean glob(String pattern, String value, boolean ignoreCase) {
        if (ignoreCase) {
            pattern = pattern.toLowerCase(Locale.ROOT);
            value = value.toLowerCase(Locale.ROOT);
        }
        int p = 0, v = 0, star = -1, mark = 0;
        while (v < value.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = v;
            } else if (p < pattern.length()
                    && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++;
                v++;
            } else if (star >= 0) {
                p = star + 1;
                v = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
