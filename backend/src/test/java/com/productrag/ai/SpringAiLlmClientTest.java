package com.productrag.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.productrag.config.AppProperties;
import com.productrag.support.TestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

class SpringAiLlmClientTest {

    private final AppProperties properties = TestProperties.defaults();

    @Test
    void streamSkipsChunksWithoutTextSuchAsTheFinalUsageChunk() {
        SpringAiLlmClient client = client(Flux.just(chunk("Wireless "), chunk(""), chunk("Headphones"),
                new ChatResponse(List.of())));

        assertThat(client.stream("system", "user").collectList().block()).containsExactly("Wireless ", "Headphones");
    }

    @Test
    void streamTranslatesProviderErrors() {
        SpringAiLlmClient client = client(Flux.concat(Flux.just(chunk("Partial")),
                Flux.error(new TransientAiException("503 Service Unavailable"))));

        assertThatThrownBy(() -> client.stream("system", "user").collectList().block())
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE"));
    }

    private SpringAiLlmClient client(Flux<ChatResponse> responses) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return responses;
            }
        };
        return new SpringAiLlmClient(model, new AiProviderStatus(properties), properties);
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
