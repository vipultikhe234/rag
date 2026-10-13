package com.productrag.catalog.variant;

import com.productrag.catalog.CatalogStatus;
import com.productrag.product.ProductSpecifications;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class ProductVariantSpecifications {

    private ProductVariantSpecifications() {
    }

    static Specification<ProductVariant> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Matches variant SKU or name, or the parent product's name or SKU. */
    static Specification<ProductVariant> textSearch(String text) {
        String pattern = "%" + ProductSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("sku")), pattern, '\\'),
                cb.like(cb.lower(root.get("variantName")), pattern, '\\'),
                cb.like(cb.lower(root.get("product").get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("product").get("sku")), pattern, '\\'));
    }

    static Specification<ProductVariant> skuContains(String sku) {
        String pattern = "%" + ProductSpecifications.escapeLike(sku.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("sku")), pattern, '\\');
    }

    static Specification<ProductVariant> productEquals(UUID productId) {
        return (root, query, cb) -> cb.equal(root.get("product").get("id"), productId);
    }

    static Specification<ProductVariant> statusEquals(CatalogStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<ProductVariant> priceAtLeast(BigDecimal value) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), value);
    }

    static Specification<ProductVariant> priceAtMost(BigDecimal value) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), value);
    }

    static Specification<ProductVariant> stockLevel(StockLevel level, int lowStockThreshold) {
        return switch (level) {
            case OUT_OF_STOCK -> (root, query, cb) -> cb.lessThanOrEqualTo(root.get("stockQuantity"), 0);
            case LOW_STOCK -> (root, query, cb) -> cb.between(root.get("stockQuantity"), 1, lowStockThreshold);
            case IN_STOCK -> (root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), lowStockThreshold);
        };
    }

    /** Any stock at all (low or plenty). */
    static Specification<ProductVariant> available() {
        return (root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), 0);
    }
}
