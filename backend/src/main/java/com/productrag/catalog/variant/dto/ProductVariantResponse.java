package com.productrag.catalog.variant.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.option.dto.SelectedOption;
import com.productrag.catalog.variant.ProductVariant;
import com.productrag.catalog.variant.StockLevel;
import com.productrag.product.Product;
import com.productrag.product.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code variantName} is the generated title, e.g. "Black / 256 GB". */
public record ProductVariantResponse(
        UUID id,
        UUID productId,
        String productName,
        String productSku,
        ProductStatus productStatus,
        UUID categoryId,
        String category,
        UUID brandId,
        String brand,
        String currency,
        String sku,
        String variantName,
        List<SelectedOption> options,
        Map<String, Object> attributes,
        BigDecimal price,
        BigDecimal compareAtPrice,
        boolean onSale,
        int stockQuantity,
        StockLevel stockLevel,
        String barcode,
        UUID imageId,
        String imageUrl,
        CatalogStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductVariantResponse from(ProductVariant variant, List<SelectedOption> options, String imageUrl,
                                              int lowStockThreshold) {
        Product product = variant.getProduct();
        return new ProductVariantResponse(variant.getId(), product.getId(), product.getName(), product.getSku(),
                product.getStatus(), product.getCategoryId(), product.getCategory(), product.getBrandId(),
                product.getBrand(), product.getCurrency(), variant.getSku(), variant.getVariantName(), options,
                variant.getAttributes(), variant.getPrice(), variant.getCompareAtPrice(),
                variant.getCompareAtPrice() != null, variant.getStockQuantity(),
                StockLevel.of(variant.getStockQuantity(), lowStockThreshold), variant.getBarcode(),
                variant.getImageId(), imageUrl, variant.getStatus(), variant.getVersion(), variant.getCreatedAt(),
                variant.getUpdatedAt());
    }
}
