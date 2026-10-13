package com.productrag.catalog.attribute;

import com.productrag.catalog.CatalogStatus;
import com.productrag.product.ProductSpecifications;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class ProductAttributeSpecifications {

    private ProductAttributeSpecifications() {
    }

    static Specification<ProductAttribute> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    static Specification<ProductAttribute> textSearch(String text) {
        String pattern = "%" + ProductSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("slug")), pattern, '\\'));
    }

    static Specification<ProductAttribute> categoryEquals(UUID categoryId) {
        return (root, query, cb) -> cb.equal(root.get("categoryId"), categoryId);
    }

    static Specification<ProductAttribute> dataTypeEquals(AttributeDataType dataType) {
        return (root, query, cb) -> cb.equal(root.get("dataType"), dataType);
    }

    static Specification<ProductAttribute> statusEquals(CatalogStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<ProductAttribute> filterableEquals(boolean filterable) {
        return (root, query, cb) -> cb.equal(root.get("filterable"), filterable);
    }
}
