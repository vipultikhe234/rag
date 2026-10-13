package com.productrag.vectorsync;

import com.productrag.config.AsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts processing right after a product change commits, so vectors are usually fresh within
 * seconds. Purely an optimization: if this notification is lost, the scheduler picks the event up.
 */
@Component
@ConditionalOnProperty(prefix = "app.vector-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(VectorSyncTrigger.class);

    private final VectorSyncProcessor processor;

    public VectorSyncTrigger(VectorSyncProcessor processor) {
        this.processor = processor;
    }

    @Async(AsyncConfig.VECTOR_SYNC_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSyncRequested(VectorSyncRequested event) {
        try {
            processor.drain();
        } catch (RuntimeException ex) {
            log.warn("Immediate vector sync failed; the scheduler will retry: {}", ex.getMessage());
        }
    }
}
