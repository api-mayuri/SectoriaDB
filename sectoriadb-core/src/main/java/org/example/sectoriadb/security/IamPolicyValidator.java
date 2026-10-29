package org.example.sectoriadb.security;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Syntax check of an IAM identity policy before it is attached to an access key. It accepts exactly what the S3
 * evaluator understands, so a policy never silently means something else than it says: unsupported constructs
 * (NotAction on Allow, Principal, policy variables, unknown condition operators or keys) are errors, unknown
 * top-level keys are warnings.
 */
public final class IamPolicyValidator {

    /** Errors reject the policy; warnings are shown to the administrator. */
    public record Result(List<String> errors, List<String> warnings) {
    }

    static final int MAX_POLICY_CHARS = 20_000;
    private static final Set<String> VERSIONS = Set.of("2012-10-17", "2008-10-17");
    private static final Set<String> TOP_LEVEL = Set.of("Version", "Id", "Statement");
    private static final Set<String> STATEMENT_KEYS = Set.of(
            "Sid", "Effect", "Action", "NotAction", "Resource", "NotResource", "Condition");
    private static final Set<String> CONDITION_OPERATORS = Set.of("Bool", "StringEquals", "StringLike");
    private static final Set<String> CONDITION_KEYS = Set.of("aws:securetransport", "s3:prefix", "s3:delimiter");

    private IamPolicyValidator() {
    }

