package com.productrag.product.filter;

/**
 * Allowlist of product fields that structured (exact) filters may use. Each field maps to a fixed
 * SQL expression; user input is only ever bound as a parameter value, never concatenated.
 */
public enum FilterField {
    PRICE("p.price", "price"),
    STOCK_QUANTITY("p.stock_quantity", "stock quantity"),
    CATEGORY("lower(p.category)", "category"),
    BRAND("lower(p.brand)", "brand"),
    CURRENCY("p.currency", "currency");

    private final String sqlExpression;
    private final String label;

    FilterField(String sqlExpression, String label) {
        this.sqlExpression = sqlExpression;
        this.label = label;
    }

    public String sqlExpression() {
        return sqlExpression;
    }

    public String label() {
        return label;
    }
}
