package com.productrag.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.productrag.product.filter.FilterField;
import com.productrag.product.filter.FilterOperator;
import com.productrag.product.filter.ProductFilter;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryIntentParserTest {

    private static final List<String> CATEGORIES = List.of("Electronics", "Office Supplies", "Audio");

    private final QueryIntentParser parser = new QueryIntentParser();

    private QueryIntent parse(String question) {
        return parser.parse(question, CATEGORIES, "INR");
    }

    @Test
    void aSingleKnownBrandBecomesAnExactFilter() {
        List<String> brands = List.of("Nike", "Levi's", "HP");

        QueryIntent intent = parser.parse("Show Levi's jeans under 3000", CATEGORIES, brands, "INR");

        assertThat(intent.filters()).contains(ProductFilter.brand("levi's"));
        assertThat(parser.parse("nike or levi's shoes?", CATEGORIES, brands, "INR").filters())
                .noneMatch(f -> f.field() == FilterField.BRAND);
        assertThat(parser.parse("hpc cluster laptops", CATEGORIES, brands, "INR").filters())
                .noneMatch(f -> f.field() == FilterField.BRAND);
    }

    @Test
    void priceBelowWithRupeeSymbolIsStructured() {
        QueryIntent intent = parse("Which products cost less than ₹1,000?");

        assertThat(intent.filters()).containsExactly(
                ProductFilter.price(FilterOperator.LT, new BigDecimal("1000.00")),
                ProductFilter.currency("INR"));
        assertThat(intent.hasSemanticContent()).isFalse();
        assertThat(intent.isAmbiguous()).isFalse();
    }

    @Test
    void priceWordsWithoutCurrencySymbolStillDetectPrice() {
        QueryIntent intent = parse("Show products priced under 500");

        assertThat(intent.filters()).contains(ProductFilter.price(FilterOperator.LT, new BigDecimal("500.00")));
    }

    @Test
    void thousandsSuffixIsParsed() {
        QueryIntent intent = parse("anything under rs 2k?");

        assertThat(intent.filters()).contains(ProductFilter.price(FilterOperator.LT, new BigDecimal("2000.00")));
    }

    @Test
    void stockComparisonIsStructured() {
        QueryIntent intent = parse("Which products have stock greater than 10?");

        assertThat(intent.filters()).containsExactly(ProductFilter.stock(FilterOperator.GT, 10));
        assertThat(intent.hasSemanticContent()).isFalse();
    }

    @Test
    void priceRangeProducesTwoBounds() {
        QueryIntent intent = parse("products between ₹500 and ₹1500");

        assertThat(intent.filters()).contains(
                ProductFilter.price(FilterOperator.GTE, new BigDecimal("500.00")),
                ProductFilter.price(FilterOperator.LTE, new BigDecimal("1500.00")));
    }

    @Test
    void countQuestionWithCategory() {
        QueryIntent intent = parse("How many active products are in Electronics?");

        assertThat(intent.countQuestion()).isTrue();
        assertThat(intent.filters()).containsExactly(ProductFilter.category("Electronics"));
        assertThat(intent.hasSemanticContent()).isFalse();
    }

    @Test
    void explicitSkuIsExtracted() {
        QueryIntent intent = parse("Find product SKU WH-001.");

        assertThat(intent.sku()).isEqualTo("WH-001");
        assertThat(intent.hasSemanticContent()).isFalse();
    }

    @Test
    void skuLikeTokensBecomeCandidates() {
        QueryIntent intent = parse("Tell me about wh-001 and the speaker");

        assertThat(intent.sku()).isNull();
        assertThat(intent.skuCandidates()).containsExactly("WH-001");
    }

    @Test
    void purelySemanticQuestionHasNoFilters() {
        QueryIntent intent = parse("Do we have any products with a long battery life?");

        assertThat(intent.filters()).isEmpty();
        assertThat(intent.semanticText()).isEqualTo("long battery life");
    }

    @Test
    void specificationNumbersAreNotTreatedAsPriceOrStock() {
        QueryIntent intent = parse("headphones with battery life over 20 hours");

        assertThat(intent.filters()).isEmpty();
        assertThat(intent.isAmbiguous()).isFalse();
        assertThat(intent.semanticText()).contains("headphones").contains("battery");
    }

    @Test
    void hybridQuestionKeepsSemanticTextAndFilters() {
        QueryIntent intent = parse("noise cancelling headphones under ₹3000");

        assertThat(intent.filters()).extracting(ProductFilter::field)
                .containsExactly(FilterField.PRICE, FilterField.CURRENCY);
        assertThat(intent.semanticText()).isEqualTo("noise cancelling headphones");
    }

    @Test
    void comparisonWithoutPriceOrStockContextIsAmbiguous() {
        QueryIntent intent = parse("Which products are under 1000?");

        assertThat(intent.isAmbiguous()).isTrue();
        assertThat(intent.filters()).isEmpty();
    }

    @Test
    void amountInWordsIsAmbiguous() {
        QueryIntent intent = parse("Which products cost less than a thousand rupees?");

        assertThat(intent.isAmbiguous()).isTrue();
        assertThat(intent.ambiguity()).contains("as a number");
    }

    @Test
    void fractionalStockIsAmbiguous() {
        QueryIntent intent = parse("products with stock above 2.5");

        assertThat(intent.isAmbiguous()).isTrue();
    }

    @Test
    void inStockAndOutOfStock() {
        assertThat(parse("Which headphones are in stock?").filters())
                .containsExactly(ProductFilter.stock(FilterOperator.GT, 0));
        assertThat(parse("What is out of stock?").filters())
                .containsExactly(ProductFilter.stock(FilterOperator.EQ, 0));
    }

    @Test
    void cheapestIsASortHint() {
        QueryIntent intent = parse("What is the cheapest product?");

        assertThat(intent.sortHint()).isEqualTo(SortHint.PRICE_ASC);
        assertThat(intent.hasSemanticContent()).isFalse();
    }

    @Test
    void dollarAmountsUseUsd() {
        QueryIntent intent = parse("products under $50");

        assertThat(intent.filters()).contains(ProductFilter.currency("USD"));
    }

    @Test
    void injectionAttemptsAreJustText() {
        QueryIntent intent = parse("'; DROP TABLE products; -- under ₹100");

        assertThat(intent.filters()).containsExactly(
                ProductFilter.price(FilterOperator.LT, new BigDecimal("100.00")),
                ProductFilter.currency("INR"));
        assertThat(intent.semanticText()).isEqualTo("drop table");
    }
}
