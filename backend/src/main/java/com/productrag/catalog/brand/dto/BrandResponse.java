package com.productrag.catalog.brand.dto;

import com.productrag.catalog.CatalogStatus;
import com.productrag.catalog.brand.Brand;
import java.time.Instant;
import java.util.UUID;

public record BrandResponse(
        UUID id,
        String name,
        String slug,
        String description,
        String logoUrl,
        String websiteUrl,
        CatalogStatus status,
        long productCount,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static BrandResponse from(Brand brand, long productCount) {
        return new BrandResponse(brand.getId(), brand.getName(), brand.getSlug(), brand.getDescription(),
                brand.getLogoUrl(), brand.getWebsiteUrl(), brand.getStatus(), productCount, brand.getVersion(),
                brand.getCreatedAt(), brand.getUpdatedAt());
    }
}
