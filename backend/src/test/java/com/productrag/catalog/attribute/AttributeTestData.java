package com.productrag.catalog.attribute;

import com.productrag.catalog.CatalogStatus;
import java.util.List;
import java.util.UUID;

/** Builds detached ProductAttribute instances for unit tests (the id setter is package-private). */
public final class AttributeTestData {

    private AttributeTestData() {
    }

    public static ProductAttribute attribute(UUID categoryId, String name, String slug, AttributeDataType type,
                                             List<String> options, boolean required, CatalogStatus status) {
        ProductAttribute attribute = new ProductAttribute();
        attribute.setId(UUID.randomUUID());
        attribute.setCategoryId(categoryId);
        attribute.setName(name);
        attribute.setSlug(slug);
        attribute.setDataType(type);
        attribute.setOptions(options);
        attribute.setRequired(required);
        attribute.setStatus(status);
        attribute.setVersion(0L);
        return attribute;
    }

    public static void setId(ProductAttribute attribute, UUID id) {
        attribute.setId(id);
    }
}
