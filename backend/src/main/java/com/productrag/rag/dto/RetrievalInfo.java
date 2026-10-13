package com.productrag.rag.dto;

import com.productrag.rag.RetrievalResult;
import java.util.List;

/** How the answer context was retrieved (shown in the UI and stored with the message). */
public record RetrievalInfo(String strategy, List<String> appliedFilters, Long totalMatches, boolean llmUsed) {

    public static RetrievalInfo from(RetrievalResult result, boolean llmUsed) {
        return new RetrievalInfo(result.strategy().name(), result.appliedFilters(), result.totalMatches(), llmUsed);
    }
}
