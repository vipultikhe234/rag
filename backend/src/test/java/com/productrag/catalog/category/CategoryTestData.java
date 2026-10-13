package com.productrag.catalog.category;

import com.productrag.catalog.CatalogStatus;
import java.util.UUID;

/** Builds detached Category instances for unit tests (the id setter is package-private). */
public final class CategoryTestData {

    private CategoryTestData() {
    }

    public static Category category(String name, String slug, UUID parentId, CatalogStatus status) {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName(name);
        category.setSlug(slug);
        category.setParentId(parentId);
        category.setStatus(status);
        category.setVersion(0L);
        return category;
    }

    public static Category active(String name, String slug) {
        return category(name, slug, null, CatalogStatus.ACTIVE);
    }

    public static void setId(Category category, UUID id) {
        category.setId(id);
    }
}
