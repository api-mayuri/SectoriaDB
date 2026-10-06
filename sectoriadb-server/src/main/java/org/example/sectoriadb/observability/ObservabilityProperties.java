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

    public long getGaugeCacheMs() { return gaugeCacheMs; }
    public void setGaugeCacheMs(long v) { this.gaugeCacheMs = v; }
}
