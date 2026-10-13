package com.productrag.rag;

import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.rag.dto.SourceReference;
import java.util.List;

/**
 * Outcome of retrieval for one question: either a deterministic answer (nothing relevant found, or a
 * clarification is needed) or the prompts the LLM must answer from, together with the source products.
 */
public record RagPlan(String directAnswer, String systemPrompt, String userPrompt, List<SourceReference> sources,
                      RetrievalInfo retrieval) {

    public static RagPlan direct(String answer, RetrievalInfo retrieval) {
        return new RagPlan(answer, null, null, List.of(), retrieval);
    }

    public static RagPlan llm(String systemPrompt, String userPrompt, List<SourceReference> sources,
                              RetrievalInfo retrieval) {
        return new RagPlan(null, systemPrompt, userPrompt, List.copyOf(sources), retrieval);
    }

    public boolean needsLlm() {
        return directAnswer == null;
    }
}
