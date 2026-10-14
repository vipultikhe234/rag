package com.productrag.vectorsync.dto;

import com.productrag.embedding.CatalogEntityType;
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
        List<CatalogIndex> catalogIndex,
        List<VectorSyncEventResponse> recentFailures) {

    /** Embeddings stored for one entity type, and ACTIVE entities of that type without one for the current model. */
    public record CatalogIndex(CatalogEntityType entityType, long embedded, long activeWithoutEmbedding) {
    }
}
