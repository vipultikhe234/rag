package com.productrag.vectorsync;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code vector.sync.events{status=...}} gauges (see /actuator/metrics/vector.sync.events).
 * Counts are cached for a few seconds so metric scrapes cannot overload the database.
 */
@Component
public class VectorSyncMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(VectorSyncMetrics.class);
    private static final long CACHE_MILLIS = 10_000;

    private final OutboxStore outboxStore;
    private volatile Map<VectorSyncStatus, Long> cached = new EnumMap<>(VectorSyncStatus.class);
    private volatile long cachedAt;

    public VectorSyncMetrics(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (VectorSyncStatus status : VectorSyncStatus.values()) {
            Gauge.builder("vector.sync.events", this, metrics -> metrics.count(status))
                    .tag("status", status.name())
                    .description("Number of vector synchronization outbox events by status")
                    .register(registry);
        }
    }

    private double count(VectorSyncStatus status) {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MILLIS) {
            try {
                cached = outboxStore.countByStatus();
            } catch (RuntimeException ex) {
                log.debug("Could not refresh vector sync metrics: {}", ex.getMessage());
            }
            cachedAt = now;
        }
        return cached.getOrDefault(status, 0L);
    }
}
