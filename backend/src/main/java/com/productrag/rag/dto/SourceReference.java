package com.productrag.rag.dto;

import com.productrag.product.Product;
import com.productrag.product.ProductStatus;
import com.productrag.rag.RetrievedProduct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Product reference returned alongside an answer; values are authoritative at answer time. */
public record SourceReference(
        UUID productId,
        String name,
        String sku,
        String category,
        String brand,
        BigDecimal price,
        String currency,
        Integer stockQuantity,
        ProductStatus status,
        Double relevanceScore,
        String matchType) {

    public static SourceReference from(RetrievedProduct retrieved) {
        Product p = retrieved.product();
        Double score = retrieved.relevanceScore() == null ? null
                : BigDecimal.valueOf(retrieved.relevanceScore()).setScale(4, RoundingMode.HALF_UP).doubleValue();
        return new SourceReference(p.getId(), p.getName(), p.getSku(), p.getCategory(), p.getBrand(), p.getPrice(),
                p.getCurrency(), p.getStockQuantity(), p.getStatus(), score, retrieved.matchType().name());
    }
}
