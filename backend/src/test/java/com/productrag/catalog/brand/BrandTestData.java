package com.productrag.catalog.brand;

import com.productrag.catalog.CatalogStatus;
import java.util.UUID;

/** Builds detached Brand instances for unit tests (the id setter is package-private). */
public final class BrandTestData {

    private BrandTestData() {
    }

    public static Brand brand(String name, String slug, CatalogStatus status) {
        Brand brand = new Brand();
        brand.setId(UUID.randomUUID());
        brand.setName(name);
        brand.setSlug(slug);
        brand.setStatus(status);
        brand.setVersion(0L);
        return brand;
    }

    public static void setId(Brand brand, UUID id) {
        brand.setId(id);
    }
}
