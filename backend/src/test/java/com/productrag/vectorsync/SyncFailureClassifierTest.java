package com.productrag.vectorsync;

import static org.assertj.core.api.Assertions.assertThat;

import com.productrag.ai.AiProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.client.ResourceAccessException;

class SyncFailureClassifierTest {

    private final SyncFailureClassifier classifier = new SyncFailureClassifier();

    @Test
    void classifiesTransientFailures() {
        assertThat(classifier.classify(AiProviderException.unavailable(null)))
                .isEqualTo(SyncFailureClassifier.Kind.TRANSIENT);
        assertThat(classifier.classify(new ResourceAccessException("connect timed out")))
                .isEqualTo(SyncFailureClassifier.Kind.TRANSIENT);
        assertThat(classifier.classify(new QueryTimeoutException("slow")))
                .isEqualTo(SyncFailureClassifier.Kind.TRANSIENT);
    }

    @Test
    void classifiesPermanentFailures() {
        assertThat(classifier.classify(new PermanentSyncException("bad")))
                .isEqualTo(SyncFailureClassifier.Kind.PERMANENT);
        assertThat(classifier.classify(AiProviderException.rejected(null)))
                .isEqualTo(SyncFailureClassifier.Kind.PERMANENT);
        assertThat(classifier.classify(AiProviderException.notConfigured()))
                .isEqualTo(SyncFailureClassifier.Kind.PERMANENT);
        assertThat(classifier.classify(new DataIntegrityViolationException("constraint")))
                .isEqualTo(SyncFailureClassifier.Kind.PERMANENT);
    }

    @Test
    void redactsSecretsFromStoredMessages() {
        String message = classifier.describe(new RuntimeException(
                "401 Incorrect API key provided: sk-proj-ABCDEF123456; header Authorization: Bearer eyJhbGciOi.abc.def"));

        assertThat(message).doesNotContain("ABCDEF123456").doesNotContain("eyJhbGciOi")
                .contains("sk-***").contains("Bearer ***");
    }

    @Test
    void truncatesLongMessages() {
        String message = classifier.describe(new RuntimeException("x".repeat(5000)));

        assertThat(message.length()).isLessThanOrEqualTo(503);
    }
}
