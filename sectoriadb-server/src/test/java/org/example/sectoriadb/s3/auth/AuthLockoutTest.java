package org.example.sectoriadb.s3.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthLockoutTest {

    final AuthLockout lockout = new AuthLockout(3, Duration.ofMinutes(1), Duration.ofMinutes(5));

    @Test
    void theFailureThatReachesTheLimitLocksTheAddressForTheDuration() {
        long t = 1_000_000;
        lockout.failure("10.0.0.1", t);
        lockout.failure("10.0.0.1", t + 1);
        assertEquals(0, lockout.lockedForSeconds("10.0.0.1", t + 2));
        lockout.failure("10.0.0.1", t + 2);
        assertEquals(300, lockout.lockedForSeconds("10.0.0.1", t + 2));
        assertEquals(0, lockout.lockedForSeconds("10.0.0.2", t + 2), "other addresses are not affected");
        assertEquals(0, lockout.lockedForSeconds("10.0.0.1", t + 2 + 300_000), "the lock ends");
    }

    @Test
    void failuresOutsideTheWindowDoNotAddUp() {
        long t = 1_000_000;
        lockout.failure("10.0.0.3", t);
        lockout.failure("10.0.0.3", t + 1);
        lockout.failure("10.0.0.3", t + 61_000);   // a new window: 1 failure
        assertEquals(0, lockout.lockedForSeconds("10.0.0.3", t + 61_000));
    }

    @Test
    void zeroDisablesIt() {
        AuthLockout off = new AuthLockout(0, Duration.ofMinutes(1), Duration.ofMinutes(5));
        for (int i = 0; i < 100; i++) off.failure("10.0.0.4", i);
        assertEquals(0, off.lockedForSeconds("10.0.0.4", 100));
        assertFalse(off.enabled());
    }
}
