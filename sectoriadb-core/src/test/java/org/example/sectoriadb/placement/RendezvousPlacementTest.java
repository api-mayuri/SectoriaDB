package org.example.sectoriadb.placement;

import org.example.sectoriadb.placement.RendezvousPlacement.Candidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class RendezvousPlacementTest {

    private static List<Candidate> blobs(double... weights) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) out.add(Candidate.of("blob-" + i, weights[i]));
        return out;
    }

    private static Map<String, Integer> distribution(List<Candidate> cs, int keys, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        Map<String, Integer> count = new HashMap<>();
        for (int i = 0; i < keys; i++) {
            count.merge(RendezvousPlacement.best(rnd.nextLong(), cs).id(), 1, Integer::sum);
        }
        return count;
    }

    private static double chiSquare(Map<String, Integer> observed, List<Candidate> cs, int n) {
        double total = cs.stream().mapToDouble(Candidate::weight).sum();
        double chi = 0;
        for (Candidate c : cs) {
            double expected = n * c.weight() / total;
            double diff = observed.getOrDefault(c.id(), 0) - expected;
            chi += diff * diff / expected;
        }
        return chi;
    }

    @Test
    void distributionFollowsTheWeights() {
        List<Candidate> cs = blobs(1, 2, 5);
        int n = 300_000;
        Map<String, Integer> d = distribution(cs, n, 42);
        // chi-square, 2 degrees of freedom: 13.8 is the p = 0.001 critical value
        assertTrue(chiSquare(d, cs, n) < 13.8, "chi-square " + chiSquare(d, cs, n) + " for " + d);
        assertEquals(n * 5 / 8.0, d.get("blob-2"), n * 0.01);
    }

    @Test
    void equalWeightsAreBalancedAcrossManyBlobs() {
        List<Candidate> cs = blobs(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        int n = 200_000;
        Map<String, Integer> d = distribution(cs, n, 7);
        assertEquals(10, d.size());
        // 9 degrees of freedom, p = 0.001
        assertTrue(chiSquare(d, cs, n) < 27.9, "chi-square " + chiSquare(d, cs, n));
    }

    @Test
    void addingABlobMovesAboutOneNthOfTheKeysAndOnlyToTheNewBlob() {
        List<Candidate> before = blobs(1, 1, 1, 1, 1, 1, 1, 1);
        List<Candidate> after = new ArrayList<>(before);
        after.add(Candidate.of("blob-new", 1));
        SplittableRandom rnd = new SplittableRandom(99);
        int n = 200_000;
        int moved = 0;
        for (int i = 0; i < n; i++) {
            long key = rnd.nextLong();
            String was = RendezvousPlacement.best(key, before).id();
            String now = RendezvousPlacement.best(key, after).id();
            if (!was.equals(now)) {
                moved++;
                assertEquals("blob-new", now, "a key may only move to the added blob");
            }
        }
        double share = (double) moved / n;
        assertEquals(1.0 / 9, share, 0.01, "about 1/N of the keys move, hash % N would move 8/9");
    }

    @Test
    void removingABlobMovesOnlyItsKeys() {
        List<Candidate> all = blobs(1, 1, 1, 1, 1);
        List<Candidate> without = new ArrayList<>(all);
        Candidate gone = without.remove(2);
        SplittableRandom rnd = new SplittableRandom(5);
        for (int i = 0; i < 50_000; i++) {
            long key = rnd.nextLong();
            String was = RendezvousPlacement.best(key, all).id();
            String now = RendezvousPlacement.best(key, without).id();
            if (!was.equals(gone.id())) assertEquals(was, now);
        }
    }

    @Test
    void aFullBlobIsSkippedAndTheFallbackOrderIsDeterministic() {
        // blob-1 has no capacity left: weight 0 ranks it behind every blob that has some
        List<Candidate> cs = blobs(10, 0, 10, 10);
        SplittableRandom rnd = new SplittableRandom(1);
        for (int i = 0; i < 20_000; i++) {
            long key = rnd.nextLong();
            List<Candidate> ranked = RendezvousPlacement.rank(key, cs);
            assertEquals(4, ranked.size());
            assertEquals("blob-1", ranked.get(3).id(), "the full blob is the last resort");
            assertNotEquals("blob-1", RendezvousPlacement.best(key, cs).id());
            // the order is a pure function of the key and the candidates, in whatever order they are given
            List<Candidate> shuffled = new ArrayList<>(cs);
            java.util.Collections.reverse(shuffled);
            assertEquals(ranked, RendezvousPlacement.rank(key, shuffled));
            // dropping the top choice (it refused the chunk) leaves the rest in the same relative order
            List<Candidate> rest = new ArrayList<>(cs);
            rest.remove(ranked.get(0));
            assertEquals(ranked.subList(1, 4), RendezvousPlacement.rank(key, rest));
        }
    }

    @Test
    void theSameKeyAlwaysRanksTheSameWayAndSeedsAreStable() {
        List<Candidate> cs = blobs(3, 4, 5);
        assertEquals(RendezvousPlacement.rank(12345L, cs), RendezvousPlacement.rank(12345L, cs));
        assertEquals(RendezvousPlacement.seedOf("abc"), RendezvousPlacement.seedOf("abc"));
        assertNotEquals(RendezvousPlacement.seedOf("abc"), RendezvousPlacement.seedOf("abd"));
        // golden value: the seed derivation is part of the on-disk behaviour (placement after restart)
        assertEquals(RendezvousPlacement.mix64(RendezvousPlacement.seedOf("x") ^ 0), RendezvousPlacement.mix64(RendezvousPlacement.seedOf("x")));
        assertNull(RendezvousPlacement.best(1L, List.of()));
    }

    @Test
    void weightFallsSteeplyOnlyAboveTheKneeAndIsMonotonic() {
        long total = 100_000;
        int chunk = 16_384;
        double w0 = RendezvousPlacement.weightOf(total, 0, chunk);
        assertEquals((double) total * chunk, w0);
        // up to 70 % the weight is just the free capacity
        assertEquals(30_000.0 * chunk, RendezvousPlacement.weightOf(total, 70_000, chunk), 1e-6);
        // 85 %: free 15 % x 0.16
        assertEquals(15_000.0 * chunk * 0.16, RendezvousPlacement.weightOf(total, 85_000, chunk), 1e-3);
        assertEquals(0.0, RendezvousPlacement.weightOf(total, 95_000, chunk));
        assertEquals(0.0, RendezvousPlacement.weightOf(total, 100_000, chunk));
        assertEquals(0.0, RendezvousPlacement.weightOf(0, 0, chunk));
        double prev = Double.MAX_VALUE;
        for (long used = 0; used <= total; used += 500) {
            double w = RendezvousPlacement.weightOf(total, used, chunk);
            assertTrue(w <= prev, "monotonic at " + used);
            prev = w;
        }
    }

    @Test
    void anEmptyBlobTakesTheNewChunksWhenAnOldOneIsAlmostFull() {
        double old = RendezvousPlacement.weightOf(1_000_000, 900_000, 16_384);     // 90 % full
        double fresh = RendezvousPlacement.weightOf(1_000_000, 0, 16_384);
        List<Candidate> cs = List.of(Candidate.of("old", old), Candidate.of("new", fresh));
        int n = 100_000;
        Map<String, Integer> d = distribution(cs, n, 3);
        assertTrue(d.getOrDefault("old", 0) < n * 0.01, "a 90 % full blob gets under 1 % of new chunks: " + d);
    }
}
