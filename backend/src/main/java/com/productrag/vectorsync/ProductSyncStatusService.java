package com.productrag.vectorsync;

import com.productrag.ai.EmbeddingClient;
import com.productrag.embedding.EmbeddingMetadata;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.vectorsync.dto.ProductSyncInfo;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Derives a per-product sync state from the latest outbox event and the stored embedding. */
@Service
public class ProductSyncStatusService {

    private final OutboxStore outboxStore;
    private final ProductEmbeddingStore embeddingStore;
    private final EmbeddingClient embeddingClient;

    public ProductSyncStatusService(OutboxStore outboxStore, ProductEmbeddingStore embeddingStore,
                                    EmbeddingClient embeddingClient) {
        this.outboxStore = outboxStore;
        this.embeddingStore = embeddingStore;
        this.embeddingClient = embeddingClient;
    }

    public Map<UUID, ProductSyncInfo> statusFor(Collection<UUID> productIds) {
        Map<UUID, ProductSyncInfo> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        Map<UUID, OutboxStore.LatestEvent> latestEvents = outboxStore.latestEventPerProduct(productIds);
        Map<UUID, EmbeddingMetadata> embeddings = embeddingStore.findMetadata(productIds);
        for (UUID id : productIds) {
            result.put(id, derive(latestEvents.get(id), embeddings.get(id)));
        }
        return result;
    }

    public ProductSyncInfo statusFor(UUID productId) {
        return statusFor(List.of(productId)).get(productId);
    }

    ProductSyncInfo derive(OutboxStore.LatestEvent latest, EmbeddingMetadata embedding) {
        boolean embeddedWithCurrentModel = embedding != null
                && embedding.embeddingModel().equals(embeddingClient.modelName());
        String state;
        if (latest == null) {
            state = embeddedWithCurrentModel ? ProductSyncInfo.SYNCED : ProductSyncInfo.NOT_SYNCED;
        } else {
            state = switch (latest.status()) {
                case PENDING, PROCESSING -> ProductSyncInfo.PENDING;
                case FAILED -> ProductSyncInfo.FAILED;
                case COMPLETED -> embeddedWithCurrentModel ? ProductSyncInfo.SYNCED : ProductSyncInfo.NOT_SYNCED;
            };
        }
        boolean autoRetry = latest != null && latest.status() == VectorSyncStatus.FAILED && latest.retryable()
                && latest.attempts() < latest.maxAttempts();
        return new ProductSyncInfo(
                state,
                latest == null ? null : latest.eventId(),
                latest == null ? null : latest.eventType().name(),
                latest == null ? null : latest.status().name(),
                latest == null ? null : latest.lastError(),
                autoRetry,
                autoRetry ? latest.nextAttemptAt() : null,
                embedding == null ? null : embedding.embeddingModel(),
                embedding == null ? null : embedding.updatedAt());
    }
}
