package com.productrag.rag;

import com.productrag.product.Product;

/**
 * An authoritative product row (loaded from PostgreSQL after retrieval) with how it matched.
 *
 * @param relevanceScore cosine similarity for semantic matches, null for exact/structured matches
 */
public record RetrievedProduct(Product product, Double relevanceScore, MatchType matchType) {
}
