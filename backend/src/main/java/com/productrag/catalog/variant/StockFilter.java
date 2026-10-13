package com.productrag.catalog.variant;

/** Stock filter for variant lists; AVAILABLE means any stock (IN_STOCK or LOW_STOCK). */
public enum StockFilter {
    AVAILABLE,
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK
}
