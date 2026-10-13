package com.productrag.ai;

import com.productrag.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class SpringAiLlmClient implements LlmClient {

    private final ChatModel chatModel;
    private final AiProviderStatus status;
    private final Double temperature;
    private final Duration readTimeout;

    public SpringAiLlmClient(ChatModel chatModel, AiProviderStatus status, AppProperties properties) {
        this.chatModel = chatModel;
        this.status = status;
        this.temperature = properties.ai().chatTemperature();
        this.readTimeout = properties.ai().readTimeout();
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        status.requireConfigured();
        ChatResponse response;
        try {
            response = chatModel.call(prompt(systemPrompt, userPrompt));
        } catch (RuntimeException ex) {
            throw AiErrors.translate(ex);
        }
        String text = text(response);
        if (text == null || text.isBlank()) {
            throw AiProviderException.emptyResponse();
        }
        return text.trim();
    }

    @Override
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        return Flux.defer(() -> {
                    status.requireConfigured();
                    return chatModel.stream(prompt(systemPrompt, userPrompt));
                })
                // The final chunk of an OpenAI-compatible stream may carry only usage data and no text.
                .mapNotNull(SpringAiLlmClient::text)
                .filter(chunk -> !chunk.isEmpty())
                // Bounds the wait for the first chunk and every gap between chunks, not the whole answer.
                .timeout(readTimeout)
                .onErrorMap(ex -> !(ex instanceof AiProviderException),
                        ex -> ex instanceof RuntimeException runtime ? AiErrors.translate(runtime)
                                : AiProviderException.unavailable(ex));
    }

    private Prompt prompt(String systemPrompt, String userPrompt) {
        List<Message> messages = List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt));
        return temperature == null
                ? new Prompt(messages)
                : new Prompt(messages, ChatOptions.builder().temperature(temperature).build());
    }

    private static String text(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
    }
}
