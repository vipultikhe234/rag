package com.productrag.vectorsync;

import java.util.UUID;

/**
 * An outbox event leased by a worker. {@code aggregateType} is PRODUCT or a {@code CatalogEntityType} name;
 * {@code attempts} already includes the current attempt.
 */
public record ClaimedEvent(UUID id, String aggregateType, UUID aggregateId, VectorSyncEventType eventType,
                           int attempts, int maxAttempts) {
}
