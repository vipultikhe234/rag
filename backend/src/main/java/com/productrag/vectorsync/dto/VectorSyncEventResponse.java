package com.productrag.vectorsync.dto;

import com.productrag.vectorsync.VectorSyncEvent;
import com.productrag.vectorsync.VectorSyncEventType;
import com.productrag.vectorsync.VectorSyncStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record VectorSyncEventResponse(
        UUID id,
        UUID productId,
        String productName,
        String productSku,
        VectorSyncEventType eventType,
        VectorSyncStatus status,
        int attempts,
        int maxAttempts,
        boolean retryable,
        Instant nextAttemptAt,
        String lastError,
        String result,
        Instant createdAt,
        Instant updatedAt,
        Instant processedAt) {

    public static VectorSyncEventResponse from(VectorSyncEvent event) {
        Map<String, Object> payload = event.getPayload();
        return new VectorSyncEventResponse(event.getId(), event.getAggregateId(),
                payload == null ? null : (String) payload.get("name"),
                payload == null ? null : (String) payload.get("sku"),
                event.getEventType(), event.getStatus(), event.getAttempts(), event.getMaxAttempts(),
                event.isRetryable(), event.getNextAttemptAt(), event.getLastError(), event.getResult(),
                event.getCreatedAt(), event.getUpdatedAt(), event.getProcessedAt());
    }
}
