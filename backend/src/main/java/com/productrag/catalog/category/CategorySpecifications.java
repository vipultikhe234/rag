package com.productrag.catalog.category;

import com.productrag.catalog.CatalogStatus;
import com.productrag.product.ProductSpecifications;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class CategorySpecifications {

    private CategorySpecifications() {
    }

    static Specification<Category> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    static Specification<Category> textSearch(String text) {
        String pattern = "%" + ProductSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("slug")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\'));
    }

    static Specification<Category> statusEquals(CatalogStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<Category> parentEquals(UUID parentId) {
        return (root, query, cb) -> cb.equal(root.get("parentId"), parentId);
    }

    static Specification<Category> rootsOnly() {
        return (root, query, cb) -> cb.isNull(root.get("parentId"));
    }
}
