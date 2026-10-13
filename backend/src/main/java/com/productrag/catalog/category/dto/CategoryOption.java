package com.productrag.catalog.category.dto;

import com.productrag.catalog.CatalogStatus;
import java.util.UUID;

/**
 * Lightweight entry for dropdowns; {@code path} is the full hierarchy, e.g. "Electronics / Laptops".
 * {@code assignable} is false when the category or any ancestor is inactive.
 */
public record CategoryOption(UUID id, String name, String slug, UUID parentId, String path, CatalogStatus status,
                             boolean assignable) {
}
