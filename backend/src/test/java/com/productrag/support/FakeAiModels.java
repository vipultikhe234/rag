package com.productrag.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.TransientAiException;

/** Spring AI model fakes for integration tests: no network calls, no API costs. */
public final class FakeAiModels {

    private FakeAiModels() {
    }

    public static class FakeEmbeddingModel implements EmbeddingModel {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean failing;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            int index = 0;
            for (String text : request.getInstructions()) {
                embeddings.add(new Embedding(embedText(text), index++));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(String text) {
            return embedText(text);
        }

        @Override
        public float[] embed(Document document) {
            return embedText(document.getText());
        }

        @Override
        public int dimensions() {
            return TestProperties.DIMENSIONS;
        }

        private float[] embedText(String text) {
            calls.incrementAndGet();
            if (failing) {
                throw new TransientAiException("Simulated provider outage (503)");
            }
            return HashingEmbedder.embed(text, TestProperties.DIMENSIONS);
        }

        public int calls() {
            return calls.get();
        }

        public void reset() {
            calls.set(0);
            failing = false;
        }

        public void setFailing(boolean failing) {
            this.failing = failing;
        }
    }

    public static class FakeChatModel implements ChatModel {

        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> reply = new AtomicReference<>("Fake answer based on the context.");

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.get()))));
        }

        public List<Prompt> prompts() {
            return prompts;
        }

        public String lastUserPrompt() {
            Prompt last = prompts.getLast();
            return last.getInstructions().getLast().getText();
        }

        public void reset() {
            prompts.clear();
        }
    }
}
