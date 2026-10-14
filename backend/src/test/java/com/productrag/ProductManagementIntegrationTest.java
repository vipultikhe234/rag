package com.productrag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Product management (categories, attributes, options, variants) against a real local PostgreSQL database, so the
 * V4 constraints, the one-transaction product create and the variant summary are exercised for real. Same safety
 * gate as {@link ProductRagIntegrationTest}: only a localhost database whose name ends in {@code _test}; the tests
 * delete all rows in it. No AI provider is called.
 */
@EnabledIfEnvironmentVariable(named = "IT_DB_URL",
        matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/[A-Za-z0-9_]+_test")
@SpringBootTest(properties = {
        "app.security.jwt.secret=" + TestProperties.JWT_SECRET,
        "app.security.admin.email=",
        "app.vector-sync.enabled=false",
        "app.vector-sync.max-attempts=3",
        "app.rag.similarity-threshold=0.2",
        "app.rate-limit.login-per-minute=1000",
        "spring.ai.openai.api-key=test-key",
        "spring.ai.model.embedding=none",
        "spring.ai.model.chat=none",
        "app.ai.embedding-model=" + TestProperties.EMBEDDING_MODEL
})
@AutoConfigureMockMvc
class ProductManagementIntegrationTest {

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
    private AppUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private VectorSyncProcessor processor;

    private String adminToken;
    private UUID electronics;
    private UUID phones;
    private UUID brand;
    private UUID storageAttribute;

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
        electronics = UUID.randomUUID();
        phones = UUID.randomUUID();
        brand = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, name, slug) VALUES (?, 'Electronics', 'electronics')", electronics);
        jdbc.update("INSERT INTO categories (id, name, slug, parent_id) VALUES (?, 'Mobile Phones', 'mobile-phones', ?)",
                phones, electronics);
        jdbc.update("INSERT INTO brands (id, name, slug) VALUES (?, 'Samsung', 'samsung')", brand);
        String email = "admin@example.com";
        userRepository.save(new AppUser(UUID.randomUUID(), email,
                passwordEncoder.encode("integration-test-password"), email, Role.ADMIN));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"integration-test-password\"}"))
                .andExpect(status().isOk()).andReturn();
        adminToken = "Bearer " + read(login).get("accessToken").asText();
        storageAttribute = id(send("POST", "/api/v1/product-attributes", Map.of(
                "categoryIds", List.of(phones), "name", "Storage Capacity", "dataType", "NUMBER", "unit", "GB",
                "required", false, "filterable", true)).andExpect(status().isCreated()));
    }

    @Test
    void variantProductIsCreatedWithOptionsAndVariantsInOneRequest() throws Exception {
        JsonNode product = read(send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", "74999", 10, "ACTIVE"),
                variant("GS24-256-BLK", "Black", "256", "79999", null, 4, "ACTIVE"),
                variant("GS24-256-GRY", "Grey", "256.0", "79999", null, 7, "INACTIVE"))))
                .andExpect(status().isCreated()).andReturn());

        assertThat(product.get("slug").asText()).isEqualTo("galaxy-s24");
        assertThat(product.get("hasVariants").asBoolean()).isTrue();
        assertThat(product.get("price").decimalValue()).isEqualByComparingTo("69999");
        assertThat(product.get("stockQuantity").asInt()).isEqualTo(14);
        assertThat(product.get("variantSummary").get("count").asInt()).isEqualTo(3);
        assertThat(product.get("variantSummary").get("activeCount").asInt()).isEqualTo(2);
        assertThat(product.get("options")).hasSize(2);
        assertThat(product.get("options").get(1).get("unit").asText()).isEqualTo("GB");
        assertThat(product.get("images")).hasSize(2);

        JsonNode variants = read(mockMvc.perform(get("/api/v1/products/" + product.get("id").asText() + "/variants")
                .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(variants).hasSize(3);
        assertThat(variants.get(0).get("variantName").asText()).isEqualTo("Black / 128 GB");
        assertThat(variants.get(0).get("onSale").asBoolean()).isTrue();
        assertThat(variants.get(0).get("compareAtPrice").decimalValue()).isEqualByComparingTo("74999");
        assertThat(variants.get(2).get("variantName").asText()).isEqualTo("Grey / 256 GB");
        UUID productId = UUID.fromString(product.get("id").asText());
        assertThat(count("SELECT count(*) FROM product_option_values v JOIN product_options o ON o.id = v.option_id "
                + "WHERE o.product_id = ?", productId)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM variant_option_values WHERE product_id = ?", productId)).isEqualTo(6);
    }

    @Test
    void aFailingVariantRollsBackTheWholeProduct() throws Exception {
        send("POST", "/api/v1/products", simple("TAKEN-SKU", "Phone Case", "499")).andExpect(status().isCreated());
        long productsBefore = count("SELECT count(*) FROM products");

        send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE"),
                variant("taken-sku", "Black", "256", "79999", null, 4, "ACTIVE"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));

        assertThat(count("SELECT count(*) FROM products")).isEqualTo(productsBefore);
        assertThat(count("SELECT count(*) FROM product_options")).isZero();
        assertThat(count("SELECT count(*) FROM product_images")).isZero();
        assertThat(count("SELECT count(*) FROM product_variants")).isZero();
    }

    @Test
    void duplicateCombinationsAreRejectedByTheApiAndTheDatabase() throws Exception {
        UUID productId = id(send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE"))))
                .andExpect(status().isCreated()));

        Map<String, Object> duplicate = new LinkedHashMap<>(variant("GS24-OTHER", "black", "128", "1", null, 1,
                "ACTIVE"));
        duplicate.put("productId", productId);
        send("POST", "/api/v1/product-variants", duplicate)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VARIANT_COMBINATION_EXISTS"));
        send("POST", "/api/v1/products/" + productId + "/variants", Map.of("variants", List.of(
                variant("GS24-A", "Grey", "128", "1", null, 1, "ACTIVE"),
                variant("GS24-B", "GREY", "128", "1", null, 1, "ACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("variants[1].options"));

        String signature = jdbc.queryForObject("SELECT option_signature FROM product_variants WHERE product_id = ?",
                String.class, productId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO product_variants (id, product_id, sku, variant_name, "
                + "attributes, price, stock_quantity, status, version, option_signature) VALUES (?, ?, 'RAW-1', 'x', "
                + "'{}', 1, 0, 'ACTIVE', 0, ?)", UUID.randomUUID(), productId, signature))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_product_variants_option_signature");
    }

    @Test
    void theProductSummaryFollowsVariantChanges() throws Exception {
        UUID productId = id(send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE"),
                variant("GS24-256-BLK", "Black", "256", "79999", null, 4, "ACTIVE"))))
                .andExpect(status().isCreated()));
        UUID cheapest = UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM product_variants WHERE sku = 'GS24-128-BLK'", String.class));

        mockMvc.perform(patch("/api/v1/product-variants/" + cheapest + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        Map<String, Object> summary = jdbc.queryForMap("SELECT price, stock_quantity, version FROM products WHERE id = ?",
                productId);
        assertThat((java.math.BigDecimal) summary.get("price")).isEqualByComparingTo("79999");
        assertThat(summary.get("stock_quantity")).isEqualTo(4);
        assertThat(summary.get("version")).as("summary updates are not admin edits").isEqualTo(0L);

        send("POST", "/api/v1/products/" + productId + "/variants", Map.of("variants", List.of(
                variant("GS24-512-GRY", "Grey", "512", "59999", null, 3, "ACTIVE"))))
                .andExpect(status().isCreated());
        assertThat(count("SELECT stock_quantity FROM products WHERE id = ?", productId)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", java.math.BigDecimal.class, productId))
                .isEqualByComparingTo("59999");
    }

    @Test
    void usedOptionValuesCannotBeRemovedAndVariantProductsCannotBecomeSimple() throws Exception {
        JsonNode product = read(send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE"))))
                .andExpect(status().isCreated()).andReturn());
        String id = product.get("id").asText();

        Map<String, Object> withoutBlack = new LinkedHashMap<>(phone("GS24", null));
        withoutBlack.put("options", List.of(option("Color", null, "Grey"), option("Storage Capacity",
                storageAttribute, "128", "256", "512")));
        withoutBlack.put("version", product.get("version").asLong());
        send("PUT", "/api/v1/products/" + id, withoutBlack)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTION_VALUE_IN_USE"));

        Map<String, Object> simple = new LinkedHashMap<>(simple("GS24", "Galaxy S24", "100"));
        simple.put("categoryId", phones);
        simple.put("hasVariants", false);
        send("PUT", "/api/v1/products/" + id, simple)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_HAS_VARIANTS"));
    }

    @Test
    void productAndVariantFiltersAreAppliedOnTheServer() throws Exception {
        send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE"),
                variant("GS24-256-GRY", "Grey", "256", "89999", null, 0, "ACTIVE")))).andExpect(status().isCreated());
        Map<String, Object> cable = new LinkedHashMap<>(simple("USB-C", "USB-C Cable", "299"));
        cable.put("categoryId", electronics);
        send("POST", "/api/v1/products", cable).andExpect(status().isCreated());

        assertThat(skus("/api/v1/products?hasVariants=true")).containsExactly("GS24");
        assertThat(skus("/api/v1/products?minPrice=80000&maxPrice=90000")).containsExactly("GS24");
        assertThat(skus("/api/v1/products?maxPrice=1000")).containsExactly("USB-C");
        assertThat(skus("/api/v1/products?categoryId=" + electronics + "&sort=sku,asc"))
                .containsExactly("GS24", "USB-C");

        assertThat(skus("/api/v1/product-variants?option=Color:grey")).containsExactly("GS24-256-GRY");
        assertThat(skus("/api/v1/product-variants?option=Color:black&option=Storage Capacity:128"))
                .containsExactly("GS24-128-BLK");
        assertThat(skus("/api/v1/product-variants?categoryId=" + electronics + "&brandId=" + brand + "&stock=AVAILABLE"))
                .containsExactly("GS24-128-BLK");
        mockMvc.perform(get("/api/v1/product-variants?option=nocolon").header("Authorization", adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inactiveParentsBlockNewAssignmentsUntilReactivated() throws Exception {
        mockMvc.perform(patch("/api/v1/categories/" + electronics + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        Map<String, Object> request = new LinkedHashMap<>(simple("NOKIA-105", "Nokia 105", "1299"));
        request.put("categoryId", phones);
        send("POST", "/api/v1/products", request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("categoryId"));
        assertThat(read(mockMvc.perform(get("/api/v1/categories/options?includeInactive=true")
                .header("Authorization", adminToken)).andReturn()).findValues("assignable").stream()
                .map(JsonNode::asBoolean).toList()).containsOnly(false);

        mockMvc.perform(patch("/api/v1/categories/" + electronics + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        send("POST", "/api/v1/products", request).andExpect(status().isCreated());
    }

    @Test
    void attributesAreGlobalAndCanApplyToSeveralCategories() throws Exception {
        UUID laptops = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, name, slug, parent_id) VALUES (?, 'Laptops', 'laptops', ?)", laptops,
                electronics);
        send("POST", "/api/v1/product-attributes", Map.of("categoryIds", List.of(phones, laptops), "name", "RAM",
                "dataType", "NUMBER", "unit", "GB")).andExpect(status().isCreated());

        for (UUID category : List.of(phones, laptops)) {
            JsonNode applicable = read(mockMvc.perform(get("/api/v1/product-attributes/applicable?categoryId=" + category)
                    .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
            assertThat(applicable.findValuesAsText("slug")).contains("ram");
        }
        send("POST", "/api/v1/product-attributes", Map.of("categoryIds", List.of(electronics), "name", "ram",
                "dataType", "NUMBER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_ALREADY_EXISTS"));
        assertThat(skus("/api/v1/product-attributes?categoryId=" + laptops, "slug")).containsExactly("ram");
    }

    @Test
    void compareAtPriceMustBeHigherThanThePrice() throws Exception {
        send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", "69999", 10, "ACTIVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("variants[0].compareAtPrice"));
    }

    @Test
    void optionsWithoutALinkedAttributeAreAccepted() throws Exception {
        Map<String, Object> body = phone("WH5", null);
        body.put("options", List.of(option("Color", null, "Black", "Silver")));
        Map<String, Object> black = variant("WH5-BLK", "Black", "128", "29990", null, 3, "ACTIVE");
        black.put("options", Map.of("Color", "Black"));
        body.put("variants", List.of(black));
        UUID productId = id(send("POST", "/api/v1/products", body).andExpect(status().isCreated()));

        Map<String, Object> silver = variant("WH5-SLV", "Silver", "128", "29990", null, 2, "ACTIVE");
        silver.put("options", Map.of("Color", "silver"));
        send("POST", "/api/v1/products/" + productId + "/variants", Map.of("variants", List.of(silver)))
                .andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM product_variants WHERE product_id = ?", productId)).isEqualTo(2);
    }

    @Test
    void onlyChangesThatAlterTheSearchDocumentQueueReembedding() throws Exception {
        UUID productId = id(send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE")))).andExpect(status().isCreated()));
        UUID variantId = UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM product_variants WHERE sku = 'GS24-128-BLK'", String.class));
        assertThat(events(productId)).isEqualTo(1);

        Map<String, Object> priceAndStock = new LinkedHashMap<>(variant("GS24-128-BLK", "Black", "128", "64999", null,
                3, "ACTIVE"));
        priceAndStock.put("productId", productId);
        send("PUT", "/api/v1/product-variants/" + variantId, priceAndStock).andExpect(status().isOk());
        assertThat(events(productId)).as("price and stock are not part of the embedded text").isEqualTo(1);

        Map<String, Object> recolored = new LinkedHashMap<>(variant("GS24-128-BLK", "Grey", "128", "64999", null, 3,
                "ACTIVE"));
        recolored.put("productId", productId);
        send("PUT", "/api/v1/product-variants/" + variantId, recolored).andExpect(status().isOk());
        assertThat(events(productId)).isEqualTo(2);

        jdbc.update("UPDATE vector_sync_events SET status = 'COMPLETED'");
        send("PUT", "/api/v1/categories/" + electronics, Map.of("name", "Consumer Electronics", "slug", "electronics",
                "version", 0)).andExpect(status().isOk());
        assertThat(events(productId)).as("the product sits in a subcategory of the renamed category").isEqualTo(3);
        send("PUT", "/api/v1/categories/" + electronics, Map.of("name", "Home Electronics", "slug", "electronics",
                "version", 1)).andExpect(status().isOk());
        assertThat(events(productId)).as("an event is already pending for it").isEqualTo(3);

        jdbc.update("UPDATE vector_sync_events SET status = 'COMPLETED'");
        send("PUT", "/api/v1/product-attributes/" + storageAttribute, Map.of("categoryIds", List.of(phones),
                "name", "Storage", "slug", "storage-capacity", "dataType", "NUMBER", "unit", "GB", "version", 0))
                .andExpect(status().isOk());
        assertThat(events(productId)).as("a variant option is linked to the renamed attribute").isEqualTo(4);
    }

    @Test
    void semanticSearchVerifiesFiltersAndReadsLivePricesFromTheDatabase() throws Exception {
        UUID productId = id(send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE"),
                variant("GS24-256-GRY", "Grey", "256", "89999", null, 0, "ACTIVE")))).andExpect(status().isCreated()));
        send("POST", "/api/v1/products", simple("USB-C", "USB-C Cable", "299")).andExpect(status().isCreated());
        processor.drain();
        String search = "/api/v1/products/semantic-search?q=Samsung Galaxy S24 smartphone display";

        JsonNode all = read(mockMvc.perform(get(search).header("Authorization", adminToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(all.get("mode").asText()).isEqualTo("HYBRID");
        JsonNode first = all.get("results").get(0);
        assertThat(first.get("product").get("sku").asText()).isEqualTo("GS24");
        assertThat(first.get("matchType").asText()).isEqualTo("SEMANTIC");
        assertThat(first.get("similarity").asDouble()).isGreaterThan(0.2);
        assertThat(first.get("matchingVariants")).hasSize(2);

        assertThat(searchSkus(search + "&option=Color:Grey&inStock=true")).doesNotContain("GS24");
        JsonNode black = read(mockMvc.perform(get(search + "&option=Color:black&maxPrice=70000&categoryId="
                + electronics).header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(black.get("results")).hasSize(1);
        assertThat(black.at("/results/0/matchingVariants/0/sku").asText()).isEqualTo("GS24-128-BLK");

        UUID blackId = UUID.fromString(black.at("/results/0/matchingVariants/0/id").asText());
        Map<String, Object> cheaper = new LinkedHashMap<>(variant("GS24-128-BLK", "Black", "128", "59999", null, 10,
                "ACTIVE"));
        cheaper.put("productId", productId);
        send("PUT", "/api/v1/product-variants/" + blackId, cheaper).andExpect(status().isOk());
        JsonNode repriced = read(mockMvc.perform(get(search + "&option=Color:Black").header("Authorization",
                adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(repriced.at("/results/0/matchingVariants/0/price").decimalValue()).isEqualByComparingTo("59999");

        assertThat(searchSkus("/api/v1/products/semantic-search?q=gs24-256-gry")).first().isEqualTo("GS24");
        mockMvc.perform(get("/api/v1/products/semantic-search?q=phone&option=nocolon")
                .header("Authorization", adminToken)).andExpect(status().isBadRequest());
    }

    @Test
    void refreshStaleQueuesOnlyProductsWhoseEmbeddingIsOutdated() throws Exception {
        UUID productId = id(send("POST", "/api/v1/products", simple("USB-C", "USB-C Cable", "299"))
                .andExpect(status().isCreated()));
        processor.drain();
        assertThat(refreshStale()).isEqualTo(Map.of("checked", 1, "stale", 0, "enqueued", 0));

        jdbc.update("UPDATE product_embeddings SET content_hash = 'built-by-an-older-version' WHERE product_id = ?",
                productId);
        assertThat(refreshStale()).isEqualTo(Map.of("checked", 1, "stale", 1, "enqueued", 1));
        assertThat(refreshStale()).as("already pending").isEqualTo(Map.of("checked", 1, "stale", 1, "enqueued", 0));

        processor.drain();
        assertThat(refreshStale()).isEqualTo(Map.of("checked", 1, "stale", 0, "enqueued", 0));
    }

    @Test
    void everyCatalogModuleIsEmbeddedAndSearchable() throws Exception {
        send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE"),
                variant("GS24-256-GRY", "Grey", "256", "89999", null, 0, "ACTIVE")))).andExpect(status().isCreated());
        send("POST", "/api/v1/brands", Map.of("name", "Nike", "description", "Running shoes and sportswear"))
                .andExpect(status().isCreated());
        send("POST", "/api/v1/categories", Map.of("name", "Running Gear",
                "description", "Shoes, shorts and watches for runners")).andExpect(status().isCreated());
        processor.drain();

        Map<String, Long> perType = new LinkedHashMap<>();
        jdbc.query("SELECT entity_type, count(*) AS n FROM catalog_embeddings GROUP BY entity_type",
                rs -> {
                    perType.put(rs.getString("entity_type"), rs.getLong("n"));
                });
        assertThat(perType).isEqualTo(Map.of("BRAND", 2L, "CATEGORY", 3L, "ATTRIBUTE", 1L, "VARIANT", 2L));
        assertThat(jdbc.queryForObject("SELECT content FROM catalog_embeddings WHERE entity_id = ?", String.class,
                brand)).contains("Brand: Samsung").contains("Mobile Phones").contains("Galaxy S24");
        assertThat(jdbc.queryForObject("SELECT content FROM catalog_embeddings WHERE entity_id = ?", String.class,
                electronics)).contains("Subcategories: Mobile Phones").contains("Brands: Samsung");

        JsonNode brands = read(mockMvc.perform(get("/api/v1/brands/semantic-search?q=Galaxy phones")
                .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(brands.get("mode").asText()).isEqualTo("HYBRID");
        assertThat(brands.at("/results/0/item/name").asText()).isEqualTo("Samsung");
        assertThat(brands.at("/results/0/matchType").asText()).isEqualTo("SEMANTIC");

        JsonNode categories = read(mockMvc.perform(get("/api/v1/categories/semantic-search?q=shoes for runners")
                .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(categories.at("/results/0/item/name").asText()).isEqualTo("Running Gear");

        JsonNode variants = read(mockMvc.perform(get("/api/v1/product-variants/semantic-search?q=gs24-256-gry")
                .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(variants.at("/results/0/item/sku").asText()).isEqualTo("GS24-256-GRY");
        assertThat(variants.at("/results/0/matchType").asText()).startsWith("SEMANTIC_AND_NAME");

        JsonNode attributes = read(mockMvc.perform(get("/api/v1/product-attributes/semantic-search?q=storage GB")
                .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(attributes.at("/results/0/item/name").asText()).isEqualTo("Storage Capacity");

        JsonNode status = read(mockMvc.perform(get("/api/v1/admin/vector-sync/status")
                .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn());
        assertThat(status.get("catalogIndex").findValues("activeWithoutEmbedding").stream().map(JsonNode::asLong)
                .toList()).containsOnly(0L);
    }

    @Test
    void summariesFollowProductAndBrandChanges() throws Exception {
        UUID productId = id(send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE")))).andExpect(status().isCreated()));
        processor.drain();

        mockMvc.perform(patch("/api/v1/products/" + productId + "/status").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM vector_sync_events WHERE status = 'PENDING' AND aggregate_type = "
                + "'BRAND' AND aggregate_id = ?", brand)).as("the brand summary lists active products").isEqualTo(1);
        processor.drain();
        assertThat(content(brand)).doesNotContain("Galaxy S24");
        assertThat(content(phones)).doesNotContain("Galaxy S24");

        send("PUT", "/api/v1/brands/" + brand, Map.of("name", "Samsung Electronics", "slug", "samsung",
                "description", "Korean electronics maker", "version", 0)).andExpect(status().isOk());
        processor.drain();
        assertThat(content(brand)).contains("Samsung Electronics").contains("Korean electronics maker");

        JsonNode fresh = refreshStaleResponse();
        assertThat(fresh.at("/catalog/stale").asLong()).isZero();
        jdbc.update("UPDATE catalog_embeddings SET content_hash = 'older' WHERE entity_id = ?", phones);
        JsonNode stale = refreshStaleResponse();
        assertThat(stale.at("/catalog/byType/CATEGORY/stale").asLong()).isEqualTo(1);
        assertThat(stale.at("/catalog/enqueued").asLong()).isEqualTo(1);
        processor.drain();

        jdbc.update("DELETE FROM catalog_embeddings WHERE entity_id = ?", brand);
        JsonNode missing = read(mockMvc.perform(post("/api/v1/admin/vector-sync/resync-missing")
                .header("Authorization", adminToken)).andExpect(status().isAccepted()).andReturn());
        assertThat(missing.get("catalogEnqueued").asInt()).isEqualTo(1);
        processor.drain();
        assertThat(content(brand)).contains("Samsung Electronics");
    }

    @Test
    void chatAnswersDiscoveryQuestionsFromCatalogEmbeddings() throws Exception {
        send("POST", "/api/v1/products", phone("GS24", List.of(
                variant("GS24-128-BLK", "Black", "128", "69999", null, 10, "ACTIVE")))).andExpect(status().isCreated());
        send("POST", "/api/v1/brands", Map.of("name", "Nike", "description", "Running shoes and sportswear"))
                .andExpect(status().isCreated());
        processor.drain();

        JsonNode answer = read(mockMvc.perform(post("/api/v1/chat").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Which brands sell Galaxy phones?\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(answer.at("/retrieval/strategy").asText()).isEqualTo("DISCOVERY");
        assertThat(answer.get("answer").asText()).contains("**Samsung**").doesNotContain("Nike")
                .contains("not an exhaustive list");
    }

    // ---------------------------------------------------------------------------------------------

    private String content(UUID entityId) {
        return jdbc.queryForObject("SELECT content FROM catalog_embeddings WHERE entity_id = ?", String.class,
                entityId);
    }

    private JsonNode refreshStaleResponse() throws Exception {
        return read(mockMvc.perform(post("/api/v1/admin/vector-sync/refresh-stale")
                .header("Authorization", adminToken)).andExpect(status().isAccepted()).andReturn());
    }

    private long events(UUID productId) {
        return count("SELECT count(*) FROM vector_sync_events WHERE aggregate_id = ?", productId);
    }

    private Map<String, Integer> refreshStale() throws Exception {
        JsonNode result = read(mockMvc.perform(post("/api/v1/admin/vector-sync/refresh-stale")
                .header("Authorization", adminToken)).andExpect(status().isAccepted()).andReturn());
        return Map.of("checked", result.get("checked").asInt(), "stale", result.get("stale").asInt(),
                "enqueued", result.get("enqueued").asInt());
    }

    private List<String> searchSkus(String path) throws Exception {
        JsonNode response = read(mockMvc.perform(get(path).header("Authorization", adminToken))
                .andExpect(status().isOk()).andReturn());
        List<String> values = new ArrayList<>();
        response.get("results").forEach(hit -> values.add(hit.get("product").get("sku").asText()));
        return values;
    }

    private Map<String, Object> phone(String sku, List<Map<String, Object>> variants) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Galaxy S24");
        body.put("sku", sku);
        body.put("description", "Samsung smartphone with a 6.2-inch display.");
        body.put("categoryId", phones);
        body.put("brandId", brand);
        body.put("hasVariants", true);
        body.put("images", List.of(Map.of("url", "https://images.example.com/gs24-front.jpg", "altText", "Front"),
                Map.of("url", "https://images.example.com/gs24-back.jpg")));
        body.put("options", List.of(option("Color", null, "Black", "Grey"),
                option("Storage Capacity", storageAttribute, "128", "256", "512")));
        if (variants != null) {
            body.put("variants", variants);
        }
        return body;
    }

    private static Map<String, Object> option(String name, UUID attributeId, String... values) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("name", name);
        option.put("attributeId", attributeId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (String value : values) {
            list.add(Map.of("value", value));
        }
        option.put("values", list);
        return option;
    }

    private static Map<String, Object> variant(String sku, String color, String storage, String price,
                                               String compareAt, int stock, String status) {
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("sku", sku);
        variant.put("options", Map.of("Color", color, "Storage Capacity", storage));
        variant.put("price", price);
        variant.put("compareAtPrice", compareAt);
        variant.put("stockQuantity", stock);
        variant.put("status", status);
        return variant;
    }

    private Map<String, Object> simple(String sku, String name, String price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("sku", sku);
        body.put("description", name + " for everyday use.");
        body.put("categoryId", electronics);
        body.put("price", price);
        body.put("stockQuantity", 5);
        return body;
    }

    private ResultActions send(String method, String path, Object body) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
        return mockMvc.perform(builder.header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<String> skus(String path) throws Exception {
        return skus(path, "sku");
    }

    private List<String> skus(String path, String field) throws Exception {
        JsonNode page = read(mockMvc.perform(get(path).header("Authorization", adminToken))
                .andExpect(status().isOk()).andReturn());
        List<String> values = new ArrayList<>();
        page.get("content").forEach(item -> values.add(item.get(field).asText()));
        return values;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID id(ResultActions actions) throws Exception {
        return UUID.fromString(read(actions.andReturn()).get("id").asText());
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
