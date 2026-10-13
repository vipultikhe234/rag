package com.productrag.catalog.attribute.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.attribute.AttributeDataType;
import com.productrag.catalog.attribute.ProductAttribute;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductAttributeResponse(
        UUID id,
        List<CategoryRef> categories,
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

    /** An assigned category; {@code active} is the category's own status. */
    public record CategoryRef(UUID id, String name, boolean active) {
    }

    public static ProductAttributeResponse from(ProductAttribute attribute, List<CategoryRef> categories) {
        return new ProductAttributeResponse(attribute.getId(), categories, attribute.getName(), attribute.getSlug(),
                attribute.getDataType(), List.copyOf(attribute.getOptions()), attribute.getUnit(),
                attribute.isRequired(), attribute.isFilterable(), attribute.getStatus(), attribute.getVersion(),
                attribute.getCreatedAt(), attribute.getUpdatedAt());
    }
}
