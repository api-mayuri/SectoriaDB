package org.example.sectoriadb.service;

/**
 * A pin on a cached resource (a blob table, an open small-object file): while it is open the cache neither evicts nor
 * closes the resource, so a thread that is using it can never see its channel closed under it. Always use it in
 * try-with-resources; {@link #close()} is idempotent.
 */
public final class ResidentHandle<T> implements AutoCloseable {

    private final ResidentCache<T> owner;
    private final ResidentCache.Entry<T> entry;
    private final T value;
    private boolean released;

    ResidentHandle(ResidentCache<T> owner, ResidentCache.Entry<T> entry, T value) {
        this.owner = owner;
        this.entry = entry;
        this.value = value;
    }

    /** The pinned resource; valid until {@link #close()}. */
    public T get() {
        return value;
    }

    @Override
    public void close() {
        if (released) return;
        released = true;
        owner.release(entry);
    }
}
