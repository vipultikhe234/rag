package com.productrag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productrag.auth.AppUser;
import com.productrag.auth.AppUserRepository;
import com.productrag.auth.Role;
import com.productrag.support.FakeAiModels;
import com.productrag.support.TestProperties;
import com.productrag.vectorsync.VectorSyncProcessor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests against a real local PostgreSQL + pgvector database with fake AI models.
 * Runs only when {@code IT_DB_URL} points to a localhost database whose name ends in {@code _test}
 * (credentials in {@code IT_DB_USERNAME} / {@code IT_DB_PASSWORD}); the tests delete all rows in it.
 */
@EnabledIfEnvironmentVariable(named = "IT_DB_URL",
        matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/[A-Za-z0-9_]+_test")
@SpringBootTest(properties = {
        "app.security.jwt.secret=" + TestProperties.JWT_SECRET,
        "app.security.admin.email=",
        "app.vector-sync.enabled=false",
        "app.vector-sync.max-attempts=3",
        "app.rag.similarity-threshold=0.2",
        // Every test signs in during setUp; the default limit (10/min) would reject later tests with 429.
        "app.rate-limit.login-per-minute=1000",
        "spring.ai.openai.api-key=test-key",
        "spring.ai.model.embedding=none",
        "spring.ai.model.chat=none",
        "app.ai.embedding-model=" + TestProperties.EMBEDDING_MODEL
})
@AutoConfigureMockMvc
class ProductRagIntegrationTest {

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("IT_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("IT_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("IT_DB_PASSWORD"));
    }

    @TestConfiguration
    static class FakeAiConfig {

        @Bean
        @Primary
        FakeAiModels.FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeAiModels.FakeEmbeddingModel();
        }

        @Bean
        @Primary
        FakeAiModels.FakeChatModel fakeChatModel() {
            return new FakeAiModels.FakeChatModel();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VectorSyncProcessor processor;
    @Autowired
    private FakeAiModels.FakeEmbeddingModel embeddingModel;
    @Autowired
    private FakeAiModels.FakeChatModel chatModel;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM chat_conversations");
        jdbc.update("DELETE FROM vector_sync_events");
        jdbc.update("DELETE FROM catalog_embeddings");
        jdbc.update("DELETE FROM product_variants");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM product_attributes");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM brands");
        jdbc.update("DELETE FROM app_users");
        // productJson refers to these by name.
        jdbc.update("INSERT INTO categories (id, name, slug) VALUES (?, 'Electronics', 'electronics')", UUID.randomUUID());
        jdbc.update("INSERT INTO brands (id, name, slug) VALUES (?, 'ExampleBrand', 'examplebrand')", UUID.randomUUID());
        embeddingModel.reset();
        chatModel.reset();
        adminToken = login(createUser("admin@example.com", Role.ADMIN));
    }

    @Test
    void createProductSynchronizesItsEmbedding() throws Exception {
        UUID id = createProduct("Wireless Headphones", "WH-001", "2499.00", 12,
                "Over-ear headphones with active noise cancellation and a 30-hour battery life.");

        assertThat(eventStatuses(id)).containsExactly("PENDING");
        processor.drain();

        assertThat(eventStatuses(id)).containsExactly("COMPLETED");
        assertThat(embeddingCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM product_embeddings WHERE product_id = ?",
                Integer.class, id)).isEqualTo(TestProperties.DIMENSIONS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_embeddings", Integer.class))
                .as("its brand and category").isEqualTo(2);
        assertThat(embeddingModel.calls()).isEqualTo(3);

        mockMvc.perform(get("/api/v1/products/" + id).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vectorSync.state").value("SYNCED"));
    }

    @Test
    void relevantUpdateRefreshesEmbeddingAndPriceChangeDoesNot() throws Exception {
        UUID id = createProduct("Wireless Headphones", "WH-001", "2499.00", 12, "Over-ear headphones.");
        processor.drain();
        String originalHash = contentHash(id);
        int callsBefore = embeddingModel.calls();

        updateProduct(id, "Wireless Headphones", "WH-001", "2499.00", 12, "Over-ear headphones with a 40-hour battery.");
        processor.drain();
        assertThat(contentHash(id)).isNotEqualTo(originalHash);
        assertThat(embeddingModel.calls()).as("brand and category summaries do not contain descriptions")
                .isEqualTo(callsBefore + 1);
        assertThat(embeddingCount(id)).isEqualTo(1);

        updateProduct(id, "Wireless Headphones", "WH-001", "1999.00", 3, "Over-ear headphones with a 40-hour battery.");
        processor.drain();
        assertThat(embeddingModel.calls()).isEqualTo(callsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT result FROM vector_sync_events WHERE aggregate_id = ? "
                + "ORDER BY created_at DESC LIMIT 1", String.class, id)).isEqualTo("UNCHANGED");
    }

    @Test
    void duplicateSkuIsRejectedByTheApi() throws Exception {
        createProduct("Wireless Headphones", "WH-001", "2499.00", 12, "Headphones.");

        mockMvc.perform(post("/api/v1/products").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Other", "wh-001", "10.00", 1, "Other product")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
    }

    @Test
    void deactivatedProductsAreExcludedFromRetrievalAndReactivationRestoresThem() throws Exception {
        UUID id = createProduct("Bluetooth Speaker", "SPK-1", "1499.00", 5, "Portable bluetooth speaker with deep bass.");
        processor.drain();
        assertThat(chatSources("portable bluetooth speaker with deep bass")).contains("SPK-1");

        mockMvc.perform(patch("/api/v1/products/" + id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        // Excluded immediately, even before the outbox event is processed.
        assertThat(chatSources("portable bluetooth speaker with deep bass")).doesNotContain("SPK-1");
        processor.drain();
        assertThat(embeddingCount(id)).isEqualTo(1);
        assertThat(chatSources("portable bluetooth speaker with deep bass")).doesNotContain("SPK-1");

        mockMvc.perform(patch("/api/v1/products/" + id + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        processor.drain();
        assertThat(chatSources("portable bluetooth speaker with deep bass")).contains("SPK-1");
        assertThat(eventTypes(id)).contains("PRODUCT_DEACTIVATED", "PRODUCT_REACTIVATED");
    }

    @Test
    void deletedProductsHaveNoVectorRecords() throws Exception {
        UUID id = createProduct("Desk Lamp", "DL-1", "799.00", 4, "LED desk lamp for office work.");
        processor.drain();
        assertThat(embeddingCount(id)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/products/" + id).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        assertThat(embeddingCount(id)).isZero();
        processor.drain();
        assertThat(eventTypes(id)).contains("PRODUCT_DELETED");
        assertThat(jdbc.queryForObject("SELECT result FROM vector_sync_events WHERE aggregate_id = ? AND "
                + "event_type = 'PRODUCT_DELETED'", String.class, id)).isEqualTo("VECTORS_DELETED");
        assertThat(jdbc.queryForObject("SELECT payload->>'sku' FROM vector_sync_events WHERE aggregate_id = ? AND "
                + "event_type = 'PRODUCT_DELETED'", String.class, id)).isEqualTo("DL-1");
    }

    @Test
    void providerFailureLeavesRetryableEventsThatCanBeRetried() throws Exception {
        embeddingModel.setFailing(true);
        UUID id = createProduct("Office Chair", "OC-1", "5999.00", 2, "Ergonomic office chair.");

        processor.drain();
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT id, status, retryable, attempts, last_error FROM vector_sync_events WHERE aggregate_id = ?", id);
        assertThat(event.get("status")).isEqualTo("FAILED");
        assertThat(event.get("retryable")).isEqualTo(true);
        assertThat(event.get("attempts")).isEqualTo(1);
        assertThat((String) event.get("last_error")).contains("unavailable");
        assertThat(embeddingCount(id)).isZero();

        embeddingModel.setFailing(false);
        mockMvc.perform(post("/api/v1/admin/vector-sync/retry/" + event.get("id")).header("Authorization", adminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        processor.drain();

        assertThat(eventStatuses(id)).containsExactly("COMPLETED");
        assertThat(embeddingCount(id)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/admin/vector-sync/retry-failed").header("Authorization", adminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requeued").value(2));
        processor.drain();
        mockMvc.perform(get("/api/v1/admin/vector-sync/status").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.embeddedProducts").value(1))
                .andExpect(jsonPath("$.catalogIndex[?(@.entityType == 'BRAND')].embedded").value(1));
    }

    @Test
    void duplicateEventProcessingIsSafe() throws Exception {
        UUID id = createProduct("USB Cable", "USB-1", "299.00", 50, "Braided USB-C cable.");
        processor.drain();
        int callsBefore = embeddingModel.calls();
        jdbc.update("UPDATE vector_sync_events SET status = 'PENDING', attempts = 0 WHERE aggregate_id = ?", id);

        processor.drain();

        assertThat(embeddingModel.calls()).isEqualTo(callsBefore);
        assertThat(embeddingCount(id)).isEqualTo(1);
        assertThat(eventStatuses(id)).containsExactly("COMPLETED");
    }

    @Test
    void staleProcessingEventsAreRecovered() throws Exception {
        UUID id = createProduct("Monitor", "MON-1", "8999.00", 7, "27-inch monitor.");
        jdbc.update("UPDATE vector_sync_events SET status = 'PROCESSING', attempts = 1, locked_by = 'dead-worker', "
                + "locked_at = now() - interval '1 hour' WHERE aggregate_id = ?", id);

        assertThat(processor.recoverStale()).isEqualTo(1);
        processor.drain();

        assertThat(eventStatuses(id)).containsExactly("COMPLETED");
    }

    @Test
    void semanticChatRetrievesRelevantProductWithSources() throws Exception {
        createProduct("Wireless Headphones", "WH-001", "2499.00", 12,
                "Over-ear wireless headphones with noise cancellation and long battery life for travel.");
        createProduct("Desk Lamp", "DL-1", "799.00", 4, "LED desk lamp with adjustable brightness.");
        processor.drain();

        JsonNode answer = chat("Which headphones are good for travel with long battery life?", null, adminToken);

        assertThat(answer.get("answer").asText()).isEqualTo("Fake answer based on the context.");
        assertThat(answer.get("retrieval").get("strategy").asText()).isEqualTo("SEMANTIC");
        assertThat(skus(answer)).first().isEqualTo("WH-001");
        assertThat(answer.get("sources").get(0).get("relevanceScore").asDouble()).isBetween(0.0, 1.0);
        assertThat(chatModel.lastUserPrompt()).contains("sku: WH-001");
    }

    @Test
    void exactPriceQuestionUsesDatabaseValues() throws Exception {
        createProduct("USB Cable", "USB-1", "299.00", 50, "Braided cable.");
        createProduct("Mouse", "MS-1", "999.00", 0, "Wireless mouse.");
        createProduct("Keyboard", "KB-1", "1000.00", 9, "Mechanical keyboard.");
        int embeddingCallsBefore = embeddingModel.calls();

        JsonNode answer = chat("Which products cost less than ₹1,000?", null, adminToken);

        assertThat(answer.get("retrieval").get("strategy").asText()).isEqualTo("STRUCTURED");
        assertThat(answer.get("retrieval").get("totalMatches").asLong()).isEqualTo(2);
        assertThat(skus(answer)).containsExactly("USB-1", "MS-1");
        assertThat(embeddingModel.calls()).isEqualTo(embeddingCallsBefore);

        JsonNode stock = chat("Which products have stock greater than 10?", null, adminToken);
        assertThat(skus(stock)).containsExactly("USB-1");
    }

    @Test
    void noResultsDoesNotCallTheLlm() throws Exception {
        JsonNode answer = chat("Do you have any submarines?", null, adminToken);

        assertThat(answer.get("sources")).isEmpty();
        assertThat(answer.get("answer").asText()).contains("couldn't find");
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void usersCannotReadAnotherUsersConversation() throws Exception {
        createProduct("Wireless Headphones", "WH-001", "2499.00", 12, "Wireless headphones.");
        processor.drain();
        JsonNode adminAnswer = chat("wireless headphones", null, adminToken);
        String conversationId = adminAnswer.get("conversationId").asText();

        String otherToken = login(createUser("user@example.com", Role.USER));

        mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/chat").header("Authorization", otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\",\"conversationId\":\"" + conversationId + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/chat/conversations/" + conversationId + "/messages")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void chatProviderFailureReturnsControlledError() throws Exception {
        createProduct("Wireless Headphones", "WH-001", "2499.00", 12, "Wireless headphones.");
        processor.drain();
        embeddingModel.setFailing(true);

        mockMvc.perform(post("/api/v1/chat").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"wireless headphones\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
    }

    // ---------------------------------------------------------------------------------------------

    private String createUser(String email, Role role) {
        String password = "integration-test-password";
        userRepository.save(new AppUser(UUID.randomUUID(), email, passwordEncoder.encode(password), email, role));
        return email;
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"integration-test-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createProduct(String name, String sku, String price, int stock, String description) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(name, sku, price, stock, description)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void updateProduct(UUID id, String name, String sku, String price, int stock, String description)
            throws Exception {
        mockMvc.perform(put("/api/v1/products/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(name, sku, price, stock, description)))
                .andExpect(status().isOk());
    }

    private String productJson(String name, String sku, String price, int stock, String description) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", name, "sku", sku, "description", description, "category", "Electronics",
                "brand", "ExampleBrand", "price", price, "stockQuantity", stock,
                "attributes", Map.of("warranty", "1 year")));
    }

    private JsonNode chat(String question, String conversationId, String token) throws Exception {
        String body = objectMapper.writeValueAsString(conversationId == null
                ? Map.of("question", question) : Map.of("question", question, "conversationId", conversationId));
        MvcResult result = mockMvc.perform(post("/api/v1/chat").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> chatSources(String question) throws Exception {
        return skus(chat(question, null, adminToken));
    }

    private static List<String> skus(JsonNode answer) {
        return java.util.stream.StreamSupport.stream(answer.get("sources").spliterator(), false)
                .map(source -> source.get("sku").asText())
                .toList();
    }

    private List<String> eventStatuses(UUID productId) {
        return jdbc.queryForList("SELECT status FROM vector_sync_events WHERE aggregate_id = ? ORDER BY created_at",
                String.class, productId);
    }

    private List<String> eventTypes(UUID productId) {
        return jdbc.queryForList("SELECT event_type FROM vector_sync_events WHERE aggregate_id = ? ORDER BY created_at",
                String.class, productId);
    }

    private int embeddingCount(UUID productId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM product_embeddings WHERE product_id = ?",
                Integer.class, productId);
        return count == null ? 0 : count;
    }

    private String contentHash(UUID productId) {
        return jdbc.queryForObject("SELECT content_hash FROM product_embeddings WHERE product_id = ?", String.class,
                productId);
    }
}
