package com.productrag.product.dto;

import com.productrag.product.Product;
import com.productrag.product.ProductStatus;
import com.productrag.vectorsync.dto.ProductSyncInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String sku,
        String description,
        String category,
        String brand,
        BigDecimal price,
        String currency,
        int stockQuantity,
        Map<String, Object> attributes,
        ProductStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        ProductSyncInfo vectorSync) {

    public static ProductResponse from(Product product, ProductSyncInfo syncInfo) {
        return new ProductResponse(product.getId(), product.getName(), product.getSku(), product.getDescription(),
                product.getCategory(), product.getBrand(), product.getPrice(), product.getCurrency(),
                product.getStockQuantity(), product.getAttributes(), product.getStatus(), product.getVersion(),
                product.getCreatedAt(), product.getUpdatedAt(), syncInfo);
    }
}
