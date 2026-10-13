package com.productrag.rag;

import com.productrag.ai.LlmClient;
import com.productrag.rag.dto.RetrievalInfo;
import com.productrag.rag.dto.SourceReference;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Retrieval-augmented answering: retrieve relevant ACTIVE products, then let the LLM answer from that
 * context only. When retrieval finds nothing (or needs clarification) a deterministic message is
 * returned and the LLM is not called, so no product can be invented.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final ProductRetriever retriever;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;

    public RagService(ProductRetriever retriever, PromptBuilder promptBuilder, LlmClient llmClient) {
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
    }

    public RagAnswer answer(String question, List<HistoryTurn> history) {
        RagPlan plan = plan(question, history);
        return new RagAnswer(generate(plan), plan.sources(), plan.retrieval());
    }

    /** Retrieves the context and builds the prompts; performs no LLM call. */
    public RagPlan plan(String question, List<HistoryTurn> history) {
        RetrievalResult retrieval = retriever.retrieve(question);
        log.debug("Retrieval strategy={} products={} filters={}", retrieval.strategy(), retrieval.products().size(),
                retrieval.appliedFilters());
        if (retrieval.products().isEmpty()) {
            String message = retrieval.message() != null ? retrieval.message()
                    : "I couldn't find a sufficiently relevant product in the current catalog.";
            return RagPlan.direct(message, RetrievalInfo.from(retrieval, false));
        }
        List<SourceReference> sources = retrieval.products().stream().map(SourceReference::from).toList();
        return RagPlan.llm(promptBuilder.systemPrompt(), promptBuilder.userPrompt(question, retrieval, history),
                sources, RetrievalInfo.from(retrieval, true));
    }

    public String generate(RagPlan plan) {
        return plan.needsLlm() ? llmClient.generate(plan.systemPrompt(), plan.userPrompt()) : plan.directAnswer();
    }

    /** Answer text in chunks as the model produces it; a deterministic answer is emitted as one chunk. */
    public Flux<String> stream(RagPlan plan) {
        return plan.needsLlm() ? llmClient.stream(plan.systemPrompt(), plan.userPrompt())
                : Flux.just(plan.directAnswer());
    }
}
