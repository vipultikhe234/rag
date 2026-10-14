package com.productrag.vectorsync;

import com.productrag.ai.AiProviderStatus;
import com.productrag.config.AppProperties;
import com.productrag.embedding.CatalogEntityType;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Claims outbox events and reconciles the affected product vectors.
 *
 * <ul>
 *   <li>Success: event becomes COMPLETED with the {@link SyncResult}.</li>
 *   <li>Transient failure with attempts left: FAILED + retryable, re-claimed after exponential backoff.</li>
 *   <li>Permanent failure or attempts exhausted: FAILED + not retryable; an admin can retry it.</li>
 * </ul>
 * While no AI provider is configured nothing is claimed, so events wait as PENDING without consuming attempts.
 */
@Component
public class VectorSyncProcessor {

    private static final Logger log = LoggerFactory.getLogger(VectorSyncProcessor.class);
    private static final int MAX_EVENTS_PER_DRAIN = 1000;

    private final OutboxStore outboxStore;
    private final ProductEmbeddingSynchronizer synchronizer;
    private final CatalogEmbeddingSynchronizer catalogSynchronizer;
    private final SyncFailureClassifier classifier;
    private final BackoffPolicy backoffPolicy;
    private final AiProviderStatus aiProviderStatus;
    private final int batchSize;
    private final Duration staleTimeout;
    private final Clock clock;
    private final String workerId;
    private final AtomicBoolean warnedNotConfigured = new AtomicBoolean();

    @Autowired
    public VectorSyncProcessor(OutboxStore outboxStore, ProductEmbeddingSynchronizer synchronizer,
                               CatalogEmbeddingSynchronizer catalogSynchronizer, SyncFailureClassifier classifier,
                               BackoffPolicy backoffPolicy, AiProviderStatus aiProviderStatus,
                               AppProperties properties) {
        this(outboxStore, synchronizer, catalogSynchronizer, classifier, backoffPolicy, aiProviderStatus,
                properties.vectorSync().batchSize(), properties.vectorSync().staleProcessingTimeout(),
                Clock.systemUTC(), defaultWorkerId());
    }

    VectorSyncProcessor(OutboxStore outboxStore, ProductEmbeddingSynchronizer synchronizer,
                        CatalogEmbeddingSynchronizer catalogSynchronizer, SyncFailureClassifier classifier,
                        BackoffPolicy backoffPolicy, AiProviderStatus aiProviderStatus, int batchSize,
                        Duration staleTimeout, Clock clock, String workerId) {
        this.outboxStore = outboxStore;
        this.synchronizer = synchronizer;
        this.catalogSynchronizer = catalogSynchronizer;
        this.classifier = classifier;
        this.backoffPolicy = backoffPolicy;
        this.aiProviderStatus = aiProviderStatus;
        this.batchSize = batchSize;
        this.staleTimeout = staleTimeout;
        this.clock = clock;
        this.workerId = workerId;
    }

    /** Processes one batch; returns the number of events claimed. */
    public int processBatch() {
        if (!aiProviderStatus.isConfigured()) {
            if (warnedNotConfigured.compareAndSet(false, true)) {
                log.warn("OPENAI_API_KEY is not configured: vector sync events stay PENDING until it is set.");
            }
            return 0;
        }
        List<ClaimedEvent> claimed = outboxStore.claimBatch(workerId, batchSize);
        for (ClaimedEvent event : claimed) {
            process(event);
        }
        return claimed.size();
    }

    /** Processes batches until the queue has no due events (bounded per call). */
    public int drain() {
        int total = 0;
        int processed;
        do {
            processed = processBatch();
            total += processed;
        } while (processed == batchSize && total < MAX_EVENTS_PER_DRAIN);
        return total;
    }

    public int recoverStale() {
        int recovered = outboxStore.recoverStale(staleTimeout);
        if (recovered > 0) {
            log.warn("Recovered {} vector sync event(s) stuck in PROCESSING for more than {}", recovered,
                    staleTimeout);
        }
        return recovered;
    }

    void process(ClaimedEvent event) {
        try {
            SyncResult result = synchronize(event);
            if (!outboxStore.markCompleted(event.id(), workerId, result)) {
                log.warn("Lease on vector sync event {} was lost before completion; result not recorded", event.id());
            }
            log.debug("Vector sync event {} ({}) for {} {} completed: {}", event.id(), event.eventType(),
                    subject(event), event.aggregateId(), result);
        } catch (RuntimeException ex) {
            handleFailure(event, ex);
        }
    }

    private SyncResult synchronize(ClaimedEvent event) {
        if (event.aggregateType() == null || "PRODUCT".equals(event.aggregateType())) {
            return synchronizer.synchronize(event.aggregateId());
        }
        CatalogEntityType type;
        try {
            type = CatalogEntityType.valueOf(event.aggregateType());
        } catch (IllegalArgumentException ex) {
            throw new PermanentSyncException("Unknown aggregate type " + event.aggregateType());
        }
        return catalogSynchronizer.synchronize(type, event.aggregateId());
    }

    private static String subject(ClaimedEvent event) {
        return event.aggregateType() == null ? "product" : event.aggregateType().toLowerCase(Locale.ROOT);
    }

    private void handleFailure(ClaimedEvent event, RuntimeException ex) {
        SyncFailureClassifier.Kind kind = classifier.classify(ex);
        String message = classifier.describe(ex);
        if (kind == SyncFailureClassifier.Kind.TRANSIENT && event.attempts() < event.maxAttempts()) {
            Instant next = clock.instant().plus(backoffPolicy.delayAfter(event.attempts()));
            outboxStore.markRetryableFailure(event.id(), workerId, message, next);
            log.warn("Vector sync event {} for {} {} failed (attempt {}/{}), retrying at {}: {}", event.id(),
                    subject(event), event.aggregateId(), event.attempts(), event.maxAttempts(), next, message);
        } else {
            String finalMessage = kind == SyncFailureClassifier.Kind.TRANSIENT
                    ? "Retry attempts exhausted. Last error: " + message : message;
            outboxStore.markPermanentFailure(event.id(), workerId, finalMessage);
            log.error("Vector sync event {} for {} {} failed permanently: {}", event.id(), subject(event),
                    event.aggregateId(), finalMessage);
        }
    }

    public String workerId() {
        return workerId;
    }

    private static String defaultWorkerId() {
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        return (runtime + "-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
