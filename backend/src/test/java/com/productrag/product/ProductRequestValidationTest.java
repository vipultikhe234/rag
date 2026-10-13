package com.productrag.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.productrag.product.dto.ProductRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validRequestPasses() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void requiredFieldsAreEnforced() {
        ProductRequest request = new ProductRequest(" ", "", "", "", null, null, null, null, null, null, null, null,
                null);
        assertThat(invalidFields(request)).contains("name", "sku", "description");
    }

    @Test
    void invalidSlugsAndTooManyOptionsAreRejected() {
        ProductRequest base = valid();
        ProductRequest request = new ProductRequest(base.name(), base.sku(), base.description(), base.category(),
                base.brand(), base.price(), base.currency(), base.stockQuantity(), base.attributes(), null, null,
                null, null, "Not A Slug", true, null, java.util.Collections.nCopies(4,
                new com.productrag.catalog.option.dto.ProductOptionRequest(null, "Size", null,
                        java.util.List.of(new com.productrag.catalog.option.dto.ProductOptionRequest.Value(null,
                                "S")))), null);
        assertThat(invalidFields(request)).contains("slug", "options");
    }

    @Test
    void negativePriceIsRejected() {
        assertThat(invalidFields(withPrice("-1.00"))).containsExactly("price");
    }

    @Test
    void priceWithMoreThanTwoDecimalsIsRejected() {
        assertThat(invalidFields(withPrice("10.999"))).containsExactly("price");
    }

    @Test
    void negativeStockIsRejected() {
        ProductRequest request = new ProductRequest("Name", "SKU-1", "Desc", "Cat", null, BigDecimal.TEN, "INR", -1,
                Map.of(), null, null, null, null);
        assertThat(invalidFields(request)).containsExactly("stockQuantity");
    }

    @Test
    void invalidSkuAndCurrencyAreRejected() {
        ProductRequest request = new ProductRequest("Name", "bad sku!", "Desc", "Cat", null, BigDecimal.TEN, "RUPEE", 1,
                Map.of(), null, null, null, null);
        assertThat(invalidFields(request)).containsExactlyInAnyOrder("sku", "currency");
    }

    @Test
    void invalidAttributesAreRejected() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("specs", Map.of("nested", "object"));
        assertThat(invalidFields(withAttributes(nested))).containsExactly("attributes");

        Map<String, Object> badKey = Map.of("<script>", "x");
        assertThat(invalidFields(withAttributes(badKey))).containsExactly("attributes");

        Map<String, Object> tooLong = Map.of("note", "x".repeat(501));
        assertThat(invalidFields(withAttributes(tooLong))).containsExactly("attributes");
    }

    @Test
    void scalarAndListAttributesAreAccepted() {
        Map<String, Object> attributes = Map.of("battery_life", "30 hours", "weight_g", 250, "wireless", true,
                "colors", List.of("black", "white"));
        assertThat(invalidFields(withAttributes(attributes))).isEmpty();
    }

    private static Set<String> invalidFields(ProductRequest request) {
        Set<ConstraintViolation<ProductRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static ProductRequest valid() {
        return new ProductRequest("Wireless Headphones", "WH-001", "Great headphones", "Electronics", "Brand",
                new BigDecimal("2499.00"), "INR", 10, Map.of("battery_life", "30 hours"), null, null, null, null);
    }

    private static ProductRequest withPrice(String price) {
        return new ProductRequest("Name", "SKU-1", "Desc", "Cat", null, new BigDecimal(price), "INR", 1, Map.of(),
                null, null, null, null);
    }

    private static ProductRequest withAttributes(Map<String, Object> attributes) {
        return new ProductRequest("Name", "SKU-1", "Desc", "Cat", null, BigDecimal.TEN, "INR", 1, attributes, null,
                null, null, null);
    }
}
