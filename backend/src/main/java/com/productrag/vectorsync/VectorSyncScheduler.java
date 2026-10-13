package com.productrag.vectorsync;

import com.productrag.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Periodic polling (catches events left over after restarts, retries due failures) and stale
 * PROCESSING recovery. Disabled with VECTOR_SYNC_ENABLED=false.
 */
@Component
@ConditionalOnProperty(prefix = "app.vector-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorSyncScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(VectorSyncScheduler.class);

    private final VectorSyncProcessor processor;
    private final AppProperties.VectorSync settings;

    public VectorSyncScheduler(VectorSyncProcessor processor, AppProperties properties) {
        this.processor = processor;
        this.settings = properties.vectorSync();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::poll, settings.pollInterval());
        registrar.addFixedDelayTask(this::recover, settings.recoveryInterval());
    }

    void poll() {
        try {
            processor.drain();
        } catch (RuntimeException ex) {
            log.warn("Vector sync polling failed: {}", ex.getMessage());
        }
    }

    void recover() {
        try {
            processor.recoverStale();
        } catch (RuntimeException ex) {
            log.warn("Stale vector sync recovery failed: {}", ex.getMessage());
        }
    }
}
