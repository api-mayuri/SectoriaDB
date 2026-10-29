package org.example.sectoriadb.s3.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary lock of a client address after a series of failed signatures (wrong secret, unknown or disabled key):
 * {@code max-failures} within {@code window} block the address for {@code duration}, during which its signed requests
 * are answered {@code 503 SlowDown} without being verified. It slows down guessing of keys and secrets; nginx limits the
 * request rate per address in front of it.
 *
 * <p>The address is the client behind the trusted proxy (Tomcat's RemoteIpValve). If the proxy is NOT configured as
 * trusted, every client has the proxy's address and one client's failures lock out all: the compose file sets the
 * trusted proxy. The table is bounded: when it holds {@value #MAX_TRACKED} addresses, expired entries are dropped and,
 * if that is not enough, the table is cleared (memory is bounded; a cleared table only forgets counts).
 */
@Component
public class AuthLockout {

    private static final Logger log = LoggerFactory.getLogger(AuthLockout.class);
    static final int MAX_TRACKED = 100_000;

    private record State(long windowStart, int failures, long blockedUntil) {
    }

    private final int maxFailures;
    private final long windowMillis;
    private final long blockMillis;
    private final ConcurrentHashMap<String, State> byAddress = new ConcurrentHashMap<>();

    public AuthLockout(@Value("${sectoriadb.s3.auth.lockout.max-failures:20}") int maxFailures,
                       @Value("${sectoriadb.s3.auth.lockout.window:1m}") Duration window,
                       @Value("${sectoriadb.s3.auth.lockout.duration:5m}") Duration duration) {
        this.maxFailures = maxFailures;
        this.windowMillis = window.toMillis();
        this.blockMillis = duration.toMillis();
    }

    boolean enabled() {
        return maxFailures > 0 && blockMillis > 0;
    }

    /** Seconds until the address may try again, 0 when it is not locked. */
    long lockedForSeconds(String address, long nowMillis) {
        if (!enabled() || address == null) return 0;
        State s = byAddress.get(address);
        if (s == null || s.blockedUntil() <= nowMillis) return 0;
        return Math.max(1, (s.blockedUntil() - nowMillis + 999) / 1000);
    }

    /** Records a failed signature of the address; the failure that reaches the limit starts the lock. */
    void failure(String address, long nowMillis) {
        if (!enabled() || address == null) return;
        if (byAddress.size() >= MAX_TRACKED) prune(nowMillis);
        State after = byAddress.compute(address, (k, s) -> {
            if (s == null || nowMillis - s.windowStart() > windowMillis) s = new State(nowMillis, 0, 0);
            int n = s.failures() + 1;
            long until = n >= maxFailures ? nowMillis + blockMillis : s.blockedUntil();
            return new State(n >= maxFailures ? nowMillis : s.windowStart(), n >= maxFailures ? 0 : n, until);
        });
        if (after.blockedUntil() == nowMillis + blockMillis && after.failures() == 0) {
            log.warn("Client {} locked out for {} s after {} failed signatures within {} s", address, blockMillis / 1000,
                    maxFailures, windowMillis / 1000);
        }
    }

    private void prune(long nowMillis) {
        byAddress.entrySet().removeIf(e -> e.getValue().blockedUntil() <= nowMillis
                && nowMillis - e.getValue().windowStart() > windowMillis);
        if (byAddress.size() >= MAX_TRACKED) byAddress.clear();
    }
}
