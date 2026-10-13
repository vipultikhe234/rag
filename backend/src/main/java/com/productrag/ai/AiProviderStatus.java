package com.productrag.ai;

import com.productrag.config.AppProperties;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Knows whether an AI provider API key is configured, without ever exposing the key itself. */
@Component
public class AiProviderStatus {

    static final String NOT_CONFIGURED = "not-configured";

    private final boolean configured;
    private final String embeddingModel;
    private final int embeddingDimensions;
    private final String chatModel;

    public AiProviderStatus(AppProperties properties) {
        String key = properties.ai().apiKey();
        this.configured = key != null && !key.isBlank() && !NOT_CONFIGURED.equals(key)
                && !key.toLowerCase(Locale.ROOT).startsWith("replace-with");
        this.embeddingModel = properties.ai().embeddingModel();
        this.embeddingDimensions = properties.ai().embeddingDimensions();
        this.chatModel = properties.ai().chatModel();
    }

    public boolean isConfigured() {
        return configured;
    }

    public void requireConfigured() {
        if (!configured) {
            throw AiProviderException.notConfigured();
        }
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public int embeddingDimensions() {
        return embeddingDimensions;
    }

    public String chatModel() {
        return chatModel;
    }
}
