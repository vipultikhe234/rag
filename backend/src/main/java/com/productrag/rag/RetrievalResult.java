package com.productrag.rag;

import java.util.List;

/**
 * @param totalMatches exact number of active products matching the structured filters, when known
 * @param message      deterministic answer to return without calling the LLM (clarification or no results)
 */
public record RetrievalResult(
        RetrievalStrategy strategy,
        List<RetrievedProduct> products,
        List<String> appliedFilters,
        Long totalMatches,
        String message) {

    public static RetrievalResult message(RetrievalStrategy strategy, List<String> appliedFilters, String message) {
        return new RetrievalResult(strategy, List.of(), appliedFilters, 0L, message);
    }
}
