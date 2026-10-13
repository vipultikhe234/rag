package com.productrag.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.productrag.catalog.attribute.AttributeDataType;
import com.productrag.catalog.attribute.AttributeTestData;
import com.productrag.catalog.attribute.AttributeValueValidator;
import com.productrag.catalog.attribute.ProductAttribute;
import com.productrag.catalog.attribute.ProductAttributeService;
import com.productrag.catalog.attribute.dto.ProductAttributeRequest;
import com.productrag.catalog.brand.dto.BrandRequest;
import com.productrag.catalog.category.dto.CategoryRequest;
import com.productrag.catalog.validation.HttpUrlValidator;
import com.productrag.catalog.option.dto.ProductOptionRequest;
import com.productrag.catalog.variant.StockLevel;
import com.productrag.catalog.variant.dto.VariantDraft;
import com.productrag.product.dto.ProductRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks scripts/catalog-fixtures.json against the same rules the API enforces, so the seed script cannot fail
 * half-way (or create inconsistent data) because of a fixture mistake.
 */
class CatalogFixturesTest {

    private static final Path FIXTURES = Path.of("..", "scripts", "catalog-fixtures.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ValidatorFactory validatorFactory;
    private static Validator validator;
    private static JsonNode root;

    @BeforeAll
    static void load() throws IOException {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
        root = MAPPER.readTree(Files.readString(FIXTURES));
    }

    @AfterAll
    static void close() {
        validatorFactory.close();
    }

    @Test
    void eachModuleHasExactlyThirtyRecordsWithBothStatuses() {
        for (String module : List.of("categories", "brands", "attributes", "variants")) {
            assertThat(root.get(module)).as(module).hasSize(30);
            assertThat(statuses(root.get(module))).as(module + " statuses").containsExactlyInAnyOrder("ACTIVE", "INACTIVE");
        }
        assertThat(root.get("products")).hasSize(10);
    }

    @Test
    void categoriesAreValidUniqueAndListedParentsFirst() {
        Set<String> seen = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode c : root.get("categories")) {
            CategoryRequest request = new CategoryRequest(c.get("name").asText(), c.get("slug").asText(),
                    c.get("description").asText(), null, null, CatalogStatus.valueOf(c.get("status").asText()), null);
            assertThat(validator.validate(request)).as(request.name()).isEmpty();
            assertThat(request.slug()).isEqualTo(Slugs.from(request.name()));
            assertThat(names.add(request.name().toLowerCase(Locale.ROOT))).as("unique name " + request.name()).isTrue();
            if (!c.get("parent").isNull()) {
                assertThat(seen).as("parent of " + request.name() + " listed earlier").contains(c.get("parent").asText());
            }
            assertThat(seen.add(request.slug())).as("unique slug " + request.slug()).isTrue();
        }
        long children = stream(root.get("categories")).filter(c -> !c.get("parent").isNull()).count();
        assertThat(children).isGreaterThan(10);
    }

    @Test
    void brandsAreValidUniqueAndHaveRealWebsiteUrls() {
        Set<String> slugs = new HashSet<>();
        for (JsonNode b : root.get("brands")) {
            BrandRequest request = new BrandRequest(b.get("name").asText(), b.get("slug").asText(),
                    b.get("description").asText(), null, b.get("websiteUrl").asText(),
                    CatalogStatus.valueOf(b.get("status").asText()), null);
            assertThat(validator.validate(request)).as(request.name()).isEmpty();
            assertThat(request.slug()).isEqualTo(Slugs.from(request.name()));
            assertThat(HttpUrlValidator.isHttpUrl(request.websiteUrl())).as(request.websiteUrl()).isTrue();
            assertThat(request.websiteUrl()).startsWith("https://");
            assertThat(b.has("logoUrl")).as("no invented logo URLs").isFalse();
            assertThat(slugs.add(request.slug())).as("unique slug " + request.slug()).isTrue();
        }
    }

