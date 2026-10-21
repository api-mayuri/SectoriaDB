package org.example.sectoriadb.engine.hash;

/** The splitmix64 finalizer, the one 64-bit mixer of the engine (bucket hashes, placement seeds). */
public final class Mix64 {
    private Mix64() {
    }

    /** A bijection on 64-bit values with full avalanche. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
