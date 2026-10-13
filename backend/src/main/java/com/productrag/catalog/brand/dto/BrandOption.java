package com.productrag.catalog.brand.dto;

import com.productrag.catalog.CatalogStatus;
import java.util.UUID;

public record BrandOption(UUID id, String name, String slug, CatalogStatus status) {
}
