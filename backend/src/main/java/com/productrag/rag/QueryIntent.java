package com.productrag.rag;

import com.productrag.product.filter.ProductFilter;
import java.util.List;

/**
 * Structured interpretation of a question.
 *
 * @param filters        validated exact filters (price, stock, category, currency)
 * @param sku            SKU explicitly requested with the word "SKU", or null
 * @param skuCandidates  tokens that look like SKUs (e.g. WH-001) to try as exact matches
 * @param countQuestion  "how many ..." questions
 * @param sortHint       cheapest / most expensive / highest stock ..., or null
 * @param semanticText   meaningful words left after removing filter phrases and stop words
 * @param ambiguity      explanation when an exact condition was detected but could not be parsed reliably
 */
public record QueryIntent(
        List<ProductFilter> filters,
        String sku,
        List<String> skuCandidates,
        boolean countQuestion,
        SortHint sortHint,
        String semanticText,
        String ambiguity) {

    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    public boolean hasSemanticContent() {
        return semanticText != null && !semanticText.isBlank();
    }

    public boolean isAmbiguous() {
        return ambiguity != null;
    }
}
