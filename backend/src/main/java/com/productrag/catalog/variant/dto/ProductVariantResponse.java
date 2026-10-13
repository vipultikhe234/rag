package com.productrag.catalog.variant.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.variant.ProductVariant;
import com.productrag.catalog.variant.StockLevel;
import com.productrag.product.Product;
import com.productrag.product.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProductVariantResponse(
        UUID id,
        UUID productId,
        String productName,
        String productSku,
        ProductStatus productStatus,
        String currency,
        String sku,
        String variantName,
        Map<String, Object> attributes,
        BigDecimal price,
        BigDecimal salePrice,
        BigDecimal effectivePrice,
        int stockQuantity,
        StockLevel stockLevel,
        CatalogStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductVariantResponse from(ProductVariant variant, int lowStockThreshold) {
        Product product = variant.getProduct();
        BigDecimal effective = variant.getSalePrice() != null ? variant.getSalePrice() : variant.getPrice();
        return new ProductVariantResponse(variant.getId(), product.getId(), product.getName(), product.getSku(),
                product.getStatus(), product.getCurrency(), variant.getSku(), variant.getVariantName(),
                variant.getAttributes(), variant.getPrice(), variant.getSalePrice(), effective,
                variant.getStockQuantity(), StockLevel.of(variant.getStockQuantity(), lowStockThreshold),
                variant.getStatus(), variant.getVersion(), variant.getCreatedAt(), variant.getUpdatedAt());
    }
}
