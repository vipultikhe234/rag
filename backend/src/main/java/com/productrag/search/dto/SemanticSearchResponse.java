package com.productrag.search.dto;

import com.productrag.product.dto.ProductResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * @param mode    {@code HYBRID} (vector + keyword + exact SKU) or {@code KEYWORD_ONLY} when the embedding provider is
 *                unavailable
 * @param warning why results may be incomplete, or null
 */
public record SemanticSearchResponse(
        String query,
        String mode,
        String embeddingModel,
        List<String> appliedFilters,
        List<Hit> results,
        String warning) {

    /**
     * @param similarity       cosine similarity of the product's search document to the query, null for non-vector
     *                         matches
     * @param matchType        EXACT_SKU, SEMANTIC, KEYWORD or SEMANTIC_AND_KEYWORD
     * @param matchingVariants ACTIVE variants satisfying the filters (price and stock read live from PostgreSQL)
     */
    public record Hit(ProductResponse product, Double similarity, String matchType,
                      List<MatchingVariant> matchingVariants) {
    }

    public record MatchingVariant(UUID id, String sku, String variantName, BigDecimal price,
                                  BigDecimal compareAtPrice, int stockQuantity) {
    }
}
