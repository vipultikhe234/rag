package com.productrag.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class AiErrorsTest {

    private static final String QUOTA_BODY = """
            { "error": { "message": "You have no credits remaining.", "type": "insufficient_quota",
              "code": "credit_balance_exhausted" } }""";

    @Test
    void exhaustedQuotaIsPermanentEvenThoughItIsHttp429() {
        AiProviderException fromSpringAi = AiErrors.translate(new TransientAiException("HTTP 429 - " + QUOTA_BODY));
        AiProviderException fromHttpClient = AiErrors.translate(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                QUOTA_BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        for (AiProviderException translated : new AiProviderException[] {fromSpringAi, fromHttpClient}) {
            assertThat(translated.getCode()).isEqualTo("AI_PROVIDER_QUOTA_EXCEEDED");
            assertThat(translated.isTransient()).isFalse();
        }
    }

    @Test
    void rateLimit429IsTransient() {
        AiProviderException translated = AiErrors.translate(
                new TransientAiException("HTTP 429 - Rate limit reached for requests"));

        assertThat(translated.getCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(translated.isTransient()).isTrue();
    }

    @Test
    void refusedConnectionIsReportedAsUnreachableAndRetried() {
        AiProviderException translated = AiErrors.translate(new ResourceAccessException(
                "I/O error on POST request for \"http://localhost:11434/v1/embeddings\": null", new ConnectException()));

        assertThat(translated.getCode()).isEqualTo("AI_PROVIDER_UNREACHABLE");
        assertThat(translated.getMessage()).contains("Ollama");
        assertThat(translated.isTransient()).isTrue();
    }

    @Test
    void rejectedRequestIsPermanent() {
        AiProviderException translated = AiErrors.translate(new NonTransientAiException("HTTP 401 - invalid api key"));

        assertThat(translated.getCode()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(translated.isTransient()).isFalse();
    }
}
