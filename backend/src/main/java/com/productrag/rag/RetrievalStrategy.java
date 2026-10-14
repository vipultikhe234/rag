package com.productrag.rag;

public enum RetrievalStrategy {
    /** Exact SKU lookup in PostgreSQL. */
    EXACT_SKU,
    /** Exact filters only (price/stock/category/count) answered from PostgreSQL. */
    STRUCTURED,
    /** pgvector similarity search constrained by exact SQL filters. */
    HYBRID,
    /** pgvector similarity search (plus products mentioned by name or SKU). */
    SEMANTIC,
    /** An exact condition could not be interpreted reliably; no retrieval was performed. */
    CLARIFICATION,
    /** Totals and lists of brands, categories, attributes or variants, answered from PostgreSQL without the LLM. */
    CATALOG,
    /** Brands, categories, attributes or variants ranked by relevance to a topic (embeddings), without the LLM. */
    DISCOVERY
}
