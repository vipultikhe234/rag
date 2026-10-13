package com.productrag.ai;

import reactor.core.publisher.Flux;

/** Generates a completion from a system prompt and a user prompt with the configured chat model. */
public interface LlmClient {

    /**
     * @throws AiProviderException when the provider is not configured, unavailable or rejects the request
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * Streams the completion as text chunks. Errors are signalled as {@link AiProviderException}.
     * The default emits the blocking result as a single chunk.
     */
    default Flux<String> stream(String systemPrompt, String userPrompt) {
        return Flux.defer(() -> Flux.just(generate(systemPrompt, userPrompt)));
    }
}
