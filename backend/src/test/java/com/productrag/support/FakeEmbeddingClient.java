package com.productrag.support;

import com.productrag.ai.AiProviderException;
import com.productrag.ai.EmbeddingClient;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic embedding client for unit tests; can be switched to fail like an unavailable provider. */
public class FakeEmbeddingClient implements EmbeddingClient {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;
    private volatile int returnedDimensions = TestProperties.DIMENSIONS;

    @Override
    public float[] embed(String text) {
        calls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return HashingEmbedder.embed(text, returnedDimensions);
    }

    @Override
    public String modelName() {
        return TestProperties.EMBEDDING_MODEL;
    }

    @Override
    public int dimensions() {
        return TestProperties.DIMENSIONS;
    }

    public int calls() {
        return calls.get();
    }

    public void failTransiently() {
        failure = AiProviderException.unavailable(new RuntimeException("503 Service Unavailable"));
    }

    public void failWith(RuntimeException ex) {
        failure = ex;
    }

    public void recover() {
        failure = null;
    }

    public void returnDimensions(int dimensions) {
        returnedDimensions = dimensions;
    }
}
