package com.productrag.catalog.variant;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.option.ProductOption;
import com.productrag.catalog.option.ProductOptionValue;
import com.productrag.catalog.option.VariantOptionValue;
import com.productrag.product.ProductSpecifications;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class ProductVariantSpecifications {

    private ProductVariantSpecifications() {
    }

    static Specification<ProductVariant> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Matches variant SKU, title or barcode, or the parent product's name or SKU. */
    static Specification<ProductVariant> textSearch(String text) {
        String pattern = "%" + ProductSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("sku")), pattern, '\\'),
                cb.like(cb.lower(root.get("variantName")), pattern, '\\'),
                cb.like(cb.lower(cb.coalesce(root.get("barcode"), "")), pattern, '\\'),
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

    static Specification<ProductVariant> productCategoryIn(Collection<UUID> categoryIds) {
        return (root, query, cb) -> root.get("product").get("categoryId").in(categoryIds);
    }

    static Specification<ProductVariant> productBrandEquals(UUID brandId) {
        return (root, query, cb) -> cb.equal(root.get("product").get("brandId"), brandId);
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

    /** The variant has the value for an option with this name (both compared case-insensitively). */
    static Specification<ProductVariant> optionEquals(String optionName, String value) {
        String name = optionName.trim().toLowerCase(Locale.ROOT);
        String expected = value.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<VariantOptionValue> row = subquery.from(VariantOptionValue.class);
            Root<ProductOption> option = subquery.from(ProductOption.class);
            Root<ProductOptionValue> optionValue = subquery.from(ProductOptionValue.class);
            subquery.select(cb.literal(1)).where(
                    cb.equal(row.get("variantId"), root.get("id")),
                    cb.equal(row.get("optionId"), option.get("id")),
                    cb.equal(row.get("optionValueId"), optionValue.get("id")),
                    cb.equal(cb.lower(option.get("name")), name),
                    cb.equal(cb.lower(optionValue.get("value")), expected));
            return cb.exists(subquery);
        };
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
