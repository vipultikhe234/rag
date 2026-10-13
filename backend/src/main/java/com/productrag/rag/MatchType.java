package com.productrag.rag;

/** Why a product was included in the answer context. */
public enum MatchType {
    EXACT_SKU,
    NAME_MATCH,
    STRUCTURED_FILTER,
    SEMANTIC
}
