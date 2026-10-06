package org.example.sectoriadb.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default (no-op) {@link StorageMetrics}. A real implementation (the server's Micrometer adapter) is declared
 * {@code @Primary} and wins; without one (CLI, tests) the engine simply reports nowhere.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public StorageMetrics noopStorageMetrics() {
        return StorageMetrics.NOOP;
    }
}
