package com.productrag.catalog.category.dto;

import com.productrag.catalog.CatalogStatus;
import java.util.UUID;

/** Lightweight entry for dropdowns; {@code path} is the full hierarchy, e.g. "Electronics / Laptops". */
public record CategoryOption(UUID id, String name, String slug, UUID parentId, String path, CatalogStatus status) {
}
