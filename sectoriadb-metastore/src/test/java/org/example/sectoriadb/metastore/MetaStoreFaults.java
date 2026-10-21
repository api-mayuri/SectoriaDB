package org.example.sectoriadb.metastore;

/**
 * Test support shared with other modules through the test-jar of this module: the fault injection of the store
 * itself is package-private, this class is the only public door to it. Never used by production code.
 */
public final class MetaStoreFaults {
    private MetaStoreFaults() {
    }

    /**
     * The next {@code writes} page writes and {@code forces} fsyncs of the store file fail with an
     * {@code IOException(message)}. {@link Integer#MAX_VALUE} means "until cleared"; 0, 0 clears.
     */
    public static void failDisk(MetaStore store, int writes, int forces, String message) {
        store.faults().failDisk(writes, forces, message);
    }
}
