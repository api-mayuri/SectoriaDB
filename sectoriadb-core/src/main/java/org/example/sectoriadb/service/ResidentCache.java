package org.example.sectoriadb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Reference-counted cache with LRU eviction, for resources that are expensive to open and hold memory or file handles
 * (the in-memory slot table of a cuckoo blob, an open small-object file).
 *
 * <ul>
 *   <li>{@link #acquire} returns a {@link ResidentHandle}; the resource stays open at least until every handle is
 *       closed. A resource is closed only when it is out of the cache AND its count is zero, so the race of stage 01
 *       (an evicted table closed under a thread that still uses it: {@code ClosedChannelException}) cannot happen.</li>
 *   <li>Eviction is bounded by the number of resident resources and an optional memory budget; the least recently
 *       acquired resource with count zero (and for which {@code evictable} holds) goes first. A cache whose members are
 *       all in use or not evictable temporarily exceeds its limits instead of blocking: the limit is soft.</li>
 *   <li>{@link #evict} (explicit: the blob is replaced or deleted) removes the resource at once; it is closed when the
 *       last handle is released.</li>
 * </ul>
 * One monitor guards the map and the counters; it is never held across a load, a close or any file access except the
 * cheap {@code onEvict} callback.
 */
final class ResidentCache<T> {

    private static final Logger log = LoggerFactory.getLogger(ResidentCache.class);

    @FunctionalInterface
    interface Loader<T> {
        T load(String id) throws IOException;
    }

    @FunctionalInterface
    interface Closer<T> {
        void close(T value) throws IOException;
    }

    /** Called for a resource that the LRU policy is evicting, under the cache monitor: must be cheap and not block. */
    @FunctionalInterface
    interface EvictionListener<T> {
        void evicting(String id, T value);
    }

    static final class Entry<T> {
        final String id;
        final CompletableFuture<T> future = new CompletableFuture<>();
        long weight;
        int refs;
        boolean retired;      // out of the map: closed when the last handle is released
        boolean closed;

        Entry(String id) {
            this.id = id;
        }
    }

    private final String name;
    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<T> weigher;
    private final Predicate<T> evictable;
    private final Closer<T> closer;
    private final EvictionListener<T> listener;
    private final LinkedHashMap<String, Entry<T>> map = new LinkedHashMap<>(16, 0.75f, true);   // access order: eldest first
    private long totalWeight;
    private long evictions;

    ResidentCache(String name, int maxEntries, long maxWeight, ToLongFunction<T> weigher, Predicate<T> evictable,
                  Closer<T> closer, EvictionListener<T> listener) {
        this.name = name;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxWeight = Math.max(0, maxWeight);
        this.weigher = weigher;
        this.evictable = evictable;
        this.closer = closer;
        this.listener = listener;
    }

    ResidentHandle<T> acquire(String id, Loader<T> loader) throws IOException {
        Entry<T> e;
        boolean loading = false;
        synchronized (this) {
            e = map.get(id);   // marks it most recently used
            if (e == null) {
                e = new Entry<>(id);
                map.put(id, e);
                loading = true;
            }
            e.refs++;
        }
        T value;
        if (loading) {
            try {
                value = loader.load(id);
            } catch (IOException | RuntimeException | Error ex) {
                synchronized (this) {
                    map.remove(id, e);
                    e.retired = true;
                    e.closed = true;
                    e.refs--;
                }
                e.future.completeExceptionally(ex);
                throw ex;
            }
            synchronized (this) {
                e.weight = weigher.applyAsLong(value);
                if (!e.retired) totalWeight += e.weight;
                e.future.complete(value);   // under the monitor: retire() must see weight and completion together
            }
        } else {
            try {
                value = e.future.get();
            } catch (InterruptedException ie) {
                release(e);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for " + name + " " + id + " to load", ie);
            } catch (ExecutionException ex) {
                release(e);
                Throwable c = ex.getCause();
                if (c instanceof IOException io) throw io;
                if (c instanceof RuntimeException re) throw re;
                if (c instanceof Error err) throw err;
                throw new IOException(c);
            }
        }
        if (loading) shrink();
        return new ResidentHandle<>(this, e, value);
    }

    /** The resource if it is resident and loaded (pinned), else null. Never loads. */
    ResidentHandle<T> acquireIfResident(String id) {
        Entry<T> e;
        synchronized (this) {
            e = map.get(id);
            if (e == null || !e.future.isDone() || e.future.isCompletedExceptionally()) return null;
            e.refs++;
        }
        return new ResidentHandle<>(this, e, e.future.join());
    }

    /** The resource if it is resident and loaded, without pinning or touching the LRU order; only for reading plain state. */
    synchronized T peek(String id) {
        Entry<T> e = map.get(id);
        if (e == null || !e.future.isDone() || e.future.isCompletedExceptionally()) return null;
        return e.future.join();
    }

    void release(Entry<T> e) {
        T toClose = null;
        synchronized (this) {
            e.refs--;
            if (e.retired && e.refs == 0 && !e.closed) {
                e.closed = true;
                toClose = e.future.getNow(null);
            }
        }
        if (toClose != null) closeQuietly(e.id, toClose);
        shrink();
    }

    /** Removes the resource from the cache now; it is closed once the last handle is released (at once if none). */
    void evict(String id) {
        T toClose = null;
        Entry<T> e;
        synchronized (this) {
            e = map.remove(id);
            if (e == null) return;
            retire(e);
            if (e.refs == 0 && !e.closed) {
                e.closed = true;
                toClose = e.future.getNow(null);
            }
        }
        if (toClose != null) closeQuietly(id, toClose);
    }

    /** Closes everything regardless of handles (shutdown). */
    void closeAll() {
        List<Entry<T>> all;
        synchronized (this) {
            all = new ArrayList<>(map.values());
            map.clear();
            for (Entry<T> e : all) retire(e);
            for (Entry<T> e : all) e.closed = true;
        }
        for (Entry<T> e : all) {
            T v = e.future.getNow(null);
            if (v != null) closeQuietly(e.id, v);
        }
    }

    /** The loaded resources, a snapshot (not pinned: use only for reading plain state). */
    synchronized Collection<T> snapshot() {
        List<T> out = new ArrayList<>(map.size());
        for (Entry<T> e : map.values()) {
            T v = e.future.getNow(null);
            if (v != null) out.add(v);
        }
        return out;
    }

    synchronized int size() {
        return map.size();
    }

    synchronized long weight() {
        return totalWeight;
    }

    synchronized long evictions() {
        return evictions;
    }

    // ------------------------------------------------------------------------------------------------------

    private void retire(Entry<T> e) {   // monitor held
        if (!e.retired) {
            e.retired = true;
            if (e.future.isDone() && !e.future.isCompletedExceptionally()) totalWeight -= e.weight;
        }
    }

    /** Evicts least recently used idle resources while the cache is over its limits. */
    private void shrink() {
        List<Entry<T>> victims = null;
        synchronized (this) {
            while (map.size() > maxEntries || (maxWeight > 0 && totalWeight > maxWeight && map.size() > 1)) {
                Entry<T> victim = null;
                for (Iterator<Entry<T>> it = map.values().iterator(); it.hasNext(); ) {
                    Entry<T> c = it.next();
                    if (c.refs == 0 && c.future.isDone() && !c.future.isCompletedExceptionally()
                            && evictable.test(c.future.join())) {
                        victim = c;
                        it.remove();
                        break;
                    }
                }
                if (victim == null) break;   // everything is in use: the limit is soft
                retire(victim);
                victim.closed = true;
                evictions++;
                try {
                    if (listener != null) listener.evicting(victim.id, victim.future.join());
                } catch (RuntimeException ex) {
                    log.debug("{} eviction listener failed for {}: {}", name, victim.id, ex.toString());
                }
                if (victims == null) victims = new ArrayList<>();
                victims.add(victim);
            }
        }
        if (victims != null) {
            for (Entry<T> v : victims) {
                log.debug("Evicted {} {} from the cache (LRU)", name, v.id);
                closeQuietly(v.id, v.future.join());
            }
        }
    }

    private void closeQuietly(String id, T value) {
        try {
            closer.close(value);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not close {} {}: {}", name, id, e.getMessage());
        }
    }
}