    @Test
    void attributesAreValidGloballyUniqueAndOnlyOnActiveCategories() {
        Map<String, JsonNode> categories = bySlug(root.get("categories"));
        Set<String> slugs = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode a : root.get("attributes")) {
            AttributeDataType type = AttributeDataType.valueOf(a.get("dataType").asText());
            List<String> options = texts(a.get("options"));
            List<String> assigned = texts(a.get("categories"));
            ProductAttributeRequest request = new ProductAttributeRequest(
                    assigned.stream().map(s -> UUID.randomUUID()).toList(), null, a.get("name").asText(),
                    a.get("slug").asText(), type, options, a.get("unit").isNull() ? null : a.get("unit").asText(),
                    a.get("required").asBoolean(), a.get("filterable").asBoolean(),
                    CatalogStatus.valueOf(a.get("status").asText()), null);
            assertThat(validator.validate(request)).as(request.name()).isEmpty();
            assertThat(request.slug()).isEqualTo(Slugs.from(request.name()));
            assertThat(slugs.add(request.slug())).as("unique slug " + request.slug()).isTrue();
            assertThat(names.add(request.name().toLowerCase(Locale.ROOT))).as("unique name " + request.name()).isTrue();

            assertThat(assigned).as("categories of " + request.name()).isNotEmpty().doesNotHaveDuplicates();
            for (String slug : assigned) {
                JsonNode category = categories.get(slug);
                assertThat(category).as("category " + slug + " of " + request.name()).isNotNull();
                assertThat(category.get("status").asText()).as("category " + slug + " of " + request.name()
                        + " is active").isEqualTo("ACTIVE");
            }
            if (type == AttributeDataType.SELECT) {
                assertThat(options).as(request.name() + " options").isNotEmpty();
                assertThat(options.stream().map(o -> o.toLowerCase(Locale.ROOT)).distinct()).hasSameSizeAs(options);
            } else {
                assertThat(options).as(request.name() + " options").isEmpty();
            }
        }
    }

    @Test
    void productsAndVariantsPassRequestAndAttributeValidation() {
        Map<String, JsonNode> categories = bySlug(root.get("categories"));
        Map<String, JsonNode> brands = bySlug(root.get("brands"));
        Map<String, UUID> categoryIds = new HashMap<>();
        categories.keySet().forEach(slug -> categoryIds.put(slug, UUID.randomUUID()));
        AttributeValueValidator values = attributeValidator(categories, categoryIds);

        Map<String, JsonNode> attributeFixtures = bySlug(root.get("attributes"));
        Map<String, Map<String, List<String>>> productOptions = new HashMap<>();
        Set<String> skus = new HashSet<>();
        for (JsonNode p : root.get("products")) {
            String category = p.get("category").asText();
            assertThat(categories.get(category).get("status").asText()).isEqualTo("ACTIVE");
            assertThat(brands.get(p.get("brand").asText()).get("status").asText()).isEqualTo("ACTIVE");
            Map<String, Object> attributes = map(p.get("attributes"));
            assertThat(p.get("hasVariants").asBoolean()).as(p.get("sku").asText() + " has variants").isTrue();
            List<ProductOptionRequest> options = new ArrayList<>();
            Map<String, List<String>> optionValues = new LinkedHashMap<>();
            for (JsonNode o : p.get("options")) {
                List<String> optionTexts = texts(o.get("values"));
                options.add(new ProductOptionRequest(null, o.get("name").asText(), null,
                        optionTexts.stream().map(t -> new ProductOptionRequest.Value(null, t)).toList()));
                optionValues.put(o.get("name").asText(), optionTexts);
                assertThat(optionTexts.stream().map(t -> t.toLowerCase(Locale.ROOT)).distinct())
                        .as("unique values of " + o.get("name").asText()).hasSameSizeAs(optionTexts);
                if (!o.get("attribute").isNull()) {
                    JsonNode attribute = attributeFixtures.get(o.get("attribute").asText());
                    assertThat(attribute).as("attribute of option " + o.get("name").asText()).isNotNull();
                    assertThat(attribute.get("status").asText()).isEqualTo("ACTIVE");
                    assertThat(chain(categories, category)).as("option attribute applies to " + category)
                            .containsAnyElementsOf(texts(attribute.get("categories")));
                    ProductAttribute definition = AttributeTestData.attribute(null, attribute.get("name").asText(),
                            attribute.get("slug").asText(), AttributeDataType.valueOf(attribute.get("dataType").asText()),
                            texts(attribute.get("options")), false, CatalogStatus.ACTIVE);
                    optionTexts.forEach(t -> assertThatCode(() -> AttributeValueValidator.coerce(definition, t))
                            .as("value " + t + " of " + o.get("name").asText()).doesNotThrowAnyException());
                }
            }
            assertThat(optionValues.keySet().stream().map(n -> n.toLowerCase(Locale.ROOT)).distinct())
                    .hasSameSizeAs(optionValues.keySet());
            ProductRequest request = new ProductRequest(p.get("name").asText(), p.get("sku").asText(),
                    p.get("description").asText(), null, null, null, p.get("currency").asText(), null, attributes,
                    null, null, categoryIds.get(category), UUID.randomUUID(), null, true, null, options, null);
            assertThat(validator.validate(request)).as(request.sku()).isEmpty();
            assertThatCode(() -> values.validate(categoryIds.get(category), attributes, Map.of()))
                    .as("attributes of " + request.sku()).doesNotThrowAnyException();
            assertThat(skus.add(request.sku().toUpperCase(Locale.ROOT))).as("unique SKU " + request.sku()).isTrue();
            productOptions.put(request.sku(), optionValues);
        }

        List<StockLevel> levels = new ArrayList<>();
        Set<String> combinations = new HashSet<>();
        for (JsonNode v : root.get("variants")) {
            String product = v.get("product").asText();
            String sku = v.get("sku").asText();
            assertThat(productOptions).as("product of " + sku).containsKey(product);
            Map<String, String> selected = MAPPER.convertValue(v.get("options"),
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
            BigDecimal compareAt = v.get("compareAtPrice").isNull() ? null : v.get("compareAtPrice").decimalValue();
            VariantDraft draft = new VariantDraft(sku, selected, null, v.get("price").decimalValue(), compareAt,
                    v.get("stockQuantity").asInt(), null, null, null, CatalogStatus.valueOf(v.get("status").asText()));
            assertThat(validator.validate(draft)).as(sku).isEmpty();
            if (compareAt != null) {
                assertThat(compareAt).as("compare-at price of " + sku).isGreaterThan(draft.price());
            }
            Map<String, List<String>> options = productOptions.get(product);
            assertThat(selected.keySet()).as("options of " + sku).containsExactlyInAnyOrderElementsOf(options.keySet());
            selected.forEach((name, value) -> assertThat(options.get(name)).as(name + " of " + sku).contains(value));
            assertThat(combinations.add(product + "|" + new java.util.TreeMap<>(selected)))
                    .as("unique combination of " + sku).isTrue();
            assertThat(skus.add(sku.toUpperCase(Locale.ROOT))).as("unique SKU " + sku).isTrue();
            levels.add(StockLevel.of(draft.stockQuantity(), 5));
        }
        assertThat(levels).contains(StockLevel.IN_STOCK, StockLevel.LOW_STOCK, StockLevel.OUT_OF_STOCK);
        assertThat(stream(root.get("variants")).map(v -> v.get("product").asText()).distinct()).hasSize(10);
    }

    private static List<String> chain(Map<String, JsonNode> categories, String slug) {
        List<String> result = new ArrayList<>();
        for (String current = slug; current != null; ) {
            result.add(current);
            JsonNode parent = categories.get(current).get("parent");
            current = parent.isNull() ? null : parent.asText();
        }
        return result;
    }

    /** A real validator whose definitions come from the fixtures, inherited along the category tree. */
    private static AttributeValueValidator attributeValidator(Map<String, JsonNode> categories,
                                                              Map<String, UUID> categoryIds) {
        Map<String, List<ProductAttribute>> own = new HashMap<>();
        for (JsonNode a : root.get("attributes")) {
            for (String category : texts(a.get("categories"))) {
                own.computeIfAbsent(category, k -> new ArrayList<>()).add(AttributeTestData.attribute(
                        categoryIds.get(category), a.get("name").asText(), a.get("slug").asText(),
                        AttributeDataType.valueOf(a.get("dataType").asText()), texts(a.get("options")),
                        a.get("required").asBoolean(), CatalogStatus.valueOf(a.get("status").asText())));
            }
        }
        ProductAttributeService service = mock(ProductAttributeService.class);
        when(service.applicableDefinitions(any(), anyBoolean())).thenReturn(List.of());
        for (String slug : categories.keySet()) {
            List<ProductAttribute> applicable = new ArrayList<>();
            for (String current = slug; current != null; ) {
                applicable.addAll(own.getOrDefault(current, List.of()));
                JsonNode parent = categories.get(current).get("parent");
                current = parent.isNull() ? null : parent.asText();
            }
            when(service.applicableDefinitions(categoryIds.get(slug), true)).thenReturn(applicable);
        }
        return new AttributeValueValidator(service);
    }

    private static Map<String, JsonNode> bySlug(JsonNode array) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        array.forEach(node -> result.put(node.get("slug").asText(), node));
        return result;
    }

    private static Set<String> statuses(JsonNode array) {
        Set<String> result = new HashSet<>();
        array.forEach(node -> result.add(node.get("status").asText()));
        return result;
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode node) {
        return MAPPER.convertValue(node, LinkedHashMap.class);
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list.stream();
    }
}
