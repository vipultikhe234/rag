package com.productrag.catalog.attribute.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.Slugs;
import com.productrag.catalog.attribute.AttributeDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Create/update payload. {@code categoryIds} lists the categories the attribute is assigned to (it also applies to
 * their subcategories); older clients may send a single {@code categoryId} instead. {@code options} is required
 * (1-50 distinct values) for SELECT and must be empty for every other type. A blank slug is derived from the name;
 * product values are keyed by the slug, which is unique across all attributes.
 */
public record ProductAttributeRequest(
        @Size(max = 20, message = "An attribute can be assigned to at most 20 categories")
        List<@NotNull(message = "Category ids must not be empty") UUID> categoryIds,

        UUID categoryId,

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 64, message = "Slug must be at most 64 characters")
        @Pattern(regexp = "^$|" + Slugs.PATTERN,
                message = "Slug may contain only lower-case letters, digits and single hyphens")
        String slug,

        @NotNull(message = "Data type is required")
        AttributeDataType dataType,

        @Size(max = 50, message = "At most 50 options are allowed")
        List<@NotBlank(message = "Options must not be blank")
             @Size(max = 100, message = "Options must be at most 100 characters") String> options,

        @Size(max = 20, message = "Unit must be at most 20 characters")
        String unit,

        Boolean required,

        Boolean filterable,

        CatalogStatus status,

        Long version) {
}
