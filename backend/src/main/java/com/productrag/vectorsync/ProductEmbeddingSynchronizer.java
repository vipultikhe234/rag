package com.productrag.vectorsync;

import com.productrag.ai.EmbeddingClient;
import com.productrag.embedding.EmbeddingContentBuilder;
import com.productrag.embedding.EmbeddingMetadata;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.product.Product;
import com.productrag.product.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Reconciles a product's vector with the product's current state in PostgreSQL.
 *
 * <p>Processing is state-based rather than delta-based: whatever the event type, the current product
 * row decides the outcome. This makes processing idempotent (re-processing an event is a no-op) and
 * safe against out-of-order events. No database transaction is open during the embedding call.
 */
@Component
public class ProductEmbeddingSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingSynchronizer.class);

    private final ProductRepository productRepository;
    private final ProductEmbeddingStore embeddingStore;
    private final EmbeddingContentBuilder contentBuilder;
    private final EmbeddingClient embeddingClient;

    public ProductEmbeddingSynchronizer(ProductRepository productRepository, ProductEmbeddingStore embeddingStore,
                                        EmbeddingContentBuilder contentBuilder, EmbeddingClient embeddingClient) {
        this.productRepository = productRepository;
        this.embeddingStore = embeddingStore;
        this.contentBuilder = contentBuilder;
        this.embeddingClient = embeddingClient;
    }

    public SyncResult synchronize(UUID productId) {
        Optional<Product> current = productRepository.findById(productId);
        if (current.isEmpty()) {
            int removed = embeddingStore.deleteByProductId(productId);
            log.debug("Product {} no longer exists; removed {} vector row(s)", productId, removed);
            return SyncResult.VECTORS_DELETED;
        }
        Product product = current.get();
        long version = product.getVersion() == null ? 0 : product.getVersion();
        String content = contentBuilder.build(product);
        if (content.isBlank()) {
            throw new PermanentSyncException("Product " + productId + " has no content to embed");
        }
        String hash = contentBuilder.hash(content);
        String model = embeddingClient.modelName();

        Optional<EmbeddingMetadata> existing = embeddingStore.findMetadata(productId);
        if (existing.isPresent() && existing.get().contentHash().equals(hash)
                && existing.get().embeddingModel().equals(model)) {
            embeddingStore.markCurrent(productId, version, product.getUpdatedAt());
            return SyncResult.UNCHANGED;
        }

        float[] vector = embeddingClient.embed(content);
        if (vector.length != embeddingClient.dimensions()) {
            throw new PermanentSyncException("Embedding model '" + model + "' returned " + vector.length
                    + " dimensions but EMBEDDING_DIMENSIONS is " + embeddingClient.dimensions());
        }
        try {
            boolean written = embeddingStore.upsert(productId, content, hash, model, vector, version,
                    product.getUpdatedAt());
            return written ? SyncResult.EMBEDDED : SyncResult.SUPERSEDED;
        } catch (DataIntegrityViolationException ex) {
            // The product was deleted while we were calling the provider (FK violation): nothing to keep.
            if (!productRepository.existsById(productId)) {
                embeddingStore.deleteByProductId(productId);
                return SyncResult.VECTORS_DELETED;
            }
            throw ex;
        }
    }
}
