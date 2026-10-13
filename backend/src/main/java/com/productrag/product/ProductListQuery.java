package com.productrag.product;

import java.util.Map;
import java.util.UUID;

/**
 * Product list filters. {@code categoryId} also matches products in its subcategories; {@code attributes} maps an
 * attribute slug to the required value.
 */
public record ProductListQuery(
        String search,
        String category,
        UUID categoryId,
        UUID brandId,
        ProductStatus status,
        Map<String, String> attributes) {

    public ProductListQuery {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ProductListQuery of(String search, String category, ProductStatus status) {
        return new ProductListQuery(search, category, null, null, status, Map.of());
    }
}
