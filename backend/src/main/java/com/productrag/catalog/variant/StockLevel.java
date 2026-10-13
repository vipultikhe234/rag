package com.productrag.catalog.variant;

public enum StockLevel {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK;

    public static StockLevel of(int stockQuantity, int lowStockThreshold) {
        if (stockQuantity <= 0) {
            return OUT_OF_STOCK;
        }
        return stockQuantity <= lowStockThreshold ? LOW_STOCK : IN_STOCK;
    }
}
