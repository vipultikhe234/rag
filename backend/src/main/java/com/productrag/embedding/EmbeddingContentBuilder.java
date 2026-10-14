package com.productrag.embedding;

import com.productrag.embedding.ProductSearchContext.AttributeLabel;
import com.productrag.embedding.ProductSearchContext.OptionSummary;
import com.productrag.product.Product;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the search document that is embedded for a product.
 *
 * <p>Fields that affect the embedding (semantic fields): name, SKU, category path, brand name, description,
 * attributes (with their display names and units) and the option values offered by ACTIVE variants. Fields
 * deliberately excluded: prices, currency, stock quantities, status, variant SKUs, brand/category descriptions,
 * timestamps and ids. Price and stock change often and are always read live from PostgreSQL when answering, and
 * status is enforced by the retrieval query, so changing them never triggers a new embedding call.
 */
@Component
public class EmbeddingContentBuilder {

    static final int MAX_DESCRIPTION_CHARS = 6000;
    static final int MAX_ATTRIBUTE_VALUE_CHARS = 300;
    static final int MAX_OPTION_VALUES = 20;

    public String build(Product product) {
        return build(product, ProductSearchContext.EMPTY);
    }

    public String build(Product product, ProductSearchContext context) {
        StringBuilder text = new StringBuilder(512);
        line(text, "Product", product.getName());
        line(text, "SKU", product.getSku());
        line(text, "Category", context.categoryPath().isEmpty() ? product.getCategory()
                : String.join(" > ", context.categoryPath()));
        line(text, "Brand", product.getBrand());
        line(text, "Description", truncate(normalize(product.getDescription()), MAX_DESCRIPTION_CHARS));
        line(text, "Attributes", formatAttributes(product.getAttributes(), context.attributes()));
        line(text, "Options", formatOptions(context.options()));
        return text.toString().trim();
    }

    public String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    static String formatAttributes(Map<String, Object> attributes) {
        return formatAttributes(attributes, Map.of());
    }

    /** "RAM: 12 GB; Network Support: 5G"; keys without a definition are shown as humanized keys. */
    static String formatAttributes(Map<String, Object> attributes, Map<String, AttributeLabel> labels) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        // Sorted by key so the text (and therefore the hash) is deterministic.
        return new TreeMap<>(attributes).entrySet().stream()
                .map(entry -> {
                    AttributeLabel label = labels.get(entry.getKey());
                    String value = truncate(formatValue(entry.getValue()), MAX_ATTRIBUTE_VALUE_CHARS);
                    if (value.isEmpty()) {
                        return "";
                    }
                    if (label == null) {
                        return humanize(entry.getKey()) + ": " + value;
                    }
                    boolean withUnit = "NUMBER".equals(label.dataType()) && label.unit() != null
                            && !label.unit().isBlank() && value.matches("-?\\d+(\\.\\d+)?");
                    return normalize(label.name()) + ": " + value + (withUnit ? " " + normalize(label.unit()) : "");
                })
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.joining("; "));
    }

    /** "Color: Black, Blue; Storage Capacity: 256 GB, 512 GB" in the product's option order. */
    static String formatOptions(List<OptionSummary> options) {
        return options.stream()
                .filter(o -> !o.values().isEmpty())
                .map(o -> {
                    List<String> values = o.values().stream().map(EmbeddingContentBuilder::normalize).toList();
                    String shown = String.join(", ", values.subList(0, Math.min(values.size(), MAX_OPTION_VALUES)));
                    return normalize(o.name()) + ": " + shown
                            + (values.size() > MAX_OPTION_VALUES ? " and more" : "");
                })
                .collect(Collectors.joining("; "));
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).map(EmbeddingContentBuilder::normalize)
                    .collect(Collectors.joining(", "));
        }
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        return normalize(String.valueOf(value));
    }

    private static String humanize(String key) {
        return normalize(key.replace('_', ' '));
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(normalize(value)).append('\n');
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
