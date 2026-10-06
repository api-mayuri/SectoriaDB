package org.example.sectoriadb.s3.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates a bucket policy for an ANONYMOUS caller, following the AWS evaluation order:
 * an explicit Deny on a matching statement always wins over any Allow.
 *
 * Supported: Effect Allow/Deny; Principal "*" / {"AWS":"*"} / {"AWS":["*", ...]};
 * Action and Resource as string or array with the wildcards {@code *} and {@code ?};
 * Statement as an array or a single object; NotAction / NotResource / NotPrincipal on Deny statements.
 *
 * Fail-closed rules (documented in docs/architecture/02-security.md):
 *  - an Allow statement that has a Condition block is never applied (conditions are not evaluated);
 *  - a Deny statement that has a Condition block IS applied as if the condition were true;
 *  - Allow statements with NotAction / NotResource / NotPrincipal are never applied;
 *  - an unparseable policy yields {@link Decision#NONE} (no grants at all).
 */
public final class BucketPolicyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(BucketPolicyEvaluator.class);

    public enum Decision { ALLOW, DENY, NONE }

    private final ObjectMapper mapper;

    public BucketPolicyEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @param action e.g. "s3:GetObject"; @param resource full ARN, e.g. "arn:aws:s3:::b/key" */
    public Decision evaluate(String policyJson, String action, String resource) {
        if (policyJson == null || policyJson.isBlank()) return Decision.NONE;
        JsonNode root;
        try {
            root = mapper.readTree(policyJson);
        } catch (Exception e) {
            log.warn("Unparseable bucket policy ignored: {}", e.getMessage());
            return Decision.NONE;
        }
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

            if (allow && (stmt.has("Condition") || stmt.has("NotAction")
                    || stmt.has("NotResource") || stmt.has("NotPrincipal"))) {
                continue; // fail closed: cannot grant on something we do not evaluate
            }
            if (!principalMatches(stmt)) continue;
            if (!actionMatches(stmt, action)) continue;
            if (!resourceMatches(stmt, resource)) continue;

            if (deny) return Decision.DENY;   // explicit Deny (incl. Deny with unevaluated Condition)
            allowed = true;
        }
        return allowed ? Decision.ALLOW : Decision.NONE;
    }

    private static boolean principalMatches(JsonNode stmt) {
        JsonNode np = stmt.get("NotPrincipal");
        if (np != null) {
            // Applies to everyone except the listed principals; anonymous is excluded only by "*".
            return !containsWildcard(np);
        }
        JsonNode p = stmt.get("Principal");
        return p != null && containsWildcard(p);
    }

    /** True for "*", {"AWS":"*"}, {"AWS":["*",...]}. */
    private static boolean containsWildcard(JsonNode p) {
        if (p.isTextual()) return "*".equals(p.asText());
        if (p.isObject()) {
            JsonNode aws = p.get("AWS");
            if (aws == null) return false;
            if (aws.isTextual()) return "*".equals(aws.asText());
            if (aws.isArray()) {
                for (JsonNode n : aws) if (n.isTextual() && "*".equals(n.asText())) return true;
            }
        }
        return false;
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
