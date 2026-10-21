package org.example.sectoriadb.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code sectoriadb.observability.*}. Metrics endpoints themselves are configured with {@code management.*}. */
@ConfigurationProperties(prefix = "sectoriadb.observability")
public class ObservabilityProperties {

    /** Requests slower than this are logged at WARN (request id, operation, status, duration, bytes). 0 disables. */
    private long slowRequestMs = 2000;

    /** How long the state gauges (fill, metastore stats, disk space, object totals) are cached between scrapes. */
    private long gaugeCacheMs = 15_000;

    public long getSlowRequestMs() { return slowRequestMs; }
    public void setSlowRequestMs(long v) { this.slowRequestMs = v; }

    /**
     * How often the per-tree page gauges ({@code sectoriadb_metastore_tree_pages}) are recomputed. Walking the trees reads
     * the whole metadata file and keeps one read transaction open meanwhile, so it runs in the background and rarely;
     * 0 turns it off. The per-tree entry counts are cheap and follow the normal gauge cache instead.
     */
    private long treePagesIntervalMs = 600_000;

    public long getTreePagesIntervalMs() { return treePagesIntervalMs; }
    public void setTreePagesIntervalMs(long v) { this.treePagesIntervalMs = v; }

    public long getGaugeCacheMs() { return gaugeCacheMs; }
    public void setGaugeCacheMs(long v) { this.gaugeCacheMs = v; }
}
