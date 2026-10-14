package com.productrag.vectorsync;

import com.productrag.embedding.CatalogEntityType;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** State transitions of outbox events, implemented with short, explicit SQL statements. */
public interface OutboxStore {

    /**
     * Atomically leases up to {@code limit} due events (PENDING, or FAILED but still retryable),
     * skipping rows locked by other workers and products that already have an event in PROCESSING.
     */
    List<ClaimedEvent> claimBatch(String workerId, int limit);

    /** @return false if the lease was lost (e.g. reclaimed as stale) - the result is then not recorded */
    boolean markCompleted(UUID eventId, String workerId, SyncResult result);

    boolean markRetryableFailure(UUID eventId, String workerId, String error, Instant nextAttemptAt);

    boolean markPermanentFailure(UUID eventId, String workerId, String error);

    /** Returns PROCESSING events whose lease is older than {@code timeout} to the queue. */
    int recoverStale(Duration timeout);

    /** Resets a FAILED event to PENDING with a fresh attempt budget. */
    boolean retryFailed(UUID eventId);

    int retryAllFailed();

    /** Enqueues a resync event for every product lacking an embedding for {@code embeddingModel}. */
    int enqueueResyncForMissingEmbeddings(String embeddingModel, int maxAttempts);

    /**
     * Enqueues one event per listed product, skipping products that already have a PENDING event (it will read the
     * latest state anyway). Runs in the caller's transaction when there is one.
     */
    int enqueueForProducts(Collection<UUID> productIds, VectorSyncEventType type, int maxAttempts);

    /** As {@link #enqueueForProducts}, for every product assigned to one of the categories. */
    int enqueueForCategories(Collection<UUID> categoryIds, VectorSyncEventType type, int maxAttempts);

    /** As {@link #enqueueForProducts}, for products with a value for the attribute or an option linked to it. */
    int enqueueForAttribute(UUID attributeId, String slug, VectorSyncEventType type, int maxAttempts);

    /**
     * Enqueues one event per listed brand / category / attribute / variant, skipping entities that already have a
     * PENDING event. The rest of the {@code enqueue*} methods select entities whose search document shows data
     * that just changed; all run in the caller's transaction when there is one.
     */
    int enqueueEntities(CatalogEntityType type, Collection<UUID> ids, VectorSyncEventType eventType, int maxAttempts);

    /** Brands, categories (with all ancestors) and variants of the products. */
    int enqueueProductDependents(Collection<UUID> productIds, int maxAttempts);

    /** The categories and all their ancestors. */
    int enqueueCategoryChains(Collection<UUID> categoryIds, int maxAttempts);

    /** Attributes assigned to any of the categories. */
    int enqueueAttributesOfCategories(Collection<UUID> categoryIds, int maxAttempts);

    /** Variants whose options are linked to the attribute (its unit appears in their option values). */
    int enqueueVariantsOfAttribute(UUID attributeId, int maxAttempts);

    /** ACTIVE brands, categories, attributes and variants without an embedding for {@code embeddingModel}. */
    int enqueueMissingCatalogEmbeddings(String embeddingModel, int maxAttempts);

    Map<VectorSyncStatus, Long> countByStatus();

    long countRetryableFailed();

    Optional<Instant> oldestPendingCreatedAt();

    /** Latest event per product, used to show the sync state next to each product. */
    Map<UUID, LatestEvent> latestEventPerProduct(Collection<UUID> productIds);

    record LatestEvent(UUID eventId, UUID productId, VectorSyncEventType eventType, VectorSyncStatus status,
                       boolean retryable, int attempts, int maxAttempts, String lastError, Instant nextAttemptAt,
                       Instant updatedAt) {
    }
}