    public static Result validate(JsonNode policy) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (policy == null || !policy.isObject()) {
            errors.add("the policy must be a JSON object");
            return new Result(errors, warnings);
        }
        if (policy.toString().length() > MAX_POLICY_CHARS) {
            errors.add("the policy is larger than " + MAX_POLICY_CHARS + " characters");
        }
        policy.propertyNames().forEach(name -> {
            if (!TOP_LEVEL.contains(name)) warnings.add("unknown top-level key '" + name + "' is ignored");
        });
        JsonNode version = policy.get("Version");
        if (version == null) {
            warnings.add("Version is missing; use \"2012-10-17\"");
        } else if (!version.isTextual() || !VERSIONS.contains(version.asText())) {
            errors.add("Version must be \"2012-10-17\"");
        }
        JsonNode statements = policy.get("Statement");
        if (statements == null) {
            errors.add("Statement is missing");
        } else if (statements.isObject()) {
            statement(statements, 0, errors);
        } else if (statements.isArray() && !statements.isEmpty()) {
            for (int i = 0; i < statements.size(); i++) statement(statements.get(i), i, errors);
        } else {
            errors.add("Statement must be an object or a non-empty array of objects");
        }
        return new Result(errors, warnings);
    }

    private static void statement(JsonNode stmt, int index, List<String> errors) {
        String at = "Statement[" + index + "]: ";
        if (!stmt.isObject()) {
            errors.add(at + "must be an object");
            return;
        }
        if (stmt.has("Principal") || stmt.has("NotPrincipal")) {
            errors.add(at + "Principal is not allowed in an identity policy (the principal is the access key itself)");
        }
        stmt.propertyNames().forEach(name -> {
            if (!STATEMENT_KEYS.contains(name) && !name.equals("Principal") && !name.equals("NotPrincipal")) {
                errors.add(at + "unknown element '" + name + "'");
            }
        });
        JsonNode effect = stmt.get("Effect");
        boolean allow = effect != null && effect.isTextual() && "Allow".equals(effect.asText());
        boolean deny = effect != null && effect.isTextual() && "Deny".equals(effect.asText());
        if (!allow && !deny) errors.add(at + "Effect must be \"Allow\" or \"Deny\"");

        if (stmt.has("Action") == stmt.has("NotAction")) {
            errors.add(at + "exactly one of Action / NotAction is required");
        } else if (stmt.has("NotAction")) {
            if (allow) errors.add(at + "NotAction is supported only with Effect Deny");
            strings(stmt.get("NotAction"), at + "NotAction", true, errors);
        } else {
            strings(stmt.get("Action"), at + "Action", true, errors);
        }
        if (stmt.has("Resource") == stmt.has("NotResource")) {
            errors.add(at + "exactly one of Resource / NotResource is required");
        } else if (stmt.has("NotResource")) {
            if (allow) errors.add(at + "NotResource is supported only with Effect Deny");
            strings(stmt.get("NotResource"), at + "NotResource", false, errors);
        } else {
            strings(stmt.get("Resource"), at + "Resource", false, errors);
        }
        if (stmt.has("Condition")) condition(stmt.get("Condition"), at, errors);
    }

    /** A string or non-empty array of strings: S3 actions ({@code s3:...} or {@code *}) or S3 ARNs ({@code arn:aws:s3:::...} or {@code *}). */
    private static void strings(JsonNode node, String what, boolean actions, List<String> errors) {
        List<JsonNode> values = new ArrayList<>();
        if (node.isTextual()) values.add(node);
        else if (node.isArray() && !node.isEmpty()) node.forEach(values::add);
        else {
            errors.add(what + " must be a string or a non-empty array of strings");
            return;
        }
        for (JsonNode v : values) {
            if (!v.isTextual() || v.asText().isBlank()) {
                errors.add(what + " must contain only non-empty strings");
                continue;
            }
            String s = v.asText();
            if (s.contains("${")) {
                errors.add(what + " '" + s + "': policy variables are not supported");
            } else if (actions && !s.equals("*") && !s.startsWith("s3:")) {
                errors.add(what + " '" + s + "': only S3 actions (s3:...) are supported");
            } else if (!actions && !s.equals("*") && !s.startsWith("arn:aws:s3:::")) {
                errors.add(what + " '" + s + "': a resource must be an ARN arn:aws:s3:::bucket[/key]");
            }
        }
    }

    /**
     * The Condition blocks of a policy (identity or bucket) that the evaluator cannot evaluate. Such a condition is
     * evaluated fail-closed (an Allow with it never applies, a Deny with it always does); refusing it when the policy is
     * stored makes that visible instead of a policy that silently does less (or denies more) than it says.
     */
    public static List<String> unsupportedConditions(JsonNode policy) {
        List<String> errors = new ArrayList<>();
        JsonNode st = policy == null ? null : policy.get("Statement");
        if (st == null) return errors;
        List<JsonNode> list = new ArrayList<>();
        if (st.isArray()) st.forEach(list::add);
        else list.add(st);
        for (int i = 0; i < list.size(); i++) {
            JsonNode s = list.get(i);
            if (s.isObject() && s.has("Condition")) condition(s.get("Condition"), "Statement " + (i + 1) + ": ", errors);
        }
        return errors;
    }

    private static void condition(JsonNode cond, String at, List<String> errors) {
        if (!cond.isObject() || cond.isEmpty()) {
            errors.add(at + "Condition must be a non-empty object");
            return;
        }
        for (var op : cond.properties()) {
            if (!CONDITION_OPERATORS.contains(op.getKey())) {
                errors.add(at + "unsupported condition operator '" + op.getKey() + "' (supported: Bool, StringEquals, StringLike)");
                continue;
            }
            JsonNode keys = op.getValue();
            if (!keys.isObject() || keys.isEmpty()) {
                errors.add(at + "Condition " + op.getKey() + " must be a non-empty object");
                continue;
            }
            for (var e : keys.properties()) {
                String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!CONDITION_KEYS.contains(key)) {
                    errors.add(at + "unsupported condition key '" + e.getKey() + "' (supported: aws:SecureTransport, s3:prefix, s3:delimiter)");
                } else if (key.equals("aws:securetransport") != op.getKey().equals("Bool")) {
                    errors.add(at + "operator " + op.getKey() + " does not fit the key " + e.getKey());
                }
                JsonNode vals = e.getValue();
                boolean ok = vals.isValueNode() && !vals.isNull()
                        || vals.isArray() && !vals.isEmpty();
                if (ok && vals.isArray()) for (JsonNode v : vals) ok &= v.isValueNode() && !v.isNull();
                if (!ok) {
                    errors.add(at + "Condition value of " + e.getKey() + " must be a string or an array of strings");
                } else if (vals.toString().contains("${")) {
                    errors.add(at + "policy variables are not supported in Condition values");
                }
            }
        }
    }
}
