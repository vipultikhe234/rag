package com.productrag.product.filter;

import com.productrag.product.Product;
import com.productrag.product.ProductSpecifications;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;

/**
 * A validated structured product filter (field, operator, value). Construction rejects any
 * combination that is not explicitly supported, so filters can safely be turned into SQL.
 */
public record ProductFilter(FilterField field, FilterOperator operator, Object value) {

    public ProductFilter {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value");
        switch (field) {
            case PRICE -> {
                if (!(value instanceof BigDecimal price) || price.signum() < 0) {
                    throw new IllegalArgumentException("Price filters require a non-negative decimal value");
                }
            }
            case STOCK_QUANTITY -> {
                if (!(value instanceof Integer stock) || stock < 0) {
                    throw new IllegalArgumentException("Stock filters require a non-negative integer value");
                }
            }
            case CATEGORY, BRAND -> {
                if (!(value instanceof String text) || text.isBlank() || operator != FilterOperator.EQ) {
                    throw new IllegalArgumentException(field.label() + " filters require EQ and a non-blank value");
                }
                value = text.trim().toLowerCase(Locale.ROOT);
            }
            case CURRENCY -> {
                if (!(value instanceof String currency) || !currency.matches("^[A-Z]{3}$")
                        || operator != FilterOperator.EQ) {
                    throw new IllegalArgumentException("Currency filters require EQ and a 3-letter code");
                }
            }
        }
    }

    public static ProductFilter price(FilterOperator operator, BigDecimal value) {
        return new ProductFilter(FilterField.PRICE, operator, value);
    }

    public static ProductFilter stock(FilterOperator operator, int value) {
        return new ProductFilter(FilterField.STOCK_QUANTITY, operator, value);
    }

    public static ProductFilter category(String category) {
        return new ProductFilter(FilterField.CATEGORY, FilterOperator.EQ, category);
    }

    public static ProductFilter brand(String brand) {
        return new ProductFilter(FilterField.BRAND, FilterOperator.EQ, brand);
    }

    public static ProductFilter currency(String currency) {
        return new ProductFilter(FilterField.CURRENCY, FilterOperator.EQ, currency);
    }

    /** SQL condition for native queries that alias the products table as {@code p}. */
    public String sqlCondition(String parameterName) {
        return field.sqlExpression() + " " + operator.sql() + " :" + parameterName;
    }

    public Specification<Product> toSpecification() {
        return switch (field) {
            case PRICE -> {
                BigDecimal price = (BigDecimal) value;
                yield switch (operator) {
                    case LT -> ProductSpecifications.priceLessThan(price);
                    case LTE -> ProductSpecifications.priceLessThanOrEqual(price);
                    case GT -> ProductSpecifications.priceGreaterThan(price);
                    case GTE -> ProductSpecifications.priceGreaterThanOrEqual(price);
                    case EQ -> (root, query, cb) -> cb.equal(root.get("price"), price);
                };
            }
            case STOCK_QUANTITY -> {
                int stock = (Integer) value;
                yield switch (operator) {
                    case LT -> ProductSpecifications.stockLessThan(stock);
                    case LTE -> ProductSpecifications.stockLessThanOrEqual(stock);
                    case GT -> ProductSpecifications.stockGreaterThan(stock);
                    case GTE -> ProductSpecifications.stockGreaterThanOrEqual(stock);
                    case EQ -> ProductSpecifications.stockEquals(stock);
                };
            }
            case CATEGORY -> ProductSpecifications.categoryEquals((String) value);
            case BRAND -> ProductSpecifications.brandEquals((String) value);
            case CURRENCY -> ProductSpecifications.currencyEquals((String) value);
        };
    }

    public String describe() {
        String shown = value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
        return field.label() + " " + operator.sql() + " " + shown;
    }
}
