package com.productrag.ai;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Maps raw Spring AI / HTTP client exceptions to {@link AiProviderException}.
 * Non-transient provider responses (4xx other than 429) are "rejected"; an exhausted account quota
 * (HTTP 429 with insufficient_quota) is reported separately because retrying cannot succeed until billing
 * changes; a refused connection or unknown host is "unreachable" (typically Ollama not running); everything
 * else (5xx, 429 rate limits, timeouts, unknown failures) is treated as temporarily unavailable.
 */
public final class AiErrors {

    private static final String[] QUOTA_MARKERS = {"insufficient_quota", "credit_balance_exhausted"};

    private AiErrors() {
    }

    public static AiProviderException translate(RuntimeException ex) {
        if (ex instanceof AiProviderException provider) {
            return provider;
        }
        if (isQuotaExhausted(ex)) {
            return AiProviderException.quotaExceeded(ex);
        }
        if (ex instanceof NonTransientAiException) {
            return AiProviderException.rejected(ex);
        }
        if (ex instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status != 429 && status >= 400 && status < 500) {
                return AiProviderException.rejected(ex);
            }
        }
        if (isConnectionFailure(ex)) {
            return AiProviderException.unreachable(ex);
        }
        return AiProviderException.unavailable(ex);
    }

    /** Nothing is listening at the configured address, or the host name cannot be resolved. */
    private static boolean isConnectionFailure(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof UnresolvedAddressException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static boolean isQuotaExhausted(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String text = current instanceof RestClientResponseException response
                    ? response.getResponseBodyAsString() + " " + current.getMessage()
                    : current.getMessage();
            if (text != null) {
                String lower = text.toLowerCase(Locale.ROOT);
                for (String marker : QUOTA_MARKERS) {
                    if (lower.contains(marker)) {
                        return true;
                    }
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
