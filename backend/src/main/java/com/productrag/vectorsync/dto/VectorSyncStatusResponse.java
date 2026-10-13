package com.productrag.vectorsync.dto;

import java.time.Instant;
import java.util.List;

public record VectorSyncStatusResponse(
        boolean aiProviderConfigured,
        boolean workerEnabled,
        String embeddingModel,
        int embeddingDimensions,
        long pending,
        long processing,
        long completed,
        long failed,
        long failedRetryable,
        long failedPermanent,
        Instant oldestPendingAt,
        long embeddedProducts,
        long activeProductsWithoutEmbedding,
        List<VectorSyncEventResponse> recentFailures) {
}
