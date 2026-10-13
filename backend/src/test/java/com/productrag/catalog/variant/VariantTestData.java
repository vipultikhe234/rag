package com.productrag.catalog.variant;

import com.productrag.catalog.CatalogStatus;
import com.productrag.product.Product;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Builds detached ProductVariant instances for unit tests (the id setter is package-private). */
public final class VariantTestData {

    private VariantTestData() {
    }

    public static ProductVariant variant(Product product, String sku, String price, int stock, CatalogStatus status) {
        ProductVariant variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setProduct(product);
        variant.setSku(sku);
        variant.setVariantName(sku + " variant");
        variant.setAttributes(Map.of());
        variant.setPrice(new BigDecimal(price));
        variant.setStockQuantity(stock);
        variant.setStatus(status);
        variant.setVersion(0L);
        return variant;
    }

    public static void setId(ProductVariant variant, UUID id) {
        variant.setId(id);
    }
}
