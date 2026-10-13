package com.productrag.catalog.attribute.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.AttributeDataType;
import com.productrag.catalog.attribute.ProductAttribute;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductAttributeResponse(
        UUID id,
        UUID categoryId,
        String categoryName,
        String name,
        String slug,
        AttributeDataType dataType,
        List<String> options,
        String unit,
        boolean required,
        boolean filterable,
        CatalogStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductAttributeResponse from(ProductAttribute attribute, String categoryName) {
        return new ProductAttributeResponse(attribute.getId(), attribute.getCategoryId(), categoryName,
                attribute.getName(), attribute.getSlug(), attribute.getDataType(), List.copyOf(attribute.getOptions()),
                attribute.getUnit(), attribute.isRequired(), attribute.isFilterable(), attribute.getStatus(),
                attribute.getVersion(), attribute.getCreatedAt(), attribute.getUpdatedAt());
    }
}
