package com.productrag.catalog.variant;

import com.productrag.catalog.CatalogStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Variant list filters. {@code categoryId} includes subcategories and, like {@code brandId}, applies through the
 * parent product; {@code options} maps an option name to the required value (both case-insensitive).
 */
public record VariantListQuery(
        String search,
        UUID productId,
        String sku,
        CatalogStatus status,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        StockFilter stock,
        UUID categoryId,
        UUID brandId,
        Map<String, String> options) {

    public VariantListQuery {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
