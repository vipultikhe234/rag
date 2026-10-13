package com.productrag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Http http,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Products products,
        @Valid @NotNull Ai ai,
        @Valid @NotNull Rag rag,
        @Valid @NotNull VectorSync vectorSync) {

    public record Security(@Valid @NotNull Jwt jwt, @Valid @NotNull Admin admin) {
    }

    public record Jwt(String secret, @NotNull Duration expiration, @NotBlank String issuer) {
    }

    public record Admin(String email, String password, String displayName) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Http(@NotNull DataSize maxRequestSize) {
    }

    public record RateLimit(@Min(1) int loginPerMinute, @Min(1) int chatPerMinute) {
    }

    public record Products(@NotBlank @Pattern(regexp = "^[A-Z]{3}$") String defaultCurrency) {
    }

    public record Ai(
            String apiKey,
            @NotBlank String embeddingModel,
            @Min(1) @Max(16000) int embeddingDimensions,
            @NotBlank String chatModel,
            Double chatTemperature,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }

    public record Rag(
            @Min(1) @Max(50) int retrievalLimit,
            @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
            @DecimalMin("0.0") @DecimalMax("1.0") double relativeScoreMargin,
            @Min(1) @Max(100) int structuredResultLimit,
            @Min(0) @Max(50) int historyMessages,
            @Min(10) @Max(10000) int maxQuestionLength,
            @Min(100) int maxContextDescriptionChars,
            @NotBlank String noResultsMessage,
            @NotBlank String systemPrompt) {
    }

    public record VectorSync(
            boolean enabled,
            @NotNull Duration pollInterval,
            @Min(1) @Max(500) int batchSize,
            @Min(1) @Max(100) int maxAttempts,
            @NotNull Duration initialBackoff,
            @NotNull Duration maxBackoff,
            @NotNull Duration staleProcessingTimeout,
            @NotNull Duration recoveryInterval) {
    }
}
