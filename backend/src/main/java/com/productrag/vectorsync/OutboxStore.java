package com.productrag.vectorsync;

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
