package com.productrag.product.dto;

import com.productrag.catalog.option.dto.ProductOptionResponse;
import com.productrag.product.Product;
import com.productrag.product.ProductStatus;
import com.productrag.product.image.ProductImageResponse;
import com.productrag.vectorsync.dto.ProductSyncInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * For variant products {@code price} is the lowest active variant price and {@code stockQuantity} the total stock of
 * active variants; {@code variantSummary} carries the counts and price range.
 */
public record ProductResponse(
        UUID id,
        String name,
        String sku,
        String slug,
        String description,
        String category,
        UUID categoryId,
        String brand,
        UUID brandId,
        BigDecimal price,
        String currency,
        int stockQuantity,
        Map<String, Object> attributes,
        ProductStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        ProductSyncInfo vectorSync,
        boolean hasVariants,
        List<ProductImageResponse> images,
        List<ProductOptionResponse> options,
        VariantSummary variantSummary) {

    public record VariantSummary(long count, long activeCount, BigDecimal minPrice, BigDecimal maxPrice) {

        public static final VariantSummary NONE = new VariantSummary(0, 0, null, null);
    }

    public static ProductResponse from(Product product, ProductSyncInfo syncInfo) {
        return from(product, syncInfo, List.of(), List.of(), VariantSummary.NONE);
    }

    public static ProductResponse from(Product product, ProductSyncInfo syncInfo, List<ProductImageResponse> images,
                                       List<ProductOptionResponse> options, VariantSummary variantSummary) {
        return new ProductResponse(product.getId(), product.getName(), product.getSku(), product.getSlug(),
                product.getDescription(), product.getCategory(), product.getCategoryId(), product.getBrand(),
                product.getBrandId(), product.getPrice(), product.getCurrency(), product.getStockQuantity(),
                product.getAttributes(), product.getStatus(), product.getVersion(), product.getCreatedAt(),
                product.getUpdatedAt(), syncInfo, product.hasVariants(), images, options, variantSummary);
    }
}
