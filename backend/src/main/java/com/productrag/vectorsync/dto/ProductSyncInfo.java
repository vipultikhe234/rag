package com.productrag.vectorsync.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Vector synchronization state of one product.
 *
 * @param state SYNCED, PENDING, FAILED or NOT_SYNCED
 */
public record ProductSyncInfo(
        String state,
        UUID lastEventId,
        String lastEventType,
        String lastEventStatus,
        String lastError,
        boolean autoRetryScheduled,
        Instant nextAttemptAt,
        String embeddingModel,
        Instant embeddedAt) {

    public static final String SYNCED = "SYNCED";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    public static final String NOT_SYNCED = "NOT_SYNCED";
}
