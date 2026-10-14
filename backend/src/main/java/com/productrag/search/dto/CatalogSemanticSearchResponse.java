package com.productrag.search.dto;

import com.productrag.embedding.CatalogEntityType;
import java.util.List;

/**
 * Semantic search over one catalog module (brands, categories, attributes or variants).
 *
 * @param mode    {@code HYBRID} (name match + vector) or {@code KEYWORD_ONLY} when the embedding provider is unavailable
 * @param warning why results may be incomplete, or null
 */
public record CatalogSemanticSearchResponse<T>(
        String query,
        CatalogEntityType entityType,
        String mode,
        String embeddingModel,
        List<Hit<T>> results,
        String warning) {

    /**
     * @param item       the same representation the module's GET-by-id endpoint returns, read live from PostgreSQL
     * @param similarity cosine similarity of the entity's search document to the query, null for name-only matches
     * @param matchType  NAME, SEMANTIC or SEMANTIC_AND_NAME
     */
    public record Hit<T>(T item, Double similarity, String matchType) {
    }
}
