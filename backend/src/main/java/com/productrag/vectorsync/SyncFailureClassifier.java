package com.productrag.vectorsync;

import com.productrag.ai.AiProviderException;
import java.util.regex.Pattern;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * Decides whether a sync failure is worth retrying automatically and produces an error message that
 * is safe to store and show (no API keys or bearer tokens).
 */
@Component
public class SyncFailureClassifier {

    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9_\\-]{6,}");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");
    private static final int MAX_MESSAGE_LENGTH = 500;

    public enum Kind {
        TRANSIENT,
        PERMANENT
    }

    public Kind classify(Throwable ex) {
        if (ex instanceof PermanentSyncException) {
            return Kind.PERMANENT;
        }
        if (ex instanceof AiProviderException provider) {
            return provider.isTransient() ? Kind.TRANSIENT : Kind.PERMANENT;
        }
        if (ex instanceof NonTransientDataAccessException) {
            return Kind.PERMANENT;
        }
        // Timeouts, connection problems, transient DB errors and unknown failures are retried with
        // bounded backoff; the attempt limit prevents endless retries.
        return Kind.TRANSIENT;
    }

    public String describe(Throwable ex) {
        String type = ex.getClass().getSimpleName();
        String message = ex.getMessage();
        if (ex instanceof AiProviderException provider && provider.getCause() != null
                && provider.getCause().getMessage() != null) {
            message = provider.getMessage() + " Cause: " + provider.getCause().getMessage();
        }
        String text = type + (message == null || message.isBlank() ? "" : ": " + message);
        text = OPENAI_KEY.matcher(text).replaceAll("sk-***");
        text = BEARER.matcher(text).replaceAll("Bearer ***");
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }
}
