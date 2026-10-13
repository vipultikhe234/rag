package com.productrag.product;

import com.productrag.catalog.variant.StockFilter;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Product list filters. {@code categoryId} also matches products in its subcategories; {@code attributes} maps an
 * attribute slug to the required value. The price range matches the product price of simple products and any active
 * variant price of variant products; {@code stock} uses the product stock (the active-variant total for variant
 * products).
 */
public record ProductListQuery(
        String search,
        String category,
        UUID categoryId,
        UUID brandId,
        ProductStatus status,
        Map<String, String> attributes,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        StockFilter stock,
        Boolean hasVariants) {

    public ProductListQuery {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public ProductListQuery(String search, String category, UUID categoryId, UUID brandId, ProductStatus status,
                            Map<String, String> attributes) {
        this(search, category, categoryId, brandId, status, attributes, null, null, null, null);
    }

    public static ProductListQuery of(String search, String category, ProductStatus status) {
        return new ProductListQuery(search, category, null, null, status, Map.of());
    }
}
