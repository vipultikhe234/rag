package com.productrag.vectorsync;

import java.util.UUID;

/** An outbox event leased by a worker. {@code attempts} already includes the current attempt. */
public record ClaimedEvent(UUID id, UUID productId, VectorSyncEventType eventType, int attempts, int maxAttempts) {
}
