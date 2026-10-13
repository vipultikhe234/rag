package com.productrag.vectorsync;

import com.productrag.ai.AiProviderStatus;
import com.productrag.common.api.PageResponse;
import com.productrag.common.exception.ConflictException;
import com.productrag.common.exception.NotFoundException;
import com.productrag.config.AppProperties;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.vectorsync.dto.VectorSyncEventResponse;
import com.productrag.vectorsync.dto.VectorSyncStatusResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class VectorSyncAdminService {

    private final OutboxStore outboxStore;
    private final VectorSyncEventRepository eventRepository;
    private final ProductEmbeddingStore embeddingStore;
    private final AiProviderStatus aiProviderStatus;
    private final ApplicationEventPublisher publisher;
    private final AppProperties.VectorSync settings;

    public VectorSyncAdminService(OutboxStore outboxStore, VectorSyncEventRepository eventRepository,
                                  ProductEmbeddingStore embeddingStore, AiProviderStatus aiProviderStatus,
                                  ApplicationEventPublisher publisher, AppProperties properties) {
        this.outboxStore = outboxStore;
        this.eventRepository = eventRepository;
        this.embeddingStore = embeddingStore;
        this.aiProviderStatus = aiProviderStatus;
        this.publisher = publisher;
        this.settings = properties.vectorSync();
    }

    public VectorSyncStatusResponse status() {
        Map<VectorSyncStatus, Long> counts = outboxStore.countByStatus();
        long failed = counts.getOrDefault(VectorSyncStatus.FAILED, 0L);
        long failedRetryable = outboxStore.countRetryableFailed();
        Page<VectorSyncEvent> recentFailures = eventRepository.findAllByStatus(VectorSyncStatus.FAILED,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new VectorSyncStatusResponse(
                aiProviderStatus.isConfigured(),
                settings.enabled(),
                aiProviderStatus.embeddingModel(),
                aiProviderStatus.embeddingDimensions(),
                counts.getOrDefault(VectorSyncStatus.PENDING, 0L),
                counts.getOrDefault(VectorSyncStatus.PROCESSING, 0L),
                counts.getOrDefault(VectorSyncStatus.COMPLETED, 0L),
                failed,
                failedRetryable,
                failed - failedRetryable,
                outboxStore.oldestPendingCreatedAt().orElse(null),
                embeddingStore.countEmbeddings(),
                embeddingStore.countActiveProductsWithoutCurrentEmbedding(aiProviderStatus.embeddingModel()),
                recentFailures.getContent().stream().map(VectorSyncEventResponse::from).toList());
    }

    public PageResponse<VectorSyncEventResponse> events(VectorSyncStatus status, UUID productId, Pageable pageable) {
        Page<VectorSyncEvent> page;
        if (productId != null) {
            page = eventRepository.findAllByAggregateId(productId, pageable);
        } else if (status != null) {
            page = eventRepository.findAllByStatus(status, pageable);
        } else {
            page = eventRepository.findAll(pageable);
        }
        return PageResponse.from(page, VectorSyncEventResponse::from);
    }

    public VectorSyncEventResponse retry(UUID eventId) {
        VectorSyncEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("SYNC_EVENT_NOT_FOUND", "Vector sync event not found"));
        if (event.getStatus() != VectorSyncStatus.FAILED || !outboxStore.retryFailed(eventId)) {
            throw new ConflictException("SYNC_EVENT_NOT_FAILED",
                    "Only FAILED events can be retried (current status: " + event.getStatus() + ")");
        }
        publisher.publishEvent(new VectorSyncRequested(eventId));
        return eventRepository.findById(eventId).map(VectorSyncEventResponse::from).orElseThrow();
    }

    public int retryAllFailed() {
        int count = outboxStore.retryAllFailed();
        if (count > 0) {
            publisher.publishEvent(new VectorSyncRequested(null));
        }
        return count;
    }

    public int resyncMissing() {
        int count = outboxStore.enqueueResyncForMissingEmbeddings(aiProviderStatus.embeddingModel(),
                settings.maxAttempts());
        if (count > 0) {
            publisher.publishEvent(new VectorSyncRequested(null));
        }
        return count;
    }
}
