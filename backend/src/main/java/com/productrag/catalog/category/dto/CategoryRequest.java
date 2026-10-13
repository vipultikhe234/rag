package com.productrag.catalog.category.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.Slugs;
import com.productrag.catalog.validation.HttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create/update payload. A blank slug is derived from the name; {@code status} defaults to ACTIVE on create and
 * is left unchanged on update when omitted; {@code version} enables optimistic concurrency checks.
 */
public record CategoryRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 120, message = "Slug must be at most 120 characters")
        @Pattern(regexp = "^$|" + Slugs.PATTERN,
                message = "Slug may contain only lower-case letters, digits and single hyphens")
        String slug,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description,

        @Size(max = 500, message = "Image URL must be at most 500 characters")
        @HttpUrl
        String imageUrl,

        UUID parentId,

        CatalogStatus status,

        Long version) {
}
