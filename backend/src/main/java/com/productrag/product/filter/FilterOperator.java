package com.productrag.product.filter;

/** Allowlist of comparison operators for structured filters. */
public enum FilterOperator {
    LT("<"),
    LTE("<="),
    GT(">"),
    GTE(">="),
    EQ("=");

    private final String sql;

    FilterOperator(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }
}
