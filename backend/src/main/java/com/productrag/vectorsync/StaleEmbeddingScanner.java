package com.productrag.vectorsync;

import com.productrag.ai.EmbeddingClient;
import com.productrag.config.AppProperties;
import com.productrag.embedding.EmbeddingContentBuilder;
import com.productrag.embedding.EmbeddingMetadata;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.embedding.ProductSearchContext;
import com.productrag.embedding.ProductSearchContextLoader;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import com.productrag.product.ProductStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Finds ACTIVE products whose stored embedding no longer matches the search document the current code would build
 * (missing, other model, or different content hash, e.g. after the document format or a related category or
 * attribute changed) and queues a re-sync for them. Nothing is embedded or deleted here: the outbox worker
 * re-embeds outside any transaction and replaces each vector with an upsert, so search keeps working throughout.
 * <p>
 * Products are read in keyset batches and every batch is queued on its own, so an interrupted run can simply be
 * started again; products that are already current or already have a pending event are skipped.
 */
@Component
public class StaleEmbeddingScanner {

    static final int BATCH_SIZE = 200;
    private static final UUID SMALLEST_UUID = new UUID(0, 0);

    private final ProductRepository productRepository;
    private final ProductEmbeddingStore embeddingStore;
    private final EmbeddingContentBuilder contentBuilder;
    private final ProductSearchContextLoader contextLoader;
    private final EmbeddingClient embeddingClient;
    private final OutboxStore outboxStore;
    private final ApplicationEventPublisher publisher;
    private final int maxAttempts;

    public StaleEmbeddingScanner(ProductRepository productRepository, ProductEmbeddingStore embeddingStore,
                                 EmbeddingContentBuilder contentBuilder, ProductSearchContextLoader contextLoader,
                                 EmbeddingClient embeddingClient, OutboxStore outboxStore,
                                 ApplicationEventPublisher publisher, AppProperties properties) {
        this.productRepository = productRepository;
        this.embeddingStore = embeddingStore;
        this.contentBuilder = contentBuilder;
        this.contextLoader = contextLoader;
        this.embeddingClient = embeddingClient;
        this.outboxStore = outboxStore;
        this.publisher = publisher;
        this.maxAttempts = properties.vectorSync().maxAttempts();
    }

    public Result refreshStale() {
        String model = embeddingClient.modelName();
        long checked = 0;
        long stale = 0;
        long enqueued = 0;
        UUID after = SMALLEST_UUID;
        while (true) {
            List<Product> batch = productRepository.findByStatusAndIdGreaterThanOrderByIdAsc(ProductStatus.ACTIVE,
                    after, PageRequest.ofSize(BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            List<UUID> ids = batch.stream().map(Product::getId).toList();
            Map<UUID, ProductSearchContext> contexts = contextLoader.load(batch);
            Map<UUID, EmbeddingMetadata> stored = embeddingStore.findMetadata(ids);
            List<UUID> staleIds = new ArrayList<>();
            for (Product product : batch) {
                EmbeddingMetadata metadata = stored.get(product.getId());
                String hash = contentBuilder.hash(contentBuilder.build(product,
                        contexts.getOrDefault(product.getId(), ProductSearchContext.EMPTY)));
                if (metadata == null || !model.equals(metadata.embeddingModel())
                        || !hash.equals(metadata.contentHash())) {
                    staleIds.add(product.getId());
                }
            }
            checked += batch.size();
            stale += staleIds.size();
            enqueued += outboxStore.enqueueForProducts(staleIds, VectorSyncEventType.PRODUCT_RESYNC_REQUESTED,
                    maxAttempts);
            after = ids.getLast();
        }
        if (enqueued > 0) {
            publisher.publishEvent(new VectorSyncRequested(null));
        }
        return new Result(checked, stale, enqueued);
    }

    /**
     * @param stale    products whose embedding is missing or out of date
     * @param enqueued re-sync events created (stale products that already had a pending event are not counted)
     */
    public record Result(long checked, long stale, long enqueued) {
    }
}
