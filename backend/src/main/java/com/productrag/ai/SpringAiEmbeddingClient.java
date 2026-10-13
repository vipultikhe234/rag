package com.productrag.ai;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class SpringAiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;
    private final AiProviderStatus status;

    public SpringAiEmbeddingClient(EmbeddingModel embeddingModel, AiProviderStatus status) {
        this.embeddingModel = embeddingModel;
        this.status = status;
    }

    @Override
    public float[] embed(String text) {
        status.requireConfigured();
        float[] vector;
        try {
            vector = embeddingModel.embed(text);
        } catch (RuntimeException ex) {
            throw AiErrors.translate(ex);
        }
        if (vector == null || vector.length == 0) {
            throw AiProviderException.emptyResponse();
        }
        return vector;
    }

    @Override
    public String modelName() {
        return status.embeddingModel();
    }

    @Override
    public int dimensions() {
        return status.embeddingDimensions();
    }
}
