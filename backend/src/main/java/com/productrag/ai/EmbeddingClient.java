package com.productrag.ai;

/** Produces embedding vectors with the configured embedding model. */
public interface EmbeddingClient {

    /**
     * @throws AiProviderException when the provider is not configured, unavailable or rejects the request
     */
    float[] embed(String text);

    String modelName();

    int dimensions();
}
