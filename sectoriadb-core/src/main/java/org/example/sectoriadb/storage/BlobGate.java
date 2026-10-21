package org.example.sectoriadb.storage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Write gate, freeze flag and redirects of blob files: the in-memory state that lets a resize replace one blob of a
 * multi-blob pool while uploads run. It knows nothing about tables or files; {@link HashTableCache}
 * decides what is resident, this class decides whether a blob may still be written to.
 *
 * <ul>
 *   <li><b>gate</b>: writers hold {@link #writerGate} around "check {@link #isFrozen}, then insert", so a resize that
 *       froze the blob knows that no insert is in flight and none can start;</li>
 *   <li><b>frozen</b>: no new chunks are placed into the blob (memory only, a restart forgets it);</li>
 *   <li><b>redirect</b>: the blob was replaced by another one; uploads that placed chunks into the old blob are
 *       re-pointed at commit, and the old blob must never be loaded again.</li>
 * </ul>
 */
public class BlobGate {

    private final ConcurrentHashMap<String, ReentrantReadWriteLock> gates = new ConcurrentHashMap<>();
    private final Set<String> frozen = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> redirects = new ConcurrentHashMap<>();

    private ReentrantReadWriteLock gate(String blobId) {
        return gates.computeIfAbsent(blobId, k -> new ReentrantReadWriteLock());
    }

    /**
     * Writers hold this read lock around "check {@link #isFrozen}, then insert into the table", so a resize that
     * froze the blob knows that no insert is in flight and none can start.
     */
    public ReentrantReadWriteLock.ReadLock writerGate(String blobId) {
        return gate(blobId).readLock();
    }

    public boolean isFrozen(String blobId) {
        return frozen.contains(blobId);
    }

    /** Stops new chunks from being placed into the blob and waits for the inserts that are running. */
    public void freeze(String blobId) {
        ReentrantReadWriteLock.WriteLock w = gate(blobId).writeLock();
        w.lock();
        try {
            frozen.add(blobId);
        } finally {
            w.unlock();
        }
    }

    public void unfreeze(String blobId) {
        frozen.remove(blobId);
    }

    /** Records that {@code oldBlobId} was replaced by {@code newBlobId}: uploads that placed chunks into the old blob are re-pointed at commit. */
    public void redirect(String oldBlobId, String newBlobId) {
        redirects.put(oldBlobId, newBlobId);
    }

    /** True once a resize has replaced the blob (it must never be loaded again: its file is about to disappear). */
    public boolean isReplaced(String blobId) {
        return redirects.containsKey(blobId);
    }

    /** Follows the replacement chain of a blob id (the id itself if it was never replaced). */
    public String resolveRedirect(String blobId) {
        String id = blobId;
        for (int i = 0; i < 16; i++) {
            String next = redirects.get(id);
            if (next == null) return id;
            id = next;
        }
        return id;
    }

    /** Forgets the gate and the freeze flag of a blob that is being removed (a replaced blob stays frozen for good). */
    public void forget(String blobId) {
        gates.remove(blobId);                                        // a stale handle keeps the gate object it already holds
        if (!redirects.containsKey(blobId)) frozen.remove(blobId);
    }
}
