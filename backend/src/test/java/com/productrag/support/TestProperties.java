package com.productrag.support;

import com.productrag.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.util.unit.DataSize;

public final class TestProperties {

    public static final String JWT_SECRET = "test-jwt-secret-that-is-long-enough-for-hs256-signing";
    public static final int DIMENSIONS = 1536;
    public static final String EMBEDDING_MODEL = "test-embedding-model";

    private TestProperties() {
    }

    public static AppProperties defaults() {
        return withRelativeScoreMargin(0.0);
    }

    public static AppProperties withRelativeScoreMargin(double relativeScoreMargin) {
        return new AppProperties(
                new AppProperties.Security(
                        new AppProperties.Jwt(JWT_SECRET, Duration.ofHours(1), "product-rag-admin"),
                        new AppProperties.Admin("admin@example.com", "a-strong-test-password", "Admin")),
                new AppProperties.Cors(List.of("http://localhost:5173")),
                new AppProperties.Http(DataSize.ofKilobytes(256)),
                new AppProperties.RateLimit(10, 20),
                new AppProperties.Products("INR", 5),
                new AppProperties.Ai("test-key", EMBEDDING_MODEL, DIMENSIONS, "test-chat-model", 0.2,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new AppProperties.Rag(5, 0.25, relativeScoreMargin, 20, 6, 1000, 1200,
                        "I couldn't find a sufficiently relevant product in the current catalog.",
                        "You are a product information assistant."),
                new AppProperties.VectorSync(false, Duration.ofSeconds(5), 10, 3, Duration.ofSeconds(10),
                        Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(1)));
    }
}
