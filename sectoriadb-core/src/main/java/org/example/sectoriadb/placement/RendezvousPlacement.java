package org.example.sectoriadb.placement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Weighted rendezvous (highest random weight, "priority hash") placement of a chunk among the blobs of a pool.
 *
 * <p>Every blob {@code b} gets a score for the chunk key {@code k}:
 *
 * <pre>
 *   u_b     = ((mix64(k ^ seed_b) >>> 11) + 0.5) / 2^53          uniform in (0, 1)
 *   score_b = -w_b / ln(u_b)
 * </pre>
 *
 * and the chunk goes to the blob with the highest score; the full descending order is the deterministic fallback
 * order (the second best is tried when the best is full). {@code -ln(u)} is exponentially distributed with rate 1,
 * so {@code score_b} is {@code w_b} divided by an exponential variable: the probability that blob {@code b} wins is
 * exactly {@code w_b / sum(w)}. Adding a blob moves only the keys for which the new blob scores highest (about
 * {@code 1/N} of them) and never moves a key between two old blobs; there is no ring or table to maintain, the
 * only state is the list of {@code (blobId, weight)}.
 *
 * <p>The class has no I/O and no framework dependencies. It is not authoritative: the place where a chunk really lives
 * is recorded in the chunk index of the metastore, so a later change of weights never moves stored data.
 */
public final class RendezvousPlacement {

    /** Weights at or below this value are raised to it: such a blob ranks last but still takes part in the order. */
    public static final double MIN_WEIGHT = 1e-9;

    /** Fill (used / total slots) up to which the blob's weight is just its free capacity. */
    public static final double FULL_WEIGHT_UP_TO = 0.70;
    /** Fill from which the blob is considered full for placement purposes (weight floor). */
    public static final double NO_WEIGHT_FROM = 0.95;

    private static final double TWO_POW_MINUS_53 = 0x1.0p-53;

    private RendezvousPlacement() {
    }

    /** A blob taking part in the ranking. {@code seed} is derived from the id and is stable across restarts. */
    public record Candidate(String id, double weight, long seed) {
        public static Candidate of(String id, double weight) {
            return new Candidate(id, weight, seedOf(id));
        }
    }

    /** Stable 64-bit seed of a blob id: FNV-1a over the UTF-8 bytes, then the splitmix64 finalizer. */
    public static long seedOf(String blobId) {
        long h = 0xcbf29ce484222325L;
        for (byte b : blobId.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return mix64(h);
    }

    /** The splitmix64 finalizer: a bijection with full avalanche. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Uniform in the open interval (0, 1), never 0 or 1, so {@code ln(u)} is finite and negative. */
    static double unit(long chunkKey, long seed) {
        return ((mix64(chunkKey ^ seed) >>> 11) + 0.5) * TWO_POW_MINUS_53;
    }

    public static double score(long chunkKey, long seed, double weight) {
        return -Math.max(weight, MIN_WEIGHT) / Math.log(unit(chunkKey, seed));
    }

    /** All candidates ordered by descending score (ties, practically impossible, broken by id). */
    public static List<Candidate> rank(long chunkKey, Collection<Candidate> candidates) {
        record Scored(Candidate c, double score) {
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) scored.add(new Scored(c, score(chunkKey, c.seed(), c.weight())));
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.c().id()));
        List<Candidate> out = new ArrayList<>(scored.size());
        for (Scored s : scored) out.add(s.c());
        return out;
    }

    /** The best candidate, or null for an empty collection. */
    public static Candidate best(long chunkKey, Collection<Candidate> candidates) {
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate c : candidates) {
            double s = score(chunkKey, c.seed(), c.weight());
            if (best == null || s > bestScore || (s == bestScore && c.id().compareTo(best.id()) < 0)) {
                best = c;
                bestScore = s;
            }
        }
        return best;
    }

    /**
     * Weight of a cuckoo blob: its free capacity in bytes, scaled by a factor that falls steeply when the table
     * gets full.
     *
     * <p>Free capacity alone makes an emptier blob proportionally more likely, which already balances fill. But the
     * cost of a cuckoo insert (length of the eviction path, number of {@code fsync}s) grows sharply near the limit
     * (the table refuses inserts at about 98 % fill, see doc 03), so above {@link #FULL_WEIGHT_UP_TO} the weight is
     * multiplied by {@code ((0.95 - fill) / 0.25)^2}, reaching zero at {@link #NO_WEIGHT_FROM}: 0.36 at 80 % fill, 0.16 at 85 %,
     * 0.04 at 90 %. A blob with weight zero is only used after every other blob refused the chunk.
     *
     * @param totalSlots slots of the table
     * @param usedSlots  active plus quarantined slots (quarantined slots are not free)
     */
    public static double weightOf(long totalSlots, long usedSlots, int chunkSize) {
        if (totalSlots <= 0) return 0;
        long free = Math.max(0, totalSlots - usedSlots);
        double fill = (double) usedSlots / totalSlots;
        double factor;
        if (fill <= FULL_WEIGHT_UP_TO) {
            factor = 1.0;
        } else if (fill >= NO_WEIGHT_FROM) {
            factor = 0.0;
        } else {
            double x = (NO_WEIGHT_FROM - fill) / (NO_WEIGHT_FROM - FULL_WEIGHT_UP_TO);
            factor = x * x;
        }
        return (double) free * chunkSize * factor;
    }
}
