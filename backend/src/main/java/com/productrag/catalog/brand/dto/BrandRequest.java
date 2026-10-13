package com.productrag.catalog.brand.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.Slugs;
import com.productrag.catalog.validation.HttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create/update payload; same slug, status and version semantics as categories. */
public record BrandRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 120, message = "Slug must be at most 120 characters")
        @Pattern(regexp = "^$|" + Slugs.PATTERN,
                message = "Slug may contain only lower-case letters, digits and single hyphens")
        String slug,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description,

        @Size(max = 500, message = "Logo URL must be at most 500 characters")
        @HttpUrl
        String logoUrl,

        @Size(max = 500, message = "Website URL must be at most 500 characters")
        @HttpUrl
        String websiteUrl,

        CatalogStatus status,

        Long version) {
}
