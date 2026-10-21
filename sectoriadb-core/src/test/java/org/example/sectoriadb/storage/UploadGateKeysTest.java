package org.example.sectoriadb.storage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadGateKeysTest {

    final UploadGate gate = new UploadGate();

    @Test
    void holdsThatNeverTouchedTheKeysDoNotMatter() {
        UploadGate.Hold h = gate.enter("p");
        h.touch(5);
        try (UploadGate.KeySweep s = gate.tryBeginKeys("p", new long[]{1, 2, 3}, 20, 60_000)) {
            assertArrayEquals(new long[]{1, 2, 3}, s.granted());
        }
        try (UploadGate.KeySweep s = gate.tryBeginKeys("p", new long[]{4, 5, 6}, 20, 60_000)) {
            assertArrayEquals(new long[]{4, 6}, s.granted(), "5 is relied on by the hold and left for later");
        }
        h.release();
        try (UploadGate.KeySweep s = gate.tryBeginKeys("p", new long[]{5}, 20, 60_000)) {
            assertEquals(1, s.granted().length);
        }
    }

    @Test
    void aTouchWaitsWhileTheKeyIsBeingFreedAndOnlyThen() throws Exception {
        UploadGate.Hold h = gate.enter("p");
        UploadGate.KeySweep s = gate.tryBeginKeys("p", new long[]{7}, 20, 60_000);
        CompletableFuture<Void> touch = CompletableFuture.runAsync(() -> h.touch(7));
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> h.touch(8));
        other.get(2, TimeUnit.SECONDS);
        Thread.sleep(100);
        assertFalse(touch.isDone(), "key 7 is being freed");
        s.close();
        touch.get(2, TimeUnit.SECONDS);
        h.release();
    }

    @Test
    void aBatchInProgressRefusesAnotherAndAFullDrain() {
        UploadGate.KeySweep s = gate.tryBeginKeys("p", new long[]{1}, 10, 60_000);
        assertNull(gate.tryBeginKeys("p", new long[]{2}, 10, 60_000));
        assertNull(gate.tryBegin("p", 10, 60_000));
        s.close();
        UploadGate.Sweep full = gate.tryBegin("p", 10, 60_000);
        assertTrue(full != null);
        assertNull(gate.tryBeginKeys("p", new long[]{2}, 10, 60_000));
        full.close();
    }

    @Test
    void aTouchedKeyIsGrantedOnceTheHoldIsRevokedByAge() {
        UploadGate.Hold h = gate.enter("p");
        h.touch(9);
        try (UploadGate.KeySweep s = gate.tryBeginKeys("p", new long[]{9}, 20, 0)) {
            assertEquals(1, s.granted().length, "a hold older than the ticket time-to-live is revoked, not waited for");
        }
        assertTrue(h.revoked());
    }
}
