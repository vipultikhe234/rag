package com.productrag.embedding;

/** Catalog records that have their own embedding in {@code catalog_embeddings}; products use product_embeddings. */
public enum CatalogEntityType {
    BRAND,
    CATEGORY,
    ATTRIBUTE,
    VARIANT
}
