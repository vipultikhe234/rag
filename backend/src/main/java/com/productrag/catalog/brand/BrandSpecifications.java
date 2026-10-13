package com.productrag.catalog.brand;

import com.productrag.catalog.CatalogStatus;
import com.productrag.product.ProductSpecifications;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class BrandSpecifications {

    private BrandSpecifications() {
    }

    static Specification<Brand> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    static Specification<Brand> textSearch(String text) {
        String pattern = "%" + ProductSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("slug")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\'));
    }

    static Specification<Brand> statusEquals(CatalogStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
