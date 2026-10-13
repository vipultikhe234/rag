package com.productrag.support;

import com.productrag.embedding.EmbeddingMetadata;
import com.productrag.embedding.ProductEmbeddingStore;
import com.productrag.embedding.ScoredProduct;
import com.productrag.product.filter.ProductFilter;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Behaves like the pgvector store for unit tests (including the source-version guard). */
public class InMemoryEmbeddingStore implements ProductEmbeddingStore {

    public record Row(String content, String hash, String model, float[] vector, long version, Instant sourceUpdatedAt) {
    }

    private final Map<UUID, Row> rows = new ConcurrentHashMap<>();

    public Map<UUID, Row> rows() {
        return rows;
    }

    @Override
    public Optional<EmbeddingMetadata> findMetadata(UUID productId) {
        return Optional.ofNullable(rows.get(productId)).map(row -> toMetadata(productId, row));
    }

    @Override
    public Map<UUID, EmbeddingMetadata> findMetadata(Collection<UUID> productIds) {
        Map<UUID, EmbeddingMetadata> result = new HashMap<>();
        productIds.forEach(id -> findMetadata(id).ifPresent(m -> result.put(id, m)));
        return result;
    }

    @Override
    public boolean upsert(UUID productId, String content, String contentHash, String embeddingModel,
                          float[] embedding, long sourceVersion, Instant sourceUpdatedAt) {
        Row existing = rows.get(productId);
        if (existing != null && existing.version() > sourceVersion) {
            return false;
        }
        rows.put(productId, new Row(content, contentHash, embeddingModel, embedding, sourceVersion, sourceUpdatedAt));
        return true;
    }

    @Override
    public void markCurrent(UUID productId, long sourceVersion, Instant sourceUpdatedAt) {
        rows.computeIfPresent(productId, (id, row) -> row.version() < sourceVersion
                ? new Row(row.content(), row.hash(), row.model(), row.vector(), sourceVersion, sourceUpdatedAt)
                : row);
    }

    @Override
    public int deleteByProductId(UUID productId) {
        return rows.remove(productId) == null ? 0 : 1;
    }

    @Override
    public List<ScoredProduct> searchActive(float[] queryVector, String embeddingModel, double minSimilarity,
                                            int limit, List<ProductFilter> filters) {
        throw new UnsupportedOperationException("Similarity search is covered by the PostgreSQL integration tests");
    }

    @Override
    public long countEmbeddings() {
        return rows.size();
    }

    @Override
    public long countActiveProductsWithoutCurrentEmbedding(String embeddingModel) {
        return 0;
    }

    private static EmbeddingMetadata toMetadata(UUID productId, Row row) {
        return new EmbeddingMetadata(productId, row.hash(), row.model(), row.version(), row.sourceUpdatedAt(),
                row.sourceUpdatedAt());
    }
}
