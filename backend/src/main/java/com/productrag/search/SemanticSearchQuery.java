package com.productrag.search;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Semantic product search request. {@code categoryId} also matches subcategories; {@code options} maps an option
 * name to a value that one ACTIVE variant must offer (all pairs on the same variant), and {@code inStock} requires a
 * sellable unit in stock (the product for simple products, a matching variant otherwise). Price bounds apply to the
 * product price of simple products and to the matching variant's price for variant products.
 */
public record SemanticSearchQuery(
        String text,
        UUID categoryId,
        UUID brandId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean inStock,
        Map<String, String> options,
        int limit) {

    public SemanticSearchQuery {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    boolean hasVariantConditions() {
        return minPrice != null || maxPrice != null || inStock || !options.isEmpty();
    }
}
