package com.productrag.ai;

import com.productrag.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Controlled failure of the external AI provider. Messages never contain credentials or prompts. */
public class AiProviderException extends ApiException {

    private final boolean transientFailure;

    private AiProviderException(HttpStatus status, String code, String message, boolean transientFailure,
                                Throwable cause) {
        super(status, code, message, cause);
        this.transientFailure = transientFailure;
    }

    public static AiProviderException notConfigured() {
        return new AiProviderException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_NOT_CONFIGURED",
                "The AI provider is not configured. Set OPENAI_API_KEY and restart the backend.", false, null);
    }

    public static AiProviderException unavailable(Throwable cause) {
        return new AiProviderException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE",
                "The AI provider is temporarily unavailable or timed out. Please try again shortly.", true, cause);
    }

    public static AiProviderException rejected(Throwable cause) {
        return new AiProviderException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_ERROR",
                "The AI provider rejected the request. Check the API key, model names and account limits.", false,
                cause);
    }

    public static AiProviderException quotaExceeded(Throwable cause) {
        return new AiProviderException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_QUOTA_EXCEEDED",
                "The AI provider account has no remaining quota or credits. Add credits or raise the limit, "
                        + "then retry.", false, cause);
    }

    public static AiProviderException emptyResponse() {
        return new AiProviderException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_EMPTY_RESPONSE",
                "The AI provider returned an empty response. Please try again.", true, null);
    }

    public boolean isTransient() {
        return transientFailure;
    }
}
