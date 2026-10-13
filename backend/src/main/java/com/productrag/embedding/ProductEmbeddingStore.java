package com.productrag.embedding;

import com.productrag.product.filter.ProductFilter;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence of product embeddings in pgvector. */
public interface ProductEmbeddingStore {

    Optional<EmbeddingMetadata> findMetadata(UUID productId);

    Map<UUID, EmbeddingMetadata> findMetadata(Collection<UUID> productIds);

    /**
     * Inserts or replaces the embedding of a product. A write carrying an older product version than
     * the stored one is ignored, so out-of-order processing can never overwrite newer data.
     *
     * @return true if a row was inserted or updated
     */
    boolean upsert(UUID productId, String content, String contentHash, String embeddingModel, float[] embedding,
                   long sourceVersion, Instant sourceUpdatedAt);

    /** Records that the stored embedding is still current for a newer product version. */
    void markCurrent(UUID productId, long sourceVersion, Instant sourceUpdatedAt);

    int deleteByProductId(UUID productId);

    /**
     * Cosine-similarity search restricted to ACTIVE products, the given embedding model and optional
     * structured filters. Results are ordered by similarity (best first).
     */
    List<ScoredProduct> searchActive(float[] queryVector, String embeddingModel, double minSimilarity, int limit,
                                     List<ProductFilter> filters);

    long countEmbeddings();

    long countActiveProductsWithoutCurrentEmbedding(String embeddingModel);
}
