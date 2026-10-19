package org.example.sectoriadb;

/**
 * One entry of {@code SECTORIADB_S3_CREDENTIALS}: {@code ACCESS:SECRET} or {@code ACCESS:SECRET|description}.
 *
 * The rule is unambiguous: the entry is split at the FIRST colon (an access key never contains one), the description starts
 * at the first {@code |}, and a colon inside the secret is refused. The older {@code ACCESS:SECRET:description} form is
 * refused with a message that names the new syntax, because reading it as "secret = everything after the first colon"
 * would silently register a longer secret (and the tail of the secret as a description), and reading it as the old form
 * would silently cut a secret that really contains a colon. Messages never contain the entry text.
 */
record EnvCredentials(String accessKeyId, String secretKey, String description) {

    static EnvCredentials parse(String entry) {
        int colon = entry.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("expected ACCESS:SECRET or ACCESS:SECRET|description");
        }
        String access = entry.substring(0, colon).trim();
        String rest = entry.substring(colon + 1);
        String description = "env";
        int bar = rest.indexOf('|');
        if (bar >= 0) {
            String d = rest.substring(bar + 1).trim();
            if (!d.isEmpty()) description = d;
            rest = rest.substring(0, bar);
        }
        String secret = rest.trim();
        if (secret.indexOf(':') >= 0) {
            throw new IllegalArgumentException("the secret contains ':'. Secrets of environment keys must not contain ':' "
                    + "(a description is separated with '|': ACCESS:SECRET|description; the form ACCESS:SECRET:description "
                    + "is no longer accepted)");
        }
        return new EnvCredentials(access, secret, description);
    }
}
