package com.productrag.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.productrag.product.Product;
import com.productrag.product.ProductStatus;
import com.productrag.product.ProductTestData;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbeddingContentBuilderTest {

    private final EmbeddingContentBuilder builder = new EmbeddingContentBuilder();

    @Test
    void includesSemanticFieldsAndAttributes() {
        String content = builder.build(ProductTestData.headphones());

        assertThat(content)
                .contains("Product: Wireless Headphones")
                .contains("SKU: WH-001")
                .contains("Category: Electronics")
                .contains("Brand: ExampleBrand")
                .contains("Description: Wireless over-ear headphones with active noise cancellation")
                .contains("battery life: 30 hours")
                .contains("noise cancellation: yes");
    }

    @Test
    void excludesPriceStockAndStatus() {
        Product product = ProductTestData.headphones();
        String content = builder.build(product);

        assertThat(content).doesNotContain("2499").doesNotContain("Stock").doesNotContain("ACTIVE");
    }

    @Test
    void nonSemanticChangesKeepTheSameHash() {
        Product product = ProductTestData.headphones();
        String before = builder.hash(builder.build(product));

        product.setPrice(new BigDecimal("1999.00"));
        product.setStockQuantity(0);
        product.setStatus(ProductStatus.INACTIVE);
        product.setCurrency("USD");

        assertThat(builder.hash(builder.build(product))).isEqualTo(before);
    }

    @Test
    void semanticChangesChangeTheHash() {
        Product product = ProductTestData.headphones();
        String before = builder.hash(builder.build(product));

        product.setDescription("Completely different description");

        assertThat(builder.hash(builder.build(product))).isNotEqualTo(before);
    }

    @Test
    void attributeOrderDoesNotAffectContent() {
        Product a = ProductTestData.headphones();
        Product b = ProductTestData.headphones();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("color", "black");
        first.put("weight", "250 g");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("weight", "250 g");
        second.put("color", "black");
        a.setAttributes(first);
        b.setAttributes(second);

        assertThat(builder.build(a)).isEqualTo(builder.build(b));
    }

    @Test
    void contextAddsCategoryPathAttributeLabelsWithUnitsAndVariantOptions() {
        Product product = ProductTestData.headphones();
        product.setAttributes(Map.of("battery-life", 30, "colour-name", "Black"));
        ProductSearchContext context = new ProductSearchContext(List.of("Electronics", "Audio", "Headphones"),
                Map.of("battery-life", new ProductSearchContext.AttributeLabel("Battery Life", "NUMBER", "hours")),
                List.of(new ProductSearchContext.OptionSummary("Color", List.of("Black", "Blue")),
                        new ProductSearchContext.OptionSummary("Size", List.of())));

        String content = builder.build(product, context);

        assertThat(content)
                .contains("Category: Electronics > Audio > Headphones")
                .contains("Battery Life: 30 hours")
                .contains("colour-name: Black")
                .contains("Options: Color: Black, Blue")
                .doesNotContain("Size");
        assertThat(builder.build(product, context)).isEqualTo(content);
    }

    @Test
    void priceAndStockChangesKeepTheHashWithContext() {
        Product product = ProductTestData.headphones();
        ProductSearchContext context = new ProductSearchContext(List.of("Electronics"), Map.of(),
                List.of(new ProductSearchContext.OptionSummary("Color", List.of("Black"))));
        String before = builder.hash(builder.build(product, context));

        product.setPrice(new BigDecimal("1.00"));
        product.setStockQuantity(999);

        assertThat(builder.hash(builder.build(product, context))).isEqualTo(before);
    }

    @Test
    void formatsListAttributes() {
        assertThat(EmbeddingContentBuilder.formatAttributes(Map.of("colors", List.of("black", "silver"))))
                .isEqualTo("colors: black, silver");
    }
}
