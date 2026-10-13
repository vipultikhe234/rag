package com.productrag.product;

import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Type-safe, parameterized query building blocks for products. */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Case-insensitive "contains" search over name, SKU, category and brand. */
    public static Specification<Product> textSearch(String text) {
        String pattern = "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("sku")), pattern, '\\'),
                cb.like(cb.lower(root.get("category")), pattern, '\\'),
                cb.like(cb.lower(root.get("brand")), pattern, '\\'));
    }

    public static Specification<Product> categoryEquals(String category) {
        String value = category.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.get("category")), value);
    }

    public static Specification<Product> statusEquals(ProductStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Product> currencyEquals(String currency) {
        return (root, query, cb) -> cb.equal(root.get("currency"), currency);
    }

    public static Specification<Product> priceLessThan(BigDecimal value) {
        return (root, query, cb) -> cb.lessThan(root.get("price"), value);
    }

    public static Specification<Product> priceLessThanOrEqual(BigDecimal value) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), value);
    }

    public static Specification<Product> priceGreaterThan(BigDecimal value) {
        return (root, query, cb) -> cb.greaterThan(root.get("price"), value);
    }

    public static Specification<Product> priceGreaterThanOrEqual(BigDecimal value) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), value);
    }

    public static Specification<Product> stockLessThan(int value) {
        return (root, query, cb) -> cb.lessThan(root.get("stockQuantity"), value);
    }

    public static Specification<Product> stockLessThanOrEqual(int value) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("stockQuantity"), value);
    }

    public static Specification<Product> stockGreaterThan(int value) {
        return (root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), value);
    }

    public static Specification<Product> stockGreaterThanOrEqual(int value) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("stockQuantity"), value);
    }

    public static Specification<Product> stockEquals(int value) {
        return (root, query, cb) -> cb.equal(root.get("stockQuantity"), value);
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
