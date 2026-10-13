package com.productrag.catalog.category.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.category.Category;
import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String slug,
        String description,
        String imageUrl,
        UUID parentId,
        String parentName,
        CatalogStatus status,
        long productCount,
        long subcategoryCount,
        long attributeCount,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static CategoryResponse from(Category category, String parentName, long productCount,
                                        long subcategoryCount, long attributeCount) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(),
                category.getDescription(), category.getImageUrl(), category.getParentId(), parentName,
                category.getStatus(), productCount, subcategoryCount, attributeCount, category.getVersion(),
                category.getCreatedAt(), category.getUpdatedAt());
    }
}
