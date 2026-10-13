package com.productrag.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds detached Product instances for unit tests (package-private setters live in this package). */
public final class ProductTestData {

    private ProductTestData() {
    }

    public static Product product(String name, String sku, String price, int stock, ProductStatus status) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName(name);
        product.setSku(sku);
        product.setDescription(name + " description with useful features.");
        product.setCategory("Electronics");
        product.setBrand("ExampleBrand");
        product.setPrice(new BigDecimal(price));
        product.setCurrency("INR");
        product.setStockQuantity(stock);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connectivity", "Bluetooth");
        product.setAttributes(attributes);
        product.setStatus(status);
        product.setVersion(0L);
        product.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        product.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return product;
    }

    public static Product headphones() {
        Product product = product("Wireless Headphones", "WH-001", "2499.00", 12, ProductStatus.ACTIVE);
        product.setDescription("Wireless over-ear headphones with active noise cancellation and a 30-hour battery life.");
        product.setAttributes(Map.of("battery_life", "30 hours", "noise_cancellation", true));
        return product;
    }

    public static void bumpVersion(Product product) {
        product.setVersion(product.getVersion() + 1);
        product.setUpdatedAt(product.getUpdatedAt().plusSeconds(60));
    }

    public static void setId(Product product, UUID id) {
        product.setId(id);
    }
}
